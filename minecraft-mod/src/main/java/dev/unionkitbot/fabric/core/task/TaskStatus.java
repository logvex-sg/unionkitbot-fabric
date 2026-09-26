package dev.unionkitbot.fabric.core.task;

/**
 * Lifecycle of a single task.
 */
public enum TaskStatus {
	/** Waiting in the queue. */
	PENDING,
	/** Currently owned by an automation module. */
	RUNNING,
	/** Finished successfully. */
	COMPLETED,
	/** Stopped by an operator or by the agent shutting down. */
	CANCELLED,
	/** Failed and will not be retried. */
	FAILED,
	/** Failed but has attempts left and is back in the queue. */
	RETRYING;

	/**
	 * @return {@code true} when the task occupies a slot in the queue
	 */
	public boolean isActive() {
		return this == PENDING || this == RUNNING || this == RETRYING;
	}

	/**
	 * @return {@code true} when no further progress is possible
	 */
	public boolean isTerminal() {
		return this == COMPLETED || this == CANCELLED || this == FAILED;
	}
}
