import type { ChatInputCommandInteraction } from "discord.js";

import type { AppConfig } from "../config/env.js";

/** Outcome of an authorization check. */
export interface AuthDecision {
  readonly allowed: boolean;
  readonly reason: string;
}

const ALLOWED: AuthDecision = { allowed: true, reason: "authorized" };

/**
 * Decides whether an interaction may run an administrative command.
 *
 * The check is deny-by-default and purely id based: no name, nickname or tag is
 * ever trusted, because those are user-controlled. DMs are always refused so an
 * operator cannot be tricked into driving a bot from a direct message.
 */
export class Authorizer {
  private readonly userIds: ReadonlySet<string>;
  private readonly roleIds: ReadonlySet<string>;
  private readonly allowedGuildId: string | undefined;

  public constructor(config: AppConfig) {
    this.userIds = config.discord.adminUserIds;
    this.roleIds = config.discord.adminRoleIds;
    this.allowedGuildId = config.discord.allowedGuildId;
  }

  /**
   * @param interaction the command interaction to authorize
   * @returns the decision, including a human readable reason when refused
   */
  public authorize(interaction: ChatInputCommandInteraction): AuthDecision {
    if (this.allowedGuildId !== undefined && interaction.guildId !== this.allowedGuildId) {
      return {
        allowed: false,
        reason: "this bot only serves a specific guild",
      };
    }
    if (interaction.guildId === null) {
      return { allowed: false, reason: "administrative commands are refused in direct messages" };
    }
    if (this.userIds.has(interaction.user.id)) {
      return ALLOWED;
    }
    const memberRoles = interaction.member?.roles;
    if (memberRoles !== undefined && memberRoles !== null && !Array.isArray(memberRoles)) {
      // GuildMember.roles is a RoleManager exposing a cache; a raw array is only
      // present on API payloads, which this interaction is not.
      for (const roleId of this.roleIds) {
        if (memberRoles.cache.has(roleId)) {
          return ALLOWED;
        }
      }
    }
    return {
      allowed: false,
      reason: "your user id and roles are not in the configured administrator lists",
    };
  }
}
