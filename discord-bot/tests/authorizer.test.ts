import type { ChatInputCommandInteraction } from "discord.js";
import { describe, expect, it } from "vitest";

import type { AppConfig } from "../src/config/env.js";
import { Authorizer } from "../src/discord/authorizer.js";

interface StubOptions {
  readonly userId: string;
  readonly guildId: string | null;
  readonly roleIds?: readonly string[];
}

/**
 * Builds the smallest object the authorizer actually touches.
 *
 * A full discord.js interaction would drag the gateway in; the authorizer only
 * reads `user.id`, `guildId` and `member.roles.cache`, so the stub models exactly
 * those and nothing else.
 */
function stubInteraction(options: StubOptions): ChatInputCommandInteraction {
  const roleCache = new Map<string, unknown>();
  for (const roleId of options.roleIds ?? []) {
    roleCache.set(roleId, { id: roleId });
  }
  return {
    user: { id: options.userId },
    guildId: options.guildId,
    member:
      options.guildId === null
        ? null
        : {
            roles: { cache: roleCache },
          },
  } as unknown as ChatInputCommandInteraction;
}

function buildConfig(overrides: {
  userIds?: readonly string[];
  roleIds?: readonly string[];
  allowedGuildId?: string | undefined;
}): AppConfig {
  return {
    discord: {
      token: "token",
      applicationId: "123456789012345678",
      guildId: undefined,
      adminUserIds: new Set(overrides.userIds ?? []),
      adminRoleIds: new Set(overrides.roleIds ?? []),
      allowedGuildId: overrides.allowedGuildId,
    },
    mod: {
      baseUrl: "http://127.0.0.1:8765",
      secret: "sixteen-char-secret",
      useTls: false,
      allowInsecureTls: false,
      protocolVersion: 1,
    },
    connection: {
      requestTimeoutMs: 10_000,
      heartbeatTimeoutMs: 45_000,
      reconnectMinMs: 1_000,
      reconnectMaxMs: 60_000,
    },
    logLevel: "info",
    logBufferSize: 200,
  };
}

const GUILD = "999999999999999999";

describe("Authorizer", () => {
  it("allows a listed user id", () => {
    const authorizer = new Authorizer(buildConfig({ userIds: ["111111111111111111"] }));
    const decision = authorizer.authorize(stubInteraction({ userId: "111111111111111111", guildId: GUILD }));
    expect(decision.allowed).toBe(true);
  });

  it("allows a user holding a listed role", () => {
    const authorizer = new Authorizer(buildConfig({ roleIds: ["555555555555555555"] }));
    const decision = authorizer.authorize(
      stubInteraction({ userId: "777777777777777777", guildId: GUILD, roleIds: ["555555555555555555"] }),
    );
    expect(decision.allowed).toBe(true);
  });

  it("denies an unlisted user", () => {
    const authorizer = new Authorizer(buildConfig({ userIds: ["111111111111111111"] }));
    const decision = authorizer.authorize(stubInteraction({ userId: "888888888888888888", guildId: GUILD }));
    expect(decision.allowed).toBe(false);
    expect(decision.reason).toMatch(/not in the configured administrator lists/);
  });

  it("denies direct messages even for an administrator", () => {
    const authorizer = new Authorizer(buildConfig({ userIds: ["111111111111111111"] }));
    const decision = authorizer.authorize(stubInteraction({ userId: "111111111111111111", guildId: null }));
    expect(decision.allowed).toBe(false);
    expect(decision.reason).toMatch(/direct messages/);
  });

  it("denies a guild other than the configured one", () => {
    const authorizer = new Authorizer(
      buildConfig({ userIds: ["111111111111111111"], allowedGuildId: GUILD }),
    );
    const decision = authorizer.authorize(
      stubInteraction({ userId: "111111111111111111", guildId: "123123123123123123" }),
    );
    expect(decision.allowed).toBe(false);
    expect(decision.reason).toMatch(/specific guild/);
  });

  it("allows the configured guild", () => {
    const authorizer = new Authorizer(
      buildConfig({ userIds: ["111111111111111111"], allowedGuildId: GUILD }),
    );
    const decision = authorizer.authorize(stubInteraction({ userId: "111111111111111111", guildId: GUILD }));
    expect(decision.allowed).toBe(true);
  });

  it("denies when no administrator is configured", () => {
    const authorizer = new Authorizer(buildConfig({}));
    const decision = authorizer.authorize(stubInteraction({ userId: "111111111111111111", guildId: GUILD }));
    expect(decision.allowed).toBe(false);
  });

  it("does not trust a user whose id merely resembles a configured role", () => {
    const authorizer = new Authorizer(buildConfig({ roleIds: ["555555555555555555"] }));
    const decision = authorizer.authorize(
      stubInteraction({ userId: "555555555555555555", guildId: GUILD, roleIds: [] }),
    );
    expect(decision.allowed).toBe(false);
  });
});
