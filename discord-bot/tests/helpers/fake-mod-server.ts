import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { Duplex } from "node:stream";
import { WebSocketServer, type WebSocket } from "ws";

import { AUTH_HEADER, PROTOCOL_VERSION, WS_SUBPROTOCOL } from "../../src/protocol/messages.js";

export interface RecordedFrame {
  readonly type: string;
  readonly id: string | undefined;
  readonly data: Record<string, unknown>;
}

export interface FakeModOptions {
  readonly secret: string;
  /** Fixed port to bind, so a restart can reuse the same endpoint. Defaults to an ephemeral port. */
  readonly port?: number;
  /** Overrides the reply produced for a request. `"silent"` leaves it unanswered. */
  readonly responder?: (frame: RecordedFrame) => Record<string, unknown> | "error" | "silent";
  /** When set, the WebSocket upgrade is refused with this HTTP status. */
  readonly rejectUpgradeStatus?: number;
}

/**
 * A miniature stand-in for the Fabric mod's control server.
 *
 * It implements just enough of the real surface (authenticated `/ws` upgrade,
 * correlated replies, `/health`) to exercise the controller's real code paths over
 * a real socket, rather than mocking the client.
 */
export class FakeModServer {
  private readonly options: FakeModOptions;
  private readonly sockets = new Set<WebSocket>();
  private readonly frames: RecordedFrame[] = [];
  private server: Server | undefined;
  private webSocketServer: WebSocketServer | undefined;
  private port = 0;
  private acceptedUpgrades = 0;

  public constructor(options: FakeModOptions) {
    this.options = options;
  }

  public get baseUrl(): string {
    return `http://127.0.0.1:${this.port}`;
  }

  public get recordedFrames(): readonly RecordedFrame[] {
    return this.frames;
  }

  public get connectionCount(): number {
    return this.acceptedUpgrades;
  }

  public async start(): Promise<void> {
    const server = createServer((request, response) => {
      this.handleHttp(request, response);
    });
    this.server = server;
    this.webSocketServer = new WebSocketServer({ noServer: true });

    server.on("upgrade", (request, socket, head) => {
      this.handleUpgrade(request, socket, head);
    });

    await new Promise<void>((resolve) => {
      server.listen(this.options.port ?? 0, "127.0.0.1", () => resolve());
    });
    const address = server.address();
    if (address === null || typeof address === "string") {
      throw new Error("fake mod server failed to bind a port");
    }
    this.port = address.port;
  }

  public async stop(): Promise<void> {
    for (const socket of this.sockets) {
      socket.terminate();
    }
    this.sockets.clear();
    const webSocketServer = this.webSocketServer;
    if (webSocketServer !== undefined) {
      await new Promise<void>((resolve) => webSocketServer.close(() => resolve()));
      this.webSocketServer = undefined;
    }
    const server = this.server;
    if (server !== undefined) {
      await new Promise<void>((resolve) => server.close(() => resolve()));
      this.server = undefined;
    }
  }

  /** Pushes an unsolicited event to every connected peer. */
  public broadcast(type: string, data: Record<string, unknown> = {}): void {
    const payload = JSON.stringify({ v: PROTOCOL_VERSION, type, ts: Date.now(), data });
    for (const socket of this.sockets) {
      socket.send(payload);
    }
  }

  private handleHttp(request: IncomingMessage, response: ServerResponse): void {
    if (request.url === "/health") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ status: "ok", protocolVersion: PROTOCOL_VERSION }));
      return;
    }
    if (request.headers[AUTH_HEADER.toLowerCase()] !== this.options.secret) {
      response.writeHead(401, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { code: "unauthorized" } }));
      return;
    }
    response.writeHead(404, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: { code: "not_found" } }));
  }

  private handleUpgrade(request: IncomingMessage, socket: Duplex, head: Buffer): void {
    if (this.options.rejectUpgradeStatus !== undefined) {
      socket.write(`HTTP/1.1 ${this.options.rejectUpgradeStatus} Refused\r\n\r\n`);
      socket.destroy();
      return;
    }
    if (request.headers[AUTH_HEADER.toLowerCase()] !== this.options.secret) {
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
    const requestedProtocols = String(request.headers["sec-websocket-protocol"] ?? "");
    const offered = requestedProtocols.split(",").map((entry) => entry.trim());
    if (!offered.includes(WS_SUBPROTOCOL)) {
      socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
      socket.destroy();
      return;
    }
    const webSocketServer = this.webSocketServer;
    if (webSocketServer === undefined) {
      socket.destroy();
      return;
    }
    webSocketServer.handleUpgrade(request, socket, head, (client) => {
      this.acceptedUpgrades += 1;
      this.sockets.add(client);
      client.on("close", () => this.sockets.delete(client));
      client.on("message", (raw: Buffer) => this.handleFrame(client, raw.toString("utf8")));
      client.send(
        JSON.stringify({
          v: PROTOCOL_VERSION,
          type: "session.welcome",
          ts: Date.now(),
          data: {
            protocolVersion: PROTOCOL_VERSION,
            modId: "unionkitbot",
            modVersion: "1.0.0-test",
            state: "IDLE",
            heartbeatSeconds: 10,
            sessionId: "test-session",
          },
        }),
      );
    });
  }

  private handleFrame(client: WebSocket, text: string): void {
    let parsed: { type?: unknown; id?: unknown; data?: unknown };
    try {
      parsed = JSON.parse(text) as { type?: unknown; id?: unknown; data?: unknown };
    } catch {
      return;
    }
    const type = typeof parsed.type === "string" ? parsed.type : "unknown";
    const id = typeof parsed.id === "string" ? parsed.id : undefined;
    const data =
      typeof parsed.data === "object" && parsed.data !== null
        ? (parsed.data as Record<string, unknown>)
        : {};
    const frame: RecordedFrame = { type, id, data };
    this.frames.push(frame);

    const decision = this.options.responder?.(frame);
    if (decision === "silent" || id === undefined) {
      return;
    }
    if (decision === "error") {
      // The real mod sends the error object as the frame payload itself.
      client.send(
        JSON.stringify({
          v: PROTOCOL_VERSION,
          type: "response.error",
          id,
          ts: Date.now(),
          data: {
            code: "invalid_request",
            message: "the mod refused the request",
            path: "kind",
            retryable: false,
          },
        }),
      );
      return;
    }
    client.send(
      JSON.stringify({
        v: PROTOCOL_VERSION,
        type: "response.ok",
        id,
        ts: Date.now(),
        data: decision ?? defaultStatusPayload(),
      }),
    );
  }
}

/**
 * The status payload the real mod returns, trimmed to the fields the controller
 * actually reads.
 */
function defaultStatusPayload(): Record<string, unknown> {
  return {
    state: "IDLE",
    running: false,
    enabled: true,
    logLevel: "INFO",
    uptimeTicks: 120,
    lastAction: "idle",
    peers: 1,
    protocolVersion: PROTOCOL_VERSION,
    modVersion: "1.0.0-test",
    apiPort: 8765,
    queue: { active: 0, pending: 1, finished: 4 },
    activeTask: null,
    modules: { navigation: true, delivery: true, scanning: false, recovery: true },
    recentErrors: [],
  };
}
