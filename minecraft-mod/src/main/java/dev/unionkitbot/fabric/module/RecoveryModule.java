package dev.unionkitbot.fabric.module;

import java.util.Optional;
import java.util.Set;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;
import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;

/**
 * Handles failure and lifecycle recovery.
 *
 * <p>Recovery owns the RECOVERING state. It releases every held input, waits out an
 * exponential backoff, and only then reports that the agent may resume. It never
 * restarts the network connection itself: reconnection belongs to the client, and
 * an agent that tried to force it would fight the user and risk breaking the
 * session. Instead it parks automation until the world is usable again.
 */
public final class RecoveryModule implements AutomationModule {
	/** Module name used in configuration and status frames. */
	public static final String NAME = "recovery";

	private final AgentActions actions;
	private final DiagnosticsLog log;

	private volatile AgentConfig.RecoveryConfig settings;

	private AgentTask task;
	private long backoffUntil;
	private int attempt;
	private String lastReason = "none";
	private boolean enabled = true;

	/**
	 * @param actions the world-facing actions
	 * @param settings the initial recovery settings
	 * @param log the diagnostics log
	 */
	public RecoveryModule(AgentActions actions, AgentConfig.RecoveryConfig settings, DiagnosticsLog log) {
		this.actions = actions;
		this.settings = settings;
		this.log = log;
	}

	/**
	 * Applies new recovery settings.
	 *
	 * @param settings the new settings
	 */
	public void applySettings(AgentConfig.RecoveryConfig settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<TaskKind> supportedKinds() {
		return AutomationModule.single(TaskKind.RECOVER);
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
		this.task = candidate;
		attempt = 0;
		log.info(NAME, "task " + candidate.id() + " accepted");
		return true;
	}

	@Override
	public Optional<AgentTask> currentTask() {
		return Optional.ofNullable(task);
	}

	/**
	 * Begins a recovery cycle outside of a task, used when the agent detects a
	 * lifecycle problem on its own.
	 *
	 * @param reason why recovery was triggered
	 */
	public void begin(String reason) {
		lastReason = reason == null ? "unspecified" : reason;
		attempt = 0;
		backoffUntil = 0L;
		ActionResult released = actions.releaseAll();
		if (!released.performed() && !released.retryable()) {
			log.warn(NAME, "could not release inputs while entering recovery: " + released.detail());
		}
		log.warn(NAME, "recovery started: " + lastReason);
	}

	@Override
	public ActionResult tick(Observation observation) {
		if (task == null) {
			return ActionResult.idle("no recovery task");
		}
		ActionResult released = actions.releaseAll();
		if (!released.performed() && !released.retryable()) {
			log.warn(NAME, "could not release inputs: " + released.detail());
		}
		long now = System.currentTimeMillis();
		if (backoffUntil == 0L) {
			attempt++;
			long delay = settings.backoffFor(attempt);
			backoffUntil = now + delay;
			task.addNote("recovery attempt " + attempt + " after " + delay + "ms");
			return ActionResult.ok("backing off for " + delay + "ms");
		}
		if (now < backoffUntil) {
			long remaining = backoffUntil - now;
			task.setProgress(1.0d - Math.min(1.0d, (double) remaining / Math.max(1L, settings.maxBackoffMillis())));
			return ActionResult.ok("waiting " + remaining + "ms before resuming");
		}
		if (!observation.isActionable()) {
			// Keep waiting: resuming into a missing world would immediately fail again.
			backoffUntil = now + settings.backoffMillis();
			return ActionResult.ok("world not ready, extending backoff");
		}
		backoffUntil = 0L;
		return ActionResult.completed("recovery complete after " + attempt + " attempts");
	}

	/**
	 * @return the reason for the most recent recovery cycle
	 */
	public String lastReason() {
		return lastReason;
	}

	/**
	 * @return how many attempts the current cycle has made
	 */
	public int attempt() {
		return attempt;
	}

	@Override
	public void stop(String reason) {
		actions.releaseAll();
		task = null;
		backoffUntil = 0L;
		attempt = 0;
		log.info(NAME, "cancelled: " + reason);
	}

	@Override
	public void reset(String reason) {
		stop(reason);
	}

	@Override
	public String statusLine() {
		if (task == null) {
			return "idle (last: " + lastReason + ")";
		}
		long remaining = Math.max(0L, backoffUntil - System.currentTimeMillis());
		return "task " + task.id() + " attempt " + attempt + ", " + remaining + "ms remaining";
	}
}
