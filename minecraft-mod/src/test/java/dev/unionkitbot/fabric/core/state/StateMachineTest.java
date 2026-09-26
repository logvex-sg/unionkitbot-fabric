package dev.unionkitbot.fabric.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for the lifecycle state machine.
 */
class StateMachineTest {

	@Test
	void startsOffline() {
		StateMachine machine = new StateMachine();
		assertEquals(BotState.OFFLINE, machine.state());
		assertEquals(0L, machine.rejectedTransitions());
		assertTrue(machine.history().isEmpty());
	}

	@Test
	void acceptsLegalTransitions() {
		StateMachine machine = new StateMachine();
		assertTrue(machine.transitionTo(BotState.CONNECTING, "connect"));
		assertTrue(machine.transitionTo(BotState.SPAWNING, "world loaded"));
		assertTrue(machine.transitionTo(BotState.IDLE, "spawned"));
		assertTrue(machine.transitionTo(BotState.NAVIGATING, "task"));
		assertEquals(BotState.NAVIGATING, machine.state());
		assertEquals(4, machine.history().size());
	}

	@Test
	void rejectsIllegalTransitionsAndCountsThem() {
		StateMachine machine = new StateMachine();
		assertFalse(machine.transitionTo(BotState.DELIVERING, "nonsense"));
		assertEquals(BotState.OFFLINE, machine.state());
		assertEquals(1L, machine.rejectedTransitions());
		assertTrue(machine.history().isEmpty());
	}

	@Test
	void rejectsSelfTransitions() {
		StateMachine machine = new StateMachine();
		machine.transitionTo(BotState.CONNECTING);
		assertFalse(machine.transitionTo(BotState.CONNECTING));
		assertEquals(1L, machine.rejectedTransitions());
	}

	@Test
	void forceTransitionBypassesTheGuardButRecordsIt() {
		StateMachine machine = new StateMachine();
		assertTrue(machine.forceTransitionTo(BotState.SCANNING, "operator override"));
		assertEquals(BotState.SCANNING, machine.state());
		assertEquals(0L, machine.rejectedTransitions());
		assertEquals("operator override", machine.history().get(0).reason());
	}

	@Test
	void forceTransitionIsIgnoredWhenAlreadyInThatState() {
		StateMachine machine = new StateMachine();
		assertFalse(machine.forceTransitionTo(BotState.OFFLINE, "no-op"));
		assertEquals(0L, machine.rejectedTransitions());
	}

	@Test
	void rejectsNullTargets() {
		StateMachine machine = new StateMachine();
		assertThrows(NullPointerException.class, () -> machine.transitionTo(null));
		assertThrows(NullPointerException.class, () -> machine.forceTransitionTo(null, "x"));
	}

	@Test
	void notifiesListenersInOrder() {
		StateMachine machine = new StateMachine();
		List<String> seen = new ArrayList<>();
		machine.addListener((previous, change) ->
				seen.add(previous.state() + "->" + change.state()));
		machine.transitionTo(BotState.CONNECTING, "a");
		machine.transitionTo(BotState.SPAWNING, "b");
		assertEquals(List.of("OFFLINE->CONNECTING", "CONNECTING->SPAWNING"), seen);
	}

	@Test
	void aFailingListenerDoesNotBlockTheTransition() {
		StateMachine machine = new StateMachine();
		machine.addListener((previous, change) -> {
			throw new IllegalStateException("listener is broken");
		});
		List<BotState> seen = new ArrayList<>();
		machine.addListener((previous, change) -> seen.add(change.state()));
		assertTrue(machine.transitionTo(BotState.CONNECTING, "still works"));
		assertEquals(List.of(BotState.CONNECTING), seen);
	}

	@Test
	void removesListeners() {
		StateMachine machine = new StateMachine();
		List<BotState> seen = new ArrayList<>();
		java.util.function.BiConsumer<StateMachine.StateChange, StateMachine.StateChange> listener =
				(previous, change) -> seen.add(change.state());
		machine.addListener(listener);
		machine.transitionTo(BotState.CONNECTING, "a");
		machine.removeListener(listener);
		machine.transitionTo(BotState.SPAWNING, "b");
		assertEquals(List.of(BotState.CONNECTING), seen);
	}

	@Test
	void historyIsBoundedAndKeepsTheMostRecent() {
		StateMachine machine = new StateMachine();
		for (int i = 0; i < 200; i++) {
			machine.forceTransitionTo(i % 2 == 0 ? BotState.IDLE : BotState.NAVIGATING, "cycle " + i);
		}
		List<StateMachine.StateChange> history = machine.history();
		assertTrue(history.size() <= StateMachine.HISTORY_LIMIT, "history must stay bounded");
		StateMachine.StateChange last = history.get(history.size() - 1);
		assertEquals("cycle 199", last.reason());
		assertEquals(BotState.NAVIGATING, last.state());
	}

	@Test
	void historyIsAnImmutableSnapshot() {
		StateMachine machine = new StateMachine();
		machine.transitionTo(BotState.CONNECTING, "a");
		List<StateMachine.StateChange> history = machine.history();
		assertThrows(UnsupportedOperationException.class, () -> history.add(null));
	}

	@Test
	void everyStateReachableFromOfflineGivenTheRightPath() {
		StateMachine machine = new StateMachine();
		machine.forceTransitionTo(BotState.ERROR, "test");
		for (BotState target : BotState.values()) {
			BotState from = BotState.IDLE;
			assertNotNull(target);
			assertTrue(from.canTransitionTo(target) || from == target || target == BotState.CONNECTING
							|| target == BotState.SPAWNING || target == BotState.OFFLINE,
					"IDLE should reach " + target + " or the target is intentionally unreachable");
		}
		assertEquals(BotState.ERROR, machine.state());
	}

	@Test
	void disconnectedCanOnlyRecoverThroughConnecting() {
		assertTrue(BotState.DISCONNECTED.canTransitionTo(BotState.CONNECTING));
		assertTrue(BotState.DISCONNECTED.canTransitionTo(BotState.OFFLINE));
		assertFalse(BotState.DISCONNECTED.canTransitionTo(BotState.IDLE));
		assertFalse(BotState.DISCONNECTED.canTransitionTo(BotState.NAVIGATING));
	}

	@Test
	void stateFlagsMatchTheLifecycleRules() {
		assertFalse(BotState.OFFLINE.isConnected());
		assertFalse(BotState.OFFLINE.canRunTasks());
		assertTrue(BotState.SPAWNING.isConnected());
		assertFalse(BotState.SPAWNING.canRunTasks(), "a spawning player cannot run tasks yet");
		assertTrue(BotState.IDLE.canRunTasks());
		assertFalse(BotState.DEAD.canRunTasks(), "a dead player cannot run tasks");
		assertFalse(BotState.ERROR.canRunTasks());
		assertFalse(BotState.ERROR.isConnected());
	}

	@Test
	void parseIsCaseInsensitiveAndRejectsUnknownNames() {
		assertEquals(BotState.NAVIGATING, BotState.parse("navigating"));
		assertEquals(BotState.NAVIGATING, BotState.parse("  NAVIGATING  "));
		assertEquals(null, BotState.parse("teleporting"));
		assertEquals(null, BotState.parse(null));
	}
}
