package dev.unionkitbot.fabric.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bounded in-memory log plus structured error ring for the agent.
 *
 * <p>Minecraft clients have no log file the agent controls, and operators need
 * recent history from the in-game screen and from Discord. Both buffers are hard
 * capped so a chatty failure loop cannot grow the heap without bound.
 */
public final class DiagnosticsLog {
	private static final int MAX_ENTRIES = 512;
	private static final int MAX_ERRORS = 128;

	private final Object lock = new Object();
	private final Deque<Entry> entries = new ArrayDeque<>();
	private final Deque<ErrorEntry> errors = new ArrayDeque<>();
	private final List<Consumer<Entry>> sinks = new CopyOnWriteArrayList<>();

	private LogLevel level;
	private long dropped;
	private long counter;

	/**
	 * @param level the initial minimum level to emit
	 */
	public DiagnosticsLog(LogLevel level) {
		this.level = Objects.requireNonNull(level, "level");
	}

	/**
	 * @return the current minimum level
	 */
	public LogLevel level() {
		return level;
	}

	/**
	 * Changes the minimum level.
	 *
	 * @param level the new minimum level
	 */
	public void setLevel(LogLevel level) {
		this.level = Objects.requireNonNull(level, "level");
	}

	/**
	 * Adds an external sink, used to mirror entries into the game log.
	 *
	 * @param sink receives every accepted entry
	 */
	public void addSink(Consumer<Entry> sink) {
		sinks.add(Objects.requireNonNull(sink, "sink"));
	}

	/**
	 * Records an informational entry.
	 *
	 * @param category the subsystem name
	 * @param message the message
	 */
	public void info(String category, String message) {
		log(LogLevel.INFO, category, message, null);
	}

	/**
	 * Records a warning.
	 *
	 * @param category the subsystem name
	 * @param message the message
	 */
	public void warn(String category, String message) {
		log(LogLevel.WARN, category, message, null);
	}

	/**
	 * Records a debug entry.
	 *
	 * @param category the subsystem name
	 * @param message the message
	 */
	public void debug(String category, String message) {
		log(LogLevel.DEBUG, category, message, null);
	}

	/**
	 * Records a trace entry.
	 *
	 * @param category the subsystem name
	 * @param message the message
	 */
	public void trace(String category, String message) {
		log(LogLevel.TRACE, category, message, null);
	}

	/**
	 * Records an error together with the throwable that caused it. Exceptions are
	 * never swallowed silently: the message and the stack trace summary are stored
	 * and forwarded to every sink.
	 *
	 * @param category the subsystem name
	 * @param message the message
	 * @param cause the throwable, may be {@code null}
	 */
	public void error(String category, String message, Throwable cause) {
		log(LogLevel.ERROR, category, message, cause);
	}

	/**
	 * Records an entry.
	 *
	 * @param entryLevel the level of the message
	 * @param category the subsystem name
	 * @param message the message
	 * @param cause the throwable, may be {@code null}
	 */
	public void log(LogLevel entryLevel, String category, String message, Throwable cause) {
		if (!level.permits(entryLevel)) {
			synchronized (lock) {
				dropped++;
			}
			return;
		}
		String safeCategory = category == null ? "general" : category;
		String safeMessage = message == null ? "" : message;
		Entry entry;
		synchronized (lock) {
			entry = new Entry(counter++, System.currentTimeMillis(), entryLevel, safeCategory, safeMessage,
					describe(cause));
			entries.addLast(entry);
			while (entries.size() > MAX_ENTRIES) {
				entries.removeFirst();
			}
			if (entryLevel == LogLevel.ERROR) {
				errors.addLast(new ErrorEntry(entry.timestamp(), safeCategory, safeMessage, entry.cause()));
				while (errors.size() > MAX_ERRORS) {
					errors.removeFirst();
				}
			}
		}
		for (Consumer<Entry> sink : sinks) {
			try {
				sink.accept(entry);
			} catch (RuntimeException sinkFailure) {
				// A broken sink must not be able to stop the agent, but it must be visible.
				synchronized (lock) {
					dropped++;
					errors.addLast(new ErrorEntry(System.currentTimeMillis(), "diagnostics",
							"log sink failed: " + sinkFailure.getClass().getSimpleName(), null));
				}
			}
		}
	}

	private static String describe(Throwable cause) {
		if (cause == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder(cause.getClass().getName());
		String message = cause.getMessage();
		if (message != null && !message.isBlank()) {
			builder.append(": ").append(message);
		}
		StackTraceElement[] stack = cause.getStackTrace();
		int limit = Math.min(6, stack.length);
		for (int i = 0; i < limit; i++) {
			builder.append("\n  at ").append(stack[i]);
		}
		if (stack.length > limit) {
			builder.append("\n  ... ").append(stack.length - limit).append(" more");
		}
		return builder.toString();
	}

	/**
	 * @param limit the maximum number of entries to return
	 * @return recent entries, oldest first
	 */
	public List<Entry> recent(int limit) {
		synchronized (lock) {
			List<Entry> all = new ArrayList<>(entries);
			int from = Math.max(0, all.size() - Math.max(0, limit));
			return List.copyOf(all.subList(from, all.size()));
		}
	}

	/**
	 * @param limit the maximum number of errors to return
	 * @return recent errors, oldest first
	 */
	public List<ErrorEntry> recentErrors(int limit) {
		synchronized (lock) {
			List<ErrorEntry> all = new ArrayList<>(errors);
			int from = Math.max(0, all.size() - Math.max(0, limit));
			return List.copyOf(all.subList(from, all.size()));
		}
	}

	/**
	 * @return how many entries were dropped because of level filtering or sink failure
	 */
	public long dropped() {
		synchronized (lock) {
			return dropped;
		}
	}

	/**
	 * @return diagnostic counters
	 */
	public Map<String, Object> diagnostics() {
		synchronized (lock) {
			return Map.of(
					"level", level.name(),
					"entries", entries.size(),
					"errors", errors.size(),
					"dropped", dropped);
		}
	}

	/**
	 * One log entry.
	 *
	 * @param sequence monotonic sequence number
	 * @param timestamp epoch milliseconds
	 * @param level the entry level
	 * @param category the subsystem name
	 * @param message the message
	 * @param cause formatted throwable description, may be {@code null}
	 */
	public record Entry(long sequence, long timestamp, LogLevel level, String category, String message, String cause) {
	}

	/**
	 * One structured error.
	 *
	 * @param timestamp epoch milliseconds
	 * @param category the subsystem name
	 * @param message the message
	 * @param cause formatted throwable description, may be {@code null}
	 */
	public record ErrorEntry(long timestamp, String category, String message, String cause) {
	}
}
