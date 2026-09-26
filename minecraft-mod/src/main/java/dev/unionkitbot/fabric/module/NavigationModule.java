package dev.unionkitbot.fabric.module;

import java.util.Optional;
import java.util.Set;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;
import dev.unionkitbot.fabric.core.task.TaskParameters;
import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;

/**
 * Walks the local player towards a target coordinate.
 *
 * <p>This is a deliberately simple, deterministic controller: it faces the target,
 * holds the forward key, and declares arrival once inside
 * {@link AgentConfig.NavigationConfig#arriveRadius()}. It does not path-find around
 * obstacles. When it stops making progress it reports a retryable failure and lets
 * the recovery module decide what to do, rather than burning the task's whole
 * timeout standing still.
 */
public final class NavigationModule implements AutomationModule {
	/** Module name used in configuration and status frames. */
	public static final String NAME = "navigation";

	private final AgentActions actions;
	private final DiagnosticsLog log;

	private volatile AgentConfig.NavigationConfig settings;

	private AgentTask task;
	private TaskParameters.Position target;
	private int ticksSinceInputUpdate;
	private int stuckTicks;
	private TaskParameters.Position lastPosition;
	private double initialDistance = 1.0d;
	private boolean holdingForward;
	private boolean enabled = true;

	/**
	 * @param actions the world-facing actions
	 * @param settings the initial navigation settings
	 * @param log the diagnostics log
	 */
	public NavigationModule(AgentActions actions, AgentConfig.NavigationConfig settings, DiagnosticsLog log) {
		this.actions = actions;
		this.settings = settings;
		this.log = log;
	}

	/**
	 * Applies new navigation settings.
	 *
	 * @param settings the new settings
	 */
	public void applySettings(AgentConfig.NavigationConfig settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<TaskKind> supportedKinds() {
		return Set.of(TaskKind.NAVIGATE, TaskKind.RETURN_HOME);
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Enables or disables the module.
	 *
	 * @param enabled the desired state
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (!enabled) {
			stop("module disabled");
		}
	}

	@Override
	public boolean isIdle() {
		return task == null;
	}

	@Override
	public boolean accept(AgentTask candidate) {
		if (candidate == null || !supportedKinds().contains(candidate.kind()) || task != null) {
			return false;
		}
		TaskParameters.Position requested = resolveTarget(candidate);
		if (requested == null) {
			log.warn(NAME, "task " + candidate.id() + " rejected: no usable destination");
			return false;
		}
		this.task = candidate;
		this.target = requested;
		this.stuckTicks = 0;
		this.lastPosition = null;
		this.ticksSinceInputUpdate = 0;
		log.info(NAME, "task " + candidate.id() + " accepted, target "
				+ String.format("%.1f/%.1f/%.1f", requested.x(), requested.y(), requested.z()));
		return true;
	}

	private TaskParameters.Position resolveTarget(AgentTask candidate) {
		Optional<TaskParameters.Position> explicit = candidate.parameters().position();
		if (explicit.isPresent()) {
			return explicit.get();
		}
		if (candidate.kind() == TaskKind.RETURN_HOME) {
			return settings.home().orElse(null);
		}
		return null;
	}

	@Override
	public Optional<AgentTask> currentTask() {
		return Optional.ofNullable(task);
	}

	@Override
	public ActionResult tick(Observation observation) {
		if (task == null) {
			return ActionResult.idle("no navigation task");
		}
		if (!observation.isActionable()) {
			// Re-check every tick: the world can vanish between the offer and the act.
			releaseInputs();
			return ActionResult.notActionable();
		}
		Optional<TaskParameters.Position> current = observation.position();
		if (current.isEmpty()) {
			releaseInputs();
			return ActionResult.retryable("player position unavailable");
		}
		TaskParameters.Position here = current.get();
		if (lastPosition == null) {
			// Anchor progress to the distance at the first actionable tick.
			initialDistance = Math.max(here.distanceTo(target), 1.0d);
		}
		double distance = here.distanceTo(target);
		double arriveRadius = settings.arriveRadius();
		task.setProgress(1.0d - Math.min(1.0d, distance / initialDistance));
		if (distance <= arriveRadius) {
			releaseInputs();
			return ActionResult.completed(String.format("arrived within %.2f blocks", distance));
		}
		ActionResult look = actions.lookAt(target.x(), target.z());
		if (!look.performed() && !look.retryable()) {
			releaseInputs();
			return look;
		}
		updateStuckCounter(here);
		if (stuckTicks >= settings.stepTicks() * 25) {
			releaseInputs();
			return ActionResult.retryable("no positional progress for " + stuckTicks + " ticks");
		}
		ticksSinceInputUpdate++;
		if (ticksSinceInputUpdate >= settings.stepTicks() || !holdingForward) {
			ticksSinceInputUpdate = 0;
			ActionResult pressed = actions.press(AgentActions.MovementInput.FORWARD);
			if (pressed.performed()) {
				holdingForward = true;
			} else if (!pressed.retryable()) {
				releaseInputs();
				return pressed;
			}
		}
		return ActionResult.ok(String.format("walking, %.2f blocks remaining", distance));
	}

	private void updateStuckCounter(TaskParameters.Position here) {
		if (lastPosition == null) {
			lastPosition = here;
			return;
		}
		double moved = lastPosition.distanceTo(here);
		if (moved < 0.05d) {
			stuckTicks++;
		} else {
			stuckTicks = 0;
			lastPosition = here;
		}
	}

	@Override
	public void stop(String reason) {
		releaseInputs();
		task = null;
		target = null;
		lastPosition = null;
		stuckTicks = 0;
		log.info(NAME, "cancelled: " + reason);
	}

	@Override
	public void reset(String reason) {
		stop(reason);
	}

	private void releaseInputs() {
		if (!holdingForward) {
			return;
		}
		ActionResult result = actions.release(AgentActions.MovementInput.FORWARD);
		if (!result.performed() && !result.retryable()) {
			log.warn(NAME, "failed to release forward input: " + result.detail());
		}
		holdingForward = false;
	}

	@Override
	public String statusLine() {
		if (task == null) {
			return "idle";
		}
		return target == null
				? "task " + task.id()
				: String.format("task %s -> %.1f/%.1f/%.1f", task.id(), target.x(), target.y(), target.z());
	}
}
