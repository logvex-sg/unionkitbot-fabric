package dev.unionkitbot.fabric.net.ws;

import java.util.Locale;
import java.util.Optional;

import dev.unionkitbot.fabric.net.AuthTokens;
import dev.unionkitbot.fabric.net.http.HttpRequest;
import dev.unionkitbot.fabric.protocol.ProtocolVersion;

/**
 * Validates an RFC 6455 upgrade request before a session is created.
 *
 * <p>Three things are checked, in this order: the request really is a WebSocket
 * upgrade, the protocol version matches this build, and the caller knows the
 * pre-shared secret. Authentication happens before the socket is handed to
 * {@link WebSocketSession}, so an unauthenticated client never reaches the frame
 * reader.
 */
public final class WebSocketHandshake {
	private static final String WEBSOCKET_UUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private final String clientKey;
	private final String peerName;
	private final int protocolVersion;

	private WebSocketHandshake(String clientKey, String peerName, int protocolVersion) {
		this.clientKey = clientKey;
		this.peerName = peerName;
		this.protocolVersion = protocolVersion;
	}

	/**
	 * Raised when an upgrade request must be refused.
	 */
	public static final class RejectedException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private final int status;

		/**
		 * @param status the HTTP status to report
		 * @param message the reason
		 */
		public RejectedException(int status, String message) {
			super(message);
			this.status = status;
		}

		/**
		 * @return the HTTP status to report
		 */
		public int status() {
			return status;
		}
	}

	/**
	 * @return the {@code Sec-WebSocket-Key} presented by the client
	 */
	public String clientKey() {
		return clientKey;
	}

	/**
	 * @return the peer name supplied during the upgrade, or {@code "unknown"}
	 */
	public String peerName() {
		return peerName;
	}

	/**
	 * @return the protocol version supplied during the upgrade, or {@code 0}
	 */
	public int protocolVersion() {
		return protocolVersion;
	}

	/**
	 * Validates an upgrade request.
	 *
	 * @param request the parsed request
	 * @param expectedSecret the configured pre-shared secret
	 * @param allowRemote whether non-loopback callers are accepted
	 * @param allowedOrigins origins accepted by the handshake, empty means any
	 * @return the validated handshake
	 * @throws RejectedException when the request must be refused
	 */
	public static WebSocketHandshake validate(HttpRequest request, String expectedSecret, boolean allowRemote,
			java.util.List<String> allowedOrigins) {
		if (request == null) {
			throw new RejectedException(400, "missing request");
		}
		if (!"GET".equals(request.method())) {
			throw new RejectedException(405, "the WebSocket endpoint only accepts GET");
		}
		String upgrade = request.header("upgrade").orElse("");
		if (!"websocket".equalsIgnoreCase(upgrade.trim())) {
			throw new RejectedException(400, "missing Upgrade: websocket");
		}
		String connection = request.header("connection").orElse("");
		if (!connection.toLowerCase(Locale.ROOT).contains("upgrade")) {
			throw new RejectedException(400, "missing Connection: Upgrade");
		}
		String key = request.header("sec-websocket-key").orElse(null);
		if (key == null || key.isBlank()) {
			throw new RejectedException(400, "missing Sec-WebSocket-Key");
		}
		String version = request.header("sec-websocket-version").orElse("");
		if (!"13".equals(version.trim())) {
			throw new RejectedException(426, "only WebSocket version 13 is supported");
		}
		if (!allowRemote && !isLoopback(request)) {
			throw new RejectedException(403, "remote clients are disabled; set api.allowRemote to enable them");
		}
		checkOrigin(request, allowedOrigins);
		authenticate(request, expectedSecret);
		int protocol = request.header(ProtocolVersion.VERSION_HEADER)
				.map(WebSocketHandshake::parseProtocolVersion)
				.orElse(ProtocolVersion.CURRENT);
		if (!ProtocolVersion.isSupported(protocol)) {
			throw new RejectedException(426, "unsupported protocol version " + protocol);
		}
		String peerName = request.param("peer").or(() -> request.header("x-unionkitbot-peer")).orElse("unknown");
		return new WebSocketHandshake(key, peerName, protocol);
	}

	private static int parseProtocolVersion(String raw) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static void checkOrigin(HttpRequest request, java.util.List<String> allowedOrigins) {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			// No allowlist configured: any origin is accepted, but the secret is still
			// required, which is what actually gates access.
			return;
		}
		Optional<String> origin = request.header("origin");
		if (origin.isEmpty()) {
			return;
		}
		for (String allowed : allowedOrigins) {
			if (allowed.equalsIgnoreCase(origin.get())) {
				return;
			}
		}
		throw new RejectedException(403, "origin not allowed");
	}

	private static void authenticate(HttpRequest request, String expectedSecret) {
		String presented = request.header(ProtocolVersion.AUTH_HEADER)
				.or(() -> request.param(ProtocolVersion.AUTH_QUERY_PARAM))
				.or(() -> AuthTokens.bearerToken(request.header("authorization").orElse(null)))
				.orElse(null);
		if (!AuthTokens.matches(expectedSecret, presented)) {
			throw new RejectedException(401, "invalid or missing API secret");
		}
	}

	/**
	 * @param request the parsed request
	 * @return {@code true} when the request originates from the local machine
	 */
	public static boolean isLoopback(HttpRequest request) {
		String forwarded = request.header("x-forwarded-for").orElse(null);
		if (forwarded != null && !forwarded.isBlank()) {
			// A proxy is in front of us; the real client is not necessarily local.
			return false;
		}
		String host = request.header("host").orElse("");
		if (host.isBlank()) {
			return false;
		}
		String hostPart = host;
		if (host.startsWith("[")) {
			int end = host.indexOf(']');
			hostPart = end > 0 ? host.substring(1, end) : host;
		} else {
			int colon = host.lastIndexOf(':');
			if (colon > 0) {
				hostPart = host.substring(0, colon);
			}
		}
		return "127.0.0.1".equals(hostPart) || "localhost".equalsIgnoreCase(hostPart) || "::1".equals(hostPart)
				|| "0:0:0:0:0:0:0:1".equals(hostPart);
	}

	/**
	 * @return the WebSocket GUID, exposed for tests
	 */
	public static String guid() {
		return WEBSOCKET_UUID;
	}
}
