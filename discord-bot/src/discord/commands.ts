import { SlashCommandBuilder, SlashCommandSubcommandBuilder } from "discord.js";

import { MODULE_NAMES, TASK_KINDS, TASK_PRIORITIES } from "../protocol/messages.js";

/**
 * Slash command definitions.
 *
 * Definitions are kept in one place so command registration and the dispatcher
 * cannot drift apart.
 */
export const commandDefinitions = [
  new SlashCommandBuilder()
    .setName("status")
    .setDescription("Show the current UnionKitBot state, queue and connection status"),
  new SlashCommandBuilder()
    .setName("start")
    .setDescription("Enable automation on the Minecraft client"),
  new SlashCommandBuilder()
    .setName("stop")
    .setDescription("Disable automation and cancel active work"),
  new SlashCommandBuilder()
    .setName("restart")
    .setDescription("Restart the agent and cancel all queued tasks"),
  new SlashCommandBuilder()
    .setName("tasks")
    .setDescription("List queued and recently finished tasks")
    .addIntegerOption((option) =>
      option
        .setName("limit")
        .setDescription("Maximum number of tasks to show per section (1-50)")
        .setMinValue(1)
        .setMaxValue(50),
    ),
  new SlashCommandBuilder()
    .setName("task")
    .setDescription("Create, inspect or cancel a single task")
    .addSubcommand((subcommand: SlashCommandSubcommandBuilder) =>
      subcommand
        .setName("create")
        .setDescription("Queue a new task")
        .addStringOption((option) =>
          option
            .setName("kind")
            .setDescription("The kind of work to queue")
            .setRequired(true)
            .addChoices(...TASK_KINDS.map((kind) => ({ name: kind, value: kind }))),
        )
        .addStringOption((option) =>
          option
            .setName("priority")
            .setDescription("Scheduling priority")
            .addChoices(...TASK_PRIORITIES.map((priority) => ({ name: priority, value: priority }))),
        )
        .addNumberOption((option) =>
          option.setName("x").setDescription("Target X coordinate, for navigation tasks"),
        )
        .addNumberOption((option) =>
          option.setName("y").setDescription("Target Y coordinate, for navigation tasks"),
        )
        .addNumberOption((option) =>
          option.setName("z").setDescription("Target Z coordinate, for navigation tasks"),
        )
        .addStringOption((option) =>
          option.setName("target").setDescription("Target player name, for delivery tasks"),
        ),
    )
    .addSubcommand((subcommand: SlashCommandSubcommandBuilder) =>
      subcommand
        .setName("info")
        .setDescription("Show one task by id")
        .addStringOption((option) =>
          option.setName("id").setDescription("The task id").setRequired(true),
        ),
    )
    .addSubcommand((subcommand: SlashCommandSubcommandBuilder) =>
      subcommand
        .setName("cancel")
        .setDescription("Cancel one task by id")
        .addStringOption((option) =>
          option.setName("id").setDescription("The task id").setRequired(true),
        ),
    ),
  new SlashCommandBuilder()
    .setName("logs")
    .setDescription("Show recent diagnostics from the Fabric mod")
    .addIntegerOption((option) =>
      option
        .setName("limit")
        .setDescription("Maximum number of entries to show (1-50)")
        .setMinValue(1)
        .setMaxValue(50),
    )
    .addStringOption((option) =>
      option
        .setName("level")
        .setDescription("Minimum level to include")
        .addChoices(
          { name: "debug", value: "debug" },
          { name: "info", value: "info" },
          { name: "warn", value: "warn" },
          { name: "error", value: "error" },
        ),
    ),
  new SlashCommandBuilder()
    .setName("config")
    .setDescription("Inspect or change the Fabric mod configuration")
    .addSubcommand((subcommand: SlashCommandSubcommandBuilder) =>
      subcommand.setName("show").setDescription("Show the redacted mod configuration"),
    )
    .addSubcommand((subcommand: SlashCommandSubcommandBuilder) =>
      subcommand
        .setName("module")
        .setDescription("Enable or disable an automation module")
        .addStringOption((option) =>
          option
            .setName("name")
            .setDescription("The module to toggle")
            .setRequired(true)
            .addChoices(...MODULE_NAMES.map((name) => ({ name, value: name }))),
        )
        .addBooleanOption((option) =>
          option.setName("enabled").setDescription("Whether the module should run").setRequired(true),
        ),
    ),
  new SlashCommandBuilder()
    .setName("home")
    .setDescription("Record the current position as the navigation home point"),
].map((builder) => builder.toJSON());
