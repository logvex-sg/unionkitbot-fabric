import {
  MAX_FRAME_CHARS,
  MESSAGE_TYPES,
  PROTOCOL_VERSION,
  messageTypeFromWireName,
  type MessageTypeName,
} from "./messages.js";

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonObject | JsonValue[];
export interface JsonObject {
  readonly [key: string]: JsonValue;
}

/**
 * A single protocol frame.
 *
 * `data` is always an object: the mod rejects non-object payloads, so normalising
 * here keeps every consumer free of shape checks.
 */
export interface Envelope {
  readonly v: number;
  readonly type: MessageTypeName;
  readonly id: string | undefined;
  readonly timestamp: number;
  readonly data: JsonObject;
}

/** Raised when a frame fails validation. */
export class ProtocolError extends Error {
  public readonly path: string;

  public constructor(path: string, message: string) {
    super(message);
    this.name = "ProtocolError";
    this.path = path;
  }
}

/** Raised when the mod answered a request with an error frame. */
export class RemoteError extends Error {
  public readonly code: string;
  public readonly path: string | undefined;
  public readonly retryable: boolean;

  public constructor(code: string, message: string, path: string | undefined, retryable: boolean) {
    super(message);
    this.name = "RemoteError";
    this.code = code;
    this.path = path;
    this.retryable = retryable;
  }
}

const FRAME_KEYS = ["v", "type", "id", "ts", "data"] as const;

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireString(source: Record<string, unknown>, member: string, path: string): string {
  const value = source[member];
  if (typeof value !== "string" || value.trim() === "") {
    throw new ProtocolError(`${path}.${member}`, `${member} must be a non-empty string`);
  }
  return value;
}

/**
 * Validates and normalises a decoded JSON value into an {@link Envelope}.
 *
 * @param raw the parsed JSON value
 * @throws ProtocolError when the frame is malformed or uses an unsupported version
 */
export function parseEnvelope(raw: unknown): Envelope {
  if (!isJsonObject(raw)) {
    throw new ProtocolError("", "a frame must be a JSON object");
  }

  for (const key of Object.keys(raw)) {
    if (!(FRAME_KEYS as readonly string[]).includes(key)) {
      throw new ProtocolError(key, `unknown frame member '${key}'`);
    }
  }

  const version = raw["v"];
  if (typeof version !== "number" || !Number.isInteger(version)) {
    throw new ProtocolError("v", "v must be an integer protocol version");
  }
  if (version !== PROTOCOL_VERSION) {
    throw new ProtocolError("v", `unsupported protocol version ${version}`);
  }

  const wireType = requireString(raw, "type", "");
  const type = messageTypeFromWireName(wireType);
  if (type === undefined) {
    throw new ProtocolError("type", `unknown frame type '${wireType}'`);
  }

  const idRaw = raw["id"];
  if (idRaw !== undefined && idRaw !== null && typeof idRaw !== "string") {
    throw new ProtocolError("id", "id must be a string when present");
  }
  const id = typeof idRaw === "string" && idRaw !== "" ? idRaw : undefined;

  const timestampRaw = raw["ts"];
  if (
    timestampRaw !== undefined &&
    (typeof timestampRaw !== "number" || !Number.isFinite(timestampRaw))
  ) {
    throw new ProtocolError("ts", "ts must be a finite number when present");
  }
  const timestamp = typeof timestampRaw === "number" ? timestampRaw : 0;

  const dataRaw = raw["data"];
  let data: JsonObject = {};
  if (dataRaw !== undefined && dataRaw !== null) {
    if (!isJsonObject(dataRaw)) {
      throw new ProtocolError("data", "data must be a JSON object");
    }
    data = dataRaw as JsonObject;
  }

  return { v: version, type, id, timestamp, data };
}

/**
 * Parses a raw frame string.
 *
 * @param text the frame text
 * @throws ProtocolError when the text is oversized, not JSON, or fails validation
 */
export function decodeEnvelope(text: string): Envelope {
  if (text.length > MAX_FRAME_CHARS) {
    throw new ProtocolError("", `frame exceeds ${MAX_FRAME_CHARS} characters`);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    const detail = error instanceof Error ? error.message : "invalid JSON";
    throw new ProtocolError("", `frame is not valid JSON: ${detail}`);
  }
  return parseEnvelope(parsed);
}

/**
 * Builds an outbound request frame.
 *
 * @param type the request type; must be inbound relative to the mod
 * @param data the payload, defaults to an empty object
 * @param id the correlation id, generated when omitted
 */
export function buildRequest(
  type: MessageTypeName,
  data: JsonObject = {},
  id: string = newCorrelationId(),
): Envelope {
  if (MESSAGE_TYPES[type].direction !== "REQUEST") {
    throw new ProtocolError("type", `${type} is not a request type`);
  }
  return { v: PROTOCOL_VERSION, type, id, timestamp: Date.now(), data };
}

/**
 * @returns a short correlation id
 */
export function newCorrelationId(): string {
  const buffer = new Uint8Array(4);
  globalThis.crypto.getRandomValues(buffer);
  return Array.from(buffer, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * Serialises a frame for transmission.
 */
export function encodeEnvelope(envelope: Envelope): string {
  const out: Record<string, unknown> = {
    v: envelope.v,
    type: MESSAGE_TYPES[envelope.type].wireName,
  };
  if (envelope.id !== undefined) {
    out["id"] = envelope.id;
  }
  out["ts"] = envelope.timestamp;
  out["data"] = envelope.data;
  return JSON.stringify(out);
}

/**
 * Reads a nested object member.
 */
export function objectAt(source: JsonObject, member: string): JsonObject | undefined {
  const value = source[member];
  return isJsonObject(value) ? (value as JsonObject) : undefined;
}

/**
 * Reads a string member.
 */
export function stringAt(source: JsonObject, member: string): string | undefined {
  const value = source[member];
  return typeof value === "string" ? value : undefined;
}

/**
 * Reads a finite number member.
 */
export function numberAt(source: JsonObject, member: string): number | undefined {
  const value = source[member];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/**
 * Reads a boolean member.
 */
export function booleanAt(source: JsonObject, member: string): boolean | undefined {
  const value = source[member];
  return typeof value === "boolean" ? value : undefined;
}

/**
 * Reads an array member of objects.
 */
export function objectArrayAt(source: JsonObject, member: string): JsonObject[] {
  const value = source[member];
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter(isJsonObject) as JsonObject[];
}

/**
 * Extracts a structured error from a `response.error` frame.
 *
 * The mod sends the error object as the frame payload itself; a nested `error`
 * member is also accepted so either shape works.
 *
 * @throws ProtocolError when the payload is not a structured error
 */
export function remoteErrorFrom(envelope: Envelope): RemoteError {
  if (envelope.type !== "RESPONSE_ERROR") {
    throw new ProtocolError("type", "expected a response.error frame");
  }
  const nested = objectAt(envelope.data, "error");
  const error = nested ?? envelope.data;
  const code = stringAt(error, "code");
  if (code === undefined) {
    return new RemoteError("unknown_error", "the mod reported an unspecified error", undefined, false);
  }
  return new RemoteError(
    code,
    stringAt(error, "message") ?? "the mod reported an unspecified error",
    stringAt(error, "path"),
    booleanAt(error, "retryable") ?? false,
  );
}
