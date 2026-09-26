package dev.unionkitbot.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.unionkitbot.fabric.client.gui.BotConfigScreen;
import dev.unionkitbot.fabric.runtime.BotRuntime;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point.
 *
 * <p>Wires the runtime into the Minecraft client: the agent loop runs on the client
 * tick, the control API starts with the client and stops with it, and the HUD,
 * configuration screen and client command are registered.
 *
 * <p>Nothing here may prevent the client from starting. If the control API cannot
 * bind, or a module fails to construct, the failure is logged and the game continues
 * with automation available locally. Shutdown is equally defensive: it runs on the
 * client-stopping event, so the socket, peers and queue are always released.
 */
public final class UnionKitBotClient implements ClientModInitializer {
	/** Logger shared by the client integration. */
	public static final Logger LOGGER = LoggerFactory.getLogger("unionkitbot");


	private BotRuntime runtime;
	private KeyMapping openScreenKey;
	private MinecraftAgentActions actions;
	private MinecraftWorldObserver observer;
	private long ticksSinceHeartbeat;

	@Override
	public void onInitializeClient() {
		LOGGER.info("UnionKitBot Fabric initialising (protocol {}, mod version {})",
				dev.unionkitbot.fabric.net.BuildInfo.protocolVersion(),
				dev.unionkitbot.fabric.net.BuildInfo.version());

		java.nio.file.Path directory = FabricLoader.getInstance().getConfigDir().resolve("unionkitbot");
		dev.unionkitbot.fabric.diag.DiagnosticsLog log =
				new dev.unionkitbot.fabric.diag.DiagnosticsLog(dev.unionkitbot.fabric.diag.LogLevel.INFO);
		this.actions = new MinecraftAgentActions(log);
		this.observer = new MinecraftWorldObserver();

		try {
			this.runtime = new BotRuntime(directory, actions, log, clientExecutor(), UnionKitBotClient::forward);
		} catch (RuntimeException e) {
			LOGGER.error("UnionKitBot Fabric failed to construct its runtime; automation is unavailable", e);
			return;
		}

		boolean listening = runtime.start();
		if (!listening) {
			LOGGER.warn("UnionKitBot Fabric started without a control API; the agent still runs locally");
		}

		registerKeyBinding();
		registerHud();
		registerClientCommand();
		registerTickHandler();
		registerShutdownHandler();

		LOGGER.info("UnionKitBot Fabric ready; state={} running={} apiPort={}",
				runtime.agent().state(), runtime.agent().isRunning(), runtime.server().port());
	}

	private static dev.unionkitbot.fabric.net.MainThreadExecutor clientExecutor() {
		return work -> {
			Minecraft client = Minecraft.getInstance();
			if (client == null || !client.isRunning()) {
				// The client is gone; refusing the work is correct, and the caller times
				// out and reports it rather than blocking forever.
				return;
			}
			client.execute(work);
		};
	}

	/**
	 * Forwards a diagnostics entry to the game log, prefixing the category so a
	 * support log shows which subsystem produced each line.
	 *
	 * @param entry the entry
	 */
	private static void forward(dev.unionkitbot.fabric.diag.DiagnosticsLog.Entry entry) {
		String line = "[UnionKitBot/" + entry.category() + "] " + entry.message();
		switch (entry.level()) {
			case ERROR -> {
				if (entry.cause() == null) {
					LOGGER.error(line);
				} else {
					LOGGER.error(line + " (" + entry.cause() + ")");
				}
			}
			case WARN -> LOGGER.warn(line);
			case INFO -> LOGGER.info(line);
			default -> LOGGER.debug(line);
		}
	}

	private void registerKeyBinding() {
		openScreenKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.unionkitbot.open_screen",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				KeyMapping.Category.MISC));
	}

	private void registerHud() {
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath("unionkitbot", "status_overlay"),
				new dev.unionkitbot.fabric.client.hud.BotHud(runtime));
	}

	/**
	 * Registers {@code /unionkitbot}. Every branch re-reads the runtime: the command
	 * is available before a world exists, and it must never throw into the dispatcher.
	 */
	private void registerClientCommand() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("unionkitbot")
						.executes(context -> openScreen(context.getSource()))
						.then(ClientCommandManager.literal("status")
								.executes(context -> reportStatus(context.getSource())))
						.then(ClientCommandManager.literal("start")
								.executes(context -> startAutomation(context.getSource())))
						.then(ClientCommandManager.literal("stop")
								.executes(context -> stopAutomation(context.getSource())))));
	}

	private int openScreen(FabricClientCommandSource source) {
		Minecraft client = Minecraft.getInstance();
		if (runtime == null || client == null) {
			source.sendError(Component.literal("UnionKitBot is not initialised."));
			return 0;
		}
		client.setScreen(new BotConfigScreen(runtime, client.screen));
		return 1;
	}

	private int reportStatus(FabricClientCommandSource source) {
		if (runtime == null) {
			source.sendError(Component.literal("UnionKitBot is not initialised."));
			return 0;
		}
		String api = runtime.isServerRunning() ? "port " + runtime.server().port() : "offline";
		source.sendFeedback(Component.literal("UnionKitBot state=" + runtime.agent().state()
				+ " running=" + runtime.agent().isRunning()
				+ " queue=" + runtime.queue().activeCount() + " active, "
				+ runtime.queue().pendingCount() + " pending"
				+ " api=" + api
				+ " peers=" + runtime.peerCount()));
		return 1;
	}

	private int startAutomation(FabricClientCommandSource source) {
		if (runtime == null) {
			source.sendError(Component.literal("UnionKitBot is not initialised."));
			return 0;
		}
		boolean changed = runtime.agent().start("started by /unionkitbot");
		source.sendFeedback(Component.literal(changed ? "Automation started." : "Automation was already running."));
		return 1;
	}

	private int stopAutomation(FabricClientCommandSource source) {
		if (runtime == null) {
			source.sendError(Component.literal("UnionKitBot is not initialised."));
			return 0;
		}
		boolean changed = runtime.agent().stop("stopped by /unionkitbot");
		source.sendFeedback(Component.literal(changed ? "Automation stopped." : "Automation was already stopped."));
		return 1;
	}

	private void registerTickHandler() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (runtime == null) {
				return;
			}
			try {
				while (openScreenKey != null && openScreenKey.consumeClick()) {
					client.setScreen(new BotConfigScreen(runtime, client.screen));
				}
				dev.unionkitbot.fabric.core.world.Observation observation = observer.observe();
				runtime.tick(observation);
				ticksSinceHeartbeat++;
				long heartbeatTicks = Math.max(20L, runtime.config().config().api().heartbeatSeconds() * 20L);
				if (ticksSinceHeartbeat >= heartbeatTicks) {
					ticksSinceHeartbeat = 0;
					runtime.heartbeat();
				}
			} catch (RuntimeException e) {
				// A failure in one tick must never take the client down. The agent records
				// it, and the next tick tries again.
				runtime.log().error("client", "tick handler failed", e);
			}
		});
	}

	private void registerShutdownHandler() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (runtime == null) {
				return;
			}
			try {
				runtime.announceShutdown("client stopping");
				runtime.close();
			} catch (RuntimeException e) {
				LOGGER.error("UnionKitBot Fabric failed to shut down cleanly", e);
			} finally {
				runtime = null;
			}
		});
	}
}
