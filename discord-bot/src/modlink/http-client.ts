import type { Logger } from "../logging/logger.js";
import { RemoteError, decodeEnvelope, encodeEnvelope, remoteErrorFrom, type Envelope } from "../protocol/envelope.js";
import { AUTH_HEADER, VERSION_HEADER } from "../protocol/messages.js";

/** Raised when a request could not be completed at the transport level. */
export class TransportError extends Error {
  public readonly retryable: boolean;

  public constructor(message: string, retryable: boolean, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "TransportError";
    this.retryable = retryable;
  }
}

export interface HttpClientOptions {
  readonly baseUrl: string;
  readonly secret: string;
  readonly timeoutMs: number;
  readonly protocolVersion: number;
  readonly logger: Logger;
}

/**
 * Thin, authenticated JSON client for the mod's HTTP control endpoints.
 *
 * Used for short-lived operations (health probes and one-shot commands) while the
 * WebSocket carries the long-lived event stream. Every failure is surfaced as a
 * {@link TransportError} or {@link RemoteError}; nothing is swallowed.
 */
export class HttpClient {
  private readonly options: HttpClientOptions;

  public constructor(options: HttpClientOptions) {
    this.options = options;
  }

  /**
   * Calls an unauthenticated endpoint such as `/health`.
   *
   * @returns the decoded JSON body
   * @throws TransportError when the request fails or returns a non-2xx status
   */
  public async health(): Promise<Record<string, unknown>> {
    return this.requestJson("GET", "/health", undefined, { authenticated: false });
  }

  /**
   * Sends a command envelope to `/command` and returns the reply envelope.
   *
   * @param request the request frame
   * @throws TransportError on transport failure or timeout
   * @throws RemoteError when the mod answers with an error frame
   */
  public async command(request: Envelope): Promise<Envelope> {
    const body = encodeEnvelope(request);
    const reply = await this.requestText("POST", "/command", body, { authenticated: true });
    const envelope = decodeEnvelope(reply);
    if (envelope.type === "RESPONSE_ERROR") {
      throw remoteErrorFrom(envelope);
    }
    return envelope;
  }

  private async requestJson(
    method: string,
    path: string,
    body: string | undefined,
    options: { authenticated: boolean },
  ): Promise<Record<string, unknown>> {
    const text = await this.requestText(method, path, body, options);
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch (error) {
      const detail = error instanceof Error ? error.message : "invalid JSON";
      throw new TransportError(`${path} returned a body that is not JSON: ${detail}`, true);
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw new TransportError(`${path} returned a non-object body`, true);
    }
    return parsed as Record<string, unknown>;
  }

  private async requestText(
    method: string,
    path: string,
    body: string | undefined,
    options: { authenticated: boolean },
  ): Promise<string> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.options.timeoutMs);
    const headers: Record<string, string> = {
      accept: "application/json",
      [VERSION_HEADER]: String(this.options.protocolVersion),
    };
    if (options.authenticated) {
      headers[AUTH_HEADER] = this.options.secret;
    }
    if (body !== undefined) {
      headers["content-type"] = "application/json";
    }

    try {
      const init: RequestInit = {
        method,
        headers,
        signal: controller.signal,
      };
      if (body !== undefined) {
        init.body = body;
      }
      const response = await fetch(`${this.options.baseUrl}${path}`, init);
      const text = await response.text();
      if (!response.ok) {
        // A 401/403 means the shared secret is wrong, which retrying cannot fix.
        const retryable = response.status >= 500 || response.status === 429;
        throw new TransportError(`${path} responded with HTTP ${response.status}`, retryable);
      }
      return text;
    } catch (error) {
      if (error instanceof TransportError) {
        throw error;
      }
      if (error instanceof Error && error.name === "AbortError") {
        throw new TransportError(`${path} timed out after ${this.options.timeoutMs} ms`, true, {
          cause: error,
        });
      }
      const detail = error instanceof Error ? error.message : String(error);
      this.options.logger.debug("mod.http", `${method} ${path} failed`, detail);
      throw new TransportError(`${path} could not be reached: ${detail}`, true, { cause: error });
    } finally {
      clearTimeout(timer);
    }
  }
}
