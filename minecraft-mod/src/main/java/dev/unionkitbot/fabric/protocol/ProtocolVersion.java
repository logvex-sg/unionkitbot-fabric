package dev.unionkitbot.fabric.protocol;

import java.util.List;

import com.google.gson.JsonObject;

/**
 * Version metadata for the control protocol.
 *
 * <p>The protocol is versioned on every frame ({@code v}). A peer whose version
 * falls outside {@link #SUPPORTED_VERSIONS} is rejected during the handshake with a
 * structured error rather than being allowed to send frames the other side cannot
 * interpret.
 */
public final class ProtocolVersion {
	/** Version emitted by this build. */
	public static final int CURRENT = 1;

	/** Versions this build can talk to. */
	public static final List<Integer> SUPPORTED_VERSIONS = List.of(1);

	/** Protocol name, used to reject unrelated WebSocket clients early. */
	public static final String NAME = "unionkitbot-control";

	/** Header carrying the protocol version on HTTP requests. */
	public static final String VERSION_HEADER = "X-UnionKitBot-Protocol";

	/** Header carrying the pre-shared secret on HTTP requests. */
	public static final String AUTH_HEADER = "X-UnionKitBot-Secret";

	/** Query parameter carrying the pre-shared secret on the WebSocket handshake. */
	public static final String AUTH_QUERY_PARAM = "secret";

	/** Sub-protocol negotiated on the WebSocket handshake. */
	public static final String WS_SUBPROTOCOL = "unionkitbot.v1";

	private ProtocolVersion() {
	}

	/**
	 * @param version the candidate version
	 * @return {@code true} when this build supports the version
	 */
	public static boolean isSupported(Integer version) {
		return version != null && SUPPORTED_VERSIONS.contains(version);
	}

	/**
	 * Builds the handshake metadata block shared by both directions.
	 *
	 * @param peerName a human readable peer name
	 * @param peerVersion the peer's own version string
	 * @return the metadata object
	 */
	public static JsonObject metadata(String peerName, String peerVersion) {
		JsonObject out = new JsonObject();
		out.addProperty("protocol", NAME);
		out.addProperty("protocolVersion", CURRENT);
		out.addProperty("supportedProtocolVersions", SUPPORTED_VERSIONS.toString());
		out.addProperty("peerName", peerName == null ? "unknown" : peerName);
		out.addProperty("peerVersion", peerVersion == null ? "unknown" : peerVersion);
		return out;
	}
}
