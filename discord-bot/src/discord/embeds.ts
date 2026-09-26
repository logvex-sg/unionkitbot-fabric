import { EmbedBuilder } from "discord.js";

import type { LinkState } from "../modlink/mod-link.js";
import type { ErrorView, ModLogView, StatusView, TaskView } from "../modlink/mod-controller.js";
import type { LogRecord } from "../logging/logger.js";

/** Colour palette used across embeds. */
const COLOURS = {
  online: 0x2ecc71,
  working: 0x3498db,
  idle: 0x95a5a6,
  warning: 0xf1c40f,
  error: 0xe74c3c,
  offline: 0x7f8c8d,
} as const;

const MAX_FIELD_CHARS = 1_000;

function colourForStatus(status: StatusView): number {
  switch (status.state) {
    case "ERROR":
    case "DEAD":
      return COLOURS.error;
    case "DISCONNECTED":
    case "OFFLINE":
      return COLOURS.offline;
    case "IDLE":
      return COLOURS.idle;
    case "NAVIGATING":
    case "DELIVERING":
    case "SCANNING":
    case "RECOVERING":
      return COLOURS.working;
    default:
      return COLOURS.online;
  }
}

function truncate(value: string, limit: number = MAX_FIELD_CHARS): string {
  return value.length <= limit ? value : `${value.slice(0, limit - 1)}…`;
}

function formatTimestamp(epochMs: number): string {
  if (epochMs <= 0) {
    return "unknown";
  }
  return `<t:${Math.floor(epochMs / 1000)}:R>`;
}

function moduleSummary(modules: Readonly<Record<string, boolean>>): string {
  const entries = Object.entries(modules);
  if (entries.length === 0) {
    return "no module data reported";
  }
  return entries
    .map(([name, enabled]) => `${enabled ? "🟢" : "⚪"} ${name}`)
    .join("  ");
}

/**
 * Renders a status snapshot as a Discord embed.
 */
export function statusEmbed(status: StatusView, link: LinkState, reconnects: number): EmbedBuilder {
  const embed = new EmbedBuilder()
    .setTitle("UnionKitBot status")
    .setColor(colourForStatus(status))
    .addFields(
      { name: "State", value: status.state, inline: true },
      { name: "Automation", value: status.running ? "running" : "stopped", inline: true },
      { name: "Link", value: link, inline: true },
      { name: "Queue", value: `active ${status.queueActive} · pending ${status.queuePending}`, inline: true },
      { name: "Modules", value: truncate(moduleSummary(status.modules)), inline: false },
      { name: "Last action", value: truncate(status.lastAction === "" ? "none" : status.lastAction) },
    )
    .setFooter({ text: `mod ${status.modVersion} · protocol v${status.protocolVersion} · reconnects ${reconnects}` })
    .setTimestamp(new Date());

  if (status.activeTask !== undefined) {
    embed.addFields({ name: "Active task", value: truncate(formatTaskLine(status.activeTask)), inline: false });
  }
  if (status.recentErrors.length > 0) {
    embed.addFields({
      name: "Recent errors",
      value: truncate(status.recentErrors.map((error) => `• ${error}`).join("\n")),
      inline: false,
    });
  }
  return embed;
}

/**
 * Renders a task list as a Discord embed.
 */
export function tasksEmbed(active: readonly TaskView[], finished: readonly TaskView[]): EmbedBuilder {
  const embed = new EmbedBuilder()
    .setTitle("UnionKitBot tasks")
    .setColor(active.length > 0 ? COLOURS.working : COLOURS.idle)
    .setTimestamp(new Date());

  embed.addFields({
    name: `Active (${active.length})`,
    value: active.length === 0 ? "nothing queued" : truncate(active.map(formatTaskLine).join("\n")),
  });
  embed.addFields({
    name: `Recently finished (${finished.length})`,
    value: finished.length === 0 ? "nothing finished yet" : truncate(finished.map(formatTaskLine).join("\n")),
  });
  return embed;
}

/**
 * Renders a single task as a Discord embed.
 */
export function taskEmbed(task: TaskView): EmbedBuilder {
  const embed = new EmbedBuilder()
    .setTitle(`Task ${task.id}`)
    .setColor(task.status === "FAILED" ? COLOURS.error : COLOURS.working)
    .addFields(
      { name: "Kind", value: task.kind, inline: true },
      { name: "Status", value: task.status, inline: true },
      { name: "Priority", value: task.priority, inline: true },
      { name: "Owner", value: task.owner ?? "unclaimed", inline: true },
      { name: "Attempts", value: String(task.attempts), inline: true },
      { name: "Progress", value: `${Math.round(task.progress * 100)}%`, inline: true },
      { name: "Submitted", value: formatTimestamp(task.submittedAt), inline: true },
    );
  if (task.failureReason !== undefined) {
    embed.addFields({ name: "Failure", value: truncate(task.failureReason), inline: false });
  }
  return embed.setTimestamp(new Date());
}

/**
 * Renders mod log entries as a Discord embed.
 */
export function logsEmbed(entries: readonly ModLogView[], errors: readonly ErrorView[]): EmbedBuilder {
  const embed = new EmbedBuilder().setTitle("UnionKitBot logs").setColor(COLOURS.idle).setTimestamp(new Date());
  embed.addFields({
    name: `Entries (${entries.length})`,
    value:
      entries.length === 0
        ? "no entries reported"
        : truncate(
            entries
              .map((entry) => `\`${entry.level.toUpperCase()}\` [${entry.scope}] ${entry.message}`)
              .join("\n"),
          ),
  });
  if (errors.length > 0) {
    embed.addFields({
      name: `Errors (${errors.length})`,
      value: truncate(errors.map((error) => `• [${error.category}] ${error.message}`).join("\n")),
    });
  }
  return embed;
}

/**
 * Renders controller-side log records, used when the mod is unreachable.
 */
export function controllerLogsEmbed(records: readonly LogRecord[]): EmbedBuilder {
  return new EmbedBuilder()
    .setTitle("UnionKitBot controller logs")
    .setColor(COLOURS.warning)
    .setDescription(
      records.length === 0
        ? "no controller log entries"
        : truncate(
            records
              .map(
                (record) =>
                  `\`${record.level.toUpperCase()}\` [${record.scope}] ${record.message}${
                    record.detail === undefined ? "" : ` :: ${record.detail}`
                  }`,
              )
              .join("\n"),
            4_000,
          ),
    )
    .setTimestamp(new Date());
}

/**
 * Renders a configuration object as a Discord embed.
 *
 * The object comes from the mod's own redacted view, so it cannot contain the API
 * secret. It is additionally truncated to stay inside Discord's field limit.
 */
export function configEmbed(config: Record<string, unknown>): EmbedBuilder {
  const embed = new EmbedBuilder().setTitle("UnionKitBot configuration").setColor(COLOURS.idle);
  const rendered = JSON.stringify(config, null, 2) ?? "{}";
  embed.setDescription(`\`\`\`json\n${truncate(rendered, 3_800)}\n\`\`\``);
  return embed.setTimestamp(new Date());
}

function formatTaskLine(task: TaskView): string {
  const owner = task.owner === undefined ? "" : ` → ${task.owner}`;
  return `\`${task.id}\` ${task.kind} [${task.status}]${owner}`;
}
