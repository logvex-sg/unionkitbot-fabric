package dev.unionkitbot.fabric.core.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, priority ordered queue of {@link AgentTask}s.
 *
 * <p>Ordering is fully deterministic: tasks are compared by priority weight first
 * and by submission sequence second, so two runs of the same input schedule work
 * in the same order. Tasks are created from control-API threads and advanced from
 * the Minecraft client thread, so every mutation is guarded by {@link #lock}.
 */
public final class TaskQueue {
	/** Schema version written by {@link #persist()}. */
	public static final int STORE_VERSION = 1;

	/** Maximum number of retained finished tasks. */
	public static final int FINISHED_HISTORY_LIMIT = 128;
	private static final int MAX_RESTORED_TASKS = 256;
	private static final Comparator<AgentTask> ORDER = Comparator
			.comparingInt((AgentTask task) -> task.priority().weight())
			.thenComparingLong(AgentTask::sequence);

	private final Object lock = new Object();
	private final TreeSet<AgentTask> pending = new TreeSet<>(ORDER);
	private final Map<String, AgentTask> byId = new LinkedHashMap<>();
	private final Map<String, AgentTask.Snapshot> finished = new LinkedHashMap<>();
	private final List<TaskQueueListener> listeners = new CopyOnWriteArrayList<>();

	private TaskStore store = TaskStore.NOOP;
	private long sequence;
	private boolean dirty;
	private long lastPersistFailure;
	private String lastPersistError;
	private long failedListeners;

	/**
	 * Registers a listener for queue and task lifecycle events.
	 *
	 * @param listener the listener
	 */
	public void addListener(TaskQueueListener listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	/**
	 * Removes a previously registered listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeListener(TaskQueueListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Installs a persistence store.
	 *
	 * @param store the store, {@code null} disables persistence
	 */
	public void setStore(TaskStore store) {
		this.store = store == null ? TaskStore.NOOP : store;
	}

	/**
	 * @return the installed store
	 */
	public TaskStore store() {
		return store;
	}

	/**
	 * Submits a task.
	 *
	 * @param kind the kind of work
	 * @param priority the scheduling priority
	 * @param parameters the task parameters
	 * @return the created task, or empty when the queue is saturated
	 */
	public Optional<AgentTask> submit(TaskKind kind, TaskPriority priority, TaskParameters parameters) {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(priority, "priority");
		Objects.requireNonNull(parameters, "parameters");
		AgentTask task;
		synchronized (lock) {
			if (byId.size() >= MAX_RESTORED_TASKS + FINISHED_HISTORY_LIMIT) {
				pruneLocked();
			}
			if (byId.size() >= MAX_RESTORED_TASKS + FINISHED_HISTORY_LIMIT) {
				return Optional.empty();
			}
			String id = UUID.randomUUID().toString().substring(0, 8);
			task = new AgentTask(id, kind, priority, parameters, System.currentTimeMillis(), sequence++);
			byId.put(id, task);
			pending.add(task);
			dirty = true;
		}
		notifySubmitted(task);
		return Optional.of(task);
	}

	/**
	 * @return the highest priority pending task without removing it
	 */
	public Optional<AgentTask> peek() {
		synchronized (lock) {
			return pending.isEmpty() ? Optional.empty() : Optional.of(pending.first());
		}
	}

	/**
	 * @return the highest priority pending task, without changing its status
	 */
	public Optional<AgentTask> poll() {
		synchronized (lock) {
			if (pending.isEmpty()) {
				return Optional.empty();
			}
			AgentTask task = pending.pollFirst();
			return Optional.of(task);
		}
	}

	/**
	 * Looks up a task by identifier, including finished tasks.
	 *
	 * @param id the task identifier
	 * @return the task when known
	 */
	public Optional<AgentTask> find(String id) {
		if (id == null) {
			return Optional.empty();
		}
		synchronized (lock) {
			return Optional.ofNullable(byId.get(id));
		}
	}

	/**
	 * Cancels a task whether it is pending or running.
	 *
	 * @param id the task identifier
	 * @param reason why the task is being cancelled
	 * @return {@code true} when a task was cancelled
	 */
	public boolean cancel(String id, String reason) {
		AgentTask task;
		synchronized (lock) {
			task = byId.get(id);
		}
		if (task == null) {
			return false;
		}
		if (!task.markCancelled(reason == null ? "cancelled by operator" : reason)) {
			return false;
		}
		finish(task);
		return true;
	}

	/**
	 * Cancels every task, pending or running.
	 *
	 * @param reason why the tasks are being cancelled
	 * @return how many tasks were cancelled
	 */
	public int cancelAll(String reason) {
		List<AgentTask> snapshot;
		synchronized (lock) {
			snapshot = new ArrayList<>(byId.values());
		}
		int cancelled = 0;
		for (AgentTask task : snapshot) {
			if (task.status().isTerminal()) {
				continue;
			}
			if (task.markCancelled(reason == null ? "cancelled by operator" : reason)) {
				finish(task);
				cancelled++;
			}
		}
		return cancelled;
	}

	/**
	 * Marks a task as running under the given module.
	 *
	 * @param id the task identifier
	 * @param module the module taking ownership
	 * @return {@code true} when the task was claimed
	 */
	public boolean claim(String id, String module) {
		AgentTask task;
		synchronized (lock) {
			task = byId.get(id);
		}
		if (task == null || !task.markRunning(module)) {
			return false;
		}
		synchronized (lock) {
			pending.remove(task);
			dirty = true;
		}
		notifyStatus(task);
		return true;
	}

	/**
	 * Marks a task as completed.
	 *
	 * @param id the task identifier
	 * @param note a short description of the outcome
	 * @return {@code true} when the task was completed
	 */
	public boolean complete(String id, String note) {
		return transition(id, task -> task.markCompleted(note));
	}

	/**
	 * Marks a task as terminally failed.
	 *
	 * @param id the task identifier
	 * @param reason why the task failed
	 * @return {@code true} when the task was failed
	 */
	public boolean fail(String id, String reason) {
		return transition(id, task -> task.markFailed(reason));
	}

	/**
	 * Puts a task back in the queue for another attempt.
	 *
	 * @param id the task identifier
	 * @param reason why the attempt failed
	 * @return {@code true} when the task was requeued
	 */
	public boolean retry(String id, String reason) {
		AgentTask task;
		synchronized (lock) {
			task = byId.get(id);
		}
		if (task == null || !task.markRetrying(reason)) {
			return false;
		}
		synchronized (lock) {
			pending.add(task);
			dirty = true;
		}
		notifyStatus(task);
		return true;
	}

	private boolean transition(String id, java.util.function.Predicate<AgentTask> action) {
		AgentTask task;
		synchronized (lock) {
			task = byId.get(id);
		}
		if (task == null || !action.test(task)) {
			return false;
		}
		finish(task);
		return true;
	}

	private void finish(AgentTask task) {
		synchronized (lock) {
			pending.remove(task);
			finished.put(task.id(), task.snapshot());
			pruneFinishedLocked();
			dirty = true;
		}
		// The task is terminal and no longer occupies a queue slot, so it must not stay
		// resolvable by id: callers use find() to decide whether work is still live.
		synchronized (lock) {
			AgentTask tracked = byId.get(task.id());
			if (tracked != null && tracked.status().isTerminal()) {
				byId.remove(task.id());
			}
		}
		notifyStatus(task);
	}

	/**
	 * @return the number of tasks still occupying a queue slot
	 */
	public int activeCount() {
		synchronized (lock) {
			return pending.size();
		}
	}

	/**
	 * @return the number of pending tasks only
	 */
	public int pendingCount() {
		synchronized (lock) {
			return pending.size();
		}
	}

	/**
	 * @return the number of finished tasks retained for reporting
	 */
	public int finishedCount() {
		synchronized (lock) {
			return finished.size();
		}
	}

	/**
	 * @return snapshots of active tasks in scheduling order
	 */
	public List<AgentTask.Snapshot> activeSnapshots() {
		synchronized (lock) {
			List<AgentTask.Snapshot> out = new ArrayList<>(pending.size());
			for (AgentTask task : pending) {
				out.add(task.snapshot());
			}
			return out;
		}
	}

	/**
	 * @return snapshots of retained finished tasks, most recent first
	 */
	public List<AgentTask.Snapshot> finishedSnapshots() {
		synchronized (lock) {
			List<AgentTask.Snapshot> out = new ArrayList<>(finished.values());
			java.util.Collections.reverse(out);
			return out;
		}
	}

	/**
	 * Finds the running task owned by a module.
	 *
	 * @param module the module name
	 * @return the first running task owned by that module
	 */
	public Optional<AgentTask> runningTaskOf(String module) {
		synchronized (lock) {
			for (AgentTask task : byId.values()) {
				if (task.status() == TaskStatus.RUNNING && task.owner().filter(module::equals).isPresent()) {
					return Optional.of(task);
				}
			}
			return Optional.empty();
		}
	}

	/**
	 * @return the currently running task, when exactly one exists
	 */
	public Optional<AgentTask> runningTask() {
		synchronized (lock) {
			for (AgentTask task : byId.values()) {
				if (task.status() == TaskStatus.RUNNING) {
					return Optional.of(task);
				}
			}
			return Optional.empty();
		}
	}

	private void pruneLocked() {
		pruneFinishedLocked();
		if (byId.size() < MAX_RESTORED_TASKS + FINISHED_HISTORY_LIMIT) {
			return;
		}
		// Drop the oldest finished entries first, then the lowest priority pending ones.
		List<Map.Entry<String, AgentTask.Snapshot>> entries = new ArrayList<>(finished.entrySet());
		for (Map.Entry<String, AgentTask.Snapshot> entry : entries) {
			finished.remove(entry.getKey());
			byId.remove(entry.getKey());
			if (byId.size() < MAX_RESTORED_TASKS) {
				break;
			}
		}
	}

	private void pruneFinishedLocked() {
		while (finished.size() > FINISHED_HISTORY_LIMIT) {
			String oldest = finished.keySet().iterator().next();
			finished.remove(oldest);
			AgentTask task = byId.get(oldest);
			if (task != null && task.status().isTerminal()) {
				byId.remove(oldest);
			}
		}
	}

	/**
	 * Persists the queue through the installed store.
	 *
	 * @return {@code true} when persistence succeeded or was unnecessary
	 */
	public boolean persist() {
		List<AgentTask.Snapshot> active;
		List<AgentTask.Snapshot> done;
		TaskStore target;
		synchronized (lock) {
			if (!dirty) {
				return true;
			}
			active = activeSnapshots();
			done = finishedSnapshots();
			target = store;
		}
		boolean ok;
		try {
			ok = target.save(active, done);
		} catch (RuntimeException e) {
			ok = false;
			recordPersistFailure(e);
		}
		synchronized (lock) {
			if (ok) {
				dirty = false;
			}
		}
		return ok;
	}

	private void recordPersistFailure(RuntimeException e) {
		synchronized (lock) {
			lastPersistFailure = System.currentTimeMillis();
			lastPersistError = e.getClass().getSimpleName() + ": " + e.getMessage();
		}
	}

	/**
	 * Restores tasks from the installed store. Running tasks are downgraded to
	 * pending because no module owns them after a restart.
	 *
	 * @return how many tasks were restored
	 */
	public int restore() {
		TaskStore.Payload payload;
		try {
			payload = store.load();
		} catch (RuntimeException e) {
			recordPersistFailure(e);
			return 0;
		}
		if (payload == null) {
			return 0;
		}
		int restored = 0;
		synchronized (lock) {
			for (AgentTask.Snapshot snapshot : payload.active()) {
				AgentTask task = rebuild(snapshot);
				if (task == null) {
					continue;
				}
				byId.put(task.id(), task);
				pending.add(task);
				restored++;
			}
			for (AgentTask.Snapshot snapshot : payload.finished()) {
				if (finished.size() >= FINISHED_HISTORY_LIMIT) {
					break;
				}
				AgentTask task = rebuild(snapshot);
				if (task == null) {
					continue;
				}
				task.markCancelled("interrupted by restart");
				finished.put(task.id(), task.snapshot());
			}
			dirty = restored > 0;
		}
		return restored;
	}

	private AgentTask rebuild(AgentTask.Snapshot snapshot) {
		if (snapshot == null || snapshot.id() == null || snapshot.kind() == null) {
			return null;
		}
		TaskKind kind = TaskKind.parse(snapshot.kind());
		if (kind == null) {
			return null;
		}
		TaskPriority priority = TaskPriority.parse(snapshot.priority());
		if (priority == null) {
			priority = TaskPriority.NORMAL;
		}
		return new AgentTask(
				snapshot.id(),
				kind,
				priority,
				TaskParameters.of(snapshot.parameters()),
				snapshot.submittedAt() == 0 ? System.currentTimeMillis() : snapshot.submittedAt(),
				sequence++);
	}

	/**
	 * @return diagnostic information about queue and persistence health
	 */
	public Map<String, Object> diagnostics() {
		synchronized (lock) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("active", pending.size());
			out.put("finished", finished.size());
			out.put("tracked", byId.size());
			out.put("sequence", sequence);
			out.put("dirty", dirty);
			out.put("store", store.describe());
			if (lastPersistError != null) {
				out.put("lastPersistError", lastPersistError);
				out.put("lastPersistFailureAt", lastPersistFailure);
			}
			return out;
		}
	}

	private void notifySubmitted(AgentTask task) {
		for (TaskQueueListener listener : listeners) {
			try {
				listener.onSubmitted(task);
			} catch (RuntimeException e) {
				failedListeners++;
			}
		}
	}

	private void notifyStatus(AgentTask task) {
		AgentTask.Snapshot snapshot = task.snapshot();
		for (TaskQueueListener listener : listeners) {
			try {
				listener.onStatusChanged(snapshot);
			} catch (RuntimeException e) {
				failedListeners++;
			}
		}
	}

	/**
	 * @return how many listener callbacks have thrown since construction
	 */
	public long failedListeners() {
		return failedListeners;
	}

	/**
	 * Receives task queue events. Implementations must not throw; exceptions are
	 * caught and dropped so a bad listener cannot break the queue.
	 */
	public interface TaskQueueListener {
		/**
		 * @param task the newly submitted task
		 */
		default void onSubmitted(AgentTask task) {
		}

		/**
		 * @param task the task whose status changed
		 */
		default void onStatusChanged(AgentTask.Snapshot task) {
		}
	}
}
