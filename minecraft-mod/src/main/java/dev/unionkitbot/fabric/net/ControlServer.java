package dev.unionkitbot.fabric.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.config.AgentConfig;
import dev.unionkitbot.fabric.config.ConfigManager;
import dev.unionkitbot.fabric.core.util.Json;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.net.http.HttpPeer;
import dev.unionkitbot.fabric.net.http.HttpRequest;
import dev.unionkitbot.fabric.net.http.HttpRequestReader;
import dev.unionkitbot.fabric.net.http.HttpResponse;
import dev.unionkitbot.fabric.net.ws.WebSocketHandshake;
import dev.unionkitbot.fabric.net.ws.WebSocketSession;
import dev.unionkitbot.fabric.protocol.Envelope;
import dev.unionkitbot.fabric.protocol.MessageType;
import dev.unionkitbot.fabric.protocol.Messages;
import dev.unionkitbot.fabric.protocol.ProtocolVersion;

/**
 * The local control API: a loopback HTTP endpoint plus a WebSocket upgrade path.
 *
 * <p>Lifecycle is explicit. {@link #start()} either binds and reports the port, or
 * fails and leaves the server stopped, with the reason recorded and logged. Nothing
 * here is allowed to take the game down: every socket failure is logged and confined
 * to the connection it happened on, and the accept loop keeps running until
 * {@link #stop(String)} is called.
 *
 * <p>Two independent guards protect the endpoint. The first is the bind address: by
 * default it listens on {@code 127.0.0.1} only, and non-loopback callers are refused
 * unless {@code api.allowRemote} is set. The second is the pre-shared secret, which
 * is required on every request and compared in constant time.
 */
public final class ControlServer {
	private static final String CATEGORY = "api";
	private static final int MAX_CONNECTIONS = 32;
	private static final long HTTP_REPLY_TIMEOUT_MS = 5_000L;

	private final DiagnosticsLog log;
	private final PeerRegistry peers;
	private final ControlDispatcher dispatcher;
	private final Consumer<AgentConfig.ApiConfig> configSink;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong accepted = new AtomicLong();
	private final AtomicLong rejected = new AtomicLong();
	private final AtomicLong requestCount = new AtomicLong();

	private volatile AgentConfig.ApiConfig settings;
	private volatile ServerSocket serverSocket;
	private volatile Thread acceptThread;
	private volatile ExecutorService workers;
	private volatile int boundPort = -1;
	private volatile String lastError;

	/**
	 * @param settings the initial API settings
	 * @param log the diagnostics log
	 * @param peers the peer registry
	 * @param dispatcher the request dispatcher
	 * @param configSink invoked when the bound port must be written back to the
	 *        configuration, so the operator can discover an ephemeral port
	 */
	public ControlServer(AgentConfig.ApiConfig settings, DiagnosticsLog log, PeerRegistry peers,
			ControlDispatcher dispatcher, Consumer<AgentConfig.ApiConfig> configSink) {
		this.settings = settings;
		this.log = log;
		this.peers = peers;
		this.dispatcher = dispatcher;
		this.configSink = configSink;
	}

	/**
	 * @return {@code true} while the server is accepting connections
	 */
	public boolean isRunning() {
		return running.get();
	}

	/**
	 * @return the bound port, or {@code -1} when stopped
	 */
	public int port() {
		return boundPort;
	}

	/**
	 * @return the last failure message, or {@code null}
	 */
	public String lastError() {
		return lastError;
	}

	/**
	 * @return the active API settings
	 */
	public AgentConfig.ApiConfig settings() {
		return settings;
	}

	/**
	 * Binds the socket and starts accepting connections.
	 *
	 * @return {@code true} when the server started
	 */
	public synchronized boolean start() {
		if (running.get()) {
			return true;
		}
		if (!settings.hasSecret()) {
			lastError = "no API secret configured; set it in " + ConfigManager.SECRET_FILE
					+ " or " + ConfigManager.SECRET_ENV;
			log.error(CATEGORY, lastError, null);
			return false;
		}
		InetAddress address;
		try {
			address = InetAddress.getByName(settings.host());
		} catch (IOException e) {
			lastError = "cannot resolve bind address '" + settings.host() + "': " + e.getMessage();
			log.error(CATEGORY, lastError, e);
			return false;
		}
		if (!settings.allowRemote() && !address.isLoopbackAddress()) {
			lastError = "bind address " + settings.host() + " is not loopback but api.allowRemote is false";
			log.error(CATEGORY, lastError, null);
			return false;
		}
		try {
			ServerSocket socket = new ServerSocket();
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress(address, settings.port()), 16);
			this.serverSocket = socket;
			this.boundPort = socket.getLocalPort();
		} catch (IOException e) {
			lastError = "cannot bind " + settings.host() + ":" + settings.port() + ": " + e.getMessage();
			log.error(CATEGORY, lastError, e);
			return false;
		}
		this.workers = Executors.newVirtualThreadPerTaskExecutor();
		running.set(true);
		lastError = null;
		Thread thread = new Thread(this::acceptLoop, "unionkitbot-api-accept");
		thread.setDaemon(true);
		this.acceptThread = thread;
		thread.start();
		log.info(CATEGORY, "control API listening on " + settings.host() + ":" + boundPort
				+ " (secret " + AuthTokens.mask(settings.secret()) + ")");
		if (boundPort != settings.port()) {
			configSink.accept(new AgentConfig.ApiConfig(settings.host(), boundPort, settings.secret(),
					settings.allowRemote(), settings.heartbeatSeconds(), settings.maxPayloadBytes(),
					settings.allowedOrigins()));
		}
		return true;
	}

	/**
	 * Applies new settings. When the bind address, port or secret changed the server
	 * is restarted so the new settings take effect.
	 *
	 * @param updated the new settings
	 * @return {@code true} when the server is running afterwards
	 */
	public synchronized boolean applySettings(AgentConfig.ApiConfig updated) {
		boolean needsRestart = running.get()
				&& (!updated.host().equals(settings.host())
						|| updated.port() != settings.port()
						|| !updated.secret().equals(settings.secret())
						|| updated.allowRemote() != settings.allowRemote());
		this.settings = updated;
		if (!needsRestart) {
			return running.get();
		}
		log.info(CATEGORY, "API settings changed; restarting the control server");
		stop("settings changed");
		return start();
	}

	/**
	 * Stops the server and closes every peer.
	 *
	 * @param reason why the server is stopping
	 */
	public synchronized void stop(String reason) {
		if (!running.getAndSet(false)) {
			return;
		}
		log.info(CATEGORY, "control API stopping: " + reason);
		peers.closeAll(1001, reason);
		ServerSocket socket = serverSocket;
		serverSocket = null;
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
				log.warn(CATEGORY, "failed to close the listening socket: " + e.getMessage());
			}
		}
		ExecutorService executor = workers;
		workers = null;
		if (executor != null) {
			executor.shutdownNow();
			try {
				if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
					log.warn(CATEGORY, "worker pool did not terminate cleanly");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Thread thread = acceptThread;
		acceptThread = null;
		if (thread != null && thread != Thread.currentThread()) {
			try {
				thread.join(2_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		boundPort = -1;
	}

	private void acceptLoop() {
		while (running.get()) {
			ServerSocket socket = serverSocket;
			if (socket == null) {
				return;
			}
			try {
				Socket client = socket.accept();
				if (peers.size() >= MAX_CONNECTIONS) {
					rejected.incrementAndGet();
					log.warn(CATEGORY, "refusing connection from " + client.getRemoteSocketAddress()
							+ ": too many peers");
					closeQuietly(client);
					continue;
				}
				ExecutorService executor = workers;
				if (executor == null) {
					closeQuietly(client);
					continue;
				}
				executor.execute(() -> handleConnection(client));
			} catch (SocketException e) {
				if (running.get()) {
					log.warn(CATEGORY, "accept loop stopped unexpectedly: " + e.getMessage());
				}
				return;
			} catch (IOException e) {
				if (running.get()) {
					log.error(CATEGORY, "failed to accept a connection", e);
				}
			} catch (RuntimeException e) {
				log.error(CATEGORY, "unexpected failure in the accept loop", e);
			}
		}
	}

	private void handleConnection(Socket client) {
		accepted.incrementAndGet();
		String remote = String.valueOf(client.getRemoteSocketAddress());
		try {
			client.setSoTimeout(15_000);
			client.setTcpNoDelay(true);
			HttpRequest request = HttpRequestReader.read(client.getInputStream(), settings.maxPayloadBytes());
			requestCount.incrementAndGet();
			String path = request.path().toLowerCase(Locale.ROOT);
			if ("/ws".equals(path) || "/websocket".equals(path)) {
				upgradeToWebSocket(client, request, remote);
				return;
			}
			HttpResponse response = route(request, remote);
			response.write(client.getOutputStream());
		} catch (HttpRequestReader.BadRequestException e) {
			rejected.incrementAndGet();
			log.warn(CATEGORY, "bad request from " + remote + ": " + e.getMessage());
			writeQuietly(client, HttpResponse.text(e.status(), e.getMessage()));
		} catch (IOException e) {
			log.warn(CATEGORY, "connection from " + remote + " failed: " + e.getMessage());
		} catch (RuntimeException e) {
			log.error(CATEGORY, "unexpected failure handling " + remote, e);
			writeQuietly(client, HttpResponse.text(500, "internal error"));
		} finally {
			closeQuietly(client);
		}
	}

	private HttpResponse route(HttpRequest request, String remote) {
		String path = request.path().toLowerCase(Locale.ROOT);
		if ("/health".equals(path)) {
			// Deliberately unauthenticated and information-free: it only proves the
			// socket is up, which is all a supervisor needs.
			JsonObject body = new JsonObject();
			body.addProperty("status", "ok");
			body.addProperty("protocolVersion", ProtocolVersion.CURRENT);
			return HttpResponse.ok(Json.write(body));
		}
		if (!AuthTokens.matches(settings.secret(), presentedSecret(request))) {
			rejected.incrementAndGet();
			log.warn(CATEGORY, "unauthorised request from " + remote + " to " + path);
			return HttpResponse.json(401, Json.write(Messages.error("unauthorized",
					"a valid API secret is required", null, false)));
		}
		if (!settings.allowRemote() && !WebSocketHandshake.isLoopback(request)) {
			rejected.incrementAndGet();
			return HttpResponse.json(403, Json.write(Messages.error("forbidden",
					"remote clients are disabled; set api.allowRemote to enable them", null, false)));
		}
		return switch (path) {
			case "/status" -> HttpResponse.ok(Json.write(dispatcher.status()));
			case "/command" -> handleCommand(request, remote);
			default -> HttpResponse.json(404, Json.write(Messages.error("not_found",
					"unknown endpoint '" + request.path() + "'", null, false)));
		};
	}

	private HttpResponse handleCommand(HttpRequest request, String remote) {
		if (!"POST".equals(request.method())) {
			return HttpResponse.json(405, Json.write(Messages.error("method_not_allowed",
					"POST is required for /command", null, false)));
		}
		if (request.body().isBlank()) {
			return HttpResponse.json(400, Json.write(Messages.error("invalid_request",
					"an envelope body is required", null, false)));
		}
		Envelope envelope;
		try {
			envelope = Envelope.decode(request.body());
		} catch (Json.JsonProblem e) {
			return HttpResponse.json(400, Json.write(Messages.error("invalid_request", e.getMessage(), e.path(),
					false)));
		}
		if (!envelope.type().isInbound()) {
			return HttpResponse.json(400, Json.write(Messages.error("invalid_request",
					"the mod does not accept '" + envelope.type().wireName() + "' requests", "type", false)));
		}
		HttpPeer peer = new HttpPeer(remote);
		dispatcher.handle(peer, envelope);
		Envelope reply = peer.await(HTTP_REPLY_TIMEOUT_MS);
		if (reply == null) {
			return HttpResponse.json(504, Json.write(Messages.error("timeout",
					"the mod did not produce a reply in time", null, true)));
		}
		return HttpResponse.ok(reply.encode());
	}

	private String presentedSecret(HttpRequest request) {
		return request.header(ProtocolVersion.AUTH_HEADER)
				.or(() -> AuthTokens.bearerToken(request.header("authorization").orElse(null)))
				.or(() -> request.param(ProtocolVersion.AUTH_QUERY_PARAM))
				.orElse(null);
	}

	private void upgradeToWebSocket(Socket client, HttpRequest request, String remote) {
		WebSocketHandshake handshake;
		try {
			handshake = WebSocketHandshake.validate(request, settings.secret(), settings.allowRemote(),
					settings.allowedOrigins());
		} catch (WebSocketHandshake.RejectedException e) {
			rejected.incrementAndGet();
			log.warn(CATEGORY, "WebSocket upgrade from " + remote + " refused: " + e.getMessage());
			writeQuietly(client, HttpResponse.json(e.status(), Json.write(Messages.error(
					e.status() == 401 ? "unauthorized" : "invalid_handshake", e.getMessage(), null, false))));
			return;
		}
		String accept = WebSocketSession.acceptToken(handshake.clientKey());
		StringBuilder response = new StringBuilder(256);
		response.append("HTTP/1.1 101 Switching Protocols\r\n");
		response.append("Upgrade: websocket\r\n");
		response.append("Connection: Upgrade\r\n");
		response.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
		response.append("Sec-WebSocket-Version: 13\r\n");
		response.append("Sec-WebSocket-Protocol: ").append(ProtocolVersion.WS_SUBPROTOCOL).append("\r\n");
		response.append("\r\n");
		try {
			client.getOutputStream().write(response.toString().getBytes(StandardCharsets.US_ASCII));
			client.getOutputStream().flush();
			client.setSoTimeout(0);
			WebSocketSession session = new WebSocketSession(client, handshake, this::onText,
					this::onSessionClosed, throwable -> log.error(CATEGORY, "WebSocket transport error", throwable));
			session.setPeerName(handshake.peerName());
			peers.add(session);
			session.start();
			session.send(Envelope.of(MessageType.WELCOME, welcomePayload(session)));
		} catch (IOException e) {
			log.warn(CATEGORY, "failed to complete the WebSocket upgrade for " + remote + ": " + e.getMessage());
			closeQuietly(client);
		}
	}

	private JsonObject welcomePayload(WebSocketSession session) {
		JsonObject payload = ProtocolVersion.metadata(BuildInfo.NAME, BuildInfo.version());
		payload.addProperty("modId", BuildInfo.MOD_ID);
		payload.addProperty("state", dispatcher.status().get("state").getAsString());
		payload.addProperty("heartbeatSeconds", settings.heartbeatSeconds());
		payload.addProperty("sessionId", session.id());
		return payload;
	}

	private void onText(String text) {
		Envelope request;
		try {
			request = Envelope.decode(text);
		} catch (Json.JsonProblem e) {
			log.warn(CATEGORY, "rejected frame: " + e.path() + ": " + e.getMessage());
			peers.broadcast(MessageType.ERROR, Messages.error("invalid_frame", e.getMessage(), e.path(), false));
			return;
		}
		if (!request.type().isInbound()) {
			log.warn(CATEGORY, "ignoring outbound-only frame type " + request.type().wireName());
			return;
		}
		HttpPeer collector = new HttpPeer("websocket");
		dispatcher.handle(collector, request);
		Envelope reply = collector.await(HTTP_REPLY_TIMEOUT_MS);
		if (reply != null) {
			peers.broadcast(reply);
		}
	}

	private void onSessionClosed(WebSocketSession session) {
		peers.remove(session);
	}

	private static void writeQuietly(Socket client, HttpResponse response) {
		try {
			response.write(client.getOutputStream());
		} catch (IOException ignored) {
			// The peer is gone; there is nothing to report it to.
		}
	}

	private static void closeQuietly(Socket client) {
		try {
			client.close();
		} catch (IOException ignored) {
			// Closing an already-broken socket is not actionable.
		}
	}

	/**
	 * @return diagnostic counters for the control server
	 */
	public Map<String, Object> diagnostics() {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("running", running.get());
		out.put("host", settings.host());
		out.put("port", boundPort);
		out.put("allowRemote", settings.allowRemote());
		out.put("secretConfigured", settings.hasSecret());
		out.put("acceptedConnections", accepted.get());
		out.put("rejectedConnections", rejected.get());
		out.put("requests", requestCount.get());
		out.put("peers", peers.size());
		if (lastError != null) {
			out.put("lastError", lastError);
		}
		return Map.copyOf(out);
	}
}
