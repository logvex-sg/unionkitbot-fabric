package dev.unionkitbot.fabric.core.world;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.unionkitbot.fabric.core.task.TaskParameters.Position;

/**
 * Everything the agent is allowed to know about the client and the world for one
 * tick.
 *
 * <p>This is the single source of truth for the OBSERVE phase. Nothing else in the
 * agent reads Minecraft state directly, which is what keeps the modules free of
 * null-prone client access and makes them unit testable. Every field is either a
 * primitive or an immutable value; there is no way to hold on to a client object
 * across ticks.
 *
 * @param tick the host tick counter, monotonically increasing
 * @param clientReady whether the Minecraft client exists and finished loading
 * @param integratedServer whether the client hosts a single player world
 * @param hasNetworkHandler whether a play network handler is currently installed
 * @param hasWorld whether a client world is loaded
 * @param hasPlayer whether a local player entity exists
 * @param playerAlive whether the local player is alive
 * @param screenOpen whether any screen is currently displayed
 * @param dimensionId the dimension identifier, or empty when unknown
 * @param position the local player position, or empty when unavailable
 * @param health the local player health, or {@code 0} when unavailable
 * @param foodLevel the local player food level
 * @param selectedSlot the hotbar slot index
 * @param selectedItemId the registry id of the held item, or empty
 * @param worldTime the world time in ticks, or {@code -1} when unavailable
 * @param nearbyPlayers other players in range
 */
public record Observation(
		long tick,
		boolean clientReady,
		boolean integratedServer,
		boolean hasNetworkHandler,
		boolean hasWorld,
		boolean hasPlayer,
		boolean playerAlive,
		boolean screenOpen,
		Optional<String> dimensionId,
		Optional<Position> position,
		float health,
		int foodLevel,
		int selectedSlot,
		Optional<String> selectedItemId,
		long worldTime,
		List<NearbyPlayer> nearbyPlayers) {

	/** Maximum number of nearby players retained in a snapshot. */
	public static final int MAX_NEARBY_PLAYERS = 64;

	public Observation {
		dimensionId = dimensionId == null ? Optional.empty() : dimensionId;
		position = position == null ? Optional.empty() : position;
		selectedItemId = selectedItemId == null ? Optional.empty() : selectedItemId;
		nearbyPlayers = nearbyPlayers == null
				? List.of()
				: List.copyOf(nearbyPlayers.subList(0, Math.min(nearbyPlayers.size(), MAX_NEARBY_PLAYERS)));
	}

	/**
	 * @return an observation describing a client that is not in a world
	 * @param tick the tick counter
	 */
	public static Observation offline(long tick) {
		return new Observation(tick, false, false, false, false, false, false, false, Optional.empty(),
				Optional.empty(), 0.0f, 0, 0, Optional.empty(), -1L, List.of());
	}

	/**
	 * A snapshot can only drive automation when the client, world, network handler
	 * and player are all present. Checking this once here means modules never have
	 * to repeat the null-safe dance.
	 *
	 * @return {@code true} when automation modules may act on this observation
	 */
	public boolean isActionable() {
		return clientReady && hasNetworkHandler && hasWorld && hasPlayer && playerAlive;
	}

	/**
	 * @return {@code true} when the client is connected to a server
	 */
	public boolean isConnected() {
		return hasNetworkHandler;
	}

	/**
	 * @return a one line summary used in logs and status frames
	 */
	public String summary() {
		if (!clientReady) {
			return "client not ready";
		}
		if (!hasNetworkHandler) {
			return "no network handler";
		}
		if (!hasWorld) {
			return "no world";
		}
		if (!hasPlayer) {
			return "no player";
		}
		if (!playerAlive) {
			return "player dead";
		}
		return position.map(p -> String.format("at %.1f/%.1f/%.1f in %s", p.x(), p.y(), p.z(),
				dimensionId.orElse("unknown dimension"))).orElse("player position unknown");
	}

	/**
	 * Finds a nearby player by name.
	 *
	 * @param name the player name, case insensitive
	 * @return the player when present in range
	 */
	public Optional<NearbyPlayer> playerByName(String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		return nearbyPlayers.stream().filter(p -> p.name().equalsIgnoreCase(name.trim())).findFirst();
	}

	/**
	 * Finds a nearby player by identifier.
	 *
	 * @param uuid the player uuid
	 * @return the player when present in range
	 */
	public Optional<NearbyPlayer> playerByUuid(UUID uuid) {
		if (uuid == null) {
			return Optional.empty();
		}
		return nearbyPlayers.stream().filter(p -> p.uuid().equals(uuid)).findFirst();
	}

	/**
	 * Another player visible to this client.
	 *
	 * @param uuid the player uuid
	 * @param name the player name
	 * @param position the player position
	 * @param distance distance from the local player in blocks
	 */
	public record NearbyPlayer(UUID uuid, String name, Position position, double distance) {
	}
}
