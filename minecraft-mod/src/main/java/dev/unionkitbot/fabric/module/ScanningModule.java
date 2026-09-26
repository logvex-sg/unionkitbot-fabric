package dev.unionkitbot.fabric.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;
import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;

/**
 * Surveys the surroundings without moving.
 *
 * <p>Scanning is a pure observation module: it reads the world snapshot it is given
 * and produces a report. It holds no client references, so a scan that starts
 * before a disconnect simply reports nothing rather than throwing.
 */
public final class ScanningModule implements AutomationModule {
	/** Module name used in configuration and status frames. */
	public static final String NAME = "scanning";

	private final DiagnosticsLog log;

	private volatile AgentConfig.ScanConfig settings;

	private AgentTask task;
	private Map<String, Object> lastReport = Map.of();
	private boolean enabled = true;

	/**
	 * @param settings the initial scan settings
	 * @param log the diagnostics log
	 */
	public ScanningModule(AgentConfig.ScanConfig settings, DiagnosticsLog log) {
		this.settings = settings;
		this.log = log;
	}

	/**
	 * Applies new scan settings.
	 *
	 * @param settings the new settings
	 */
	public void applySettings(AgentConfig.ScanConfig settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<TaskKind> supportedKinds() {
		return Set.of(TaskKind.SCAN, TaskKind.WAIT);
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
		log.info(NAME, "task " + candidate.id() + " accepted (" + candidate.kind().id() + ")");
		return true;
	}

	@Override
	public Optional<AgentTask> currentTask() {
		return Optional.ofNullable(task);
	}

	@Override
	public ActionResult tick(Observation observation) {
		if (task == null) {
			return ActionResult.idle("no scan task");
		}
		if (!observation.isActionable()) {
			return ActionResult.notActionable();
		}
		if (task.kind() == TaskKind.WAIT) {
			return ActionResult.ok("waiting");
		}
		lastReport = buildReport(observation);
		task.setProgress(1.0d);
		return ActionResult.completed("scan complete: " + lastReport.get("playerCount") + " players in range");
	}

	private Map<String, Object> buildReport(Observation observation) {
		int radius = settings.radius();
		int vertical = settings.verticalRadius();
		int max = settings.maxResults();
		Optional<dev.unionkitbot.fabric.core.task.TaskParameters.Position> origin = observation.position();
		List<Map<String, Object>> players = new ArrayList<>();
		for (Observation.NearbyPlayer player : observation.nearbyPlayers()) {
			if (players.size() >= max) {
				break;
			}
			if (origin.isEmpty()) {
				break;
			}
			double dy = Math.abs(player.position().y() - origin.get().y());
			if (player.distance() > radius || dy > vertical) {
				continue;
			}
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("name", player.name());
			entry.put("uuid", player.uuid().toString());
			entry.put("distance", player.distance());
			entry.put("x", player.position().x());
			entry.put("y", player.position().y());
			entry.put("z", player.position().z());
			players.add(Map.copyOf(entry));
		}
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("radius", radius);
		report.put("verticalRadius", vertical);
		report.put("playerCount", players.size());
		report.put("players", List.copyOf(players));
		report.put("dimension", observation.dimensionId().orElse("unknown"));
		report.put("worldTime", observation.worldTime());
		report.put("health", observation.health());
		report.put("foodLevel", observation.foodLevel());
		report.put("truncated", observation.nearbyPlayers().size() > players.size());
		return Map.copyOf(report);
	}

	/**
	 * @return the report produced by the most recent scan
	 */
	public Map<String, Object> lastReport() {
		return lastReport;
	}

	@Override
	public void stop(String reason) {
		task = null;
		log.info(NAME, "cancelled: " + reason);
	}

	@Override
	public void reset(String reason) {
		stop(reason);
		lastReport = Map.of();
	}

	@Override
	public String statusLine() {
		return task == null ? "idle" : "task " + task.id() + " (" + task.kind().id() + ")";
	}
}
