package dev.unionkitbot.fabric.core.task;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Kinds of work the agent can be asked to perform.
 *
 * <p>The set is intentionally small and closed: the control API validates incoming
 * task kinds against this enum instead of accepting free-form strings.
 */
public enum TaskKind {
	/** Walk to a coordinate without interacting with the world. */
	NAVIGATE("navigate", EnumSet.of(TaskCapability.MOVEMENT)),
	/** Walk to a target player and hand over items. */
	DELIVER("deliver", EnumSet.of(TaskCapability.MOVEMENT, TaskCapability.INVENTORY)),
	/** Survey the area around the current position. */
	SCAN("scan", EnumSet.of(TaskCapability.OBSERVATION)),
	/** Wait for a condition without moving. */
	WAIT("wait", EnumSet.of(TaskCapability.OBSERVATION)),
	/** Return to the recorded home position. */
	RETURN_HOME("return_home", EnumSet.of(TaskCapability.MOVEMENT)),
	/** Trigger the recovery routine without performing new work. */
	RECOVER("recover", EnumSet.of(TaskCapability.OBSERVATION));

	/**
	 * Capabilities a module must expose before it can run a task kind.
	 */
	public enum TaskCapability {
		MOVEMENT,
		INVENTORY,
		OBSERVATION
	}

	private final String id;
	private final Set<TaskCapability> requiredCapabilities;

	TaskKind(String id, Set<TaskCapability> requiredCapabilities) {
		this.id = id;
		this.requiredCapabilities = Set.copyOf(requiredCapabilities);
	}

	/**
	 * @return the stable identifier used on the wire
	 */
	public String id() {
		return id;
	}

	/**
	 * @return the capabilities required to execute this kind of task
	 */
	public Set<TaskCapability> requiredCapabilities() {
		return requiredCapabilities;
	}

	/**
	 * Parses a wire identifier.
	 *
	 * @param raw the identifier, case insensitive, {@code -} treated as {@code _}
	 * @return the matching kind, or {@code null} when unknown
	 */
	public static TaskKind parse(String raw) {
		if (raw == null) {
			return null;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		for (TaskKind kind : values()) {
			if (kind.id.equals(normalized) || kind.name().toLowerCase(Locale.ROOT).equals(normalized)) {
				return kind;
			}
		}
		return null;
	}
}
