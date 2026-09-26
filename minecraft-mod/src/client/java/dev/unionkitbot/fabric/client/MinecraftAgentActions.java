package dev.unionkitbot.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.module.AgentActions;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;

/**
 * {@link AgentActions} backed by the live Minecraft client.
 *
 * <p>This is the only place in the mod that mutates client state, and every method
 * re-reads the client, world, player and options before touching anything. The
 * fields it needs can all disappear between ticks: the client can be torn down, the
 * world unloaded, the player removed. Each method therefore either performs the
 * action or returns a {@link ActionResult} explaining why it could not, and never
 * throws.
 *
 * <p>Movement is driven through the vanilla {@link KeyMapping} instances in
 * {@link net.minecraft.client.Options} rather than by writing input state directly.
 * That keeps the agent inside the same input path the player uses, so it cannot
 * produce input a player could not.
 */
public final class MinecraftAgentActions implements AgentActions {
	private static final String CATEGORY = "actions";

	private final DiagnosticsLog log;

	/**
	 * @param log the diagnostics log
	 */
	public MinecraftAgentActions(DiagnosticsLog log) {
		this.log = log;
	}

	private Optional<KeyMapping> mapping(MovementInput input) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return Optional.empty();
		}
		KeyMapping key = switch (input) {
			case FORWARD -> client.options.keyUp;
			case BACK -> client.options.keyDown;
			case LEFT -> client.options.keyLeft;
			case RIGHT -> client.options.keyRight;
			case JUMP -> client.options.keyJump;
			case SNEAK -> client.options.keyShift;
			case SPRINT -> client.options.keySprint;
		};
		return Optional.ofNullable(key);
	}

	private Optional<LocalPlayer> player() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null) {
			return Optional.empty();
		}
		return Optional.of(client.player);
	}

	@Override
	public ActionResult press(MovementInput input) {
		if (input == null) {
			return ActionResult.fatal("no movement input supplied");
		}
		if (player().isEmpty()) {
			return ActionResult.notActionable();
		}
		Optional<KeyMapping> key = mapping(input);
		if (key.isEmpty()) {
			return ActionResult.notActionable();
		}
		key.get().setDown(true);
		return ActionResult.ok("pressing " + input.name().toLowerCase(Locale.ROOT));
	}

	@Override
	public ActionResult release(MovementInput input) {
		if (input == null) {
			return ActionResult.fatal("no movement input supplied");
		}
		Optional<KeyMapping> key = mapping(input);
		if (key.isEmpty()) {
			// Releasing is best effort: if the options are gone the whole client is
			// going away, and the input state goes with it.
			return ActionResult.idle("client options unavailable; nothing to release");
		}
		key.get().setDown(false);
		return ActionResult.ok("released " + input.name().toLowerCase(Locale.ROOT));
	}

	@Override
	public ActionResult releaseAll() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return ActionResult.idle("client options unavailable; nothing to release");
		}
		for (MovementInput input : MovementInput.values()) {
			mapping(input).ifPresent(key -> key.setDown(false));
		}
		return ActionResult.ok("all movement inputs released");
	}

	@Override
	public ActionResult lookAt(double x, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(z)) {
			return ActionResult.fatal("target coordinates are not finite");
		}
		Optional<LocalPlayer> player = player();
		if (player.isEmpty()) {
			return ActionResult.notActionable();
		}
		LocalPlayer local = player.get();
		double dx = x - local.getX();
		double dz = z - local.getZ();
		if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
			return ActionResult.idle("already facing the target");
		}
		local.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
		return ActionResult.ok("facing the target");
	}

	@Override
	public ActionResult swingMainHand() {
		Optional<LocalPlayer> player = player();
		if (player.isEmpty()) {
			return ActionResult.notActionable();
		}
		player.get().swing(InteractionHand.MAIN_HAND);
		return ActionResult.ok("swung the main hand");
	}

	@Override
	public ActionResult dropSelected(boolean all) {
		Optional<LocalPlayer> player = player();
		if (player.isEmpty()) {
			return ActionResult.notActionable();
		}
		LocalPlayer local = player.get();
		if (!local.canDropItems()) {
			return ActionResult.retryable("the player cannot drop items right now");
		}
		return local.drop(all)
				? ActionResult.ok(all ? "dropped the whole stack" : "dropped one item")
				: ActionResult.retryable("the selected slot is empty");
	}

	@Override
	public ActionResult selectSlot(int slot) {
		Optional<LocalPlayer> player = player();
		if (player.isEmpty()) {
			return ActionResult.notActionable();
		}
		if (slot < 0 || slot >= 9) {
			return ActionResult.fatal("hotbar slot " + slot + " is out of range");
		}
		Inventory inventory = player.get().getInventory();
		inventory.setSelectedSlot(slot);
		return ActionResult.ok("selected hotbar slot " + slot);
	}

	@Override
	public ActionResult notifyLocal(String message) {
		if (message == null || message.isBlank()) {
			return ActionResult.idle("nothing to say");
		}
		Optional<LocalPlayer> player = player();
		if (player.isEmpty()) {
			return ActionResult.idle("no player to notify");
		}
		player.get().displayClientMessage(Component.literal("[UnionKitBot] " + message), false);
		return ActionResult.ok("notified the local player");
	}

	/**
	 * @return the movement inputs currently held down, for diagnostics
	 */
	public List<String> heldInputs() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return List.of();
		}
		List<String> held = new ArrayList<>();
		for (MovementInput input : MovementInput.values()) {
			if (mapping(input).map(KeyMapping::isDown).orElse(false)) {
				held.add(input.name().toLowerCase(Locale.ROOT));
			}
		}
		return List.copyOf(held);
	}

	/**
	 * @return the number of movement inputs currently held down by the agent
	 */
	public int heldInputCount() {
		return heldInputs().size();
	}

	/**
	 * Records a diagnostic line describing an unexpected state.
	 *
	 * @param detail the description
	 */
	public void note(String detail) {
		log.debug(CATEGORY, detail);
	}
}
