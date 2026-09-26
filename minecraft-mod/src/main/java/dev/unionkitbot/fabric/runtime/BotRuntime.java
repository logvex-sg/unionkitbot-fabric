package dev.unionkitbot.fabric.runtime;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.config.ConfigManager;
import dev.unionkitbot.fabric.core.agent.BotAgent;
import dev.unionkitbot.fabric.core.state.StateMachine;
import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.task.FileTaskStore;
import dev.unionkitbot.fabric.core.task.TaskQueue;
import dev.unionkitbot.fabric.core.world.Observation;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.module.AgentActions;
import dev.unionkitbot.fabric.module.AutomationModule;
import dev.unionkitbot.fabric.module.DeliveryModule;
import dev.unionkitbot.fabric.module.ModuleRegistry;
import dev.unionkitbot.fabric.module.NavigationModule;
import dev.unionkitbot.fabric.module.RecoveryModule;
import dev.unionkitbot.fabric.module.ScanningModule;
import dev.unionkitbot.fabric.net.ControlDispatcher;
import dev.unionkitbot.fabric.net.ControlServer;
import dev.unionkitbot.fabric.net.MainThreadExecutor;
import dev.unionkitbot.fabric.net.PeerRegistry;
import dev.unionkitbot.fabric.net.http.HttpPeer;
import dev.unionkitbot.fabric.net.ws.WebSocketSession;
import dev.unionkitbot.fabric.protocol.Envelope;
import dev.unionkitbot.fabric.protocol.MessageType;
import dev.unionkitbot.fabric.protocol.Messages;

/**
 * Assembles the agent, modules, task queue, diagnostics and control API into one
 * object with an explicit lifecycle.
 *
 * <p>The runtime is deliberately independent of Minecraft: it takes an
 * {@link AgentActions} implementation and an observation source, so the whole system
 * can be built and exercised in tests with no client at all. Startup and shutdown are
 * explicit and report failure through the return value rather than throwing, because
 * a failure to start the control API must not prevent the game from loading.
 */
public final class BotRuntime implements AutoCloseable {
	private static final String CATEGORY = "runtime";

	private final ConfigManager config;
	private final DiagnosticsLog log;
	private final StateMachine stateMachine;
	private final TaskQueue queue;
	private final ModuleRegistry modules;
	private final BotAgent agent;
	private final PeerRegistry peers;
	private final ControlDispatcher dispatcher;
	private final ControlServer server;
	private final FileTaskStore store;
	private final MainThreadExecutor mainThread;

	private volatile Observation lastObservation = Observation.offline(0L);
	private volatile boolean started;
	private volatile String lastError;

	/**
	 * @param directory the configuration directory
	 * @param actions the world-facing actions
	 * @param log the shared diagnostics log
	 * @param mainThread marshals commands onto the client thread
	 * @param externalSink optional sink that forwards diagnostics to the host logger
	 */
	public BotRuntime(Path directory, AgentActions actions, DiagnosticsLog log, MainThreadExecutor mainThread,
			Consumer<DiagnosticsLog.Entry> externalSink) {
		this.mainThread = mainThread;
		this.config = new ConfigManager(directory);
		AgentConfig initial = config.load();
		this.log = log;
		this.log.setLevel(initial.logLevel());
		if (externalSink != null) {
			this.log.addSink(externalSink);
		}
		this.stateMachine = new StateMachine();
		this.queue = new TaskQueue();
		this.store = new FileTaskStore(directory);
		this.queue.setStore(store);
		this.modules = new ModuleRegistry(java.util.List.of(
				new NavigationModule(actions, initial.navigation(), log),
				new DeliveryModule(actions, initial.delivery(), log),
				new ScanningModule(initial.scan(), log),
				new RecoveryModule(actions, initial.recovery(), log)));
		this.peers = new PeerRegistry(log);
		this.agent = new BotAgent(stateMachine, queue, modules, initial, log, this::currentObservation);
		this.dispatcher = new ControlDispatcher(agent, config, log, peers, mainThread, this::worldStatus);
		this.server = new ControlServer(initial.api(), log, peers, dispatcher, api -> {
			AgentConfig current = config.config();
			AgentConfig updated = new AgentConfig(current.enabled(), current.logLevel(), api, current.modules(),
					current.limits(), current.navigation(), current.delivery(), current.scan(), current.recovery());
			config.replace(updated);
			config.save();
		});
		wireEvents();
	}

	private Observation currentObservation() {
		return lastObservation;
	}

	private void wireEvents() {
		queue.addListener(new TaskQueue.TaskQueueListener() {
			@Override
			public void onSubmitted(AgentTask task) {
				dispatcher.publishTaskEvent(MessageType.TASK_CREATED, task.snapshot());
			}

			@Override
			public void onStatusChanged(AgentTask.Snapshot snapshot) {
				MessageType type = switch (snapshot.status().toUpperCase(Locale.ROOT)) {
					case "COMPLETED" -> MessageType.TASK_COMPLETED;
					case "FAILED" -> MessageType.TASK_FAILED;
					case "CANCELLED" -> MessageType.TASK_CANCELLED;
					default -> MessageType.TASK_UPDATED;
				};
				dispatcher.publishTaskEvent(type, snapshot);
			}
		});
		agent.addStateListener(state -> dispatcher.publishStateChanged(lastObservation));
		log.addSink(dispatcher::publishLog);
	}

	/**
	 * Loads the queue, starts the control API and enables automation if configured.
	 *
	 * @return {@code true} when the control API is listening
	 */
	public synchronized boolean start() {
		if (started) {
			return server.isRunning();
		}
		started = true;
		int restored = queue.restore();
		if (restored > 0) {
			log.info(CATEGORY, "restored " + restored + " queued task(s) from disk");
		}
		config.lastLoadError().ifPresent(error -> log.warn(CATEGORY, "configuration problem: " + error));
		store.lastError().ifPresent(error -> log.warn(CATEGORY, "task store problem: " + error));
		AgentConfig active = config.config();
		if (active.enabled()) {
			agent.start("configuration enabled automation");
		}
		boolean listening = server.start();
		if (!listening) {
			lastError = server.lastError();
			log.warn(CATEGORY, "the control API is not available: " + lastError
					+ "; the agent will still run locally");
			dispatcher.publishError(CATEGORY, "control API unavailable: " + lastError, null);
		}
		return listening;
	}

	/**
	 * Runs one iteration of the agent loop.
	 *
	 * @param observation the current world snapshot
	 */
	public void tick(Observation observation) {
		if (observation != null) {
			lastObservation = observation;
		}
		agent.tick();
	}

	/**
	 * Publishes a periodic heartbeat to connected peers.
	 */
	public void heartbeat() {
		dispatcher.publishHeartbeat();
	}

	/**
	 * Notifies peers that the client is going away.
	 *
	 * @param reason why the client is stopping
	 */
	public void announceShutdown(String reason) {
		dispatcher.publishGoodbye(reason);
	}

	/**
	 * @return the agent
	 */
	public BotAgent agent() {
		return agent;
	}

	/**
	 * @return the configuration manager
	 */
	public ConfigManager config() {
		return config;
	}

	/**
	 * @return the diagnostics log
	 */
	public DiagnosticsLog log() {
		return log;
	}

	/**
	 * @return the control server
	 */
	public ControlServer server() {
		return server;
	}

	/**
	 * @return the peer registry
	 */
	public PeerRegistry peers() {
		return peers;
	}

	/**
	 * @return the task queue
	 */
	public TaskQueue queue() {
		return queue;
	}

	/**
	 * @return the most recent observation
	 */
	public Observation lastObservation() {
		return lastObservation;
	}

	/**
	 * @return the startup failure message, or empty when startup succeeded
	 */
	public Optional<String> lastError() {
		return Optional.ofNullable(lastError);
	}

	/**
	 * Builds the world summary included in status frames.
	 *
	 * @return the world summary
	 */
	public Map<String, Object> worldStatus() {
		Observation observation = lastObservation;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("clientReady", observation.clientReady());
		out.put("connected", observation.isConnected());
		out.put("hasWorld", observation.hasWorld());
		out.put("hasPlayer", observation.hasPlayer());
		out.put("alive", observation.playerAlive());
		out.put("screenOpen", observation.screenOpen());
		out.put("dimension", observation.dimensionId().orElse("unknown"));
		out.put("integratedServer", observation.integratedServer());
		out.put("summary", observation.summary());
		observation.position().ifPresent(position -> {
			out.put("x", position.x());
			out.put("y", position.y());
			out.put("z", position.z());
		});
		if (observation.hasPlayer()) {
			out.put("health", observation.health());
			out.put("foodLevel", observation.foodLevel());
		}
		out.put("selectedSlot", observation.selectedSlot());
		observation.selectedItemId().ifPresent(item -> out.put("selectedItem", item));
		out.put("worldTime", observation.worldTime());
		out.put("nearbyPlayers", observation.nearbyPlayers().size());
		return Map.copyOf(out);
	}

	/**
	 * Runs a control request without a transport, for the in-game screen and tests.
	 *
	 * @param type the request type
	 * @param payload the request payload
	 * @return the reply envelope
	 */
	public Envelope dispatchLocal(MessageType type, com.google.gson.JsonObject payload) {
		HttpPeer peer = new HttpPeer("in-game");
		dispatcher.handle(peer, Envelope.of(type, payload));
		Envelope reply = peer.await(3_000L);
		if (reply == null) {
			return Envelope.reply(MessageType.RESPONSE_ERROR, null,
					Messages.error("timeout", "the agent did not reply in time", null, true));
		}
		return reply;
	}

	@Override
	public synchronized void close() {
		announceShutdown("client stopping");
		server.stop("runtime closed");
		peers.closeAll(1001, "runtime closed");
		if (agent.isRunning()) {
			agent.stop("runtime closed");
		}
		queue.persist();
		log.info(CATEGORY, "runtime stopped");
	}

	/**
	 * @return whether the control API is currently listening
	 */
	public boolean isServerRunning() {
		return server.isRunning();
	}

	/**
	 * @return the active module names, for the in-game screen
	 */
	public java.util.List<String> moduleNames() {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (AutomationModule module : modules.all()) {
			names.add(module.name());
		}
		return java.util.List.copyOf(names);
	}

	/**
	 * @return the number of peers currently connected
	 */
	public int peerCount() {
		return peers.size();
	}

	/**
	 * @return whether any peer is connected
	 */
	public boolean hasPeers() {
		return peers.hasPeers();
	}

	/**
	 * @return whether any peer is a live WebSocket session
	 */
	public boolean hasWebSocketPeer() {
		return peers.peers().stream().anyMatch(WebSocketSession.class::isInstance);
	}
}
