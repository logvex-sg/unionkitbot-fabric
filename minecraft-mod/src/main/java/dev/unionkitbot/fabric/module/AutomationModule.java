package dev.unionkitbot.fabric.module;

import java.util.Optional;
import java.util.Set;

import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;
import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;

/**
 * A self-contained unit of automation.
 *
 * <p>Modules are the ACT step of the OBSERVE → DECIDE → ACT → VERIFY loop. Each
 * module owns exactly one kind of task, decides for itself whether it can act on an
 * observation, and reports a single {@link ActionResult} per tick. Navigation,
 * delivery, scanning and recovery are separate implementations, so a failure in one
 * cannot stall the others.
 */
public interface AutomationModule {

	/**
	 * @return the stable module name used in configuration and status frames
	 */
	String name();

	/**
	 * @return the task kinds this module can execute
	 */
	Set<TaskKind> supportedKinds();

	/**
	 * @return {@code true} when the module is enabled by configuration
	 */
	boolean isEnabled();

	/**
	 * @return {@code true} when the module can accept a new task right now
	 */
	boolean isIdle();

	/**
	 * Offers a task to the module. Implementations must only accept tasks whose kind
	 * they support and must return {@code false} when already busy.
	 *
	 * @param task the candidate task
	 * @return {@code true} when the module took ownership
	 */
	boolean accept(AgentTask task);

	/**
	 * @return the task currently owned by this module, if any
	 */
	Optional<AgentTask> currentTask();

	/**
	 * Runs one tick of the module's ACT step.
	 *
	 * <p>Implementations must be defensive: the observation is guaranteed to be a
	 * consistent snapshot, but the underlying world can change at any moment, so any
	 * state read must be re-checked before use.
	 *
	 * @param observation the current observation
	 * @return the outcome of this tick
	 */
	ActionResult tick(Observation observation);

	/**
	 * Cancels the current task, releasing any inputs the module pressed.
	 *
	 * @param reason why the module is being stopped
	 */
	void stop(String reason);

	/**
	 * Releases all held inputs and clears internal state. Called on disconnect, world
	 * unload, respawn and shutdown.
	 *
	 * @param reason why the module is being reset
	 */
	void reset(String reason);

	/**
	 * @return a short human readable status line for the in-game screen
	 */
	String statusLine();

	/**
	 * Default implementation of {@link #supportedKinds()} for single-kind modules.
	 *
	 * @param kind the supported kind
	 * @return a single element set
	 */
	static Set<TaskKind> single(TaskKind kind) {
		return Set.of(kind);
	}
}
