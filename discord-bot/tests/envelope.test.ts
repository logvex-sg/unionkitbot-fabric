import { describe, expect, it } from "vitest";

import {
  ProtocolError,
  RemoteError,
  buildRequest,
  decodeEnvelope,
  encodeEnvelope,
  parseEnvelope,
  remoteErrorFrom,
  type Envelope,
} from "../src/protocol/envelope.js";
import {
  MAX_FRAME_CHARS,
  MESSAGE_TYPES,
  messageTypeFromWireName,
  wireNameOf,
  type MessageTypeName,
} from "../src/protocol/messages.js";

describe("message type registry", () => {
  it("has unique, namespaced wire names", () => {
    const names = Object.keys(MESSAGE_TYPES) as MessageTypeName[];
    const wireNames = names.map(wireNameOf);
    expect(new Set(wireNames).size).toBe(wireNames.length);
    for (const wireName of wireNames) {
      expect(wireName).toMatch(/^[a-z]+(\.[a-z]+)+$/);
    }
  });

  it("resolves wire names case insensitively", () => {
    expect(messageTypeFromWireName("STATUS.GET")).toBe("STATUS_GET");
    expect(messageTypeFromWireName(" control.start ")).toBe("START");
    expect(messageTypeFromWireName("nope")).toBeUndefined();
  });

  it("partitions requests from outbound frames", () => {
    for (const [name, descriptor] of Object.entries(MESSAGE_TYPES)) {
      if (descriptor.direction === "REQUEST") {
        expect(buildRequest(name as MessageTypeName).type).toBe(name);
      } else {
        expect(() => buildRequest(name as MessageTypeName)).toThrowError(ProtocolError);
      }
    }
  });
});

describe("parseEnvelope", () => {
  it("normalises a minimal frame", () => {
    const envelope = parseEnvelope({ v: 1, type: "status.get" });
    expect(envelope.type).toBe("STATUS_GET");
    expect(envelope.id).toBeUndefined();
    expect(envelope.timestamp).toBe(0);
    expect(envelope.data).toEqual({});
  });

  it("rejects a non-object frame", () => {
    expect(() => parseEnvelope([])).toThrowError(/must be a JSON object/);
    expect(() => parseEnvelope("{}")).toThrowError(/must be a JSON object/);
  });

  it("rejects an unknown member", () => {
    expect(() => parseEnvelope({ v: 1, type: "status.get", surprise: true })).toThrowError(
      /unknown frame member/,
    );
  });

  it("rejects a missing or non-integer version", () => {
    expect(() => parseEnvelope({ type: "status.get" })).toThrowError(/v must be an integer/);
    expect(() => parseEnvelope({ v: 1.5, type: "status.get" })).toThrowError(/v must be an integer/);
  });

  it("rejects an unsupported version", () => {
    expect(() => parseEnvelope({ v: 99, type: "status.get" })).toThrowError(/unsupported protocol/);
  });

  it("rejects an unknown type", () => {
    expect(() => parseEnvelope({ v: 1, type: "not.a.type" })).toThrowError(/unknown frame type/);
  });

  it("rejects a non-string id", () => {
    expect(() => parseEnvelope({ v: 1, type: "status.get", id: 42 })).toThrowError(/id must be a string/);
  });

  it("rejects a non-object data member", () => {
    expect(() => parseEnvelope({ v: 1, type: "status.get", data: [1, 2] })).toThrowError(
      /data must be a JSON object/,
    );
  });

  it("rejects a non-finite timestamp", () => {
    expect(() => parseEnvelope({ v: 1, type: "status.get", ts: Number.NaN })).toThrowError(
      /ts must be a finite number/,
    );
  });

  it("rejects the legacy timestamp member name", () => {
    // The mod's frame field is `ts`; accepting `timestamp` would silently drop it.
    expect(() => parseEnvelope({ v: 1, type: "status.get", timestamp: 1 })).toThrowError(
      /unknown frame member 'timestamp'/,
    );
  });
});

describe("decodeEnvelope", () => {
  it("rejects malformed JSON", () => {
    expect(() => decodeEnvelope("{not json")).toThrowError(/not valid JSON/);
  });

  it("rejects an oversized frame", () => {
    const padding = "x".repeat(MAX_FRAME_CHARS + 1);
    expect(() => decodeEnvelope(padding)).toThrowError(/exceeds/);
  });

  it("round-trips a request with its correlation id", () => {
    const request = buildRequest("TASK_CREATE", { kind: "navigate" }, "abc12345");
    const encoded = encodeEnvelope(request);
    expect(JSON.parse(encoded)).toMatchObject({ v: 1, type: "tasks.create", id: "abc12345" });
    const decoded = decodeEnvelope(encoded);
    expect(decoded.type).toBe("TASK_CREATE");
    expect(decoded.id).toBe("abc12345");
    expect(decoded.data["kind"]).toBe("navigate");
    expect(decoded.timestamp).toBe(request.timestamp);
  });
});

describe("buildRequest", () => {
  it("generates unique correlation ids", () => {
    const ids = new Set(Array.from({ length: 200 }, () => buildRequest("PING").id));
    expect(ids.size).toBe(200);
    for (const id of ids) {
      expect(id).toMatch(/^[0-9a-f]{8}$/);
    }
  });

  it("refuses to build an event frame", () => {
    expect(() => buildRequest("STATE_CHANGED")).toThrowError(/not a request type/);
  });
});

describe("remoteErrorFrom", () => {
  it("extracts a structured error", () => {
    const envelope: Envelope = {
      v: 1,
      type: "RESPONSE_ERROR",
      id: "abc",
      timestamp: 0,
      data: {
        error: {
          code: "invalid_request",
          message: "bad kind",
          path: "kind",
          retryable: false,
        },
      },
    };
    const error = remoteErrorFrom(envelope);
    expect(error).toBeInstanceOf(RemoteError);
    expect(error.code).toBe("invalid_request");
    expect(error.message).toBe("bad kind");
    expect(error.path).toBe("kind");
    expect(error.retryable).toBe(false);
  });

  it("falls back to a safe default for an empty payload", () => {
    const error = remoteErrorFrom({ v: 1, type: "RESPONSE_ERROR", id: undefined, timestamp: 0, data: {} });
    expect(error.code).toBe("unknown_error");
    expect(error.retryable).toBe(false);
  });

  it("accepts the mod's flat error payload", () => {
    const error = remoteErrorFrom({
      v: 1,
      type: "RESPONSE_ERROR",
      id: undefined,
      timestamp: 0,
      data: { code: "invalid_request", message: "bad kind", path: "kind", retryable: true },
    });
    expect(error.code).toBe("invalid_request");
    expect(error.message).toBe("bad kind");
    expect(error.path).toBe("kind");
    expect(error.retryable).toBe(true);
  });

  it("accepts a nested error payload", () => {
    const error = remoteErrorFrom({
      v: 1,
      type: "RESPONSE_ERROR",
      id: undefined,
      timestamp: 0,
      data: { error: { code: "internal_error", message: "boom", retryable: false } },
    });
    expect(error.code).toBe("internal_error");
    expect(error.message).toBe("boom");
  });

  it("refuses a non-error frame", () => {
    expect(() =>
      remoteErrorFrom({ v: 1, type: "RESPONSE_OK", id: undefined, timestamp: 0, data: {} }),
    ).toThrowError(/expected a response.error/);
  });
});
