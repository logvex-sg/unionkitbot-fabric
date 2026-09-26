package dev.unionkitbot.fabric.net;

/**
 * Runs a unit of work on the Minecraft client thread.
 *
 * <p>All agent and world mutation must happen on the client thread; the control API
 * runs on socket threads. Commands therefore hop onto the client thread and the
 * caller waits for the result. Keeping this behind an interface means the
 * dispatcher is testable with a direct executor and the game integration can use
 * {@code MinecraftClient.execute}.
 */
@FunctionalInterface
public interface MainThreadExecutor {

	/**
	 * Schedules work on the client thread.
	 *
	 * @param work the work to run
	 */
	void execute(Runnable work);

	/**
	 * An executor that runs the work immediately on the calling thread. Used by tests
	 * and by any context that is already the client thread.
	 */
	MainThreadExecutor DIRECT = Runnable::run;
}
