import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Logger, RedactionRegistry } from "../src/logging/logger.js";

/**
 * Silences console output for the duration of a test. The logger writes through
 * `process.stdout`/`process.stderr`, so spying there is enough.
 */
function muteConsole(): void {
  vi.spyOn(process.stdout, "write").mockReturnValue(true);
  vi.spyOn(process.stderr, "write").mockReturnValue(true);
}

describe("RedactionRegistry", () => {
  it("replaces every occurrence of a registered secret", () => {
    const redaction = new RedactionRegistry();
    redaction.register("super-secret-token");
    expect(redaction.redact("token=super-secret-token&again=super-secret-token")).toBe(
      "token=<redacted>&again=<redacted>",
    );
  });

  it("ignores values that are too short to be a real secret", () => {
    const redaction = new RedactionRegistry();
    redaction.register("abc");
    expect(redaction.redact("abc")).toBe("abc");
  });

  it("redacts nested structures", () => {
    const redaction = new RedactionRegistry();
    redaction.register("topsecretvalue");
    expect(redaction.redactValue({ a: ["topsecretvalue"], b: { c: "topsecretvalue" } })).toEqual({
      a: ["<redacted>"],
      b: { c: "<redacted>" },
    });
  });

  it("redacts Error messages", () => {
    const redaction = new RedactionRegistry();
    redaction.register("leakme-secret");
    expect(redaction.redactValue(new Error("failed with leakme-secret"))).toBe("failed with <redacted>");
  });
});

describe("Logger", () => {
  beforeEach(() => {
    muteConsole();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("respects the configured level", () => {
    const logger = new Logger("warn", 10, new RedactionRegistry());
    logger.debug("scope", "debug line");
    logger.info("scope", "info line");
    logger.warn("scope", "warn line");
    expect(logger.recent(10).map((record) => record.level)).toEqual(["warn"]);
  });

  it("bounds the ring buffer", () => {
    const logger = new Logger("debug", 3, new RedactionRegistry());
    for (let index = 0; index < 10; index += 1) {
      logger.info("scope", `line ${index}`);
    }
    const recent = logger.recent(100);
    expect(recent).toHaveLength(3);
    expect(recent.map((record) => record.message)).toEqual(["line 7", "line 8", "line 9"]);
  });

  it("redacts secrets from the message and the detail", () => {
    const redaction = new RedactionRegistry();
    redaction.register("mod-api-secret-value");
    const logger = new Logger("debug", 10, redaction);
    logger.error("scope", "failed using mod-api-secret-value", { secret: "mod-api-secret-value" });
    const record = logger.recent(1)[0];
    expect(record?.message).toBe("failed using <redacted>");
    expect(record?.detail).toContain("<redacted>");
    expect(record?.detail).not.toContain("mod-api-secret-value");
  });

  it("survives a non-serialisable detail", () => {
    const logger = new Logger("debug", 10, new RedactionRegistry());
    const circular: Record<string, unknown> = {};
    circular["self"] = circular;
    logger.info("scope", "circular detail", circular);
    expect(logger.recent(1)[0]?.detail).toBe("<unserialisable>");
  });
});
