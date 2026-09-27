/**
 * Preflight and connectivity checks: `npm run doctor`.
 *
 * The point is to turn the two most common failures - a wrong token and a
 * mismatched secret - into a single command with an actionable message, instead
 * of a stack trace from somewhere deep in the gateway code. Checks run in order
 * and stop at the first hard failure, because later checks depend on earlier ones.
 */

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { ConfigError, loadConfig, type AppConfig } from "./config/env.js";
import { loadDotEnv } from "./config/dotenv.js";
import { modConfigCandidates } from "./config/wizard.js";
import { HttpClient } from "./modlink/http-client.js";
import { Logger, RedactionRegistry } from "./logging/logger.js";
import { VERSION_HEADER } from "./protocol/messages.js";

/** The mod's secret file name, mirroring `ConfigManager.SECRET_FILE`. */
const MOD_SECRET_FILE = "unionkitbot.secret";

/** REST base for the small amount of Discord verification done here. */
const DISCORD_API = "https://discord.com/api/v10";

type Status = "ok" | "warn" | "fail";

interface Check {
  readonly status: Status;
  readonly title: string;
  readonly detail: string;
  readonly hint?: string;
}

function ok(title: string, detail: string): Check {
  return { status: "ok", title, detail };
}

function warn(title: string, detail: string, hint?: string): Check {
  return hint === undefined
    ? { status: "warn", title, detail }
    : { status: "warn", title, detail, hint };
}

function fail(title: string, detail: string, hint?: string): Check {
  return hint === undefined ? { status: "fail", title, detail } : { status: "fail", title, detail, hint };
}

const SYMBOL: Record<Status, string> = { ok: "PASS", warn: "WARN", fail: "FAIL" };

/**
 * Reads the mod secret from disk if it can be located.
 *
 * @param env the process environment
 * @returns the secret and its path, or `undefined`
 */
function findModSecret(env: NodeJS.ProcessEnv): { path: string; secret: string } | undefined {
  for (const directory of modConfigCandidates(process.platform, env)) {
    const path = join(directory, MOD_SECRET_FILE);
    if (!existsSync(path)) {
      continue;
    }
    const secret = readFileSync(path, "utf8").trim();
    if (secret.length > 0) {
      return { path, secret };
    }
  }
  return undefined;
}

/** Verifies the token with Discord's own API. */
async function checkDiscordToken(config: AppConfig): Promise<Check> {
  let response: Response;
  try {
    response = await fetch(`${DISCORD_API}/users/@me`, {
      headers: { Authorization: `Bot ${config.discord.token}` },
    });
  } catch (error) {
    return fail(
      "Discord reachable",
      `request failed: ${error instanceof Error ? error.message : String(error)}`,
      "Check this machine's network and any proxy settings.",
    );
  }

  if (response.status === 401) {
    return fail(
      "Discord token",
      "Discord rejected the token (401)",
      "Reset the token in Developer Portal -> Bot, then run `npm run setup` again.",
    );
  }
  if (!response.ok) {
    return fail("Discord token", `unexpected status ${response.status} from Discord`);
  }

  const body: unknown = await response.json();
  const username =
    typeof body === "object" && body !== null && "username" in body
      ? String((body as { username: unknown }).username)
      : "unknown";
  const id =
    typeof body === "object" && body !== null && "id" in body
      ? String((body as { id: unknown }).id)
      : "unknown";
  return ok("Discord token", `authenticated as ${username} (${id})`);
}

/** Confirms the application id matches the token's application. */
async function checkApplication(config: AppConfig): Promise<Check> {
  let response: Response;
  try {
    response = await fetch(`${DISCORD_API}/applications/@me`, {
      headers: { Authorization: `Bot ${config.discord.token}` },
    });
  } catch (error) {
    return warn(
      "Application ID",
      `could not verify: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
  if (!response.ok) {
    return warn("Application ID", `could not verify (status ${response.status})`);
  }
  const body: unknown = await response.json();
  const actual =
    typeof body === "object" && body !== null && "id" in body
      ? String((body as { id: unknown }).id)
      : undefined;
  if (actual === undefined) {
    return warn("Application ID", "Discord did not return an application id");
  }
  if (actual !== config.discord.applicationId) {
    return fail(
      "Application ID",
      `DISCORD_APPLICATION_ID is ${config.discord.applicationId} but the token belongs to ${actual}`,
      "Copy the Application ID from General Information; slash commands will not register otherwise.",
    );
  }
  return ok("Application ID", `matches the token (${actual})`);
}

/** Checks whether the mod secret on disk agrees with `.env`. */
function checkSecretAgreement(config: AppConfig, env: NodeJS.ProcessEnv): Check {
  const found = findModSecret(env);
  if (found === undefined) {
    return warn(
      "Mod secret file",
      "could not find unionkitbot.secret in the usual locations",
      "Start Minecraft once with the mod installed, or set UNIONKITBOT_MOD_DIR to the config directory.",
    );
  }
  if (found.secret !== config.mod.secret) {
    return fail(
      "Mod secret agrees",
      `${found.path} differs from MOD_API_SECRET in .env`,
      "Re-run `npm run setup`, which reads the secret from that file for you.",
    );
  }
  return ok("Mod secret agrees", "the value in .env matches the mod's secret file");
}

/** Probes the mod's unauthenticated health endpoint. */
async function checkModReachable(config: AppConfig, logger: Logger): Promise<Check> {
  const client = new HttpClient({
    baseUrl: config.mod.baseUrl,
    secret: config.mod.secret,
    timeoutMs: Math.min(config.connection.requestTimeoutMs, 4_000),
    protocolVersion: config.mod.protocolVersion,
    logger,
  });
  try {
    const body = await client.health();
    const protocol = body["protocolVersion"];
    if (typeof protocol === "number" && protocol !== config.mod.protocolVersion) {
      return warn(
        "Mod reachable",
        `the mod speaks protocol ${protocol}; .env expects ${config.mod.protocolVersion}`,
        "Set MOD_PROTOCOL_VERSION to match the mod.",
      );
    }
    return ok("Mod reachable", `health OK at ${config.mod.baseUrl} (protocol ${String(protocol ?? "?")})`);
  } catch (error) {
    return warn(
      "Mod reachable",
      `no answer from ${config.mod.baseUrl}: ${error instanceof Error ? error.message : String(error)}`,
      "Expected when Minecraft is not running. Start the game with the mod loaded; the bot retries on its own.",
    );
  }
}

/** Probes an authenticated endpoint, which is what catches a secret mismatch. */
async function checkModAuth(config: AppConfig): Promise<Check> {
  let response: Response;
  try {
    response = await fetch(`${config.mod.baseUrl}/status`, {
      headers: {
        "X-UnionKitBot-Secret": config.mod.secret,
        [VERSION_HEADER]: String(config.mod.protocolVersion),
      },
      signal: AbortSignal.timeout(Math.min(config.connection.requestTimeoutMs, 4_000)),
    });
  } catch (error) {
    return warn(
      "Mod authenticated",
      `could not check: ${error instanceof Error ? error.message : String(error)}`,
      "Start Minecraft with the mod loaded to verify this.",
    );
  }

  if (response.status === 401) {
    return fail(
      "Mod authenticated",
      "the mod rejected the secret (401)",
      "Re-run `npm run setup` to copy the secret from unionkitbot.secret, then restart Minecraft.",
    );
  }
  if (response.status === 403) {
    return fail(
      "Mod authenticated",
      "the mod refused the client (403)",
      "The mod allows loopback only; set api.allowRemote to true only if you use a TLS proxy.",
    );
  }
  if (!response.ok) {
    return warn("Mod authenticated", `unexpected status ${response.status} from the mod`);
  }
  return ok("Mod authenticated", "the mod accepted the secret");
}

async function main(): Promise<number> {
  const applied = loadDotEnv();
  const checks: Check[] = [];

  let config: AppConfig;
  const redaction = new RedactionRegistry();
  try {
    config = loadConfig(process.env);
  } catch (error) {
    if (error instanceof ConfigError) {
      process.stderr.write(`FAIL  Configuration: ${error.variable}: ${error.message}\n`);
      process.stderr.write("      Run `npm run setup` to create .env interactively.\n");
      return 2;
    }
    throw error;
  }

  redaction.register(config.discord.token);
  redaction.register(config.mod.secret);
  const logger = new Logger(config.logLevel, config.logBufferSize, redaction);

  checks.push(
    applied.length > 0
      ? ok("Configuration", `loaded ${applied.length} value(s) from .env`)
      : warn("Configuration", "no .env file found; using the process environment only"),
  );
  checks.push(ok("Admin access", `${config.discord.adminUserIds.size} user(s), ${config.discord.adminRoleIds.size} role(s)`));
  checks.push(
    config.discord.guildId === undefined
      ? warn(
          "Guild scope",
          "DISCORD_GUILD_ID is unset, so commands register globally",
          "Global command updates can take up to an hour. Set a guild id for instant registration.",
        )
      : ok("Guild scope", `commands register in guild ${config.discord.guildId}`),
  );

  checks.push(checkSecretAgreement(config, process.env));
  checks.push(await checkDiscordToken(config));
  checks.push(await checkApplication(config));
  checks.push(await checkModReachable(config, logger));
  checks.push(await checkModAuth(config));

  process.stdout.write("\nUnionKitBot doctor\n\n");
  for (const check of checks) {
    process.stdout.write(`${SYMBOL[check.status]}  ${check.title}: ${check.detail}\n`);
    if (check.hint !== undefined) {
      process.stdout.write(`      -> ${check.hint}\n`);
    }
  }

  const failed = checks.filter((check) => check.status === "fail").length;
  const warned = checks.filter((check) => check.status === "warn").length;
  process.stdout.write(`\n${checks.length - failed - warned} passed, ${warned} warning(s), ${failed} failure(s)\n`);

  if (failed > 0) {
    process.stdout.write("Fix the failures above before starting the bot.\n");
    return 1;
  }
  process.stdout.write("No blocking problems. Start the bot with `npm start`.\n");
  return 0;
}

main()
  .then((code) => {
    process.exit(code);
  })
  .catch((error: unknown) => {
    process.stderr.write(`doctor failed: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  });
