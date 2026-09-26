package dev.unionkitbot.fabric.core.task;

import java.util.List;
import java.util.Map;

/**
 * Durable backing store for the task queue.
 *
 * <p>Implementations must never throw: persistence is best-effort and a failing
 * store must not be able to stop automation. Failures are reported through the
 * return value so the caller can log them.
 */
public interface TaskStore {
	/**
	 * Persists the current queue contents.
	 *
	 * @param active tasks still occupying a queue slot
	 * @param finished most recently finished tasks
	 * @return {@code true} when the write succeeded
	 */
	boolean save(List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished);

	/**
	 * Loads previously persisted tasks.
	 *
	 * @return the persisted payload, or an empty payload when nothing is stored
	 */
	Payload load();

	/**
	 * Persisted queue contents.
	 *
	 * @param version the payload version that produced this data
	 * @param active tasks that were active when the payload was written
	 * @param finished most recently finished tasks
	 */
	record Payload(int version, List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished) {
		/**
		 * @return an empty payload with the current schema version
		 */
		public static Payload empty() {
			return new Payload(TaskQueue.STORE_VERSION, List.of(), List.of());
		}
	}

	/**
	 * A store that drops everything. Useful for tests and for disabling persistence.
	 */
	TaskStore NOOP = new TaskStore() {
		@Override
		public boolean save(List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished) {
			return true;
		}

		@Override
		public Payload load() {
			return Payload.empty();
		}
	};

	/**
	 * A store that always fails. Used to exercise degraded-mode behaviour.
	 */
	TaskStore FAILING = new TaskStore() {
		@Override
		public boolean save(List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished) {
			return false;
		}

		@Override
		public Payload load() {
			return new Payload(TaskQueue.STORE_VERSION, List.of(), List.of());
		}
	};

	/**
	 * @return the number of bytes the store would roughly use, for diagnostics
	 */
	default Map<String, Object> describe() {
		return Map.of("type", getClass().getSimpleName());
	}
}
