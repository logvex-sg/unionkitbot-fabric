package dev.unionkitbot.fabric.client.hud;

import java.util.ArrayList;
import java.util.List;

import dev.unionkitbot.fabric.core.state.BotState;
import dev.unionkitbot.fabric.runtime.BotRuntime;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A compact status overlay drawn in the corner of the screen.
 *
 * <p>Kept to four short lines and no textures so it costs almost nothing to render.
 * The overlay is hidden unless the agent is switched on or the control API is
 * listening, and it never draws when a screen is open, which keeps it out of the way
 * of menus and chat.
 */
public final class BotHud implements HudElement {
	private static final int LINE_HEIGHT = 10;
	private static final int MARGIN = 4;
	private static final int COLOR_BACKGROUND = 0x80000000;
	private static final int COLOR_LABEL = 0xAAAAAA;
	private static final int COLOR_OK = 0x55FF55;
	private static final int COLOR_WARN = 0xFFAA00;
	private static final int COLOR_BAD = 0xFF5555;

	private final BotRuntime runtime;

	/**
	 * @param runtime the runtime to display
	 */
	public BotHud(BotRuntime runtime) {
		this.runtime = runtime;
	}

	@Override
	public void render(GuiGraphics graphics, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.screen != null || client.options == null || client.options.hideGui) {
			return;
		}
		if (!runtime.agent().isRunning() && !runtime.isServerRunning()) {
			return;
		}
		List<String> lines = lines();
		if (lines.isEmpty()) {
			return;
		}
		int widest = 0;
		for (String line : lines) {
			widest = Math.max(widest, client.font.width(line));
		}
		int left = MARGIN;
		int top = MARGIN;
		graphics.fill(left - 2, top - 2, left + widest + 3, top + lines.size() * LINE_HEIGHT + 2,
				COLOR_BACKGROUND);
		int y = top;
		for (String line : lines) {
			graphics.drawString(client.font, line, left, y, colorFor(line));
			y += LINE_HEIGHT;
		}
	}

	/**
	 * @return the lines to draw, exposed for tests
	 */
	public List<String> lines() {
		List<String> lines = new ArrayList<>(4);
		BotState state = runtime.agent().state();
		lines.add("UnionKitBot: " + state.name());
		String connection = runtime.hasPeers()
				? "Discord: " + runtime.peerCount() + " peer(s)"
				: (runtime.isServerRunning() ? "Discord: not connected" : "Discord: API off");
		lines.add(connection);
		lines.add("Queue: " + runtime.queue().activeCount() + " active, "
				+ runtime.queue().pendingCount() + " pending");
		lines.add(trim(runtime.agent().lastActionDetail(), 48));
		return lines;
	}

	private static String trim(String value, int max) {
		if (value == null || value.isBlank()) {
			return "no recent action";
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
	}

	private int colorFor(String line) {
		if (line.startsWith("UnionKitBot")) {
			BotState state = runtime.agent().state();
			if (state == BotState.ERROR || state == BotState.DISCONNECTED || state == BotState.DEAD) {
				return COLOR_BAD;
			}
			return state.canRunTasks() ? COLOR_OK : COLOR_WARN;
		}
		if (line.startsWith("Discord")) {
			return runtime.hasPeers() ? COLOR_OK : COLOR_WARN;
		}
		return COLOR_LABEL;
	}
}
