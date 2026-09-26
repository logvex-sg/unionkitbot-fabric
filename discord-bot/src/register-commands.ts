import { Client, GatewayIntentBits, REST, Routes } from "discord.js";

import { ConfigError, loadConfig } from "./config/env.js";
import { loadDotEnv } from "./config/dotenv.js";
import { commandDefinitions } from "./discord/commands.js";

/**
 * Registers slash commands and exits.
 *
 * This is a separate entrypoint so an operator can refresh command definitions
 * without starting the full controller (useful during development, and for the
 * one-off global registration whose propagation is slow).
 */
async function main(): Promise<number> {
  loadDotEnv();
  let token: string;
  let applicationId: string;
  let guildId: string | undefined;
  try {
    const config = loadConfig(process.env);
    token = config.discord.token;
    applicationId = config.discord.applicationId;
    guildId = config.discord.guildId;
  } catch (error) {
    if (error instanceof ConfigError) {
      process.stderr.write(`configuration error: ${error.variable}: ${error.message}\n`);
      return 2;
    }
    throw error;
  }

  const rest = new REST({ version: "10" }).setToken(token);
  const body = commandDefinitions;

  try {
    if (guildId !== undefined) {
      await rest.put(Routes.applicationGuildCommands(applicationId, guildId), { body });
      process.stdout.write(`registered ${body.length} commands in guild ${guildId}\n`);
    } else {
      await rest.put(Routes.applicationCommands(applicationId), { body });
      process.stdout.write(`registered ${body.length} global commands\n`);
    }
  } catch (error) {
    process.stderr.write(`registration failed: ${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  }

  // A throwaway client is created only to prove the token is valid; it is
  // destroyed immediately so the process can exit.
  const client = new Client({ intents: [GatewayIntentBits.Guilds] });
  try {
    await client.login(token);
    process.stdout.write(`token accepted by Discord as ${client.user?.tag ?? "unknown"}\n`);
  } catch (error) {
    process.stderr.write(`login check failed: ${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  } finally {
    await client.destroy();
  }
  return 0;
}

main()
  .then((code) => process.exit(code))
  .catch((error: unknown) => {
    process.stderr.write(`fatal: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  });
