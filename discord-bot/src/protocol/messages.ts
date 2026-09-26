/**
 * Wire protocol shared with the Fabric mod.
 *
 * The constants here mirror `dev.unionkitbot.fabric.protocol.ProtocolVersion` and
 * `MessageType`. They are duplicated rather than generated so the controller stays
 * independently buildable; a mismatch is caught at handshake time by comparing
 * {@link PROTOCOL_VERSION}.
 */

/** Protocol version this controller speaks. */
export const PROTOCOL_VERSION = 1;

/** Header carrying the pre-shared secret on HTTP and WebSocket requests. */
export const AUTH_HEADER = "X-UnionKitBot-Secret";

/** Query parameter accepted as an alternative to {@link AUTH_HEADER}. */
export const AUTH_QUERY_PARAM = "secret";

/** Header carrying the protocol version during the WebSocket upgrade. */
export const VERSION_HEADER = "X-UnionKitBot-Protocol";

/** WebSocket subprotocol offered during the upgrade. */
export const WS_SUBPROTOCOL = "unionkitbot.v1";

/** Largest frame the mod will accept, in UTF-16 code units. */
export const MAX_FRAME_CHARS = 1_048_576;

/** Direction of a frame relative to the Fabric mod. */
export type MessageDirection = "REQUEST" | "RESPONSE" | "EVENT";

export interface MessageTypeDescriptor {
  readonly wireName: string;
  readonly direction: MessageDirection;
}

/**
 * Every frame type understood by the control protocol, keyed by enum-style name.
 */
export const MESSAGE_TYPES = {
  // Requests: Discord bot -> Fabric mod.
  HELLO: { wireName: "session.hello", direction: "REQUEST" },
  STATUS_GET: { wireName: "status.get", direction: "REQUEST" },
  START: { wireName: "control.start", direction: "REQUEST" },
  STOP: { wireName: "control.stop", direction: "REQUEST" },
  RESTART: { wireName: "control.restart", direction: "REQUEST" },
  TASKS_LIST: { wireName: "tasks.list", direction: "REQUEST" },
  TASK_GET: { wireName: "tasks.get", direction: "REQUEST" },
  TASK_CREATE: { wireName: "tasks.create", direction: "REQUEST" },
  TASK_CANCEL: { wireName: "tasks.cancel", direction: "REQUEST" },
  TASKS_CANCEL_ALL: { wireName: "tasks.cancelall", direction: "REQUEST" },
  LOGS_GET: { wireName: "logs.get", direction: "REQUEST" },
  CONFIG_GET: { wireName: "config.get", direction: "REQUEST" },
  CONFIG_PATCH: { wireName: "config.patch", direction: "REQUEST" },
  MODULES_SET: { wireName: "modules.set", direction: "REQUEST" },
  HOME_SET: { wireName: "home.set", direction: "REQUEST" },
  PING: { wireName: "session.ping", direction: "REQUEST" },

  // Responses: Fabric mod -> Discord bot.
  RESPONSE_OK: { wireName: "response.ok", direction: "RESPONSE" },
  RESPONSE_ERROR: { wireName: "response.error", direction: "RESPONSE" },

  // Events: Fabric mod -> Discord bot, unsolicited.
  WELCOME: { wireName: "session.welcome", direction: "EVENT" },
  HEARTBEAT: { wireName: "session.heartbeat", direction: "EVENT" },
  STATE_CHANGED: { wireName: "state.changed", direction: "EVENT" },
  TASK_CREATED: { wireName: "task.created", direction: "EVENT" },
  TASK_UPDATED: { wireName: "task.updated", direction: "EVENT" },
  TASK_COMPLETED: { wireName: "task.completed", direction: "EVENT" },
  TASK_FAILED: { wireName: "task.failed", direction: "EVENT" },
  TASK_CANCELLED: { wireName: "task.cancelled", direction: "EVENT" },
  ERROR: { wireName: "system.error", direction: "EVENT" },
  LOG: { wireName: "system.log", direction: "EVENT" },
  CONFIG_CHANGED: { wireName: "config.changed", direction: "EVENT" },
  MODULES_CHANGED: { wireName: "modules.changed", direction: "EVENT" },
  GOODBYE: { wireName: "session.goodbye", direction: "EVENT" },
} as const satisfies Record<string, MessageTypeDescriptor>;

export type MessageTypeName = keyof typeof MESSAGE_TYPES;

const BY_WIRE_NAME: ReadonlyMap<string, MessageTypeName> = new Map(
  (Object.keys(MESSAGE_TYPES) as MessageTypeName[]).map((name) => [
    MESSAGE_TYPES[name].wireName,
    name,
  ]),
);

/**
 * Resolves a wire name to its enum-style name.
 *
 * @param wireName the wire name, case insensitive
 * @returns the message type name, or undefined when unknown
 */
export function messageTypeFromWireName(wireName: string): MessageTypeName | undefined {
  return BY_WIRE_NAME.get(wireName.trim().toLowerCase());
}

/**
 * @param name the message type name
 * @returns the stable wire name
 */
export function wireNameOf(name: MessageTypeName): string {
  return MESSAGE_TYPES[name].wireName;
}

/**
 * @param name the message type name
 * @returns true when the mod may emit this type
 */
export function isOutbound(name: MessageTypeName): boolean {
  return MESSAGE_TYPES[name].direction !== "REQUEST";
}

/**
 * @param name the message type name
 * @returns true when the controller may emit this type
 */
export function isInbound(name: MessageTypeName): boolean {
  return MESSAGE_TYPES[name].direction === "REQUEST";
}

/** Agent lifecycle states reported by the mod. */
export const BOT_STATES = [
  "OFFLINE",
  "CONNECTING",
  "SPAWNING",
  "IDLE",
  "NAVIGATING",
  "DELIVERING",
  "SCANNING",
  "RECOVERING",
  "DISCONNECTED",
  "DEAD",
  "ERROR",
] as const;

export type BotState = (typeof BOT_STATES)[number];

/**
 * @param value a raw state string from the mod
 * @returns true when the value is a known lifecycle state
 */
export function isBotState(value: unknown): value is BotState {
  return typeof value === "string" && (BOT_STATES as readonly string[]).includes(value);
}

/** Task kinds accepted by the mod. */
export const TASK_KINDS = ["navigate", "return_home", "deliver", "scan", "recover", "wait"] as const;

export type TaskKind = (typeof TASK_KINDS)[number];

/**
 * @param value a raw task kind
 * @returns true when the value is a known task kind
 */
export function isTaskKind(value: unknown): value is TaskKind {
  return typeof value === "string" && (TASK_KINDS as readonly string[]).includes(value);
}

/** Task priorities accepted by the mod. */
export const TASK_PRIORITIES = ["low", "normal", "high", "critical"] as const;

export type TaskPriority = (typeof TASK_PRIORITIES)[number];

/**
 * @param value a raw task priority
 * @returns true when the value is a known priority
 */
export function isTaskPriority(value: unknown): value is TaskPriority {
  return typeof value === "string" && (TASK_PRIORITIES as readonly string[]).includes(value);
}

/** Task statuses reported by the mod. */
export const TASK_STATUSES = [
  "PENDING",
  "RUNNING",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "RETRYING",
] as const;

export type TaskStatus = (typeof TASK_STATUSES)[number];

/** Automation module names. */
export const MODULE_NAMES = ["navigation", "delivery", "scanning", "recovery"] as const;

export type ModuleName = (typeof MODULE_NAMES)[number];
