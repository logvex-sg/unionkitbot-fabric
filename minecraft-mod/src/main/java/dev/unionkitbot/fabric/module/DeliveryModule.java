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
 * Walks to a target player and hands over the held item.
 *
 * <p>Delivery is a two phase module: it navigates until the recipient is inside
 * {@link AgentConfig.DeliveryConfig#interactRadius()}, then faces the recipient and
 * swings the main hand, which is the client-side action that signals a hand-off.
 * The module never invents a recipient: if the task names nobody and no default is
 * configured, the task is rejected so a delivery cannot silently go to the wrong
 * player.
 */
public final class DeliveryModule implements AutomationModule {
	/** Module name used in configuration and status frames. */
	public static final String NAME = "delivery";

	private static final int SWING_INTERVAL_TICKS = 10;
	private static final int MAX_SWINGS = 12;

	private final AgentActions actions;
	private final DiagnosticsLog log;

	private volatile AgentConfig.DeliveryConfig settings;

	private AgentTask task;
	private String targetName;
	private int ticksSinceInputUpdate;
	private int stuckTicks;
	private TaskParameters.Position lastPosition;
	private double initialDistance = 1.0d;
	private int swings;
	private int ticksSinceSwing;
	private boolean holdingForward;
	private boolean enabled = true;

	/**
	 * @param actions the world-facing actions
	 * @param settings the initial delivery settings
	 * @param log the diagnostics log
	 */
	public DeliveryModule(AgentActions actions, AgentConfig.DeliveryConfig settings, DiagnosticsLog log) {
		this.actions = actions;
		this.settings = settings;
		this.log = log;
	}

	/**
	 * Applies new delivery settings.
	 *
	 * @param settings the new settings
	 */
	public void applySettings(AgentConfig.DeliveryConfig settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<TaskKind> supportedKinds() {
		return AutomationModule.single(TaskKind.DELIVER);
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
		String requested = candidate.parameters().getString("target")
				.or(() -> candidate.parameters().getString("player"))
				.orElse(settings.defaultTargetPlayer().isBlank() ? null : settings.defaultTargetPlayer());
		if (requested == null) {
			log.warn(NAME, "task " + candidate.id() + " rejected: no recipient configured or named");
			return false;
		}
		this.task = candidate;
		this.targetName = requested;
		this.stuckTicks = 0;
		this.lastPosition = null;
		this.swings = 0;
		this.ticksSinceSwing = 0;
		this.ticksSinceInputUpdate = 0;
		log.info(NAME, "task " + candidate.id() + " accepted, recipient " + requested);
		return true;
	}

	@Override
	public Optional<AgentTask> currentTask() {
		return Optional.ofNullable(task);
	}

	@Override
	public ActionResult tick(Observation observation) {
		if (task == null) {
			return ActionResult.idle("no delivery task");
		}
		if (!observation.isActionable()) {
			releaseInputs();
			return ActionResult.notActionable();
		}
		Optional<Observation.NearbyPlayer> recipient = observation.playerByName(targetName);
		if (recipient.isEmpty()) {
			releaseInputs();
			return ActionResult.retryable("recipient '" + targetName + "' is not in range");
		}
		Observation.NearbyPlayer player = recipient.get();
		double radius = settings.interactRadius();
		if (player.distance() <= radius) {
			releaseInputs();
			return handOver(observation, player);
		}
		return approach(observation, player);
	}

	private ActionResult approach(Observation observation, Observation.NearbyPlayer player) {
		Optional<TaskParameters.Position> current = observation.position();
		if (current.isEmpty()) {
			releaseInputs();
			return ActionResult.retryable("player position unavailable");
		}
		TaskParameters.Position here = current.get();
		if (lastPosition == null) {
			initialDistance = Math.max(here.distanceTo(player.position()), 1.0d);
		}
		task.setProgress(0.8d * (1.0d - Math.min(1.0d, player.distance() / initialDistance)));
		ActionResult look = actions.lookAt(player.position().x(), player.position().z());
		if (!look.performed() && !look.retryable()) {
			releaseInputs();
			return look;
		}
		updateStuckCounter(here);
		if (stuckTicks >= 200) {
			releaseInputs();
			return ActionResult.retryable("no progress towards recipient for " + stuckTicks + " ticks");
		}
		ticksSinceInputUpdate++;
		if (ticksSinceInputUpdate >= 2 || !holdingForward) {
			ticksSinceInputUpdate = 0;
			ActionResult pressed = actions.press(AgentActions.MovementInput.FORWARD);
			if (pressed.performed()) {
				holdingForward = true;
			} else if (!pressed.retryable()) {
				releaseInputs();
				return pressed;
			}
		}
		return ActionResult.ok(String.format("approaching %s, %.2f blocks", targetName, player.distance()));
	}

	private ActionResult handOver(Observation observation, Observation.NearbyPlayer player) {
		String held = observation.selectedItemId().orElse("");
		if (!settings.itemWhitelist().isEmpty() && !settings.itemWhitelist().contains(held)) {
			return ActionResult.fatal("held item '" + (held.isBlank() ? "none" : held)
					+ "' is not in the delivery whitelist");
		}
		ticksSinceSwing++;
		if (ticksSinceSwing < SWING_INTERVAL_TICKS && swings > 0) {
			task.setProgress(0.8d + 0.2d * Math.min(1.0d, (double) swings / MAX_SWINGS));
			return ActionResult.ok("waiting for hand-off to complete");
		}
		ticksSinceSwing = 0;
		ActionResult look = actions.lookAt(player.position().x(), player.position().z());
		if (!look.performed() && !look.retryable()) {
			return look;
		}
		ActionResult swing = actions.swingMainHand();
		if (!swing.performed()) {
			return swing.retryable() ? ActionResult.retryable("hand-off not ready: " + swing.detail()) : swing;
		}
		swings++;
		task.setProgress(0.8d + 0.2d * Math.min(1.0d, (double) swings / MAX_SWINGS));
		if (swings >= MAX_SWINGS) {
			return ActionResult.completed("handed over to " + targetName + " after " + swings + " attempts");
		}
		return ActionResult.ok("hand-off attempt " + swings + " of " + MAX_SWINGS);
	}

	private void updateStuckCounter(TaskParameters.Position here) {
		if (lastPosition == null) {
			lastPosition = here;
			return;
		}
		if (lastPosition.distanceTo(here) < 0.05d) {
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
		targetName = null;
		lastPosition = null;
		stuckTicks = 0;
		swings = 0;
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
		return "task " + task.id() + " -> " + targetName + " (swings " + swings + ")";
	}
}
