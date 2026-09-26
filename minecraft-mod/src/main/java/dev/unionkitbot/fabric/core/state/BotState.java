package dev.unionkitbot.fabric.core.state;

import java.util.Map;
import java.util.Set;

/**
 * Lifecycle states of the local automation agent.
 *
 * <p>Each state declares whether the client currently has a usable server
 * connection ({@link #isConnected()}) and whether automation is allowed to
 * execute tasks ({@link #canRunTasks()}). Guard code uses those flags instead of
 * comparing enum constants, so adding a state does not require touching every
 * safety check.
 */
public enum BotState {
	/** No connection has been attempted yet, or the agent is switched off. */
	OFFLINE(false, false),
	/** A connection attempt is in flight; no world or player is guaranteed. */
	CONNECTING(false, false),
	/** Connected, waiting for the player entity and world to become usable. */
	SPAWNING(true, false),
	/** Connected and healthy, waiting for work. */
	IDLE(true, true),
	/** Following a path towards a destination. */
	NAVIGATING(true, true),
	/** Handing items to a target player. */
	DELIVERING(true, true),
	/** Surveying the surroundings. */
	SCANNING(true, true),
	/** Recovering from a failure such as a stuck path or a lost target. */
	RECOVERING(true, true),
	/** The server connection ended; the client may still be able to reconnect. */
	DISCONNECTED(false, false),
	/** The player is dead and waiting on a respawn. */
	DEAD(true, false),
	/** The agent hit an unrecoverable error and halted automation. */
	ERROR(false, false);

	private static final Map<BotState, Set<BotState>> ALLOWED = Map.ofEntries(
			Map.entry(OFFLINE, Set.of(CONNECTING)),
			Map.entry(CONNECTING, Set.of(SPAWNING, DISCONNECTED, ERROR, OFFLINE)),
			Map.entry(SPAWNING, Set.of(IDLE, DISCONNECTED, DEAD, ERROR, OFFLINE)),
			Map.entry(IDLE, Set.of(NAVIGATING, DELIVERING, SCANNING, RECOVERING, DISCONNECTED, DEAD, ERROR, OFFLINE)),
			Map.entry(NAVIGATING, Set.of(IDLE, DELIVERING, SCANNING, RECOVERING, DISCONNECTED, DEAD, ERROR)),
			Map.entry(DELIVERING, Set.of(IDLE, NAVIGATING, RECOVERING, SCANNING, DISCONNECTED, DEAD, ERROR)),
			Map.entry(SCANNING, Set.of(IDLE, NAVIGATING, RECOVERING, DELIVERING, DISCONNECTED, DEAD, ERROR)),
			Map.entry(RECOVERING, Set.of(IDLE, NAVIGATING, DELIVERING, SCANNING, DISCONNECTED, DEAD, ERROR)),
			Map.entry(DISCONNECTED, Set.of(CONNECTING, OFFLINE, ERROR)),
			Map.entry(DEAD, Set.of(RECOVERING, SPAWNING, IDLE, DISCONNECTED, ERROR)),
			Map.entry(ERROR, Set.of(OFFLINE, CONNECTING, RECOVERING, DISCONNECTED)));

	private final boolean connected;
	private final boolean canRunTasks;

	BotState(boolean connected, boolean canRunTasks) {
		this.connected = connected;
		this.canRunTasks = canRunTasks;
	}

	/**
	 * @return {@code true} when a server connection is expected to exist
	 */
	public boolean isConnected() {
		return connected;
	}

	/**
	 * @return {@code true} when automation modules may execute tasks
	 */
	public boolean canRunTasks() {
		return canRunTasks;
	}

	/**
	 * @param next the candidate target state
	 * @return {@code true} when moving from this state to {@code next} is legal
	 */
	public boolean canTransitionTo(BotState next) {
		if (next == null || next == this) {
			return false;
		}
		return ALLOWED.getOrDefault(this, Set.of()).contains(next);
	}

	/**
	 * @return the states reachable from this one
	 */
	public Set<BotState> allowedTransitions() {
		return ALLOWED.getOrDefault(this, Set.of());
	}

	/**
	 * Parses a state name coming from configuration or the control API.
	 *
	 * @param name the raw name, case insensitive
	 * @return the matching state, or {@code null} when unknown
	 */
	public static BotState parse(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		for (BotState state : values()) {
			if (state.name().equalsIgnoreCase(trimmed)) {
				return state;
			}
		}
		return null;
	}
}
