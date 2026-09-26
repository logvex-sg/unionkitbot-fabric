package dev.unionkitbot.fabric.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.core.task.TaskParameters;
import dev.unionkitbot.fabric.core.util.Json;
import dev.unionkitbot.fabric.diag.LogLevel;

/**
 * Immutable agent configuration.
 *
 * <p>Configuration lives in {@code config/unionkitbot.json} inside the game
 * directory and can be edited at runtime through the control API or the in-game
 * screen. Secrets are never serialised: {@link #toJson()} emits
 * {@link #REDACTED} in place of the API secret, and the secret is only available
 * through {@link ApiConfig#secret()}.
 */
public record AgentConfig(
		boolean enabled,
		LogLevel logLevel,
		ApiConfig api,
		ModuleToggles modules,
		LimitsConfig limits,
		NavigationConfig navigation,
		DeliveryConfig delivery,
		ScanConfig scan,
		RecoveryConfig recovery) {

	/** Replacement text used whenever a secret would otherwise be serialised. */
	public static final String REDACTED = "<redacted>";

	/** Shortest accepted API secret. */
	public static final int MIN_SECRET_LENGTH = 16;

	/**
	 * Control API settings.
	 *
	 * @param host bind address for the local API
	 * @param port bind port for the local API
	 * @param secret pre-shared secret required from the Discord bot
	 * @param allowRemote when {@code false} only loopback clients are accepted
	 * @param heartbeatSeconds interval between status frames
	 * @param maxPayloadBytes maximum accepted request body size
	 * @param allowedOrigins origins accepted by the WebSocket handshake
	 */
	public record ApiConfig(
			String host,
			int port,
			String secret,
			boolean allowRemote,
			int heartbeatSeconds,
			int maxPayloadBytes,
			List<String> allowedOrigins) {

		public ApiConfig {
			host = (host == null || host.isBlank()) ? "127.0.0.1" : host.trim();
			port = clamp(port, 1, 65535);
			secret = secret == null ? "" : secret;
			heartbeatSeconds = clamp(heartbeatSeconds, 1, 600);
			maxPayloadBytes = clamp(maxPayloadBytes, 1024, 8 * 1024 * 1024);
			allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
		}

		/**
		 * @return {@code true} when a usable secret is configured
		 */
		public boolean hasSecret() {
			return secret.length() >= MIN_SECRET_LENGTH;
		}

		/**
		 * @return the effective heartbeat interval in milliseconds
		 */
		public long heartbeatMillis() {
			return heartbeatSeconds * 1000L;
		}

		/**
		 * @return a copy with a new secret
		 * @throws Json.ValidationFailure when the secret is too short
		 */
		public ApiConfig withSecret(String newSecret) {
			requireSecret(newSecret);
			return new ApiConfig(host, port, newSecret, allowRemote, heartbeatSeconds, maxPayloadBytes, allowedOrigins);
		}

		/**
		 * Validates a candidate secret.
		 *
		 * @param candidate the secret to check
		 * @throws Json.ValidationFailure when the secret is unusable
		 */
		public static void requireSecret(String candidate) {
			if (candidate == null || candidate.trim().length() < MIN_SECRET_LENGTH) {
				throw new Json.ValidationFailure("api.secret",
						"secret must be at least " + MIN_SECRET_LENGTH + " characters");
			}
		}

		/**
		 * @return JSON without the secret
		 */
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("host", host);
			out.addProperty("port", port);
			out.addProperty("secret", REDACTED);
			out.addProperty("secretConfigured", hasSecret());
			out.addProperty("allowRemote", allowRemote);
			out.addProperty("heartbeatSeconds", heartbeatSeconds);
			out.addProperty("maxPayloadBytes", maxPayloadBytes);
			JsonArray origins = new JsonArray();
			allowedOrigins.forEach(origins::add);
			out.add("allowedOrigins", origins);
			return out;
		}
	}

	/**
	 * Per-module enable flags.
	 *
	 * @param navigation enables the navigation module
	 * @param delivery enables the delivery module
	 * @param scanning enables the scanning module
	 * @param recovery enables the recovery module
	 */
	public record ModuleToggles(boolean navigation, boolean delivery, boolean scanning, boolean recovery) {
		/**
		 * @return JSON representation
		 */
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("navigation", navigation);
			out.addProperty("delivery", delivery);
			out.addProperty("scanning", scanning);
			out.addProperty("recovery", recovery);
			return out;
		}

		/**
		 * @param module the module name
		 * @return whether the module is enabled
		 */
		public boolean isEnabled(String module) {
			if (module == null) {
				return false;
			}
			return switch (module.toLowerCase(Locale.ROOT)) {
				case "navigation" -> navigation;
				case "delivery" -> delivery;
				case "scanning" -> scanning;
				case "recovery" -> recovery;
				default -> false;
			};
		}

		/**
		 * @param module the module name
		 * @param value the desired state
		 * @return a copy with the module flag updated, or this instance when unknown
		 */
		public ModuleToggles with(String module, boolean value) {
			if (module == null) {
				return this;
			}
			return switch (module.toLowerCase(Locale.ROOT)) {
				case "navigation" -> new ModuleToggles(value, delivery, scanning, recovery);
				case "delivery" -> new ModuleToggles(navigation, value, scanning, recovery);
				case "scanning" -> new ModuleToggles(navigation, delivery, value, recovery);
				case "recovery" -> new ModuleToggles(navigation, delivery, scanning, value);
				default -> this;
			};
		}

		/**
		 * @return the module names recognised by {@link #with(String, boolean)}
		 */
		public static List<String> names() {
			return List.of("navigation", "delivery", "scanning", "recovery");
		}
	}

	/**
	 * Global execution limits.
	 *
	 * @param tickIntervalMs minimum delay between agent ticks
	 * @param maxAttempts attempts allowed before a task fails permanently
	 * @param taskTimeoutMs wall clock budget for a single task attempt
	 * @param stuckTicks consecutive ticks without positional progress before recovery
	 * @param maxQueueSize maximum number of queued tasks
	 */
	public record LimitsConfig(
			long tickIntervalMs,
			int maxAttempts,
			long taskTimeoutMs,
			int stuckTicks,
			int maxQueueSize) {

		public LimitsConfig {
			tickIntervalMs = clamp(tickIntervalMs, 20L, 10_000L);
			maxAttempts = clamp(maxAttempts, 1, 20);
			taskTimeoutMs = clamp(taskTimeoutMs, 1_000L, 3_600_000L);
			stuckTicks = clamp(stuckTicks, 10, 20_000);
			maxQueueSize = clamp(maxQueueSize, 1, 4096);
		}

		/**
		 * @return JSON representation
		 */
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("tickIntervalMs", tickIntervalMs);
			out.addProperty("maxAttempts", maxAttempts);
			out.addProperty("taskTimeoutMs", taskTimeoutMs);
			out.addProperty("stuckTicks", stuckTicks);
			out.addProperty("maxQueueSize", maxQueueSize);
			return out;
		}
	}

	/**
	 * Navigation module settings.
	 *
	 * @param arriveRadius distance at which a destination counts as reached
	 * @param stepTicks ticks between movement input updates
	 * @param homeX recorded home x coordinate, or {@code null}
	 * @param homeY recorded home y coordinate, or {@code null}
	 * @param homeZ recorded home z coordinate, or {@code null}
	 */
	public record NavigationConfig(double arriveRadius, int stepTicks, Double homeX, Double homeY, Double homeZ) {

		public NavigationConfig {
			arriveRadius = clamp(arriveRadius, 0.5d, 32.0d);
			stepTicks = clamp(stepTicks, 1, 100);
		}

		/**
		 * @return the configured home position, when all components are present
		 */
		public Optional<TaskParameters.Position> home() {
			if (homeX == null || homeY == null || homeZ == null) {
				return Optional.empty();
			}
			if (!Double.isFinite(homeX) || !Double.isFinite(homeY) || !Double.isFinite(homeZ)) {
				return Optional.empty();
			}
			return Optional.of(new TaskParameters.Position(homeX, homeY, homeZ));
		}

		/**
		 * @param position the position to record
		 * @return a copy with the home position set
		 */
		public NavigationConfig withHome(TaskParameters.Position position) {
			return new NavigationConfig(arriveRadius, stepTicks, position.x(), position.y(), position.z());
		}

		/**
		 * @return JSON representation
		 */
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("arriveRadius", arriveRadius);
			out.addProperty("stepTicks", stepTicks);
			JsonObject home = new JsonObject();
			home.addProperty("x", homeX);
			home.addProperty("y", homeY);
			home.addProperty("z", homeZ);
			out.add("home", home);
			return out;
		}
	}

	/**
	 * Delivery module settings.
	 *
	 * @param interactRadius distance at which a hand-off is attempted
	 * @param itemWhitelist item identifiers allowed to be delivered, empty means any
	 * @param defaultTargetPlayer default recipient when a task omits one
	 */
	public record DeliveryConfig(double interactRadius, List<String> itemWhitelist, String defaultTargetPlayer) {

		public DeliveryConfig {
			interactRadius = clamp(interactRadius, 1.0d, 16.0d);
			itemWhitelist = itemWhitelist == null
					? List.of()
					: List.copyOf(itemWhitelist.stream().filter(s -> s != null && !s.isBlank()).toList());
			defaultTargetPlayer = defaultTargetPlayer == null ? "" : defaultTargetPlayer.trim();
		}

		/**
		 * @return JSON representation
		 */
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("interactRadius", interactRadius);
			out.addProperty("defaultTargetPlayer", defaultTargetPlayer);
			JsonArray items = new JsonArray();
			itemWhitelist.forEach(items::add);
			out.add("itemWhitelist", items);
			return out;
		}
	}

	/**
	 * Scanning module settings.
	 *
	 * @param radius horizontal search radius in blocks
	 * @param verticalRadius vertical search radius in blocks
	 * @param maxResults maximum entries returned by one scan
	 */
	public record ScanConfig(int radius, int verticalRadius, int maxResults) {

		public ScanConfig {
			radius = clamp(radius, 1, 128);
			verticalRadius = clamp(verticalRadius, 1, 64);
			maxResults = clamp(maxResults, 1, 4096);
		}

		/**
		 * @return JSON representation
		 */
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("radius", radius);
			out.addProperty("verticalRadius", verticalRadius);
			out.addProperty("maxResults", maxResults);
			return out;
		}
	}

	/**
	 * Recovery module settings.
	 *
	 * @param backoffMillis base delay between recovery attempts
	 * @param maxBackoffMillis ceiling for the exponential backoff
	 * @param disconnectGraceMillis delay before leaving the DISCONNECTED state
	 */
	public record RecoveryConfig(long backoffMillis, long maxBackoffMillis, long disconnectGraceMillis) {

		public RecoveryConfig {
			backoffMillis = clamp(backoffMillis, 100L, 120_000L);
			maxBackoffMillis = clamp(maxBackoffMillis, backoffMillis, 600_000L);
			disconnectGraceMillis = clamp(disconnectGraceMillis, 0L, 600_000L);
		}

		/**
		 * Computes the backoff delay for an attempt count.
		 *
		 * @param attempt the 1-based attempt number
		 * @return the delay in milliseconds, capped at {@link #maxBackoffMillis()}
		 */
		public long backoffFor(int attempt) {
			if (attempt <= 1) {
				return backoffMillis;
			}
			int shift = Math.min(attempt - 1, 16);
			long delay = backoffMillis << shift;
			if (delay <= 0 || delay > maxBackoffMillis) {
				return maxBackoffMillis;
			}
			return delay;
		}

		/**
		 * @return JSON representation
		 */
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("backoffMillis", backoffMillis);
			out.addProperty("maxBackoffMillis", maxBackoffMillis);
			out.addProperty("disconnectGraceMillis", disconnectGraceMillis);
			return out;
		}
	}

	/**
	 * @return the default configuration used on first launch
	 */
	public static AgentConfig defaults() {
		return new AgentConfig(
				false,
				LogLevel.INFO,
				new ApiConfig("127.0.0.1", 8765, "", false, 10, 262_144, List.of()),
				new ModuleToggles(true, true, true, true),
				new LimitsConfig(100L, 3, 120_000L, 100, 128),
				new NavigationConfig(1.5d, 2, null, null, null),
				new DeliveryConfig(3.0d, List.of(), ""),
				new ScanConfig(24, 8, 256),
				new RecoveryConfig(1_000L, 30_000L, 5_000L));
	}

	/**
	 * @return JSON representation with every secret redacted
	 */
	public JsonObject toJson() {
		JsonObject out = new JsonObject();
		out.addProperty("enabled", enabled);
		out.addProperty("logLevel", logLevel.name());
		out.add("api", api.toJson());
		out.add("modules", modules.toJson());
		out.add("limits", limits.toJson());
		out.add("navigation", navigation.toJson());
		out.add("delivery", delivery.toJson());
		out.add("scan", scan.toJson());
		out.add("recovery", recovery.toJson());
		return out;
	}

	/**
	 * Applies a partial update. Unknown keys are rejected so typos surface instead
	 * of being silently ignored.
	 *
	 * @param patch the partial configuration
	 * @return a new configuration with the patch applied
	 * @throws Json.ValidationFailure when a value is invalid or a key is unknown
	 */
	public AgentConfig patched(JsonObject patch) {
		if (patch == null || patch.isEmpty()) {
			return this;
		}
		boolean newEnabled = enabled;
		LogLevel newLevel = logLevel;
		ApiConfig newApi = api;
		ModuleToggles newModules = modules;
		LimitsConfig newLimits = limits;
		NavigationConfig newNavigation = navigation;
		DeliveryConfig newDelivery = delivery;
		ScanConfig newScan = scan;
		RecoveryConfig newRecovery = recovery;

		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			String key = entry.getKey();
			JsonElement value = entry.getValue();
			switch (key) {
				case "enabled" -> newEnabled = requireBoolean(key, value);
				case "logLevel" -> {
					String raw = requireStringValue(key, value);
					LogLevel parsed = LogLevel.parse(raw, null);
					if (parsed == null) {
						throw new Json.ValidationFailure(key, "unknown log level '" + raw + "'");
					}
					newLevel = parsed;
				}
				case "api" -> newApi = patchApi(newApi, requireObject(key, value));
				case "modules" -> newModules = patchModules(newModules, requireObject(key, value));
				case "limits" -> newLimits = patchLimits(newLimits, requireObject(key, value));
				case "navigation" -> newNavigation = patchNavigation(newNavigation, requireObject(key, value));
				case "delivery" -> newDelivery = patchDelivery(newDelivery, requireObject(key, value));
				case "scan" -> newScan = patchScan(newScan, requireObject(key, value));
				case "recovery" -> newRecovery = patchRecovery(newRecovery, requireObject(key, value));
				default -> throw new Json.ValidationFailure(key, "unknown configuration key");
			}
		}
		return new AgentConfig(newEnabled, newLevel, newApi, newModules, newLimits, newNavigation, newDelivery,
				newScan, newRecovery);
	}

	private static ApiConfig patchApi(ApiConfig current, JsonObject patch) {
		String host = current.host();
		int port = current.port();
		String secret = current.secret();
		boolean allowRemote = current.allowRemote();
		int heartbeat = current.heartbeatSeconds();
		int maxPayload = current.maxPayloadBytes();
		List<String> origins = current.allowedOrigins();
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			switch (entry.getKey()) {
				case "host" -> host = requireStringValue("api.host", entry.getValue());
				case "port" -> port = (int) requireInteger("api.port", entry.getValue());
				case "secret" -> {
					secret = requireStringValue("api.secret", entry.getValue());
					ApiConfig.requireSecret(secret);
				}
				case "allowRemote" -> allowRemote = requireBoolean("api.allowRemote", entry.getValue());
				case "heartbeatSeconds" -> heartbeat = (int) requireInteger("api.heartbeatSeconds", entry.getValue());
				case "maxPayloadBytes" -> maxPayload = (int) requireInteger("api.maxPayloadBytes", entry.getValue());
				case "allowedOrigins" -> origins = requireStringList("api.allowedOrigins", entry.getValue());
				default -> throw new Json.ValidationFailure("api." + entry.getKey(), "unknown configuration key");
			}
		}
		return new ApiConfig(host, port, secret, allowRemote, heartbeat, maxPayload, origins);
	}

	private static ModuleToggles patchModules(ModuleToggles current, JsonObject patch) {
		ModuleToggles result = current;
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			String module = entry.getKey();
			if (!ModuleToggles.names().contains(module.toLowerCase(Locale.ROOT))) {
				throw new Json.ValidationFailure("modules." + module, "unknown module");
			}
			result = result.with(module, requireBoolean("modules." + module, entry.getValue()));
		}
		return result;
	}

	private static LimitsConfig patchLimits(LimitsConfig current, JsonObject patch) {
		long tick = current.tickIntervalMs();
		int attempts = current.maxAttempts();
		long timeout = current.taskTimeoutMs();
		int stuck = current.stuckTicks();
		int queue = current.maxQueueSize();
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			switch (entry.getKey()) {
				case "tickIntervalMs" -> tick = requireInteger("limits.tickIntervalMs", entry.getValue());
				case "maxAttempts" -> attempts = (int) requireInteger("limits.maxAttempts", entry.getValue());
				case "taskTimeoutMs" -> timeout = requireInteger("limits.taskTimeoutMs", entry.getValue());
				case "stuckTicks" -> stuck = (int) requireInteger("limits.stuckTicks", entry.getValue());
				case "maxQueueSize" -> queue = (int) requireInteger("limits.maxQueueSize", entry.getValue());
				default -> throw new Json.ValidationFailure("limits." + entry.getKey(), "unknown configuration key");
			}
		}
		return new LimitsConfig(tick, attempts, timeout, stuck, queue);
	}

	private static NavigationConfig patchNavigation(NavigationConfig current, JsonObject patch) {
		double radius = current.arriveRadius();
		int step = current.stepTicks();
		Double hx = current.homeX();
		Double hy = current.homeY();
		Double hz = current.homeZ();
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			switch (entry.getKey()) {
				case "arriveRadius" -> radius = requireNumber("navigation.arriveRadius", entry.getValue());
				case "stepTicks" -> step = (int) requireInteger("navigation.stepTicks", entry.getValue());
				case "home" -> {
					JsonObject home = requireObject("navigation.home", entry.getValue());
					if (home.isEmpty()) {
						hx = null;
						hy = null;
						hz = null;
					} else {
						hx = Json.requireDouble(home, "x");
						hy = Json.requireDouble(home, "y");
						hz = Json.requireDouble(home, "z");
					}
				}
				default -> throw new Json.ValidationFailure("navigation." + entry.getKey(), "unknown configuration key");
			}
		}
		return new NavigationConfig(radius, step, hx, hy, hz);
	}

	private static DeliveryConfig patchDelivery(DeliveryConfig current, JsonObject patch) {
		double radius = current.interactRadius();
		List<String> items = current.itemWhitelist();
		String target = current.defaultTargetPlayer();
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			switch (entry.getKey()) {
				case "interactRadius" -> radius = requireNumber("delivery.interactRadius", entry.getValue());
				case "itemWhitelist" -> items = requireStringList("delivery.itemWhitelist", entry.getValue());
				case "defaultTargetPlayer" -> target = requireStringValue("delivery.defaultTargetPlayer", entry.getValue());
				default -> throw new Json.ValidationFailure("delivery." + entry.getKey(), "unknown configuration key");
			}
		}
		return new DeliveryConfig(radius, items, target);
	}

	private static ScanConfig patchScan(ScanConfig current, JsonObject patch) {
		int radius = current.radius();
		int vertical = current.verticalRadius();
		int max = current.maxResults();
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			switch (entry.getKey()) {
				case "radius" -> radius = (int) requireInteger("scan.radius", entry.getValue());
				case "verticalRadius" -> vertical = (int) requireInteger("scan.verticalRadius", entry.getValue());
				case "maxResults" -> max = (int) requireInteger("scan.maxResults", entry.getValue());
				default -> throw new Json.ValidationFailure("scan." + entry.getKey(), "unknown configuration key");
			}
		}
		return new ScanConfig(radius, vertical, max);
	}

	private static RecoveryConfig patchRecovery(RecoveryConfig current, JsonObject patch) {
		long backoff = current.backoffMillis();
		long max = current.maxBackoffMillis();
		long grace = current.disconnectGraceMillis();
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			switch (entry.getKey()) {
				case "backoffMillis" -> backoff = requireInteger("recovery.backoffMillis", entry.getValue());
				case "maxBackoffMillis" -> max = requireInteger("recovery.maxBackoffMillis", entry.getValue());
				case "disconnectGraceMillis" -> grace = requireInteger("recovery.disconnectGraceMillis", entry.getValue());
				default -> throw new Json.ValidationFailure("recovery." + entry.getKey(), "unknown configuration key");
			}
		}
		return new RecoveryConfig(backoff, max, grace);
	}

	private static JsonObject requireObject(String path, JsonElement value) {
		if (value == null || !value.isJsonObject()) {
			throw new Json.ValidationFailure(path, "expected an object");
		}
		return value.getAsJsonObject();
	}

	private static String requireStringValue(String path, JsonElement value) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new Json.ValidationFailure(path, "expected a string");
		}
		return value.getAsString();
	}

	private static boolean requireBoolean(String path, JsonElement value) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new Json.ValidationFailure(path, "expected a boolean");
		}
		return value.getAsBoolean();
	}

	private static long requireInteger(String path, JsonElement value) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new Json.ValidationFailure(path, "expected a number");
		}
		double raw = value.getAsDouble();
		if (!Double.isFinite(raw) || raw != Math.rint(raw)) {
			throw new Json.ValidationFailure(path, "expected an integer");
		}
		return (long) raw;
	}

	private static double requireNumber(String path, JsonElement value) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new Json.ValidationFailure(path, "expected a number");
		}
		double raw = value.getAsDouble();
		if (!Double.isFinite(raw)) {
			throw new Json.ValidationFailure(path, "expected a finite number");
		}
		return raw;
	}

	private static List<String> requireStringList(String path, JsonElement value) {
		if (value == null || !value.isJsonArray()) {
			throw new Json.ValidationFailure(path, "expected an array of strings");
		}
		List<String> out = new java.util.ArrayList<>();
		for (JsonElement element : value.getAsJsonArray()) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
				throw new Json.ValidationFailure(path, "expected an array of strings");
			}
			String raw = element.getAsString().trim();
			if (!raw.isEmpty()) {
				out.add(raw);
			}
		}
		return List.copyOf(out);
	}

	/**
	 * Parses a configuration from JSON, falling back to defaults for absent keys.
	 *
	 * @param object the parsed configuration document
	 * @return the configuration
	 * @throws Json.ValidationFailure when the document is invalid
	 */
	public static AgentConfig fromJson(JsonObject object) {
		return defaults().patched(object);
	}

	/**
	 * @return a redacted, ordered summary for diagnostics
	 */
	public Map<String, Object> describe() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("enabled", enabled);
		out.put("logLevel", logLevel.name());
		out.put("apiHost", api.host());
		out.put("apiPort", api.port());
		out.put("apiSecret", api.hasSecret() ? REDACTED : "<not configured>");
		out.put("allowRemote", api.allowRemote());
		out.put("modules", modules.toJson().toString());
		out.put("tickIntervalMs", limits.tickIntervalMs());
		out.put("maxQueueSize", limits.maxQueueSize());
		return out;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
