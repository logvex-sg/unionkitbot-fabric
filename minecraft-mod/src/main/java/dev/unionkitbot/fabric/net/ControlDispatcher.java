package dev.unionkitbot.fabric.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.config.ConfigManager;
import dev.unionkitbot.fabric.core.agent.BotAgent;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.TaskKind;
import dev.unionkitbot.fabric.core.task.TaskParameters;
import dev.unionkitbot.fabric.core.task.TaskPriority;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.diag.LogLevel;
import dev.unionkitbot.fabric.net.ws.Peer;
import dev.unionkitbot.fabric.protocol.Envelope;
import dev.unionkitbot.fabric.protocol.MessageType;
import dev.unionkitbot.fabric.protocol.Messages;
import dev.unionkitbot.fabric.protocol.ProtocolVersion;
import dev.unionkitbot.fabric.core.util.Json;

/**
 * Translates control-protocol requests into agent operations and publishes agent
 * events back to connected peers.
 *
 * <p>Every mutating command is marshalled onto the Minecraft client thread, because
 * the agent and the world it touches are not thread safe. Read-only commands that
 * only touch the agent's own concurrent structures run on the caller's thread. The
 * dispatcher never throws: a malformed request produces a structured
 * {@code response.error}, and an unexpected failure produces a structured
 * {@code error} event with a stable code.
 */
public final class ControlDispatcher {
	private static final String CATEGORY = "api";
	private static final long CLIENT_THREAD_TIMEOUT_MS = 2_000L;

	private final BotAgent agent;
	private final ConfigManager config;
	private final DiagnosticsLog log;
	private final PeerRegistry peers;
	private final MainThreadExecutor mainThread;
	private final Supplier<Map<String, Object>> worldStatus;

	/**
	 * @param agent the agent
	 * @param config the configuration manager
	 * @param log the diagnostics log
	 * @param peers the connected peers
	 * @param mainThread marshals work onto the client thread
	 * @param worldStatus supplies extra world information for status frames
	 */
	public ControlDispatcher(BotAgent agent, ConfigManager config, DiagnosticsLog log, PeerRegistry peers,
			MainThreadExecutor mainThread, Supplier<Map<String, Object>> worldStatus) {
		this.agent = agent;
		this.config = config;
		this.log = log;
		this.peers = peers;
		this.mainThread = mainThread;
		this.worldStatus = worldStatus;
	}

	/**
	 * Handles one request frame.
	 *
	 * @param peer the requesting peer
	 * @param request the request envelope
	 */
	public void handle(Peer peer, Envelope request) {
		String correlation = request.correlationId().orElse(null);
		try {
			JsonObject payload = dispatch(peer, request);
			peer.send(Envelope.reply(MessageType.RESPONSE_OK, correlation, payload));
		} catch (Json.JsonProblem e) {
			log.warn(CATEGORY, "rejected " + request.type().wireName() + ": " + e.path() + ": " + e.getMessage());
			peer.send(Envelope.reply(MessageType.RESPONSE_ERROR, correlation,
					Messages.error("invalid_request", e.getMessage(), e.path(), false)));
		} catch (TimeoutException e) {
			log.warn(CATEGORY, "timed out waiting for the client thread: " + request.type().wireName());
			peer.send(Envelope.reply(MessageType.RESPONSE_ERROR, correlation,
					Messages.error("client_thread_timeout", "the Minecraft client thread did not respond in time",
							null, true)));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			peer.send(Envelope.reply(MessageType.RESPONSE_ERROR, correlation,
					Messages.error("interrupted", "the request was interrupted", null, true)));
		} catch (RuntimeException | ExecutionException e) {
			Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
			log.error(CATEGORY, "failed to handle " + request.type().wireName(), cause);
			peer.send(Envelope.reply(MessageType.RESPONSE_ERROR, correlation,
					Messages.error("internal_error", "the Fabric mod failed to handle this request", null, true)));
		}
	}

	private JsonObject dispatch(Peer peer, Envelope request)
			throws TimeoutException, InterruptedException, ExecutionException {
		MessageType type = request.type();
		if (!type.isInbound()) {
			throw new Json.ValidationFailure("type", "the mod does not accept '" + type.wireName() + "' requests");
		}
		JsonObject data = request.data() == null ? new JsonObject() : request.data();
		return switch (type) {
			case HELLO -> hello(peer, data);
			case PING -> ping();
			case STATUS_GET -> status();
			case START -> start(data);
			case STOP -> stop(data);
			case RESTART -> restart(data);
			case TASKS_LIST -> tasksList(data);
			case TASK_GET -> taskGet(data);
			case TASK_CREATE -> taskCreate(data);
			case TASK_CANCEL -> taskCancel(data);
			case TASKS_CANCEL_ALL -> tasksCancelAll(data);
			case LOGS_GET -> logsGet(data);
			case CONFIG_GET -> configGet();
			case CONFIG_PATCH -> configPatch(data);
			case MODULES_SET -> modulesSet(data);
			case HOME_SET -> homeSet(data);
			default -> throw new Json.ValidationFailure("type",
					"unsupported request type '" + type.wireName() + "'");
		};
	}

	private JsonObject hello(Peer peer, JsonObject data) {
		int version = Json.optInt(data, "protocolVersion", ProtocolVersion.CURRENT);
		if (!ProtocolVersion.isSupported(version)) {
			throw new Json.ValidationFailure("protocolVersion",
					"unsupported protocol version " + version + "; this build supports "
							+ ProtocolVersion.SUPPORTED_VERSIONS);
		}
		String name = Json.optString(data, "peerName").orElse("discord-bot");
		String peerVersion = Json.optString(data, "peerVersion").orElse("unknown");
		if (peer instanceof dev.unionkitbot.fabric.net.ws.WebSocketSession session) {
			session.setPeerName(name);
		}
		log.info(CATEGORY, "handshake from " + name + " " + peerVersion + " (protocol " + version + ")");
		JsonObject out = ProtocolVersion.metadata("unionkitbot-fabric", BuildInfo.version());
		out.addProperty("modId", BuildInfo.MOD_ID);
		out.addProperty("state", agent.state().name());
		out.addProperty("running", agent.isRunning());
		out.add("config", config.config().toJson());
		return out;
	}

	private JsonObject ping() {
		JsonObject out = new JsonObject();
		out.addProperty("pong", true);
		out.addProperty("timestamp", System.currentTimeMillis());
		return out;
	}

	/**
	 * Builds a full status snapshot.
	 *
	 * @return the status payload
	 */
	public JsonObject status() {
		AgentConfig active = config.config();
		JsonObject out = new JsonObject();
		out.addProperty("state", agent.state().name());
		out.addProperty("running", agent.isRunning());
		out.addProperty("enabled", active.enabled());
		out.addProperty("logLevel", log.level().name());
		out.addProperty("uptimeTicks", agent.ticks());
		out.addProperty("lastAction", agent.lastActionDetail());
		out.addProperty("peers", peers.size());
		out.addProperty("protocolVersion", ProtocolVersion.CURRENT);
		out.addProperty("modVersion", BuildInfo.version());
		out.addProperty("apiPort", active.api().port());

		JsonObject queue = new JsonObject();
		queue.addProperty("active", agent.queue().activeCount());
		queue.addProperty("pending", agent.queue().pendingCount());
		queue.addProperty("finished", agent.queue().finishedCount());
		out.add("queue", queue);

		Optional<AgentTask> running = agent.queue().runningTask();
		if (running.isPresent()) {
			out.add("activeTask", Messages.task(running.get().snapshot()));
		} else {
			out.add("activeTask", null);
		}

		JsonObject modules = new JsonObject();
		for (Map.Entry<String, Object> entry : agent.modules().status().entrySet()) {
			modules.add(entry.getKey(), Json.parse(Json.write(entry.getValue()), entry.getKey()));
		}
		out.add("modules", modules);

		JsonArray errors = new JsonArray();
		for (DiagnosticsLog.ErrorEntry error : log.recentErrors(10)) {
			errors.add(Messages.errorEntry(error));
		}
		out.add("recentErrors", errors);

		out.add("world", Json.parse(Json.write(worldStatus.get()), "world"));
		out.addProperty("serverTime", System.currentTimeMillis());
		return out;
	}

	private JsonObject start(JsonObject data)
			throws TimeoutException, InterruptedException, ExecutionException {
		String reason = Json.optString(data, "reason").orElse("requested by operator");
		Boolean changed = onClientThread(() -> agent.start(reason));
		return transitionResult(changed, "automation started");
	}

	private JsonObject stop(JsonObject data)
			throws TimeoutException, InterruptedException, ExecutionException {
		String reason = Json.optString(data, "reason").orElse("requested by operator");
		Boolean changed = onClientThread(() -> agent.stop(reason));
		return transitionResult(changed, "automation stopped");
	}

	private JsonObject restart(JsonObject data)
			throws TimeoutException, InterruptedException, ExecutionException {
		String reason = Json.optString(data, "reason").orElse("requested by operator");
		Integer cancelled = onClientThread(() -> agent.restart(reason));
		JsonObject out = transitionResult(true, "automation restarted");
		out.addProperty("cancelledTasks", cancelled);
		return out;
	}

	private JsonObject transitionResult(boolean changed, String detail) {
		JsonObject out = new JsonObject();
		out.addProperty("changed", changed);
		out.addProperty("state", agent.state().name());
		out.addProperty("running", agent.isRunning());
		out.addProperty("detail", detail);
		return out;
	}

	private JsonObject tasksList(JsonObject data) {
		int limit = Math.max(1, Math.min(200, Json.optInt(data, "limit", 50)));
		JsonArray active = new JsonArray();
		List<AgentTask.Snapshot> activeSnapshots = agent.queue().activeSnapshots();
		for (int i = 0; i < Math.min(limit, activeSnapshots.size()); i++) {
			active.add(Messages.task(activeSnapshots.get(i)));
		}
		JsonArray finished = new JsonArray();
		List<AgentTask.Snapshot> finishedSnapshots = agent.queue().finishedSnapshots();
		for (int i = finishedSnapshots.size() - 1, taken = 0; i >= 0 && taken < limit; i--, taken++) {
			finished.add(Messages.task(finishedSnapshots.get(i)));
		}
		JsonObject out = new JsonObject();
		out.add("active", active);
		out.add("finished", finished);
		out.addProperty("activeCount", agent.queue().activeCount());
		out.addProperty("pendingCount", agent.queue().pendingCount());
		out.addProperty("finishedCount", agent.queue().finishedCount());
		return out;
	}

	private JsonObject taskGet(JsonObject data) {
		String id = Json.requireString(data, "id");
		Optional<AgentTask> task = agent.queue().find(id);
		if (task.isPresent()) {
			JsonObject out = new JsonObject();
			out.add("task", Messages.task(task.get().snapshot()));
			return out;
		}
		Optional<AgentTask.Snapshot> finished = agent.queue().finishedSnapshots().stream()
				.filter(snapshot -> snapshot.id().equals(id))
				.findFirst();
		if (finished.isEmpty()) {
			throw new Json.ValidationFailure("id", "no task with id '" + id + "'");
		}
		JsonObject out = new JsonObject();
		out.add("task", Messages.task(finished.get()));
		return out;
	}

	private JsonObject taskCreate(JsonObject data)
			throws TimeoutException, InterruptedException, ExecutionException {
		String kindId = Json.requireString(data, "kind");
		TaskKind kind = TaskKind.parse(kindId);
		if (kind == null) {
			throw new Json.ValidationFailure("kind", "unknown task kind '" + kindId + "'");
		}
		String priorityId = Json.optString(data, "priority").orElse("normal");
		TaskPriority priority = TaskPriority.parse(priorityId);
		if (priority == null) {
			throw new Json.ValidationFailure("priority", "unknown priority '" + priorityId + "'");
		}
		TaskParameters parameters = parseParameters(data);
		Optional<AgentTask> created = onClientThread(() -> agent.submit(kind, priority, parameters));
		if (created.isEmpty()) {
			throw new Json.ValidationFailure("kind",
					"the task was rejected; check the queue limit and that a module supports '" + kind.id() + "'");
		}
		JsonObject out = new JsonObject();
		out.add("task", Messages.task(created.get().snapshot()));
		out.addProperty("accepted", true);
		return out;
	}

	private TaskParameters parseParameters(JsonObject data) {
		JsonObject raw = data.has("parameters") && data.get("parameters").isJsonObject()
				? data.getAsJsonObject("parameters")
				: new JsonObject();
		Map<String, Object> values = new LinkedHashMap<>(Json.toMap(raw));
		// Convenience: accept x/y/z at the top level as a destination.
		if (data.has("x") && data.has("y") && data.has("z")) {
			Map<String, Object> position = new LinkedHashMap<>();
			position.put("x", Json.requireDouble(data, "x"));
			position.put("y", Json.requireDouble(data, "y"));
			position.put("z", Json.requireDouble(data, "z"));
			values.put("position", Map.copyOf(position));
		}
		if (data.has("target")) {
			values.put("target", Json.requireString(data, "target"));
		}
		return values.isEmpty() ? TaskParameters.empty() : TaskParameters.of(values);
	}

	private JsonObject taskCancel(JsonObject data) {
		String id = Json.requireString(data, "id");
		String reason = Json.optString(data, "reason").orElse("cancelled by operator");
		boolean cancelled = agent.queue().cancel(id, reason);
		agent.queue().persist();
		if (!cancelled) {
			throw new Json.ValidationFailure("id", "task '" + id + "' is unknown or already finished");
		}
		JsonObject out = new JsonObject();
		out.addProperty("cancelled", true);
		out.addProperty("id", id);
		return out;
	}

	private JsonObject tasksCancelAll(JsonObject data) {
		String reason = Json.optString(data, "reason").orElse("all tasks cancelled by operator");
		int cancelled = onClientThreadUnchecked(() -> {
			agent.modules().stopAll(reason);
			return agent.queue().cancelAll(reason);
		});
		agent.queue().persist();
		JsonObject out = new JsonObject();
		out.addProperty("cancelled", cancelled);
		return out;
	}

	private JsonObject logsGet(JsonObject data) {
		int limit = Math.max(1, Math.min(500, Json.optInt(data, "limit", 100)));
		String minimum = Json.optString(data, "level").orElse(null);
		LogLevel floor = minimum == null ? null : LogLevel.parse(minimum, null);
		if (minimum != null && floor == null) {
			throw new Json.ValidationFailure("level", "unknown log level '" + minimum + "'");
		}
		JsonArray entries = new JsonArray();
		for (DiagnosticsLog.Entry entry : log.recent(limit)) {
			if (floor != null && !floor.permits(entry.level())) {
				continue;
			}
			entries.add(Messages.logEntry(entry));
		}
		JsonArray errors = new JsonArray();
		for (DiagnosticsLog.ErrorEntry error : log.recentErrors(limit)) {
			errors.add(Messages.errorEntry(error));
		}
		JsonObject out = new JsonObject();
		out.add("entries", entries);
		out.add("errors", errors);
		out.addProperty("level", log.level().name());
		out.addProperty("dropped", log.dropped());
		return out;
	}

	private JsonObject configGet() {
		JsonObject out = new JsonObject();
		out.add("config", config.config().toJson());
		config.lastLoadError().ifPresent(error -> out.addProperty("loadError", error));
		out.addProperty("configFile", config.directory().resolve(ConfigManager.CONFIG_FILE).toString());
		return out;
	}

	private JsonObject configPatch(JsonObject data)
			throws TimeoutException, InterruptedException, ExecutionException {
		JsonObject patch = data.has("config") && data.get("config").isJsonObject()
				? data.getAsJsonObject("config")
				: data;
		AgentConfig updated = onClientThread(() -> {
			AgentConfig result = config.update(patch);
			agent.applyConfig(result);
			return result;
		});
		publishConfigChanged(updated);
		JsonObject out = new JsonObject();
		out.add("config", updated.toJson());
		out.addProperty("applied", true);
		return out;
	}

	private JsonObject modulesSet(JsonObject data)
			throws TimeoutException, InterruptedException, ExecutionException {
		JsonObject patch = data.has("modules") && data.get("modules").isJsonObject()
				? data.getAsJsonObject("modules")
				: data;
		if (patch.isEmpty()) {
			throw new Json.ValidationFailure("modules", "no module flags supplied");
		}
		JsonObject wrapper = new JsonObject();
		wrapper.add("modules", patch);
		AgentConfig updated = onClientThread(() -> {
			AgentConfig result = config.update(wrapper);
			agent.applyConfig(result);
			return result;
		});
		publishModulesChanged(updated);
		JsonObject out = new JsonObject();
		out.add("modules", updated.modules().toJson());
		return out;
	}

	private JsonObject homeSet(JsonObject data)
			throws TimeoutException, InterruptedException, ExecutionException {
		JsonObject patch = new JsonObject();
		JsonObject home = new JsonObject();
		if (data.has("clear") && Json.optBoolean(data, "clear", false)) {
			JsonObject navigation = new JsonObject();
			navigation.add("home", new JsonObject());
			patch.add("navigation", navigation);
		} else {
			home.addProperty("x", Json.requireDouble(data, "x"));
			home.addProperty("y", Json.requireDouble(data, "y"));
			home.addProperty("z", Json.requireDouble(data, "z"));
			JsonObject navigation = new JsonObject();
			navigation.add("home", home);
			patch.add("navigation", navigation);
		}
		AgentConfig updated = onClientThread(() -> {
			AgentConfig result = config.update(patch);
			agent.applyConfig(result);
			return result;
		});
		JsonObject out = new JsonObject();
		out.add("home", updated.navigation().toJson().get("home"));
		return out;
	}

	/**
	 * Runs work on the client thread and waits for the result.
	 *
	 * @param work the work
	 * @param <T> the result type
	 * @return the result
	 * @throws TimeoutException when the client thread did not respond
	 * @throws InterruptedException when the calling thread was interrupted
	 * @throws ExecutionException when the work failed
	 */
	private <T> T onClientThread(Supplier<T> work)
			throws TimeoutException, InterruptedException, ExecutionException {
		if (mainThread == MainThreadExecutor.DIRECT) {
			return work.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		mainThread.execute(() -> {
			try {
				future.complete(work.get());
			} catch (RuntimeException e) {
				future.completeExceptionally(e);
			}
		});
		try {
			return future.get(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Json.JsonProblem problem) {
				throw problem;
			}
			throw e;
		}
	}

	private int onClientThreadUnchecked(Supplier<Integer> work) {
		try {
			Integer result = onClientThread(work);
			return result == null ? 0 : result;
		} catch (TimeoutException | InterruptedException | ExecutionException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn(CATEGORY, "client thread operation did not complete: " + e.getMessage());
			return 0;
		}
	}

	// ---------------------------------------------------------------- publishing

	/**
	 * Publishes a lifecycle state change to every connected peer.
	 *
	 * @param observation the observation that accompanied the change, may be {@code null}
	 */
	public void publishStateChanged(Observation observation) {
		JsonObject payload = new JsonObject();
		payload.addProperty("state", agent.state().name());
		payload.addProperty("running", agent.isRunning());
		payload.addProperty("lastAction", agent.lastActionDetail());
		payload.addProperty("timestamp", System.currentTimeMillis());
		if (observation != null) {
			payload.addProperty("summary", observation.summary());
		}
		peers.broadcast(MessageType.STATE_CHANGED, payload);
	}

	/**
	 * Publishes a task event.
	 *
	 * @param type the event type
	 * @param snapshot the task snapshot
	 */
	public void publishTaskEvent(MessageType type, AgentTask.Snapshot snapshot) {
		if (snapshot == null || !type.isOutbound()) {
			return;
		}
		peers.broadcast(type, Messages.task(snapshot));
	}

	/**
	 * Publishes a structured error to every connected peer.
	 *
	 * @param category the subsystem that failed
	 * @param message the explanation
	 * @param cause the throwable, may be {@code null}
	 */
	public void publishError(String category, String message, Throwable cause) {
		JsonObject payload = new JsonObject();
		payload.addProperty("timestamp", System.currentTimeMillis());
		payload.addProperty("category", category == null ? "general" : category);
		payload.addProperty("message", message == null ? "unspecified failure" : message);
		if (cause != null) {
			payload.addProperty("exception", cause.getClass().getName());
			if (cause.getMessage() != null) {
				payload.addProperty("detail", cause.getMessage());
			}
		}
		payload.addProperty("state", agent.state().name());
		peers.broadcast(MessageType.ERROR, payload);
	}

	/**
	 * Publishes a diagnostics entry to every connected peer.
	 *
	 * @param entry the entry
	 */
	public void publishLog(DiagnosticsLog.Entry entry) {
		if (entry == null) {
			return;
		}
		peers.broadcast(MessageType.LOG, Messages.logEntry(entry));
	}

	/**
	 * Publishes a configuration change.
	 *
	 * @param config the new configuration
	 */
	public void publishConfigChanged(AgentConfig config) {
		JsonObject payload = new JsonObject();
		payload.add("config", config.toJson());
		payload.addProperty("timestamp", System.currentTimeMillis());
		peers.broadcast(MessageType.CONFIG_CHANGED, payload);
	}

	/**
	 * Publishes a module toggle change.
	 *
	 * @param config the new configuration
	 */
	public void publishModulesChanged(AgentConfig config) {
		JsonObject payload = new JsonObject();
		payload.add("modules", config.modules().toJson());
		payload.addProperty("timestamp", System.currentTimeMillis());
		peers.broadcast(MessageType.MODULES_CHANGED, payload);
	}

	/**
	 * Publishes a heartbeat frame.
	 */
	public void publishHeartbeat() {
		JsonObject payload = new JsonObject();
		payload.addProperty("state", agent.state().name());
		payload.addProperty("running", agent.isRunning());
		payload.addProperty("timestamp", System.currentTimeMillis());
		payload.addProperty("activeTasks", agent.queue().activeCount());
		payload.addProperty("pendingTasks", agent.queue().pendingCount());
		payload.addProperty("lastAction", agent.lastActionDetail());
		peers.broadcast(MessageType.HEARTBEAT, payload);
	}

	/**
	 * Publishes a graceful shutdown notice.
	 *
	 * @param reason why the mod is shutting down
	 */
	public void publishGoodbye(String reason) {
		JsonObject payload = new JsonObject();
		payload.addProperty("reason", reason == null ? "shutting down" : reason);
		payload.addProperty("timestamp", System.currentTimeMillis());
		peers.broadcast(MessageType.GOODBYE, payload);
	}

	/**
	 * @return the set of request type names this dispatcher understands, for docs
	 */
	public static List<String> supportedRequestTypes() {
		List<String> names = new ArrayList<>();
		for (MessageType type : MessageType.values()) {
			if (type.isInbound()) {
				names.add(type.wireName());
			}
		}
		return List.copyOf(names);
	}
}
