import { describe, expect, it } from "vitest";

import { ConfigError, describeConfig, loadConfig } from "../src/config/env.js";

const BASE_ENV: NodeJS.ProcessEnv = {
  DISCORD_TOKEN: "discord-token-value",
  DISCORD_APPLICATION_ID: "123456789012345678",
  DISCORD_ADMIN_USER_IDS: "111111111111111111,222222222222222222",
  MOD_API_URL: "http://127.0.0.1:8765",
  MOD_API_SECRET: "sixteen-char-secret",
};

describe("loadConfig", () => {
  it("applies documented defaults", () => {
    const config = loadConfig({ ...BASE_ENV });
    expect(config.mod.baseUrl).toBe("http://127.0.0.1:8765");
    expect(config.mod.protocolVersion).toBe(1);
    expect(config.mod.useTls).toBe(false);
    expect(config.connection.requestTimeoutMs).toBe(10_000);
    expect(config.connection.reconnectMinMs).toBe(1_000);
    expect(config.connection.reconnectMaxMs).toBe(60_000);
    expect(config.logLevel).toBe("info");
    expect(config.logBufferSize).toBe(200);
    expect(config.discord.guildId).toBeUndefined();
  });

  it("rejects a missing Discord token", () => {
    const env = { ...BASE_ENV };
    delete env["DISCORD_TOKEN"];
    expect(() => loadConfig(env)).toThrowError(ConfigError);
  });

  it("rejects a short API secret", () => {
    expect(() => loadConfig({ ...BASE_ENV, MOD_API_SECRET: "short" })).toThrowError(/at least 16/);
  });

  it("requires at least one administrator", () => {
    const env = { ...BASE_ENV };
    delete env["DISCORD_ADMIN_USER_IDS"];
    expect(() => loadConfig(env)).toThrowError(/DISCORD_ADMIN_USER_IDS/);
  });

  it("rejects a non-numeric administrator id", () => {
    expect(() => loadConfig({ ...BASE_ENV, DISCORD_ADMIN_USER_IDS: "not-a-snowflake" })).toThrowError(
      /snowflake/,
    );
  });

  it("accepts role ids as an alternative to user ids", () => {
    const env = { ...BASE_ENV };
    delete env["DISCORD_ADMIN_USER_IDS"];
    env["DISCORD_ADMIN_ROLE_IDS"] = "333333333333333333";
    const config = loadConfig(env);
    expect(config.discord.adminRoleIds.has("333333333333333333")).toBe(true);
    expect(config.discord.adminUserIds.size).toBe(0);
  });

  it("rejects a non-http API url", () => {
    expect(() => loadConfig({ ...BASE_ENV, MOD_API_URL: "ftp://example.invalid" })).toThrowError(
      /http or https/,
    );
  });

  it("rejects a relative API url", () => {
    expect(() => loadConfig({ ...BASE_ENV, MOD_API_URL: "127.0.0.1:8765" })).toThrowError(
      /absolute http/,
    );
  });

  it("strips a trailing slash from the API url", () => {
    const config = loadConfig({ ...BASE_ENV, MOD_API_URL: "http://127.0.0.1:8765/" });
    expect(config.mod.baseUrl).toBe("http://127.0.0.1:8765");
  });

  it("rejects an out-of-range timeout", () => {
    expect(() => loadConfig({ ...BASE_ENV, MOD_REQUEST_TIMEOUT_MS: "1" })).toThrowError(/between/);
  });

  it("rejects a reconnect window where max is below min", () => {
    expect(() =>
      loadConfig({ ...BASE_ENV, MOD_RECONNECT_MIN_MS: "5000", MOD_RECONNECT_MAX_MS: "1000" }),
    ).toThrowError(/greater than or equal/);
  });

  it("rejects an unknown log level", () => {
    expect(() => loadConfig({ ...BASE_ENV, LOG_LEVEL: "verbose" })).toThrowError(/LOG_LEVEL/);
  });

  it("parses booleans in the documented spellings", () => {
    expect(loadConfig({ ...BASE_ENV, MOD_API_USE_TLS: "true" }).mod.useTls).toBe(true);
    expect(loadConfig({ ...BASE_ENV, MOD_API_USE_TLS: "0" }).mod.useTls).toBe(false);
    expect(() => loadConfig({ ...BASE_ENV, MOD_API_USE_TLS: "maybe" })).toThrowError(/boolean/);
  });
});

describe("describeConfig", () => {
  it("never reveals a secret", () => {
    const config = loadConfig({ ...BASE_ENV });
    const description = describeConfig(config);
    const serialised = JSON.stringify(description);
    expect(serialised).not.toContain("sixteen-char-secret");
    expect(serialised).not.toContain("discord-token-value");
    expect(description["modApiSecret"]).toBe("<configured>");
  });
});
