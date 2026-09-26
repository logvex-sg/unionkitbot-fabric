package dev.unionkitbot.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.unionkitbot.fabric.core.task.TaskParameters.Position;
import dev.unionkitbot.fabric.core.world.Observation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Builds an {@link Observation} from the live Minecraft client.
 *
 * <p>This is the OBSERVE step's only contact with the game. Everything is read
 * defensively: the client, world, player, network handler and entity list can each be
 * absent, and any of them can vanish between two field reads. Every value is copied
 * into the immutable snapshot before it is returned, so nothing in the agent can hold
 * a reference to a client object that may be invalidated on the next tick.
 */
public final class MinecraftWorldObserver {
	/** Distance beyond which other players are not included in the snapshot. */
	public static final double NEARBY_PLAYER_RADIUS = 64.0d;

	private long tick;

	/**
	 * @return a snapshot of the current client state, never {@code null}
	 */
	public Observation observe() {
		tick++;
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return Observation.offline(tick);
		}
		boolean hasNetworkHandler = client.getConnection() != null;
		ClientLevel level = client.level;
		boolean hasWorld = level != null;
		LocalPlayer player = client.player;
		boolean hasPlayer = player != null;
		boolean playerAlive = hasPlayer && player.isAlive();
		boolean screenOpen = client.screen != null;
		boolean integratedServer = client.hasSingleplayerServer();

		Optional<String> dimensionId = Optional.empty();
		long worldTime = -1L;
		if (hasWorld) {
			Identifier dimension = level.dimension().identifier();
			if (dimension != null) {
				dimensionId = Optional.of(dimension.toString());
			}
			worldTime = level.getDayTime();
		}

		Optional<Position> position = Optional.empty();
		float health = 0.0f;
		int foodLevel = 0;
		int selectedSlot = 0;
		Optional<String> selectedItemId = Optional.empty();
		if (hasPlayer) {
			position = Optional.of(new Position(player.getX(), player.getY(), player.getZ()));
			health = player.getHealth();
			foodLevel = player.getFoodData().getFoodLevel();
			selectedSlot = player.getInventory().getSelectedSlot();
			selectedItemId = describeHeldItem(player.getInventory().getSelectedItem());
		}

		List<Observation.NearbyPlayer> nearby = hasWorld && hasPlayer
				? collectNearbyPlayers(level, player)
				: List.of();

		return new Observation(tick, true, integratedServer, hasNetworkHandler, hasWorld, hasPlayer,
				playerAlive, screenOpen, dimensionId, position, health, foodLevel, selectedSlot,
				selectedItemId, worldTime, nearby);
	}

	private static Optional<String> describeHeldItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return Optional.empty();
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id == null ? Optional.empty() : Optional.of(id.toString());
	}

	private static List<Observation.NearbyPlayer> collectNearbyPlayers(ClientLevel level, LocalPlayer self) {
		List<AbstractClientPlayer> players;
		try {
			players = level.players();
		} catch (RuntimeException e) {
			// The entity list is mutated by the network thread; a snapshot taken while it
			// is being updated can fail. Skipping this frame is better than crashing.
			return List.of();
		}
		if (players == null || players.isEmpty()) {
			return List.of();
		}
		List<Observation.NearbyPlayer> out = new ArrayList<>(
				Math.min(players.size(), Observation.MAX_NEARBY_PLAYERS));
		for (AbstractClientPlayer other : players) {
			if (out.size() >= Observation.MAX_NEARBY_PLAYERS) {
				break;
			}
			if (other == null || other == self) {
				continue;
			}
			double distance;
			try {
				distance = self.distanceTo(other);
			} catch (RuntimeException e) {
				continue;
			}
			if (!Double.isFinite(distance) || distance > NEARBY_PLAYER_RADIUS) {
				continue;
			}
			UUID uuid = other.getUUID();
			String name = other.getName() == null ? "unknown" : other.getName().getString();
			out.add(new Observation.NearbyPlayer(uuid, name,
					new Position(other.getX(), other.getY(), other.getZ()), distance));
		}
		return List.copyOf(out);
	}
}
