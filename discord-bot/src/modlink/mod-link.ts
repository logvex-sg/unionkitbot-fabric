import { EventEmitter } from "node:events";
import { randomBytes } from "node:crypto";
import WebSocket, { type RawData } from "ws";

import type { Logger } from "../logging/logger.js";
import {
  ProtocolError,
  decodeEnvelope,
  encodeEnvelope,
  remoteErrorFrom,
  type Envelope,
} from "../protocol/envelope.js";
import {
  AUTH_HEADER,
  PROTOCOL_VERSION,
  VERSION_HEADER,
  WS_SUBPROTOCOL,
  isOutbound,
  type MessageTypeName,
} from "../protocol/messages.js";
import { Backoff } from "./backoff.js";
import { TransportError } from "./http-client.js";

/** Connection state of the link to the Fabric mod. */
export type LinkState = "disconnected" | "connecting" | "connected" | "stopped";

export interface ModLinkEvents {
  /** Emitted for every accepted inbound frame (responses and events). */
  envelope: [Envelope];
  /** Emitted whenever the connection state changes. */
  state: [LinkState];
  /** Emitted after a successful WebSocket upgrade. */
  connected: [Envelope];
  /** Emitted when the socket closes for any reason. */
  disconnected: [{ code: number; reason: string }];
  /** Emitted when a protocol or transport error is observed. */
  failure: [Error];
}

export interface ModLinkOptions {
  readonly baseUrl: string;
  readonly secret: string;
  readonly useTls: boolean;
  readonly allowInsecureTls: boolean;
  readonly protocolVersion: number;
  readonly requestTimeoutMs: number;
  readonly heartbeatTimeoutMs: number;
  readonly reconnectMinMs: number;
  readonly reconnectMaxMs: number;
  readonly logger: Logger;
  readonly backoffRandom?: () => number;
}

interface PendingRequest {
  readonly type: MessageTypeName;
  readonly resolve: (envelope: Envelope) => void;
  readonly reject: (error: Error) => void;
  readonly timer: NodeJS.Timeout;
}

const PING_INTERVAL_MS = 20_000;
const OPEN_TIMEOUT_MS = 15_000;

/**
 * Long-lived, self-healing WebSocket link to the Fabric mod.
 *
 * Design notes:
 * - Reconnection is automatic and uses exponential backoff with full jitter.
 * - Requests are correlated by frame id, so a slow reply cannot be matched to the
 *   wrong caller.
 * - The link never throws into callers: {@link request} rejects with a typed error,
 *   and the bot degrades to "Minecraft offline" instead of crashing.
 */
export class ModLink extends EventEmitter<ModLinkEvents> {
  private readonly options: ModLinkOptions;
  private readonly backoff: Backoff;
  private readonly pending = new Map<string, PendingRequest>();

  private socket: WebSocket | undefined;
  private state: LinkState = "disconnected";
  private reconnectTimer: NodeJS.Timeout | undefined;
  private pingTimer: NodeJS.Timeout | undefined;
  private openTimer: NodeJS.Timeout | undefined;
  private lastFrameAt = 0;
  private stopped = false;
  private reconnectAttempts = 0;

  public constructor(options: ModLinkOptions) {
    super();
    this.options = options;
    this.backoff = new Backoff({
      minMs: options.reconnectMinMs,
      maxMs: options.reconnectMaxMs,
      ...(options.backoffRandom === undefined ? {} : { random: options.backoffRandom }),
    });
  }

  public currentState(): LinkState {
    return this.state;
  }

  public isConnected(): boolean {
    return this.state === "connected";
  }

  public millisecondsSinceLastFrame(): number {
    return this.lastFrameAt === 0 ? Number.POSITIVE_INFINITY : Date.now() - this.lastFrameAt;
  }

  public reconnectCount(): number {
    return this.reconnectAttempts;
  }

  /**
   * Opens the link and keeps it open until {@link stop} is called.
   */
  public start(): void {
    if (this.stopped) {
      throw new Error("the link has been stopped and cannot be restarted");
    }
    this.connect();
  }

  /**
   * Closes the link and cancels any scheduled reconnect.
   */
  public stop(): void {
    this.stopped = true;
    this.clearTimers();
    this.setState("stopped");
    this.failPending(new TransportError("the link was stopped", false));
    const socket = this.socket;
    this.socket = undefined;
    if (socket !== undefined) {
      socket.removeAllListeners();
      try {
        socket.close(1000, "controller shutting down");
      } catch (error) {
        this.options.logger.debug("mod.ws", "socket close failed during shutdown", error);
      }
    }
  }

  /**
   * Sends a request and waits for its correlated reply.
   *
   * @throws TransportError when the link is down or the reply times out
   * @throws RemoteError when the mod answers with an error frame
   */
  public async request(
    type: MessageTypeName,
    data: Record<string, unknown> = {},
    timeoutMs: number = this.options.requestTimeoutMs,
  ): Promise<Envelope> {
    const socket = this.socket;
    if (socket === undefined || this.state !== "connected") {
      throw new TransportError("the Minecraft client is not connected", true);
    }
    const id = randomBytes(4).toString("hex");
    const frame = encodeEnvelope({
      v: PROTOCOL_VERSION,
      type,
      id,
      timestamp: Date.now(),
      data: data as Envelope["data"],
    });

    return await new Promise<Envelope>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new TransportError(`request ${type} timed out after ${timeoutMs} ms`, true));
      }, timeoutMs);
      this.pending.set(id, { type, resolve, reject, timer });
      socket.send(frame, (error) => {
        if (error !== undefined && error !== null) {
          const pending = this.pending.get(id);
          if (pending !== undefined) {
            clearTimeout(pending.timer);
            this.pending.delete(id);
          }
          reject(new TransportError(`failed to send ${type}: ${error.message}`, true, { cause: error }));
        }
      });
    });
  }

  /**
   * Fire-and-forget send for frames that have no reply, such as a goodbye notice.
   */
  public sendEvent(type: MessageTypeName, data: Record<string, unknown> = {}): void {
    const socket = this.socket;
    if (socket === undefined || this.state !== "connected" || !isOutbound(type)) {
      return;
    }
    const frame = encodeEnvelope({
      v: PROTOCOL_VERSION,
      type,
      id: undefined,
      timestamp: Date.now(),
      data: data as Envelope["data"],
    });
    socket.send(frame, (error) => {
      if (error !== undefined && error !== null) {
        this.options.logger.debug("mod.ws", `failed to send ${type}`, error);
      }
    });
  }

  private connect(): void {
    if (this.stopped) {
      return;
    }
    this.clearTimers();
    this.setState("connecting");

    const url = this.webSocketUrl();
    const socketOptions = this.options.allowInsecureTls ? { rejectUnauthorized: false } : {};
    const socket = new WebSocket(url, WS_SUBPROTOCOL, {
      headers: {
        [AUTH_HEADER]: this.options.secret,
        [VERSION_HEADER]: String(this.options.protocolVersion),
      },
      ...socketOptions,
    });
    this.socket = socket;

    this.openTimer = setTimeout(() => {
      this.options.logger.warn("mod.ws", `upgrade did not complete within ${OPEN_TIMEOUT_MS} ms`);
      socket.terminate();
    }, OPEN_TIMEOUT_MS);

    socket.on("open", () => {
      this.clearOpenTimer();
      this.backoff.reset();
      this.lastFrameAt = Date.now();
      this.setState("connected");
      this.options.logger.info("mod.ws", `connected to ${url}`);
      this.startPinging();
    });

    socket.on("message", (data: RawData) => {
      this.handleMessage(data);
    });

    socket.on("pong", () => {
      this.lastFrameAt = Date.now();
    });

    socket.on("error", (error: Error) => {
      this.options.logger.warn("mod.ws", "socket error", error.message);
      this.emit("failure", error);
    });

    socket.on("close", (code: number, reason: Buffer) => {
      this.clearOpenTimer();
      this.clearPingTimer();
      if (this.socket === socket) {
        this.socket = undefined;
      }
      const text = reason.toString("utf8");
      this.failPending(new TransportError("the connection closed before a reply arrived", true));
      if (this.state !== "stopped") {
        this.setState("disconnected");
        this.emit("disconnected", { code, reason: text });
      }
      if (!this.stopped) {
        this.scheduleReconnect(code);
      }
    });
  }

  private scheduleReconnect(closeCode: number): void {
    const delay = this.backoff.nextDelayMs();
    this.reconnectAttempts += 1;
    const reason =
      closeCode === 1000 || closeCode === 1001
        ? "the mod closed the connection"
        : `close code ${closeCode}`;
    this.options.logger.warn(
      "mod.ws",
      `reconnecting in ${delay} ms (attempt ${this.backoff.attempts()}; ${reason})`,
    );
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  private handleMessage(data: RawData): void {
    this.lastFrameAt = Date.now();
    const text = typeof data === "string" ? data : data.toString("utf8");
    let envelope: Envelope;
    try {
      envelope = decodeEnvelope(text);
    } catch (error) {
      const failure =
        error instanceof ProtocolError ? error : new ProtocolError("", "unreadable frame");
      this.options.logger.warn("mod.ws", `discarding frame: ${failure.message}`);
      this.emit("failure", failure);
      return;
    }

    if (envelope.id !== undefined) {
      const pending = this.pending.get(envelope.id);
      if (pending !== undefined) {
        clearTimeout(pending.timer);
        this.pending.delete(envelope.id);
        if (envelope.type === "RESPONSE_ERROR") {
          pending.reject(remoteErrorFrom(envelope));
        } else {
          pending.resolve(envelope);
        }
        return;
      }
    }

    if (envelope.type === "WELCOME") {
      const version = envelope.data["protocolVersion"];
      if (typeof version === "number" && version !== this.options.protocolVersion) {
        this.options.logger.error(
          "mod.ws",
          `protocol mismatch: the mod speaks v${version}, this controller speaks v${this.options.protocolVersion}`,
        );
      }
      this.emit("connected", envelope);
    }

    this.emit("envelope", envelope);
  }

  private startPinging(): void {
    this.clearPingTimer();
    this.pingTimer = setInterval(() => {
      const socket = this.socket;
      if (socket === undefined) {
        return;
      }
      if (this.millisecondsSinceLastFrame() > this.options.heartbeatTimeoutMs) {
        this.options.logger.warn(
          "mod.ws",
          `no traffic for ${Math.round(this.millisecondsSinceLastFrame())} ms; dropping the socket`,
        );
        socket.terminate();
        return;
      }
      socket.ping();
    }, PING_INTERVAL_MS);
  }

  private webSocketUrl(): string {
    const parsed = new URL(this.options.baseUrl);
    const secure = this.options.useTls || parsed.protocol === "https:";
    parsed.protocol = secure ? "wss:" : "ws:";
    const path = parsed.pathname.replace(/\/+$/, "");
    parsed.pathname = `${path}/ws`;
    parsed.search = "";
    return parsed.toString();
  }

  private setState(next: LinkState): void {
    if (this.state === next) {
      return;
    }
    this.state = next;
    this.emit("state", next);
  }

  private failPending(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }

  private clearTimers(): void {
    this.clearOpenTimer();
    this.clearPingTimer();
    if (this.reconnectTimer !== undefined) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
  }

  private clearOpenTimer(): void {
    if (this.openTimer !== undefined) {
      clearTimeout(this.openTimer);
      this.openTimer = undefined;
    }
  }

  private clearPingTimer(): void {
    if (this.pingTimer !== undefined) {
      clearInterval(this.pingTimer);
      this.pingTimer = undefined;
    }
  }
}
