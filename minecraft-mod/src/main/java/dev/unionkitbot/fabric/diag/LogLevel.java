package dev.unionkitbot.fabric.diag;

/**
 * Log levels for the agent's own diagnostics.
 *
 * <p>Kept separate from the game's logging facade so operators can raise the
 * agent's verbosity without flooding the whole client log.
 */
public enum LogLevel {
	/** Nothing is emitted. */
	OFF(0),
	/** Only failures. */
	ERROR(1),
	/** Failures and recoverable problems. */
	WARN(2),
	/** Lifecycle and task transitions. */
	INFO(3),
	/** Per-tick decisions. */
	DEBUG(4),
	/** Everything, including observation snapshots. */
	TRACE(5);

	private final int severity;

	LogLevel(int severity) {
		this.severity = severity;
	}

	/**
	 * @return numeric severity, higher is more verbose
	 */
	public int severity() {
		return severity;
	}

	/**
	 * @param required the level of a message
	 * @return {@code true} when a message at {@code required} should be emitted
	 */
	public boolean permits(LogLevel required) {
		return required.severity <= severity;
	}

	/**
	 * Parses a configured level name.
	 *
	 * @param raw the configured value
	 * @param fallback the level to use when parsing fails
	 * @return the parsed level, or {@code fallback}
	 */
	public static LogLevel parse(String raw, LogLevel fallback) {
		if (raw == null) {
			return fallback;
		}
		for (LogLevel level : values()) {
			if (level.name().equalsIgnoreCase(raw.trim())) {
				return level;
			}
		}
		return fallback;
	}
}
