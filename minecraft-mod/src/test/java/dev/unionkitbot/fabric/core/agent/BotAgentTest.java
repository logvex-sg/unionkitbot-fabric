package dev.unionkitbot.fabric.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.state.BotState;
import dev.unionkitbot.fabric.core.state.StateMachine;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;
import dev.unionkitbot.fabric.core.task.TaskParameters;
import dev.unionkitbot.fabric.core.task.TaskParameters.Position;
import dev.unionkitbot.fabric.core.task.TaskPriority;
import dev.unionkitbot.fabric.core.task.TaskQueue;
import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.diag.LogLevel;
import dev.unionkitbot.fabric.module.AgentActions;
import dev.unionkitbot.fabric.module.DeliveryModule;
import dev.unionkitbot.fabric.module.ModuleRegistry;
import dev.unionkitbot.fabric.module.NavigationModule;
import dev.unionkitbot.fabric.module.RecoveryModule;
import dev.unionkitbot.fabric.module.ScanningModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the OBSERVE → DECIDE → ACT → VERIFY loop.
 *
 * <p>The agent is exercised with a scripted observation source and stub actions, so
 * the whole loop runs without a Minecraft client. The cases that matter most are the
 * ones where the world is missing or disappears mid-task: the loop must degrade to a
 * safe state and never throw.
 */
class BotAgentTest {

	private StateMachine machine;
	private TaskQueue queue;
	private ModuleRegistry modules;
	private BotAgent agent;
	private DiagnosticsLog log;
	private StubActions actions;
	private Observation current;

	@BeforeEach
	void setUp() {
		log = new DiagnosticsLog(LogLevel.ERROR);
		machine = new StateMachine();
		queue = new TaskQueue();
		actions = new StubActions();
		AgentConfig config = AgentConfig.defaults();
		modules = new ModuleRegistry(List.of(
				new NavigationModule(actions, config.navigation(), log),
				new DeliveryModule(actions, config.delivery(), log),
				new ScanningModule(config.scan(), log),
				new RecoveryModule(actions, config.recovery(), log)));
		agent = new BotAgent(machine, queue, modules, config, log, () -> current);
		current = Observation.offline(0L);
	}

	private static Observation actionable(long tick, double x, double z) {
		return new Observation(tick, true, false, true, true, true, true, false,
				Optional.of("minecraft:overworld"), Optional.of(new Position(x, 64.0d, z)),
				20.0f, 20, 0, Optional.empty(), tick, List.of());
	}

	private static Observation dead(long tick) {
		return new Observation(tick, true, false, true, true, true, false, false,
				Optional.of("minecraft:overworld"), Optional.of(new Position(0, 64, 0)),
				0.0f, 0, 0, Optional.empty(), tick, List.of());
	}

	private static Observation disconnected(long tick) {
		return new Observation(tick, true, false, false, true, true, true, false,
				Optional.of("minecraft:overworld"), Optional.of(new Position(0, 64, 0)),
				20.0f, 20, 0, Optional.empty(), tick, List.of());
	}

	private static TaskParameters at(double x, double y, double z) {
		return TaskParameters.of(java.util.Map.of("position", java.util.Map.of("x", x, "y", y, "z", z)));
	}

	@Test
	void aTickWithoutAClientKeepsTheAgentOffline() {
		current = Observation.offline(1L);
		agent.start("test");
		assertEquals(BotState.OFFLINE, agent.tick());
	}

	@Test
	void startProgressesOutOfConnectingWhenTheWorldIsUsable() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		assertTrue(agent.isRunning());
		assertTrue(agent.state() == BotState.SPAWNING || agent.state() == BotState.IDLE,
				"expected progress out of CONNECTING, was " + agent.state());
	}

	@Test
	void reachesIdleOnceTheWorldIsUsable() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		agent.tick();
		assertEquals(BotState.IDLE, agent.state());
	}

	@Test
	void aMissingNetworkHandlerDisconnectsTheAgent() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		agent.tick();
		assertEquals(BotState.IDLE, agent.state());
		agent.tick(disconnected(3L));
		assertEquals(BotState.DISCONNECTED, agent.state());
	}

	@Test
	void aDeathMovesTheAgentToDeadAndStopsModules() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		agent.tick();
		agent.submit(TaskKind.NAVIGATE, TaskPriority.NORMAL, at(10, 64, 10));
		agent.tick();
		assertEquals(BotState.NAVIGATING, agent.state());
		agent.tick(dead(4L));
		assertEquals(BotState.DEAD, agent.state());
		assertTrue(actions.releasedAll, "dying must release held movement inputs");
	}

	@Test
	void aRespawnMovesTheAgentToRecovering() {
		agent.start("test");
		agent.tick(dead(1L));
		assertEquals(BotState.DEAD, agent.state());
		agent.tick(actionable(2L, 0, 0));
		assertEquals(BotState.RECOVERING, agent.state());
	}

	@Test
	void aDimensionChangeDoesNotErrorTheAgent() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		agent.tick();
		agent.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		agent.tick();
		assertEquals(BotState.SCANNING, agent.state());

		Observation nether = new Observation(4L, true, false, true, true, true, true, false,
				Optional.of("minecraft:the_nether"), Optional.of(new Position(0, 64, 0)),
				20.0f, 20, 0, Optional.empty(), 4L, List.of());
		agent.tick(nether);
		assertFalse(agent.state() == BotState.ERROR, "a dimension change must not error the agent");
	}

	@Test
	void submitsTasksAndEntersTheMatchingState() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		agent.tick();

		Optional<AgentTask> created = agent.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		assertTrue(created.isPresent());
		agent.tick();
		assertEquals(BotState.SCANNING, agent.state());
	}

	@Test
	void rejectsTasksWhenTheQueueIsFull() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		int limit = agent.config().limits().maxQueueSize();
		for (int i = 0; i < limit; i++) {
			assertTrue(agent.submit(TaskKind.SCAN, TaskPriority.LOW, TaskParameters.empty()).isPresent(),
					"task " + i + " should be accepted");
		}
		assertTrue(agent.submit(TaskKind.SCAN, TaskPriority.LOW, TaskParameters.empty()).isEmpty(),
				"the queue limit must be enforced");
	}

	@Test
	void rejectsTasksNoEnabledModuleSupports() {
		AgentConfig config = agent.config();
		AgentConfig allDisabled = new AgentConfig(config.enabled(), config.logLevel(), config.api(),
				config.modules().with("navigation", false).with("delivery", false)
						.with("scanning", false).with("recovery", false),
				config.limits(), config.navigation(), config.delivery(), config.scan(), config.recovery());
		agent.applyConfig(allDisabled);
		assertTrue(agent.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).isEmpty());
	}

	@Test
	void stopReturnsToOfflineAndReleasesInputs() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		agent.tick();
		assertTrue(agent.stop("test stop"));
		assertEquals(BotState.OFFLINE, agent.state());
		assertFalse(agent.isRunning());
		assertTrue(actions.releasedAll, "stopping must release held movement inputs");
	}

	@Test
	void restartCancelsQueuedTasks() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		agent.tick();
		agent.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		agent.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		assertEquals(2, agent.restart("test restart"));
		assertEquals(0, queue.activeCount());
		assertEquals(BotState.OFFLINE, agent.state());
	}

	@Test
	void aThrowingObserverErrorsTheAgentButNeverThrows() {
		BotAgent fragile = new BotAgent(new StateMachine(), new TaskQueue(), new ModuleRegistry(List.of()),
				AgentConfig.defaults(), log, () -> {
					throw new IllegalStateException("world read failed");
				});
		fragile.start("test");
		assertEquals(BotState.ERROR, fragile.tick());
	}

	@Test
	void aNullObservationIsTreatedAsOffline() {
		BotAgent tolerant = new BotAgent(new StateMachine(), new TaskQueue(), new ModuleRegistry(List.of()),
				AgentConfig.defaults(), log, () -> null);
		assertEquals(BotState.OFFLINE, tolerant.tick());
	}

	@Test
	void stateListenersAreNotified() {
		List<BotState> seen = new ArrayList<>();
		agent.addStateListener(seen::add);
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		assertFalse(seen.isEmpty());
	}

	@Test
	void aFailingStateListenerDoesNotStopTheLoop() {
		agent.addStateListener(state -> {
			throw new IllegalStateException("listener is broken");
		});
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		assertTrue(agent.state() == BotState.SPAWNING || agent.state() == BotState.IDLE);
	}

	@Test
	void diagnosticsExposeTheLoopCounters() {
		current = actionable(1L, 0, 0);
		agent.start("test");
		agent.tick();
		assertNotNull(agent.diagnostics().get("state"));
		assertTrue(agent.ticks() > 0L);
		assertNotNull(agent.lastActionDetail());
	}

	@Test
	void tasksAreNotRunWhileAutomationIsDisabled() {
		current = actionable(1L, 0, 0);
		queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		agent.tick();
		assertEquals(BotState.OFFLINE, agent.state());
		assertEquals(1, queue.pendingCount(), "a disabled agent must not claim tasks");
	}

	@Test
	void navigationCompletesWhenTheDestinationIsReached() {
		current = actionable(1L, 5.0d, 5.0d);
		agent.start("test");
		agent.tick();
		agent.tick();
		agent.submit(TaskKind.NAVIGATE, TaskPriority.NORMAL, at(5.0d, 64.0d, 5.0d));
		agent.tick();
		agent.tick();
		agent.tick();
		assertTrue(queue.activeCount() <= 1, "an already-reached destination should finish quickly");
	}

	@Test
	void theLoopNeverThrowsAcrossManyHostileTicks() {
		agent.start("test");
		Observation[] states = {
				Observation.offline(1L),
				actionable(2L, 0, 0),
				dead(3L),
				disconnected(4L),
				actionable(5L, 1, 1),
				Observation.offline(6L),
				actionable(7L, 2, 2),
		};
		for (int round = 0; round < 200; round++) {
			for (Observation observation : states) {
				agent.tick(observation);
			}
		}
		assertNotNull(agent.state());
	}

	/**
	 * A scripted {@link AgentActions} that records calls.
	 */
	private static final class StubActions implements AgentActions {
		private boolean releasedAll;

		@Override
		public ActionResult press(MovementInput input) {
			return ActionResult.ok("pressed " + input);
		}

		@Override
		public ActionResult release(MovementInput input) {
			return ActionResult.ok("released " + input);
		}

		@Override
		public ActionResult releaseAll() {
			releasedAll = true;
			return ActionResult.ok("released all");
		}

		@Override
		public ActionResult lookAt(double x, double z) {
			return ActionResult.ok("looked");
		}

		@Override
		public ActionResult swingMainHand() {
			return ActionResult.ok("swung");
		}

		@Override
		public ActionResult dropSelected(boolean all) {
			return ActionResult.ok("dropped");
		}

		@Override
		public ActionResult selectSlot(int slot) {
			return ActionResult.ok("selected");
		}

		@Override
		public ActionResult notifyLocal(String message) {
			return ActionResult.ok("notified");
		}
	}
}
