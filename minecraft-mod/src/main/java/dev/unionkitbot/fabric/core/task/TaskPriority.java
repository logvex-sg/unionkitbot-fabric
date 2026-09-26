package dev.unionkitbot.fabric.core.task;

import java.util.Locale;
import java.util.Map;

/**
 * Ordered task priorities. A lower {@link #weight()} means the task is scheduled
 * earlier; equal weights fall back to submission order, which keeps the queue
 * deterministic.
 */
public enum TaskPriority {
	CRITICAL(0),
	HIGH(1),
	NORMAL(2),
	LOW(3),
	BACKGROUND(4);

	private final int weight;

	TaskPriority(int weight) {
		this.weight = weight;
	}

	/**
	 * @return the scheduling weight, lower runs first
	 */
	public int weight() {
		return weight;
	}

	/**
	 * Parses a priority name.
	 *
	 * @param raw the name, case insensitive
	 * @return the matching priority, or {@code null} when unknown
	 */
	public static TaskPriority parse(String raw) {
		if (raw == null) {
			return null;
		}
		String normalized = raw.trim().toUpperCase(Locale.ROOT);
		for (TaskPriority priority : values()) {
			if (priority.name().equals(normalized)) {
				return priority;
			}
		}
		return null;
	}

	/**
	 * Maps a numeric level onto a priority, clamped to the available range.
	 *
	 * @param level the numeric level, where 0 is the most urgent
	 * @return the closest matching priority
	 */
	public static TaskPriority fromLevel(int level) {
		TaskPriority best = NORMAL;
		for (TaskPriority priority : values()) {
			if (priority.weight >= level) {
				return priority;
			}
			best = priority;
		}
		return best;
	}

	/**
	 * @return a stable name to level mapping used by diagnostics and the API
	 */
	public static Map<String, Integer> weights() {
		Map<String, Integer> out = new java.util.LinkedHashMap<>();
		for (TaskPriority priority : values()) {
			out.put(priority.name(), priority.weight);
		}
		return Map.copyOf(out);
	}
}
