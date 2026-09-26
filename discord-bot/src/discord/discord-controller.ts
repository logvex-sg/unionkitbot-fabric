import { Client, Events, GatewayIntentBits, type ChatInputCommandInteraction } from "discord.js";

import type { AppConfig } from "../config/env.js";
import type { Logger } from "../logging/logger.js";
import type { ModController } from "../modlink/mod-controller.js";
import { Authorizer } from "./authorizer.js";
import { CommandRouter } from "./command-router.js";
import { commandDefinitions } from "./commands.js";

/**
 * Owns the Discord gateway connection and slash command registration.
 *
 * The client is intentionally given no privileged intents: it only needs to receive
 * application commands, so it never requests message content or member presence.
 */
export class DiscordController {
  private readonly client: Client;
  private readonly config: AppConfig;
  private readonly logger: Logger;
  private readonly router: CommandRouter;
  private stopping = false;

  public constructor(config: AppConfig, logger: Logger, modController: ModController) {
    this.config = config;
    this.logger = logger;
    this.client = new Client({ intents: [GatewayIntentBits.Guilds] });
    this.router = new CommandRouter(modController, new Authorizer(config), logger);
  }

  public get discordClient(): Client {
    return this.client;
  }

  /**
   * Logs in and wires up listeners.
   *
   * @throws Error when login fails; the caller decides whether to retry
   */
  public async start(): Promise<void> {
    this.client.on(Events.InteractionCreate, (interaction) => {
      if (!interaction.isChatInputCommand()) {
        return;
      }
      void this.dispatch(interaction);
    });

    this.client.on(Events.Error, (error) => {
      this.logger.error("discord.gateway", "gateway error", error);
    });

    this.client.on(Events.Warn, (message) => {
      this.logger.warn("discord.gateway", message);
    });

    this.client.on(Events.ShardDisconnect, (event, shardId) => {
      if (!this.stopping) {
        this.logger.warn("discord.gateway", `shard ${shardId} disconnected (code ${event.code}); discord.js will reconnect`);
      }
    });

    this.client.on(Events.ShardReconnecting, (shardId) => {
      this.logger.info("discord.gateway", `shard ${shardId} reconnecting`);
    });

    this.client.once(Events.ClientReady, (ready) => {
      this.logger.info("discord.gateway", `logged in as ${ready.user.tag}`);
    });

    await this.client.login(this.config.discord.token);
  }

  /**
   * Publishes slash commands to the configured guild, or globally when unset.
   */
  public async registerCommands(): Promise<void> {
    const guildId = this.config.discord.guildId;
    if (guildId !== undefined) {
      const guild = await this.client.guilds.fetch(guildId);
      await guild.commands.set(commandDefinitions);
      this.logger.info("discord.commands", `registered ${commandDefinitions.length} commands in guild ${guildId}`);
      return;
    }
    await this.client.application?.commands.set(commandDefinitions);
    this.logger.info(
      "discord.commands",
      `registered ${commandDefinitions.length} global commands; propagation can take up to an hour`,
    );
  }

  /**
   * Logs out and releases the gateway connection.
   */
  public async stop(): Promise<void> {
    this.stopping = true;
    this.client.removeAllListeners();
    await this.client.destroy();
    this.logger.info("discord.gateway", "gateway connection closed");
  }

  private async dispatch(interaction: ChatInputCommandInteraction): Promise<void> {
    try {
      await this.router.dispatch(interaction);
    } catch (error) {
      // dispatch already reports failures to the user; this is a last resort so a
      // listener rejection can never become an unhandled promise rejection.
      this.logger.error("discord.command", "unhandled failure while dispatching a command", error);
    }
  }
}
