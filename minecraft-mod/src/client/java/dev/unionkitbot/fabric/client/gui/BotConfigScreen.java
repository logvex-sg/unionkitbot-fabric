package dev.unionkitbot.fabric.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.core.state.BotState;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.protocol.MessageType;
import dev.unionkitbot.fabric.runtime.BotRuntime;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The in-game status and configuration screen.
 *
 * <p>Shows the current lifecycle state, control-API connectivity, the active task,
 * the queue depth and the most recent errors, and provides toggles for the four
 * automation modules plus start/stop controls.
 *
 * <p>The screen is deliberately read-mostly and pulls its data through the runtime's
 * local dispatcher rather than reading agent internals directly. It is a thin view:
 * no state is cached across frames beyond what is drawn, so it cannot go stale or keep
 * a task alive after it has finished.
 */
public final class BotConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 12;
	private static final int BUTTON_HEIGHT = 20;
	private static final int MARGIN = 12;

	private static final int COLOR_TITLE = 0xFFFFFF;
	private static final int COLOR_LABEL = 0xA0A0A0;
	private static final int COLOR_VALUE = 0xE0E0E0;
	private static final int COLOR_OK = 0x55FF55;
	private static final int COLOR_WARN = 0xFFAA00;
	private static final int COLOR_BAD = 0xFF5555;
	private static final int COLOR_PANEL = 0x90000000;

	private final BotRuntime runtime;
	private final Screen parent;

	private String statusText = "loading";
	private boolean statusOk;
	private final List<String> detailLines = new ArrayList<>();
	private final List<DiagnosticsLog.ErrorEntry> recentErrors = new ArrayList<>();

	private Button startStopButton;
	private Button refreshButton;
	private final List<ModuleToggle> toggles = new ArrayList<>();

	/**
	 * @param runtime the runtime to display
	 * @param parent the screen to return to when closed
	 */
	public BotConfigScreen(BotRuntime runtime, Screen parent) {
		super(Component.literal("UnionKitBot Fabric"));
		this.runtime = runtime;
		this.parent = parent;
	}

	@Override
	protected void init() {
		toggles.clear();
		clearWidgets();

		int left = MARGIN;
		int buttonY = height - BUTTON_HEIGHT - MARGIN;
		int buttonWidth = 100;

		startStopButton = addRenderableWidget(Button
				.builder(Component.literal("Start"), button -> toggleRunning())
				.bounds(left, buttonY, buttonWidth, BUTTON_HEIGHT)
				.build());

		refreshButton = addRenderableWidget(Button
				.builder(Component.literal("Refresh"), button -> refresh())
				.bounds(left + buttonWidth + 4, buttonY, buttonWidth, BUTTON_HEIGHT)
				.build());

		addRenderableWidget(Button
				.builder(Component.literal("Done"), button -> onClose())
				.bounds(width - buttonWidth - MARGIN, buttonY, buttonWidth, BUTTON_HEIGHT)
				.build());

		int toggleY = buttonY - BUTTON_HEIGHT - 6;
		int toggleWidth = Math.max(80, (width - 2 * MARGIN - 3 * 4) / 4);
		int x = left;
		for (String module : runtime.moduleNames()) {
			ModuleToggle toggle = new ModuleToggle(module);
			Button button = addRenderableWidget(Button
					.builder(Component.literal(label(toggle)), button2 -> toggleModule(toggle))
					.bounds(x, toggleY, toggleWidth, BUTTON_HEIGHT)
					.build());
			toggle.button = button;
			toggles.add(toggle);
			x += toggleWidth + 4;
		}
		refresh();
	}

	private static String label(ModuleToggle toggle) {
		return toggle.module.substring(0, 1).toUpperCase(Locale.ROOT) + toggle.module.substring(1)
				+ ": " + (toggle.enabled ? "on" : "off");
	}

	private void toggleModule(ModuleToggle toggle) {
		toggle.enabled = !toggle.enabled;
		JsonObject modules = new JsonObject();
		modules.addProperty(toggle.module, toggle.enabled);
		JsonObject payload = new JsonObject();
		payload.add("modules", modules);
		runtime.dispatchLocal(MessageType.MODULES_SET, payload);
		toggle.button.setMessage(Component.literal(label(toggle)));
		refresh();
	}

	private void toggleRunning() {
		MessageType type = runtime.agent().isRunning() ? MessageType.STOP : MessageType.START;
		JsonObject payload = new JsonObject();
		payload.addProperty("reason", "in-game screen");
		runtime.dispatchLocal(type, payload);
		refresh();
	}

	private void refresh() {
		detailLines.clear();
		recentErrors.clear();

		EnvelopeResult status = request(MessageType.STATUS_GET);
		if (!status.ok()) {
			statusText = "status unavailable: " + status.error;
			statusOk = false;
		} else {
			JsonObject data = status.data;
			String state = stringOr(data, "state", "UNKNOWN");
			boolean running = booleanOr(data, "running", false);
			int peers = intOr(data, "peers", 0);
			statusText = state + (running ? " (automation on)" : " (automation off)");
			statusOk = running && !"ERROR".equals(state);

			JsonObject queue = objectOr(data, "queue");
			detailLines.add("Control API: " + (runtime.isServerRunning()
					? "listening on port " + runtime.server().port()
					: "stopped" + (runtime.server().lastError() == null
							? ""
							: " (" + runtime.server().lastError() + ")")));
			detailLines.add("Discord peers: " + peers + (peers == 0 ? " (offline)" : ""));
			detailLines.add("Queue: " + intOr(queue, "active", 0) + " active, "
					+ intOr(queue, "pending", 0) + " pending, "
					+ intOr(queue, "finished", 0) + " finished");
			detailLines.add("Active task: " + describeActiveTask(data));
			detailLines.add("Last action: " + stringOr(data, "lastAction", "none"));

			JsonObject world = objectOr(data, "world");
			detailLines.add("World: " + stringOr(world, "summary", "unknown"));
			detailLines.add("Log level: " + stringOr(data, "logLevel", "INFO")
					+ ", dropped entries: " + intOr(data, "dropped", 0));

			JsonArray errors = arrayOr(data, "recentErrors");
			for (JsonElement element : errors) {
				if (element.isJsonObject()) {
					JsonObject error = element.getAsJsonObject();
					recentErrors.add(new DiagnosticsLog.ErrorEntry(
							intOr(error, "timestamp", 0),
							stringOr(error, "category", "general"),
							stringOr(error, "message", "unspecified failure"),
							stringOr(error, "cause", null)));
				}
			}
		}

		AgentConfig config = runtime.config().config();
		for (ModuleToggle toggle : toggles) {
			toggle.enabled = config.modules().isEnabled(toggle.module);
			if (toggle.button != null) {
				toggle.button.setMessage(Component.literal(label(toggle)));
			}
		}
		if (startStopButton != null) {
			startStopButton.setMessage(Component.literal(runtime.agent().isRunning() ? "Stop" : "Start"));
		}
		runtime.config().lastLoadError().ifPresent(error -> recentErrors.add(
				new DiagnosticsLog.ErrorEntry(System.currentTimeMillis(), "config", error, null)));
	}

	private String describeActiveTask(JsonObject status) {
		JsonElement element = status.get("activeTask");
		if (element == null || !element.isJsonObject()) {
			return "none";
		}
		JsonObject task = element.getAsJsonObject();
		return stringOr(task, "id", "?") + " " + stringOr(task, "kind", "?")
				+ " [" + stringOr(task, "status", "?") + "]";
	}

	private EnvelopeResult request(MessageType type) {
		try {
			var reply = runtime.dispatchLocal(type, new JsonObject());
			if (reply.type() == MessageType.RESPONSE_OK) {
				return new EnvelopeResult(true, reply.data(), null);
			}
			String message = reply.data() == null ? "unknown error"
					: stringOr(reply.data(), "message", "unknown error");
			return new EnvelopeResult(false, reply.data(), message);
		} catch (RuntimeException e) {
			// A failure to gather status must not close the screen.
			return new EnvelopeResult(false, new JsonObject(), e.getClass().getSimpleName());
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		int panelWidth = Math.min(width - 2 * MARGIN, 420);
		int panelHeight = Math.min(height - 2 * MARGIN, 200);
		int panelX = (width - panelWidth) / 2;
		int panelY = MARGIN;
		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, COLOR_PANEL);

		int x = panelX + 8;
		int y = panelY + 8;
		graphics.drawString(font, "UnionKitBot Fabric", x, y, COLOR_TITLE);
		y += ROW_HEIGHT + 2;
		graphics.drawString(font, "State: " + statusText, x, y, statusOk ? COLOR_OK : COLOR_WARN);
		y += ROW_HEIGHT + 2;
		for (String line : detailLines) {
			if (y > panelY + panelHeight - ROW_HEIGHT * 4) {
				break;
			}
			int color = line.startsWith("Control API:") && !runtime.isServerRunning() ? COLOR_WARN : COLOR_VALUE;
			graphics.drawString(font, line, x, y, color);
			y += ROW_HEIGHT;
		}
		y += 4;
		graphics.drawString(font, "Recent errors", x, y, COLOR_LABEL);
		y += ROW_HEIGHT;
		if (recentErrors.isEmpty()) {
			graphics.drawString(font, "none", x, y, COLOR_OK);
		} else {
			int shown = 0;
			for (int i = recentErrors.size() - 1; i >= 0 && shown < 3; i--, shown++) {
				DiagnosticsLog.ErrorEntry error = recentErrors.get(i);
				graphics.drawString(font, "- [" + error.category() + "] " + trim(error.message(), 60), x, y, COLOR_BAD);
				y += ROW_HEIGHT;
			}
		}
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private static String trim(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static String stringOr(JsonObject object, String member, String fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		return element.getAsString();
	}

	private static int intOr(JsonObject object, String member, int fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
				|| !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		return element.getAsInt();
	}

	private static boolean booleanOr(JsonObject object, String member, boolean fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
				|| !element.getAsJsonPrimitive().isBoolean()) {
			return fallback;
		}
		return element.getAsBoolean();
	}

	private static JsonObject objectOr(JsonObject object, String member) {
		if (object == null) {
			return new JsonObject();
		}
		JsonElement element = object.get(member);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	private static JsonArray arrayOr(JsonObject object, String member) {
		if (object == null) {
			return new JsonArray();
		}
		JsonElement element = object.get(member);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
	}

	/**
	 * @return a snapshot of the screen's current view, used by tests
	 */
	public Map<String, Object> view() {
		return Map.of(
				"status", statusText,
				"ok", statusOk,
				"details", List.copyOf(detailLines),
				"errors", recentErrors.size());
	}

	private record EnvelopeResult(boolean ok, JsonObject data, String error) {
	}

	private static final class ModuleToggle {
		private final String module;
		private boolean enabled;
		private Button button;

		private ModuleToggle(String module) {
			this.module = module;
		}
	}

	/**
	 * @param task the task to describe
	 * @return a one line description, used by tests
	 */
	public static String describe(AgentTask.Snapshot task) {
		return task == null ? "none" : task.id() + " " + task.kind() + " [" + task.status() + "]";
	}

	/**
	 * @param state the state to describe
	 * @return whether the state should be drawn as healthy
	 */
	public static boolean isHealthy(BotState state) {
		return state != BotState.ERROR && state != BotState.DISCONNECTED;
	}
}
