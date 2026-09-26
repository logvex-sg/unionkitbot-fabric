package dev.unionkitbot.fabric.core.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A unit of automation work with persistent, observable lifecycle state.
 *
 * <p>All mutable fields are guarded by an internal lock because tasks are created
 * from the control API threads but advanced from the Minecraft client thread.
 */
public final class AgentTask {
	private static final int MAX_NOTE_HISTORY = 32;

	private final Object lock = new Object();
	private final String id;
	private final TaskKind kind;
	private final TaskPriority priority;
	private final TaskParameters parameters;
	private final long submittedAt;
	private final long sequence;

	private TaskStatus status = TaskStatus.PENDING;
	private String owner;
	private String failureReason;
	private int attempts;
	private long startedAt;
	private long finishedAt;
	private double progress;
	private final List<String> notes = new ArrayList<>();
	private final List<Consumer<TaskStatus>> listeners = new ArrayList<>();

	AgentTask(String id, TaskKind kind, TaskPriority priority, TaskParameters parameters, long submittedAt, long sequence) {
		this.id = Objects.requireNonNull(id, "id");
		this.kind = Objects.requireNonNull(kind, "kind");
		this.priority = Objects.requireNonNull(priority, "priority");
		this.parameters = Objects.requireNonNull(parameters, "parameters");
		this.submittedAt = submittedAt;
		this.sequence = sequence;
	}

	/**
	 * @return the opaque task identifier
	 */
	public String id() {
		return id;
	}

	/**
	 * @return the kind of work requested
	 */
	public TaskKind kind() {
		return kind;
	}

	/**
	 * @return the scheduling priority
	 */
	public TaskPriority priority() {
		return priority;
	}

	/**
	 * @return the immutable parameters
	 */
	public TaskParameters parameters() {
		return parameters;
	}

	/**
	 * @return submission time in epoch milliseconds
	 */
	public long submittedAt() {
		return submittedAt;
	}

	/**
	 * @return the monotonic submission sequence, used to break priority ties
	 */
	public long sequence() {
		return sequence;
	}

	/**
	 * @return the current status
	 */
	public TaskStatus status() {
		synchronized (lock) {
			return status;
		}
	}

	/**
	 * @return the module currently owning the task, or empty
	 */
	public Optional<String> owner() {
		synchronized (lock) {
			return Optional.ofNullable(owner);
		}
	}

	/**
	 * @return the failure reason recorded by the last failed attempt, or empty
	 */
	public Optional<String> failureReason() {
		synchronized (lock) {
			return Optional.ofNullable(failureReason);
		}
	}

	/**
	 * @return how many times execution has been attempted
	 */
	public int attempts() {
		synchronized (lock) {
			return attempts;
		}
	}

	/**
	 * @return start time of the current or last attempt, or {@code 0}
	 */
	public long startedAt() {
		synchronized (lock) {
			return startedAt;
		}
	}

	/**
	 * @return completion time, or {@code 0} while active
	 */
	public long finishedAt() {
		synchronized (lock) {
			return finishedAt;
		}
	}

	/**
	 * @return coarse progress in the range {@code [0, 1]}
	 */
	public double progress() {
		synchronized (lock) {
			return progress;
		}
	}

	/**
	 * @return recent human readable notes, oldest first
	 */
	public List<String> notes() {
		synchronized (lock) {
			return List.copyOf(notes);
		}
	}

	/**
	 * Records that a module took ownership of the task.
	 *
	 * @param module the owning module name
	 * @return {@code true} when the task moved to {@link TaskStatus#RUNNING}
	 */
	public boolean markRunning(String module) {
		synchronized (lock) {
			if (status == TaskStatus.RUNNING || status.isTerminal()) {
				return false;
			}
			attempts++;
			startedAt = System.currentTimeMillis();
			owner = module;
			failureReason = null;
			return setStatusLocked(TaskStatus.RUNNING);
		}
	}

	/**
	 * Records a successful completion.
	 *
	 * @param note a short description of the outcome
	 * @return {@code true} when the status changed
	 */
	public boolean markCompleted(String note) {
		synchronized (lock) {
			if (status.isTerminal()) {
				return false;
			}
			addNoteLocked(note);
			progress = 1.0d;
			finishedAt = System.currentTimeMillis();
			return setStatusLocked(TaskStatus.COMPLETED);
		}
	}

	/**
	 * Records a terminal failure.
	 *
	 * @param reason why the task failed
	 * @return {@code true} when the status changed
	 */
	public boolean markFailed(String reason) {
		synchronized (lock) {
			if (status.isTerminal()) {
				return false;
			}
			failureReason = reason;
			addNoteLocked("failed: " + reason);
			finishedAt = System.currentTimeMillis();
			return setStatusLocked(TaskStatus.FAILED);
		}
	}

	/**
	 * Records a failure that still has attempts left.
	 *
	 * @param reason why the attempt failed
	 * @return {@code true} when the status changed
	 */
	public boolean markRetrying(String reason) {
		synchronized (lock) {
			if (status.isTerminal()) {
				return false;
			}
			failureReason = reason;
			addNoteLocked("retry: " + reason);
			owner = null;
			return setStatusLocked(TaskStatus.RETRYING);
		}
	}

	/**
	 * Cancels the task.
	 *
	 * @param reason why the task was cancelled
	 * @return {@code true} when the status changed
	 */
	public boolean markCancelled(String reason) {
		synchronized (lock) {
			if (status.isTerminal()) {
				return false;
			}
			addNoteLocked("cancelled: " + reason);
			finishedAt = System.currentTimeMillis();
			return setStatusLocked(TaskStatus.CANCELLED);
		}
	}

	/**
	 * Updates coarse progress.
	 *
	 * @param value the new progress, clamped to {@code [0, 1]}
	 */
	public void setProgress(double value) {
		synchronized (lock) {
			if (status.isTerminal()) {
				return;
			}
			progress = Math.max(0.0d, Math.min(1.0d, value));
		}
	}

	/**
	 * Appends a note, keeping only the most recent entries.
	 *
	 * @param note the note text, ignored when blank
	 */
	public void addNote(String note) {
		synchronized (lock) {
			addNoteLocked(note);
		}
	}

	private void addNoteLocked(String note) {
		if (note == null || note.isBlank()) {
			return;
		}
		notes.add(note);
		while (notes.size() > MAX_NOTE_HISTORY) {
			notes.remove(0);
		}
	}

	/**
	 * Registers a listener invoked whenever the status changes.
	 *
	 * @param listener the listener
	 */
	public void addStatusListener(Consumer<TaskStatus> listener) {
		synchronized (lock) {
			listeners.add(Objects.requireNonNull(listener, "listener"));
		}
	}

	private boolean setStatusLocked(TaskStatus next) {
		if (status == next) {
			return false;
		}
		status = next;
		for (Consumer<TaskStatus> listener : List.copyOf(listeners)) {
			listener.accept(next);
		}
		return true;
	}

	/**
	 * @return an immutable snapshot suitable for serialisation
	 */
	public Snapshot snapshot() {
		synchronized (lock) {
			Map<String, Object> params = new LinkedHashMap<>(parameters.raw());
			return new Snapshot(
					id,
					kind.id(),
					priority.name(),
					priority.weight(),
					status.name(),
					owner,
					failureReason,
					attempts,
					submittedAt,
					startedAt,
					finishedAt,
					progress,
					params,
					List.copyOf(notes));
		}
	}

	@Override
	public String toString() {
		return "AgentTask[" + id + " " + kind.id() + " " + priority + " " + status() + "]";
	}

	/**
	 * Immutable representation of a task, safe to hand to another thread.
	 *
	 * @param id task identifier
	 * @param kind task kind identifier
	 * @param priority priority name
	 * @param priorityWeight numeric priority weight
	 * @param status status name
	 * @param owner owning module, may be {@code null}
	 * @param failureReason last failure, may be {@code null}
	 * @param attempts number of attempts
	 * @param submittedAt submission timestamp
	 * @param startedAt start timestamp, {@code 0} when never started
	 * @param finishedAt finish timestamp, {@code 0} while active
	 * @param progress coarse progress in {@code [0, 1]}
	 * @param parameters raw parameters
	 * @param notes recent notes
	 */
	public record Snapshot(
			String id,
			String kind,
			String priority,
			int priorityWeight,
			String status,
			String owner,
			String failureReason,
			int attempts,
			long submittedAt,
			long startedAt,
			long finishedAt,
			double progress,
			Map<String, Object> parameters,
			List<String> notes) {
	}
}
