import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Logger, RedactionRegistry } from "../src/logging/logger.js";
import { ModController } from "../src/modlink/mod-controller.js";
import { ModLink } from "../src/modlink/mod-link.js";
import { TransportError } from "../src/modlink/http-client.js";
import { FakeModServer } from "./helpers/fake-mod-server.js";

const SECRET = "test-secret-value-1234";

function quietLogger(): Logger {
  const logger = new Logger("debug", 50, new RedactionRegistry());
  return logger;
}

function buildController(server: FakeModServer, overrides: Partial<{ reconnectMinMs: number }> = {}): ModController {
  return new ModController({
    baseUrl: server.baseUrl,
    secret: SECRET,
    useTls: false,
    allowInsecureTls: false,
    protocolVersion: 1,
    requestTimeoutMs: 1_500,
    heartbeatTimeoutMs: 30_000,
    reconnectMinMs: overrides.reconnectMinMs ?? 50,
    reconnectMaxMs: overrides.reconnectMinMs ?? 50,
    logger: quietLogger(),
    backoffRandom: () => 1,
  });
}

async function waitFor(predicate: () => boolean, timeoutMs = 3_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error("condition was not met before the deadline");
}

describe("ModController against a live fake mod", () => {
  let server: FakeModServer;
  let controller: ModController;
  const stdoutSpy = (): void => {
    vi.spyOn(process.stdout, "write").mockReturnValue(true);
    vi.spyOn(process.stderr, "write").mockReturnValue(true);
  };

  beforeEach(async () => {
    stdoutSpy();
    server = new FakeModServer({ secret: SECRET });
    await server.start();
    controller = buildController(server);
  });

  afterEach(async () => {
    controller.stop();
    await server.stop();
    vi.restoreAllMocks();
  });

  it("connects and reports the connected state", async () => {
    controller.start();
    await waitFor(() => controller.linkState === "connected");
    expect(server.connectionCount).toBe(1);
  });

  it("sends an authenticated, correlated request and parses the reply", async () => {
    controller.start();
    await waitFor(() => controller.linkState === "connected");

    const status = await controller.status();
    expect(status.state).toBe("IDLE");
    expect(status.protocolVersion).toBe(1);
    const sent = server.recordedFrames.find((frame) => frame.type === "status.get");
    expect(sent).toBeDefined();
    expect(sent?.id).toMatch(/^[0-9a-f]{8}$/);
  });

  it("surfaces a structured remote error", async () => {
    controller.stop();
    await server.stop();
    server = new FakeModServer({ secret: SECRET, responder: () => "error" });
    await server.start();
    controller = buildController(server);
    controller.start();
    await waitFor(() => controller.linkState === "connected");

    await expect(controller.createTask({ kind: "navigate", priority: "normal" })).rejects.toThrowError(
      /the mod refused the request/,
    );
  });

  it("times out a request the mod never answers", async () => {
    controller.stop();
    await server.stop();
    server = new FakeModServer({ secret: SECRET, responder: () => "silent" });
    await server.start();
    controller = buildController(server);
    controller.start();
    await waitFor(() => controller.linkState === "connected");

    await expect(controller.status()).rejects.toThrowError(/timed out/);
  });

  it("refuses to send while disconnected", async () => {
    await expect(controller.status()).rejects.toBeInstanceOf(TransportError);
  });

  it("reconnects automatically after the mod restarts", async () => {
    controller.stop();
    await server.stop();
    // A fixed port lets the restarted server be reachable at the same URL, which is
    // what a real Minecraft client restart looks like to the controller.
    server = new FakeModServer({ secret: SECRET, port: 0 });
    await server.start();
    const port = Number(new URL(server.baseUrl).port);
    controller = buildController(server);
    controller.start();
    await waitFor(() => controller.linkState === "connected");

    await server.stop();
    await waitFor(() => controller.linkState !== "connected");

    server = new FakeModServer({ secret: SECRET, port });
    await server.start();
    await waitFor(() => controller.linkState === "connected", 5_000);
    expect(await controller.status()).toBeDefined();
    expect(server.connectionCount).toBe(1);
  });

  it("rejects an unauthenticated upgrade", async () => {
    controller.stop();
    await server.stop();
    server = new FakeModServer({ secret: "a-completely-different-secret" });
    await server.start();
    controller = buildController(server);
    controller.start();
    // The upgrade is refused, so the link never reaches "connected".
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(controller.linkState).not.toBe("connected");
    expect(server.connectionCount).toBe(0);
  });

  it("reports link state changes to listeners", async () => {
    const seen: string[] = [];
    controller.onStateChange((state) => seen.push(state));
    controller.start();
    await waitFor(() => controller.linkState === "connected");
    expect(seen).toContain("connecting");
    expect(seen).toContain("connected");
  });

  it("delivers unsolicited events to listeners", async () => {
    controller.start();
    await waitFor(() => controller.linkState === "connected");
    const heartbeats: number[] = [];
    controller.onHeartbeat((view) => heartbeats.push(view.activeTasks ?? -1));
    server.broadcast("session.heartbeat", {
      state: "NAVIGATING",
      running: true,
      timestamp: Date.now(),
      activeTasks: 2,
      pendingTasks: 1,
      lastAction: "walking",
    });
    await waitFor(() => heartbeats.length > 0);
    expect(heartbeats[0]).toBe(2);
    expect(controller.lastHeartbeat.state).toBe("NAVIGATING");
  });

  it("stops cleanly and stays stopped", async () => {
    controller.start();
    await waitFor(() => controller.linkState === "connected");
    controller.stop();
    expect(controller.linkState).toBe("stopped");
    await expect(controller.status()).rejects.toBeInstanceOf(TransportError);
  });
});

describe("ModLink reconnect backoff", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("backs off between reconnect attempts instead of hammering the endpoint", async () => {
    vi.spyOn(process.stdout, "write").mockReturnValue(true);
    vi.spyOn(process.stderr, "write").mockReturnValue(true);

    // Nothing is listening on this port, so every attempt fails immediately.
    const link = new ModLink({
      baseUrl: "http://127.0.0.1:1",
      secret: SECRET,
      useTls: false,
      allowInsecureTls: false,
      protocolVersion: 1,
      requestTimeoutMs: 500,
      heartbeatTimeoutMs: 5_000,
      reconnectMinMs: 20,
      reconnectMaxMs: 20,
      logger: quietLogger(),
      backoffRandom: () => 1,
    });
    link.start();
    await new Promise((resolve) => setTimeout(resolve, 300));
    link.stop();
    expect(link.reconnectCount()).toBeGreaterThanOrEqual(2);
    expect(link.currentState()).toBe("stopped");
  });
});
