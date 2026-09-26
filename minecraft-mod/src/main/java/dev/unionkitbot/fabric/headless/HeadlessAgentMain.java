package dev.unionkitbot.fabric.headless;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.task.TaskParameters.Position;
import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.diag.LogLevel;
import dev.unionkitbot.fabric.module.AgentActions;
import dev.unionkitbot.fabric.net.MainThreadExecutor;
import dev.unionkitbot.fabric.runtime.BotRuntime;

/**
 * Runs the agent and its control API with no Minecraft client attached.
 *
 * <p>This exists so the Discord bot can be developed and tested against a real
 * control endpoint without launching the game. It uses the same runtime, state
 * machine, task queue, modules and protocol as the mod; the only difference is that
 * the world is simulated. It deliberately does not connect to any server and performs
 * no automation against a real world.
 *
 * <p>Usage: {@code java -jar unionkitbot-fabric-headless.jar [--config-dir DIR]
 * [--simulate]}. The agent starts in the OFFLINE state and only leaves it once a
 * simulated world is present, exactly as the in-game agent waits for the real one.
 */
public final class HeadlessAgentMain {
	private static final String CATEGORY = "headless";

	private final AtomicBoolean running = new AtomicBoolean(true);
	private final CountDownLatch stopped = new CountDownLatch(1);

	private BotRuntime runtime;

	private HeadlessAgentMain() {
	}

	/**
	 * Entry point.
	 *
	 * @param args command line arguments
	 */
	public static void main(String[] args) {
		HeadlessAgentMain main = new HeadlessAgentMain();
		Path directory = parseConfigDir(args);
		boolean simulate = hasFlag(args, "--simulate");
		int exitCode = main.run(directory, simulate);
		System.exit(exitCode);
	}

	private int run(Path directory, boolean simulate) {
		DiagnosticsLog log = new DiagnosticsLog(LogLevel.INFO);
		log.addSink(HeadlessAgentMain::print);
		SimulatedWorld world = new SimulatedWorld();
		SimulatedActions actions = new SimulatedActions(world, log);

		try {
			runtime = new BotRuntime(directory, actions, log, MainThreadExecutor.DIRECT, null);
		} catch (RuntimeException e) {
			log.error(CATEGORY, "failed to construct the runtime", e);
			return 2;
		}
		if (!runtime.start()) {
			log.warn(CATEGORY, "the control API did not start: " + runtime.lastError().orElse("unknown reason"));
			log.warn(CATEGORY, "set " + dev.unionkitbot.fabric.config.ConfigManager.SECRET_ENV
					+ " or write a secret file to enable the API");
		}
		log.info(CATEGORY, "config directory: " + directory.toAbsolutePath());
		log.info(CATEGORY, "control API port: " + runtime.server().port());

		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "unionkitbot-headless-shutdown"));

		if (simulate) {
			world.enable();
			AgentConfig config = runtime.config().config();
			runtime.agent().applyConfig(new AgentConfig(true, config.logLevel(), config.api(), config.modules(),
					config.limits(), config.navigation(), config.delivery(), config.scan(), config.recovery()));
			log.info(CATEGORY, "simulation enabled; submitting a sample scan task");
			runtime.agent().submit(dev.unionkitbot.fabric.core.task.TaskKind.SCAN,
					dev.unionkitbot.fabric.core.task.TaskPriority.NORMAL,
					dev.unionkitbot.fabric.core.task.TaskParameters.empty());
		}

		long tick = 0L;
		while (running.get()) {
			try {
				runtime.tick(world.observe(++tick));
				if (tick % 20L == 0L) {
					runtime.heartbeat();
				}
				Thread.sleep(50L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (RuntimeException e) {
				// The headless host mirrors the game: a failing tick is logged, never fatal.
				log.error(CATEGORY, "tick failed", e);
			}
		}
		awaitStop();
		return 0;
	}

	private void shutdown() {
		if (!running.getAndSet(false)) {
			return;
		}
		try {
			if (runtime != null) {
				runtime.close();
			}
		} catch (RuntimeException e) {
			// Nothing useful is left to do during shutdown.
		}
		stopped.countDown();
	}

	private void awaitStop() {
		try {
			stopped.await(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void print(DiagnosticsLog.Entry entry) {
		System.out.printf("%s [%s] %s%s%n",
				java.time.Instant.ofEpochMilli(entry.timestamp()),
				entry.category(),
				entry.message(),
				entry.cause() == null ? "" : " (" + entry.cause() + ")");
	}

	private static Path parseConfigDir(String[] args) {
		for (int i = 0; i < args.length - 1; i++) {
			if ("--config-dir".equals(args[i])) {
				return Path.of(args[i + 1]).toAbsolutePath();
			}
		}
		return Path.of(System.getProperty("user.home"), ".unionkitbot-fabric").toAbsolutePath();
	}

	private static boolean hasFlag(String[] args, String flag) {
		for (String arg : args) {
			if (flag.equals(arg)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A tiny simulated world, so the headless host can drive the same observation and
	 * action interfaces the game does without touching a real server.
	 */
	static final class SimulatedWorld {
		private final AtomicBoolean enabled = new AtomicBoolean(false);
		private double x = 0.5d;
		private double y = 64.0d;
		private double z = 0.5d;

		void enable() {
			enabled.set(true);
		}

		boolean isEnabled() {
			return enabled.get();
		}

		void moveBy(double dx, double dz) {
			x += dx;
			z += dz;
		}

		Observation observe(long tick) {
			if (!enabled.get()) {
				return Observation.offline(tick);
			}
			return new Observation(tick, true, false, true, true, true, true, false,
					Optional.of("minecraft:overworld"), Optional.of(new Position(x, y, z)),
					20.0f, 20, 0, Optional.empty(), tick, java.util.List.of());
		}
	}

	/**
	 * {@link AgentActions} that act on the simulated world instead of a client.
	 */
	static final class SimulatedActions implements AgentActions {
		private final SimulatedWorld world;
		private final DiagnosticsLog log;

		SimulatedActions(SimulatedWorld world, DiagnosticsLog log) {
			this.world = world;
			this.log = log;
		}

		@Override
		public ActionResult press(MovementInput input) {
			if (!world.isEnabled()) {
				return ActionResult.notActionable();
			}
			double step = 0.25d;
			switch (input) {
				case FORWARD -> world.moveBy(0.0d, step);
				case BACK -> world.moveBy(0.0d, -step);
				case LEFT -> world.moveBy(-step, 0.0d);
				case RIGHT -> world.moveBy(step, 0.0d);
				default -> {
					// Jump, sneak and sprint do not change the simulated position.
				}
			}
			return ActionResult.ok("simulated " + input.name().toLowerCase(java.util.Locale.ROOT));
		}

		@Override
		public ActionResult release(MovementInput input) {
			return ActionResult.ok("simulated release");
		}

		@Override
		public ActionResult releaseAll() {
			return ActionResult.ok("simulated release all");
		}

		@Override
		public ActionResult lookAt(double x, double z) {
			return world.isEnabled() ? ActionResult.ok("simulated look") : ActionResult.notActionable();
		}

		@Override
		public ActionResult swingMainHand() {
			return world.isEnabled() ? ActionResult.ok("simulated swing") : ActionResult.notActionable();
		}

		@Override
		public ActionResult dropSelected(boolean all) {
			return world.isEnabled() ? ActionResult.ok("simulated drop") : ActionResult.notActionable();
		}

		@Override
		public ActionResult selectSlot(int slot) {
			return world.isEnabled() ? ActionResult.ok("simulated slot") : ActionResult.notActionable();
		}

		@Override
		public ActionResult notifyLocal(String message) {
			log.info("simulated", message);
			return ActionResult.ok("simulated notification");
		}
	}
}
