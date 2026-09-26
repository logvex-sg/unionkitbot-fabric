package dev.unionkitbot.fabric.core.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Guards the agent lifecycle and notifies listeners about accepted transitions.
 *
 * <p>Rejected transitions are counted rather than thrown, because callers are
 * driven by unpredictable events (disconnects, respawns, world unloads) and a
 * single unexpected sequence must never take the game down.
 */
public final class StateMachine {
	/** Maximum number of retained state changes. */
	public static final int HISTORY_LIMIT = 64;

	private final Object lock = new Object();
	private final Deque<StateChange> history = new ArrayDeque<>();
	private final List<BiConsumer<StateChange, StateChange>> listeners = new CopyOnWriteArrayList<>();

	private BotState state = BotState.OFFLINE;
	private StateChange lastChange;
	private long rejectedTransitions;
	private long failedListeners;
	private long sequence;

	/**
	 * @return the current state, never {@code null}
	 */
	public BotState state() {
		return state;
	}

	/**
	 * @return how many transitions have been refused since construction
	 */
	public long rejectedTransitions() {
		synchronized (lock) {
			return rejectedTransitions;
		}
	}

	/**
	 * Attempts a normal transition.
	 *
	 * @param next the target state
	 * @return {@code true} when the transition was applied
	 */
	public boolean transitionTo(BotState next) {
		return apply(next, false, null);
	}

	/**
	 * Attempts a transition and records a reason.
	 *
	 * @param next the target state
	 * @param reason a short human readable explanation
	 * @return {@code true} when the transition was applied
	 */
	public boolean transitionTo(BotState next, String reason) {
		return apply(next, false, reason);
	}

	/**
	 * Applies a transition even when the transition table forbids it. Reserved for
	 * hard resets triggered by the user or by a fatal lifecycle event.
	 *
	 * @param next the target state
	 * @param reason a short human readable explanation
	 * @return {@code true} when the state actually changed
	 */
	public boolean forceTransitionTo(BotState next, String reason) {
		return apply(next, true, reason);
	}

	private boolean apply(BotState next, boolean force, String reason) {
		Objects.requireNonNull(next, "next state");
		StateChange previous;
		StateChange change;
		List<BiConsumer<StateChange, StateChange>> snapshot;
		synchronized (lock) {
			BotState current = state;
			if (current == next) {
				if (!force) {
					rejectedTransitions++;
				}
				return false;
			}
			if (!force && !current.canTransitionTo(next)) {
				rejectedTransitions++;
				return false;
			}
			previous = new StateChange(sequence++, current, System.currentTimeMillis(), reasonOf(lastChange));
			change = new StateChange(sequence++, next, System.currentTimeMillis(), reason);
			state = next;
			lastChange = change;
			history.addLast(change);
			while (history.size() > HISTORY_LIMIT) {
				history.removeFirst();
			}
			snapshot = List.copyOf(listeners);
		}
		for (BiConsumer<StateChange, StateChange> listener : snapshot) {
			try {
				listener.accept(previous, change);
			} catch (RuntimeException e) {
				// Listeners drive HUD updates, status frames and logging. A broken one
				// must not roll back a transition that has already been applied.
				failedListeners++;
			}
		}
		return true;
	}

	/**
	 * @return how many listener callbacks have thrown since construction
	 */
	public long failedListeners() {
		return failedListeners;
	}

	private static String reasonOf(StateChange change) {
		return change == null ? null : change.reason();
	}

	/**
	 * @return an immutable snapshot of recent accepted transitions, oldest first
	 */
	public List<StateChange> history() {
		synchronized (lock) {
			return List.copyOf(new ArrayList<>(history));
		}
	}

	/**
	 * Registers a listener invoked after each accepted transition.
	 *
	 * @param listener receives the previous and the new state change
	 */
	public void addListener(BiConsumer<StateChange, StateChange> listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	/**
	 * Removes a previously registered listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeListener(BiConsumer<StateChange, StateChange> listener) {
		listeners.remove(listener);
	}

	/**
	 * One accepted state change.
	 *
	 * @param sequence monotonically increasing sequence number
	 * @param state the state that became active
	 * @param timestamp epoch milliseconds
	 * @param reason optional explanation, may be {@code null}
	 */
	public record StateChange(long sequence, BotState state, long timestamp, String reason) {
	}
}
