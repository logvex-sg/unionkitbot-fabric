package dev.unionkitbot.fabric.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;

/**
 * Owns the four automation modules and routes tasks to the right one.
 *
 * <p>The registry is the DECIDE step: it chooses which module should run next based
 * on task kind, module availability and configuration. Keeping the routing here
 * means the modules never need to know about each other.
 */
public final class ModuleRegistry {
	private final Map<String, AutomationModule> modules = new LinkedHashMap<>();

	/**
	 * @param modules the modules to register, in priority order
	 */
	public ModuleRegistry(List<AutomationModule> modules) {
		for (AutomationModule module : modules) {
			this.modules.put(module.name(), module);
		}
	}

	/**
	 * @return the registered modules in registration order
	 */
	public List<AutomationModule> all() {
		return List.copyOf(modules.values());
	}

	/**
	 * @param name the module name
	 * @return the module when registered
	 */
	public Optional<AutomationModule> byName(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(modules.get(name));
	}

	/**
	 * Finds a module that can accept the task right now.
	 *
	 * @param task the candidate task
	 * @return the accepting module, or empty when none is available
	 */
	public Optional<AutomationModule> route(AgentTask task) {
		if (task == null) {
			return Optional.empty();
		}
		for (AutomationModule module : modules.values()) {
			if (!module.isEnabled() || !module.isIdle() || !module.supportedKinds().contains(task.kind())) {
				continue;
			}
			if (module.accept(task)) {
				return Optional.of(module);
			}
		}
		return Optional.empty();
	}

	/**
	 * Applies configuration to every module that exposes a settings setter.
	 *
	 * @param config the new configuration
	 */
	public void applyConfig(AgentConfig config) {
		for (AutomationModule module : modules.values()) {
			boolean enabled = config.modules().isEnabled(module.name());
			switch (module) {
				case NavigationModule navigation -> {
					navigation.applySettings(config.navigation());
					navigation.setEnabled(enabled);
				}
				case DeliveryModule delivery -> {
					delivery.applySettings(config.delivery());
					delivery.setEnabled(enabled);
				}
				case ScanningModule scanning -> {
					scanning.applySettings(config.scan());
					scanning.setEnabled(enabled);
				}
				case RecoveryModule recovery -> {
					recovery.applySettings(config.recovery());
					recovery.setEnabled(enabled);
				}
				default -> {
					// A module without a settings setter keeps its own defaults.
				}
			}
		}
	}

	/**
	 * Cancels every module's current task.
	 *
	 * @param reason why the modules are being cancelled
	 */
	public void stopAll(String reason) {
		for (AutomationModule module : modules.values()) {
			module.stop(reason);
		}
	}

	/**
	 * Resets every module.
	 *
	 * @param reason why the modules are being reset
	 */
	public void resetAll(String reason) {
		for (AutomationModule module : modules.values()) {
			module.reset(reason);
		}
	}

	/**
	 * @return the first module that currently owns a task
	 */
	public Optional<AutomationModule> busyModule() {
		for (AutomationModule module : modules.values()) {
			if (!module.isIdle()) {
				return Optional.of(module);
			}
		}
		return Optional.empty();
	}

	/**
	 * @return the task owned by the first busy module
	 */
	public Optional<AgentTask> busyTask() {
		return busyModule().flatMap(AutomationModule::currentTask);
	}

	/**
	 * @return {@code true} when any module owns a task
	 */
	public boolean anyBusy() {
		return busyModule().isPresent();
	}

	/**
	 * @param kind the task kind
	 * @return whether any enabled module supports the kind
	 */
	public boolean supports(TaskKind kind) {
		if (kind == null) {
			return false;
		}
		for (AutomationModule module : modules.values()) {
			if (module.isEnabled() && module.supportedKinds().contains(kind)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return a status summary for the in-game screen and status frames
	 */
	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		for (AutomationModule module : modules.values()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("enabled", module.isEnabled());
			entry.put("idle", module.isIdle());
			entry.put("status", module.statusLine());
			module.currentTask().map(AgentTask::id).ifPresent(id -> entry.put("task", id));
			out.put(module.name(), Map.copyOf(entry));
		}
		return Map.copyOf(out);
	}

	/**
	 * @return a single line summary of every module
	 */
	public String summaryLine() {
		StringBuilder builder = new StringBuilder();
		for (AutomationModule module : modules.values()) {
			if (builder.length() > 0) {
				builder.append(" | ");
			}
			builder.append(module.name()).append(module.isEnabled() ? "" : "(off)").append(':')
					.append(module.isIdle() ? "idle" : "busy");
		}
		return builder.toString();
	}
}
