import type { ChatInputCommandInteraction } from "discord.js";

import type { Logger } from "../logging/logger.js";
import { RemoteError } from "../protocol/envelope.js";
import { isTaskKind, isTaskPriority, type ModuleName, type TaskPriority } from "../protocol/messages.js";
import { TransportError } from "../modlink/http-client.js";
import type { ModController } from "../modlink/mod-controller.js";
import type { Authorizer } from "./authorizer.js";
import { configEmbed, controllerLogsEmbed, logsEmbed, statusEmbed, taskEmbed, tasksEmbed } from "./embeds.js";

const EPHEMERAL = { flags: 64 } as const;

/**
 * Executes slash commands against the Fabric mod.
 *
 * Every handler funnels through {@link run}, which owns the authorization check,
 * the offline short-circuit and the error rendering. Nothing here can throw into
 * the Discord client: a failure becomes a user-visible message plus a log line.
 */
export class CommandRouter {
  private readonly controller: ModController;
  private readonly authorizer: Authorizer;
  private readonly logger: Logger;

  public constructor(controller: ModController, authorizer: Authorizer, logger: Logger) {
    this.controller = controller;
    this.authorizer = authorizer;
    this.logger = logger;
  }

  public async handle(interaction: ChatInputCommandInteraction): Promise<void> {
    const decision = this.authorizer.authorize(interaction);
    if (!decision.allowed) {
      this.logger.warn("discord.command", `refused /${interaction.commandName} from ${interaction.user.id}: ${decision.reason}`);
      await this.respond(interaction, `⛔ ${decision.reason}`);
      return;
    }

    try {
      switch (interaction.commandName) {
        case "status":
          await this.status(interaction);
          return;
        case "start":
          await this.start(interaction);
          return;
        case "stop":
          await this.stop(interaction);
          return;
        case "restart":
          await this.restart(interaction);
          return;
        case "tasks":
          await this.tasks(interaction);
          return;
        case "task":
          await this.task(interaction);
          return;
        case "logs":
          await this.logs(interaction);
          return;
        case "config":
          await this.config(interaction);
          return;
        case "home":
          await this.home(interaction);
          return;
        default:
          await this.respond(interaction, `⛔ Unknown command \`/${interaction.commandName}\`.`);
          return;
      }
    } catch (error) {
      await this.reportFailure(interaction, error);
    }
  }

  private async status(interaction: ChatInputCommandInteraction): Promise<void> {
    const status = await this.controller.status();
    await interaction.editReply({
      embeds: [statusEmbed(status, this.controller.linkState, this.controller.reconnects)],
    });
  }

  private async start(interaction: ChatInputCommandInteraction): Promise<void> {
    const status = await this.controller.startAutomation(`requested by ${interaction.user.id}`);
    await interaction.editReply({
      content: `▶️ Automation enabled (state: **${status.state}**).`,
      embeds: [statusEmbed(status, this.controller.linkState, this.controller.reconnects)],
    });
  }

  private async stop(interaction: ChatInputCommandInteraction): Promise<void> {
    const status = await this.controller.stopAutomation(`requested by ${interaction.user.id}`);
    await interaction.editReply({
      content: `⏹️ Automation disabled (state: **${status.state}**). Queued tasks are preserved.`,
      embeds: [statusEmbed(status, this.controller.linkState, this.controller.reconnects)],
    });
  }

  private async restart(interaction: ChatInputCommandInteraction): Promise<void> {
    const result = await this.controller.restartAutomation(`requested by ${interaction.user.id}`);
    await interaction.editReply({
      content: `🔄 Agent restarted; ${result.cancelledTasks} queued task(s) cancelled.`,
      embeds: [statusEmbed(result.status, this.controller.linkState, this.controller.reconnects)],
    });
  }

  private async tasks(interaction: ChatInputCommandInteraction): Promise<void> {
    const limit = interaction.options.getInteger("limit") ?? 15;
    const { active, finished } = await this.controller.listTasks(limit);
    await interaction.editReply({ embeds: [tasksEmbed(active, finished)] });
  }

  private async task(interaction: ChatInputCommandInteraction): Promise<void> {
    const subcommand = interaction.options.getSubcommand(true);
    switch (subcommand) {
      case "create":
        await this.createTask(interaction);
        return;
      case "info": {
        const id = interaction.options.getString("id", true);
        const task = await this.controller.getTask(id);
        await interaction.editReply({ embeds: [taskEmbed(task)] });
        return;
      }
      case "cancel": {
        const id = interaction.options.getString("id", true);
        await this.controller.cancelTask(id, `cancelled by ${interaction.user.id}`);
        await interaction.editReply({ content: `🗑️ Task \`${id}\` cancelled.` });
        return;
      }
      default:
        await this.respond(interaction, `⛔ Unknown subcommand \`${subcommand}\`.`);
        return;
    }
  }

  private async createTask(interaction: ChatInputCommandInteraction): Promise<void> {
    const rawKind = interaction.options.getString("kind", true);
    if (!isTaskKind(rawKind)) {
      await this.respond(interaction, `⛔ Unknown task kind \`${rawKind}\`.`);
      return;
    }
    const rawPriority = interaction.options.getString("priority") ?? "normal";
    if (!isTaskPriority(rawPriority)) {
      await this.respond(interaction, `⛔ Unknown priority \`${rawPriority}\`.`);
      return;
    }

    const parameters = this.buildParameters(interaction, rawPriority, rawKind);
    const task = await this.controller.createTask({
      kind: rawKind,
      priority: rawPriority,
      ...(Object.keys(parameters).length === 0 ? {} : { parameters }),
    });
    await interaction.editReply({
      content: `✅ Task \`${task.id}\` queued (${task.kind}, ${task.priority}).`,
      embeds: [taskEmbed(task)],
    });
  }

  private buildParameters(
    interaction: ChatInputCommandInteraction,
    priority: TaskPriority,
    kind: string,
  ): Record<string, unknown> {
    const parameters: Record<string, unknown> = {};
    const x = interaction.options.getNumber("x");
    const y = interaction.options.getNumber("y");
    const z = interaction.options.getNumber("z");
    const target = interaction.options.getString("target");

    if (x !== null && y !== null && z !== null) {
      parameters["position"] = { x, y, z };
    } else if ((x !== null || y !== null || z !== null) && (kind === "navigate" || kind === "return_home")) {
      throw new UsageError("navigation needs all three of x, y and z, or none of them");
    }
    if (target !== null) {
      parameters["target"] = target;
    }
    if (kind === "deliver" && target === null) {
      throw new UsageError("delivery needs a target player name");
    }
    parameters["requestedPriority"] = priority;
    return parameters;
  }

  private async logs(interaction: ChatInputCommandInteraction): Promise<void> {
    const limit = interaction.options.getInteger("limit") ?? 25;
    const level = interaction.options.getString("level") ?? undefined;
    try {
      const { entries, errors } = await this.controller.logs(limit);
      const filtered = level === undefined ? entries : entries.filter((entry) => entry.level === level);
      await interaction.editReply({ embeds: [logsEmbed(filtered, errors)] });
    } catch (error) {
      if (error instanceof TransportError) {
        // The mod is unreachable; the controller's own buffer is the best available
        // substitute and makes it obvious which side is failing.
        await interaction.editReply({
          content: "⚠️ The Minecraft client is offline; showing controller logs instead.",
          embeds: [controllerLogsEmbed(this.logger.recent(limit))],
        });
        return;
      }
      throw error;
    }
  }

  private async config(interaction: ChatInputCommandInteraction): Promise<void> {
    const subcommand = interaction.options.getSubcommand(true);
    switch (subcommand) {
      case "show": {
        const config = await this.controller.config();
        await interaction.editReply({ embeds: [configEmbed(config)] });
        return;
      }
      case "module": {
        const name = interaction.options.getString("name", true);
        const enabled = interaction.options.getBoolean("enabled", true);
        const modules = await this.controller.setModules({ [name as ModuleName]: enabled });
        await interaction.editReply({
          content: `⚙️ Module \`${name}\` is now ${enabled ? "enabled" : "disabled"}.`,
          embeds: [configEmbed({ modules })],
        });
        return;
      }
      default:
        await this.respond(interaction, `⛔ Unknown subcommand \`${subcommand}\`.`);
        return;
    }
  }

  private async home(interaction: ChatInputCommandInteraction): Promise<void> {
    await this.respond(
      interaction,
      "ℹ️ Set the home point from the in-game screen, or send a `home.set` frame with x/y/z. The controller does not guess your coordinates.",
    );
  }

  /**
   * Defers, then delegates, so a slow mod cannot trip Discord's 3 second deadline.
   */
  public async dispatch(interaction: ChatInputCommandInteraction): Promise<void> {
    try {
      if (!interaction.deferred && !interaction.replied) {
        await interaction.deferReply({ ephemeral: true });
      }
    } catch (error) {
      this.logger.error("discord.command", "failed to defer an interaction", error);
      return;
    }
    await this.handle(interaction);
  }

  private async respond(interaction: ChatInputCommandInteraction, content: string): Promise<void> {
    if (interaction.deferred || interaction.replied) {
      await interaction.editReply({ content });
      return;
    }
    await interaction.reply({ content, ...EPHEMERAL });
  }

  private async reportFailure(interaction: ChatInputCommandInteraction, error: unknown): Promise<void> {
    const message = this.describeError(error);
    this.logger.error("discord.command", `/${interaction.commandName} failed`, message);
    await this.respond(interaction, `❌ ${message}`);
  }

  private describeError(error: unknown): string {
    if (error instanceof UsageError) {
      return error.message;
    }
    if (error instanceof RemoteError) {
      return `the mod refused the request (${error.code}): ${error.message}`;
    }
    if (error instanceof TransportError) {
      return `the Minecraft client is unreachable: ${error.message}`;
    }
    if (error instanceof Error) {
      return `unexpected failure: ${error.message}`;
    }
    return "unexpected failure";
  }
}

/** Raised when a command's options are inconsistent. */
export class UsageError extends Error {
  public constructor(message: string) {
    super(message);
    this.name = "UsageError";
  }
}
