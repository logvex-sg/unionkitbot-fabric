/**
 * Typed, validated view of the process environment.
 *
 * Nothing here reads a secret into a log line: values are only exposed through
 * {@link AppConfig}, and {@link describeConfig} deliberately omits them.
 */

export type LogLevel = "debug" | "info" | "warn" | "error";

export interface DiscordConfig {
  readonly token: string;
  readonly applicationId: string;
  readonly guildId: string | undefined;
  readonly adminUserIds: ReadonlySet<string>;
  readonly adminRoleIds: ReadonlySet<string>;
  readonly allowedGuildId: string | undefined;
}

export interface ModApiConfig {
  readonly baseUrl: string;
  readonly secret: string;
  readonly useTls: boolean;
  readonly allowInsecureTls: boolean;
  readonly protocolVersion: number;
}

export interface ConnectionConfig {
  readonly requestTimeoutMs: number;
  readonly heartbeatTimeoutMs: number;
  readonly reconnectMinMs: number;
  readonly reconnectMaxMs: number;
}

export interface AppConfig {
  readonly discord: DiscordConfig;
  readonly mod: ModApiConfig;
  readonly connection: ConnectionConfig;
  readonly logLevel: LogLevel;
  readonly logBufferSize: number;
}

export class ConfigError extends Error {
  public readonly variable: string;

  public constructor(variable: string, message: string) {
    super(message);
    this.name = "ConfigError";
    this.variable = variable;
  }
}

const LOG_LEVELS: readonly LogLevel[] = ["debug", "info", "warn", "error"];

function requireValue(env: NodeJS.ProcessEnv, name: string): string {
  const raw = env[name];
  const value = raw === undefined ? "" : raw.trim();
  if (value === "") {
    throw new ConfigError(name, `${name} is required but was not set`);
  }
  return value;
}

function optionalValue(env: NodeJS.ProcessEnv, name: string): string | undefined {
  const raw = env[name];
  if (raw === undefined) {
    return undefined;
  }
  const value = raw.trim();
  return value === "" ? undefined : value;
}

function parseIdSet(env: NodeJS.ProcessEnv, name: string): ReadonlySet<string> {
  const raw = optionalValue(env, name);
  if (raw === undefined) {
    return new Set<string>();
  }
  const ids = raw
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
  for (const id of ids) {
    if (!/^\d{5,32}$/.test(id)) {
      throw new ConfigError(name, `${name} contains an entry that is not a Discord snowflake`);
    }
  }
  return new Set(ids);
}

function parseInteger(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
  min: number,
  max: number,
): number {
  const raw = optionalValue(env, name);
  if (raw === undefined) {
    return fallback;
  }
  if (!/^-?\d+$/.test(raw)) {
    throw new ConfigError(name, `${name} must be an integer`);
  }
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value) || value < min || value > max) {
    throw new ConfigError(name, `${name} must be between ${min} and ${max}`);
  }
  return value;
}

function parseBoolean(env: NodeJS.ProcessEnv, name: string, fallback: boolean): boolean {
  const raw = optionalValue(env, name);
  if (raw === undefined) {
    return fallback;
  }
  const lowered = raw.toLowerCase();
  if (lowered === "true" || lowered === "1" || lowered === "yes") {
    return true;
  }
  if (lowered === "false" || lowered === "0" || lowered === "no") {
    return false;
  }
  throw new ConfigError(name, `${name} must be a boolean (true/false)`);
}

function parseLogLevel(env: NodeJS.ProcessEnv): LogLevel {
  const raw = optionalValue(env, "LOG_LEVEL");
  if (raw === undefined) {
    return "info";
  }
  const lowered = raw.toLowerCase();
  const match = LOG_LEVELS.find((level) => level === lowered);
  if (match === undefined) {
    throw new ConfigError("LOG_LEVEL", `LOG_LEVEL must be one of ${LOG_LEVELS.join(", ")}`);
  }
  return match;
}

function parseBaseUrl(env: NodeJS.ProcessEnv): string {
  const raw = requireValue(env, "MOD_API_URL");
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    throw new ConfigError("MOD_API_URL", "MOD_API_URL must be an absolute http(s) URL");
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new ConfigError("MOD_API_URL", "MOD_API_URL must use http or https");
  }
  // Normalise away a trailing slash so path joins stay predictable.
  return parsed.origin + parsed.pathname.replace(/\/+$/, "");
}

/**
 * Builds the application configuration from an environment map.
 *
 * @throws ConfigError when a required variable is missing or malformed
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const discord: DiscordConfig = {
    token: requireValue(env, "DISCORD_TOKEN"),
    applicationId: requireValue(env, "DISCORD_APPLICATION_ID"),
    guildId: optionalValue(env, "DISCORD_GUILD_ID"),
    adminUserIds: parseIdSet(env, "DISCORD_ADMIN_USER_IDS"),
    adminRoleIds: parseIdSet(env, "DISCORD_ADMIN_ROLE_IDS"),
    allowedGuildId: optionalValue(env, "DISCORD_ALLOWED_GUILD_ID"),
  };

  if (discord.adminUserIds.size === 0 && discord.adminRoleIds.size === 0) {
    throw new ConfigError(
      "DISCORD_ADMIN_USER_IDS",
      "set DISCORD_ADMIN_USER_IDS and/or DISCORD_ADMIN_ROLE_IDS; administrative commands are unusable otherwise",
    );
  }

  const mod: ModApiConfig = {
    baseUrl: parseBaseUrl(env),
    secret: requireValue(env, "MOD_API_SECRET"),
    useTls: parseBoolean(env, "MOD_API_USE_TLS", false),
    allowInsecureTls: parseBoolean(env, "MOD_API_ALLOW_INSECURE_TLS", false),
    protocolVersion: parseInteger(env, "MOD_PROTOCOL_VERSION", 1, 1, 1_000),
  };

  if (mod.secret.length < 16) {
    throw new ConfigError("MOD_API_SECRET", "MOD_API_SECRET must be at least 16 characters");
  }

  const connection: ConnectionConfig = {
    requestTimeoutMs: parseInteger(env, "MOD_REQUEST_TIMEOUT_MS", 10_000, 500, 120_000),
    heartbeatTimeoutMs: parseInteger(env, "MOD_HEARTBEAT_TIMEOUT_MS", 45_000, 5_000, 600_000),
    reconnectMinMs: parseInteger(env, "MOD_RECONNECT_MIN_MS", 1_000, 100, 300_000),
    reconnectMaxMs: parseInteger(env, "MOD_RECONNECT_MAX_MS", 60_000, 1_000, 3_600_000),
  };

  if (connection.reconnectMaxMs < connection.reconnectMinMs) {
    throw new ConfigError(
      "MOD_RECONNECT_MAX_MS",
      "MOD_RECONNECT_MAX_MS must be greater than or equal to MOD_RECONNECT_MIN_MS",
    );
  }

  return {
    discord,
    mod,
    connection,
    logLevel: parseLogLevel(env),
    logBufferSize: parseInteger(env, "LOG_BUFFER_SIZE", 200, 10, 5_000),
  };
}

/**
 * Renders a secret-free description of the configuration for startup logs.
 */
export function describeConfig(config: AppConfig): Record<string, string | number | boolean> {
  return {
    modApiUrl: config.mod.baseUrl,
    modApiSecret: config.mod.secret === "" ? "<unset>" : "<configured>",
    modProtocolVersion: config.mod.protocolVersion,
    discordGuildId: config.discord.guildId ?? "<global>",
    discordAdmins: config.discord.adminUserIds.size + config.discord.adminRoleIds.size,
    requestTimeoutMs: config.connection.requestTimeoutMs,
    reconnectMinMs: config.connection.reconnectMinMs,
    reconnectMaxMs: config.connection.reconnectMaxMs,
    logLevel: config.logLevel,
  };
}
