import { ConfigError, describeConfig, loadConfig } from "./config/env.js";
import { loadDotEnv } from "./config/dotenv.js";
import { DiscordController } from "./discord/discord-controller.js";
import { Logger, RedactionRegistry } from "./logging/logger.js";
import { ModController } from "./modlink/mod-controller.js";

const SHUTDOWN_GRACE_MS = 5_000;

/**
 * Boots the controller, then waits for a shutdown signal.
 *
 * Startup failures are reported and produce a non-zero exit code; the process is
 * never left half-initialised. Shutdown closes the Discord gateway and the mod
 * link in a defined order and is bounded by a grace timer so a stuck socket cannot
 * hang the process forever.
 */
async function main(): Promise<number> {
  const applied = loadDotEnv();
  const redaction = new RedactionRegistry();
  let logger: Logger;

  try {
    const config = loadConfig(process.env);
    redaction.register(config.discord.token);
    redaction.register(config.mod.secret);
    logger = new Logger(config.logLevel, config.logBufferSize, redaction);

    if (applied.length > 0) {
      logger.debug("config", `loaded ${applied.length} variable(s) from .env`);
    }
    logger.info("startup", "starting UnionKitBot Discord controller", describeConfig(config));

    const modController = new ModController({
      baseUrl: config.mod.baseUrl,
      secret: config.mod.secret,
      useTls: config.mod.useTls,
      allowInsecureTls: config.mod.allowInsecureTls,
      protocolVersion: config.mod.protocolVersion,
      requestTimeoutMs: config.connection.requestTimeoutMs,
      heartbeatTimeoutMs: config.connection.heartbeatTimeoutMs,
      reconnectMinMs: config.connection.reconnectMinMs,
      reconnectMaxMs: config.connection.reconnectMaxMs,
      logger,
    });

    modController.onStateChange((state) => {
      logger.info("mod.link", `link state is now ${state}`);
    });
    modController.onConnected((welcome) => {
      const modVersion = welcome.data["modVersion"];
      logger.info("mod.link", "handshake complete", { modVersion });
    });
    modController.onDisconnect(({ code, reason }) => {
      logger.warn("mod.link", `disconnected (code ${code})${reason === "" ? "" : `: ${reason}`}`);
    });
    modController.onError((view) => {
      logger.error("mod.error", `[${view.category}] ${view.message}`);
    });
    modController.onHeartbeat((view) => {
      logger.debug("mod.heartbeat", `state ${view.state ?? "unknown"}, active ${view.activeTasks ?? 0}`);
    });

    // The link reconnects on its own; failing to connect at startup is expected
    // when the game is not running and must not stop the Discord bot.
    modController.start();

    const discord = new DiscordController(config, logger, modController);
    await discord.start();
    await discord.registerCommands();

    let shuttingDown = false;
    const shutdown = async (signal: string): Promise<void> => {
      if (shuttingDown) {
        return;
      }
      shuttingDown = true;
      logger.info("shutdown", `received ${signal}; closing connections`);
      const grace = setTimeout(() => {
        logger.warn("shutdown", "grace period expired; exiting anyway");
        process.exit(1);
      }, SHUTDOWN_GRACE_MS);
      grace.unref();
      try {
        await discord.stop();
      } catch (error) {
        logger.error("shutdown", "failed to close the Discord connection", error);
      }
      try {
        modController.stop();
      } catch (error) {
        logger.error("shutdown", "failed to close the mod link", error);
      }
      clearTimeout(grace);
      logger.info("shutdown", "shutdown complete");
    };

    process.on("SIGINT", () => {
      void shutdown("SIGINT").then(() => process.exit(0));
    });
    process.on("SIGTERM", () => {
      void shutdown("SIGTERM").then(() => process.exit(0));
    });
    process.on("unhandledRejection", (reason) => {
      logger.error("process", "unhandled promise rejection", reason);
    });
    process.on("uncaughtException", (error) => {
      logger.error("process", "uncaught exception", error);
      void shutdown("uncaughtException").then(() => process.exit(1));
    });

    logger.info("startup", "controller is running; press Ctrl+C to stop");
    return 0;
  } catch (error) {
    if (error instanceof ConfigError) {
      // Configuration problems are actionable, so they are printed verbatim.
      process.stderr.write(`configuration error: ${error.variable}: ${error.message}\n`);
      return 2;
    }
    process.stderr.write(`startup failed: ${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  }
}

main()
  .then((code) => {
    if (code !== 0) {
      process.exit(code);
    }
  })
  .catch((error: unknown) => {
    process.stderr.write(`fatal: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  });
