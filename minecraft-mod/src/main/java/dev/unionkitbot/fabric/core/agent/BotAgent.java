package dev.unionkitbot.fabric.core.agent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.state.BotState;
import dev.unionkitbot.fabric.core.state.StateMachine;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;
import dev.unionkitbot.fabric.core.task.TaskParameters;
import dev.unionkitbot.fabric.core.task.TaskPriority;
import dev.unionkitbot.fabric.core.task.TaskQueue;
import dev.unionkitbot.fabric.core.task.TaskStatus;
import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.module.AutomationModule;
import dev.unionkitbot.fabric.module.ModuleRegistry;

/**
 * The agent's decision loop and lifecycle owner.
 *
 * <p>One call to {@link #tick()} runs exactly one iteration of
 * OBSERVE → DECIDE → ACT → VERIFY:
 *
 * <ol>
 * <li><b>Observe</b> — the injected {@link Supplier} produces an immutable
 * {@link Observation}. No Minecraft object survives past this step.</li>
 * <li><b>Decide</b> — the lifecycle state is reconciled with the observation, and
 * the {@link ModuleRegistry} picks a module for the highest priority pending
 * task.</li>
 * <li><b>Act</b> — the chosen module runs one tick of its controller.</li>
 * <li><b>Verify</b> — the {@link ActionResult} is translated into a task
 * transition: completed, retried, failed, or left running.</li>
 * </ol>
 *
 * <p>The loop is written to be driven from the Minecraft client thread, so it never
 * blocks and never throws: any exception raised by a module is caught, recorded and
 * turned into a task failure, because a bug in one module must not take the game
 * down. Every method is safe to call when the client, world or player is missing.
 */
public final class BotAgent {
	private static final String CATEGORY = "agent";

	private final StateMachine stateMachine;
	private final TaskQueue queue;
	private final ModuleRegistry modules;
	private final DiagnosticsLog log;
	private final Supplier<Observation> observer;

	private final AtomicReference<AgentConfig> config;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong ticks = new AtomicLong();
	private final AtomicLong moduleFailures = new AtomicLong();
	private final AtomicLong skippedTicks = new AtomicLong();
	private final CopyOnWriteArrayList<Consumer<BotState>> stateListeners = new CopyOnWriteArrayList<>();

	private volatile String lastActionDetail = "none";
	private volatile long lastTickAt;
	private volatile long disconnectGraceUntil;
	private volatile String lastObservedDimension;

	/**
	 * @param stateMachine the lifecycle state machine
	 * @param queue the task queue
	 * @param modules the module registry
	 * @param config the initial configuration
	 * @param log the diagnostics log
	 * @param observer produces an immutable world snapshot; it is called exactly once
	 *        per tick and may return {@code null} when no client exists
	 */
	public BotAgent(StateMachine stateMachine, TaskQueue queue, ModuleRegistry modules, AgentConfig config,
			DiagnosticsLog log, Supplier<Observation> observer) {
		this.stateMachine = stateMachine;
		this.queue = queue;
		this.modules = modules;
		this.config = new AtomicReference<>(config);
		this.log = log;
		this.observer = observer;
		this.modules.applyConfig(config);
		this.stateMachine.addListener((previous, change) -> {
			log.info(CATEGORY, "state " + previous.state() + " -> " + change.state()
					+ (change.reason() == null ? "" : " (" + change.reason() + ")"));
			for (Consumer<BotState> listener : stateListeners) {
				try {
					listener.accept(change.state());
				} catch (RuntimeException e) {
					log.error(CATEGORY, "state listener failed", e);
				}
			}
		});
	}

	/**
	 * Registers a listener for lifecycle state changes.
	 *
	 * @param listener the listener
	 */
	public void addStateListener(Consumer<BotState> listener) {
		stateListeners.add(listener);
	}

	/**
	 * @return the current lifecycle state
	 */
	public BotState state() {
		return stateMachine.state();
	}

	/**
	 * @return the lifecycle state machine
	 */
	public StateMachine stateMachine() {
		return stateMachine;
	}

	/**
	 * @return {@code true} when automation is switched on
	 */
	public boolean isRunning() {
		return running.get();
	}

	/**
	 * @return the task queue
	 */
	public TaskQueue queue() {
		return queue;
	}

	/**
	 * @return the module registry
	 */
	public ModuleRegistry modules() {
		return modules;
	}

	/**
	 * @return the diagnostics log
	 */
	public DiagnosticsLog log() {
		return log;
	}

	/**
	 * @return the active configuration
	 */
	public AgentConfig config() {
		return config.get();
	}

	/**
	 * Enables automation. The agent still waits for a usable world before running
	 * tasks.
	 *
	 * @param reason why automation was enabled
	 * @return {@code true} when the lifecycle state changed
	 */
	public boolean start(String reason) {
		running.set(true);
		log.info(CATEGORY, "automation enabled: " + reason);
		BotState current = state();
		if (current == BotState.OFFLINE) {
			return stateMachine.transitionTo(BotState.CONNECTING, reason);
		}
		if (current == BotState.ERROR || current == BotState.DISCONNECTED) {
			return stateMachine.forceTransitionTo(BotState.CONNECTING, reason);
		}
		return false;
	}

	/**
	 * Disables automation and stops all modules. The queue is preserved so work can
	 * resume after a restart.
	 *
	 * @param reason why automation was disabled
	 * @return {@code true} when the lifecycle state changed
	 */
	public boolean stop(String reason) {
		running.set(false);
		modules.stopAll(reason);
		log.info(CATEGORY, "automation disabled: " + reason);
		return stateMachine.forceTransitionTo(BotState.OFFLINE, reason);
	}

	/**
	 * Stops everything, clears the queue and returns to a fresh disabled state.
	 *
	 * @param reason why the agent was restarted
	 * @return the number of cancelled tasks
	 */
	public int restart(String reason) {
		running.set(false);
		modules.resetAll(reason);
		int cancelled = queue.cancelAll(reason);
		queue.persist();
		log.warn(CATEGORY, "restart requested: " + reason + " (" + cancelled + " tasks cancelled)");
		stateMachine.forceTransitionTo(BotState.OFFLINE, reason);
		return cancelled;
	}

	/**
	 * Replaces the active configuration and propagates it to every module.
	 *
	 * @param updated the new configuration
	 */
	public void applyConfig(AgentConfig updated) {
		config.set(updated);
		modules.applyConfig(updated);
		log.setLevel(updated.logLevel());
		log.info(CATEGORY, "configuration applied (enabled=" + updated.enabled() + ", level="
				+ updated.logLevel() + ")");
		if (updated.enabled() && !running.get()) {
			start("configuration enabled automation");
		} else if (!updated.enabled() && running.get()) {
			stop("configuration disabled automation");
		}
	}

	/**
	 * Runs one iteration of the decision loop.
	 *
	 * <p>This is the only method the game integration calls each tick. It never
	 * throws.
	 *
	 * @return the lifecycle state after the tick
	 */
	public BotState tick() {
		Observation observation;
		try {
			observation = observe();
		} catch (RuntimeException e) {
			log.error(CATEGORY, "observation failed", e);
			stateMachine.forceTransitionTo(BotState.ERROR, "observation failed");
			return state();
		}
		return tick(observation);
	}

	/**
	 * Runs one iteration of the decision loop against an explicit snapshot.
	 *
	 * <p>Used by the client integration and by tests, which supply the observation
	 * rather than having the agent pull it. Never throws.
	 *
	 * @param observation the world snapshot, {@code null} is treated as offline
	 * @return the lifecycle state after the tick
	 */
	public BotState tick(Observation observation) {
		ticks.incrementAndGet();
		lastTickAt = System.currentTimeMillis();
		Observation snapshot = observation == null ? Observation.offline(ticks.get()) : observation;
		try {
			reconcileLifecycle(snapshot);
			if (!running.get() || !state().canRunTasks() || !snapshot.isActionable()) {
				skippedTicks.incrementAndGet();
				return state();
			}
			decideAndAct(snapshot);
			// Reconcile the state only after the act phase: a module claimed in this tick
			// must be reflected immediately rather than one tick later.
			syncStateWithModules();
		} catch (RuntimeException e) {
			// Last line of defence: the loop must never propagate into the game.
			log.error(CATEGORY, "agent tick failed", e);
		}
		return state();
	}

	private Observation observe() {
		Observation observation = observer.get();
		return observation == null ? Observation.offline(ticks.get()) : observation;
	}

	private void reconcileLifecycle(Observation observation) {
		BotState current = state();
		long now = System.currentTimeMillis();

		if (!observation.clientReady()) {
			if (current != BotState.OFFLINE) {
				modules.resetAll("client not available");
				stateMachine.forceTransitionTo(BotState.OFFLINE, "client not available");
			}
			return;
		}
		if (!observation.hasNetworkHandler()) {
			if (current.isConnected()) {
				disconnectGraceUntil = now + config.get().recovery().disconnectGraceMillis();
				modules.resetAll("connection lost");
				stateMachine.forceTransitionTo(BotState.DISCONNECTED, "network handler missing");
			}
			return;
		}
		if (!running.get()) {
			// While disabled the agent only tracks connectivity, never advances.
			return;
		}
		if (observation.hasPlayer() && !observation.playerAlive()) {
			// Checked before the connection progression: a player can join an already
			// dead, so SPAWNING must never swallow the death.
			if (current != BotState.DEAD) {
				modules.stopAll("player died");
				stateMachine.forceTransitionTo(BotState.DEAD, "player is dead");
			}
			return;
		}
		if (current == BotState.DISCONNECTED && now >= disconnectGraceUntil) {
			stateMachine.forceTransitionTo(BotState.CONNECTING, "connection re-established");
			return;
		}
		if (current == BotState.CONNECTING && observation.hasWorld()) {
			stateMachine.transitionTo(BotState.SPAWNING, "world available");
			return;
		}
		if (current == BotState.SPAWNING && observation.isActionable()) {
			stateMachine.transitionTo(BotState.IDLE, "player spawned");
			return;
		}
		if (current == BotState.DEAD && observation.isActionable()) {
			modules.resetAll("respawned");
			stateMachine.transitionTo(BotState.RECOVERING, "respawned");
			// Give the recovery module the respawn task so the agent has something
			// concrete to do; if recovery is disabled the state falls back to IDLE.
			submit(TaskKind.RECOVER, TaskPriority.HIGH, TaskParameters.empty());
			return;
		}
		checkDimensionChange(observation);
	}

	private void checkDimensionChange(Observation observation) {
		String dimension = observation.dimensionId().orElse(null);
		if (dimension == null) {
			return;
		}
		String previous = lastObservedDimension;
		if (previous != null && !previous.equals(dimension)) {
			log.info(CATEGORY, "dimension changed from " + previous + " to " + dimension + "; resetting modules");
			modules.resetAll("dimension change");
		}
		lastObservedDimension = dimension;
	}

	/**
	 * Aligns the lifecycle state with the module that is currently working. Without
	 * this the state would drift: a module can finish or abandon work without the
	 * state machine hearing about it.
	 */
	private void syncStateWithModules() {
		Optional<AutomationModule> busy = modules.busyModule();
		BotState desired;
		if (busy.isEmpty()) {
			BotState current = state();
			if (current == BotState.IDLE || current == BotState.RECOVERING) {
				return;
			}
			desired = BotState.IDLE;
		} else {
			desired = stateForKind(busy.get().currentTask().map(AgentTask::kind).orElse(null));
		}
		if (desired == null || desired == state()) {
			return;
		}
		if (stateMachine.transitionTo(desired, "module activity")) {
			return;
		}
		// The direct edge may not exist (for example DEAD -> IDLE is illegal); fall
		// back to an explicit forced path so the state cannot drift out of sync.
		stateMachine.forceTransitionTo(desired, "module activity (forced)");
	}

	private static BotState stateForKind(TaskKind kind) {
		if (kind == null) {
			return null;
		}
		return switch (kind) {
			case NAVIGATE, RETURN_HOME -> BotState.NAVIGATING;
			case DELIVER -> BotState.DELIVERING;
			case SCAN, WAIT -> BotState.SCANNING;
			case RECOVER -> BotState.RECOVERING;
		};
	}

	private void decideAndAct(Observation observation) {
		Optional<AutomationModule> busy = modules.busyModule();
		if (busy.isPresent()) {
			AutomationModule module = busy.get();
			ActionResult result;
			try {
				result = module.tick(observation);
			} catch (RuntimeException e) {
				moduleFailures.incrementAndGet();
				log.error(CATEGORY, "module " + module.name() + " threw during tick", e);
				module.currentTask().ifPresent(task -> queue.fail(task.id(),
						"module " + module.name() + " raised " + e.getClass().getSimpleName()));
				module.stop("module error");
				queue.persist();
				lastActionDetail = "module " + module.name() + " raised " + e.getClass().getSimpleName();
				return;
			}
			verify(result);
			return;
		}
		Optional<AgentTask> candidate = queue.peek();
		if (candidate.isEmpty()) {
			stateMachine.transitionTo(BotState.IDLE, "queue empty");
			return;
		}
		AgentTask task = candidate.get();
		if (task.status() != TaskStatus.PENDING) {
			// The queue returns the highest priority pending task; anything else means
			// another owner changed it between the peek and this point.
			return;
		}
		Optional<AutomationModule> routed = modules.route(task);
		if (routed.isEmpty()) {
			queue.retry(task.id(), "no module could accept the task");
			lastActionDetail = "no module accepted task " + task.id();
			return;
		}
		AutomationModule module = routed.get();
		if (!queue.claim(task.id(), module.name())) {
			// The module took the task but the queue refused it; give it back.
			module.stop("queue refused claim");
			lastActionDetail = "queue refused claim of " + task.id();
			return;
		}
		lastActionDetail = "task " + task.id() + " assigned to " + module.name();
	}

	private void verify(ActionResult result) {
		lastActionDetail = result.detail();
		if (result.outcome() == ActionResult.Outcome.SKIPPED
				|| result.outcome() == ActionResult.Outcome.PROGRESS) {
			// Nothing to transition: the task keeps running and will be re-ticked.
			return;
		}
		Optional<AutomationModule> busy = modules.busyModule();
		if (busy.isEmpty()) {
			return;
		}
		AutomationModule module = busy.get();
		Optional<AgentTask> owned = module.currentTask();
		if (owned.isEmpty()) {
			return;
		}
		AgentTask task = owned.get();
		// The module must reflect the terminal status immediately: leaving the task
		// attached would keep the agent in the working state for one extra tick.
		module.reset("task " + task.id() + " " + result.outcome().name().toLowerCase(Locale.ROOT));
		switch (result.outcome()) {
			case COMPLETED -> {
				queue.complete(task.id(), result.detail());
				queue.persist();
				lastActionDetail = "completed " + task.id() + ": " + result.detail();
			}
			case FAILED -> {
				queue.fail(task.id(), result.detail());
				queue.persist();
				log.warn(CATEGORY, "task " + task.id() + " failed: " + result.detail());
			}
			case RETRY -> {
				if (task.attempts() >= config.get().limits().maxAttempts()) {
					queue.fail(task.id(), result.detail() + " (attempts exhausted)");
					queue.persist();
					log.warn(CATEGORY, "task " + task.id() + " exhausted its attempts: " + result.detail());
				} else {
					queue.retry(task.id(), result.detail());
				}
			}
			default -> log.warn(CATEGORY, "unhandled action outcome " + result.outcome());
		}
	}

	/**
	 * @return diagnostic counters for the agent loop
	 */
	public Map<String, Object> diagnostics() {
		return Map.of(
				"running", running.get(),
				"state", state().name(),
				"ticks", ticks.get(),
				"skippedTicks", skippedTicks.get(),
				"moduleFailures", moduleFailures.get(),
				"rejectedTransitions", stateMachine.rejectedTransitions(),
				"lastAction", lastActionDetail,
				"lastTickAt", lastTickAt,
				"queue", queue.diagnostics(),
				"modules", modules.status());
	}

	/**
	 * @return the most recent action detail, for status frames and the HUD
	 */
	public String lastActionDetail() {
		return lastActionDetail;
	}

	/**
	 * @return when the loop last ran, in epoch milliseconds
	 */
	public long lastTickAt() {
		return lastTickAt;
	}

	/**
	 * @return how many loop iterations have run since startup
	 */
	public long ticks() {
		return ticks.get();
	}

	/**
	 * Submits a task, honouring the configured queue limit.
	 *
	 * @param kind the task kind
	 * @param priority the task priority
	 * @param parameters the task parameters
	 * @return the created task, or empty when rejected
	 */
	public Optional<AgentTask> submit(TaskKind kind, TaskPriority priority, TaskParameters parameters) {
		if (queue.activeCount() >= config.get().limits().maxQueueSize()) {
			log.warn(CATEGORY, "task rejected: queue is full (" + queue.activeCount() + ")");
			return Optional.empty();
		}
		if (!modules.supports(kind)) {
			log.warn(CATEGORY, "task rejected: no enabled module supports " + kind.id());
			return Optional.empty();
		}
		Optional<AgentTask> created = queue.submit(kind, priority, parameters);
		created.ifPresent(task -> log.info(CATEGORY, "task " + task.id() + " queued (" + kind.id() + ", "
				+ priority.name() + ")"));
		return created;
	}
}
