package dev.unionkitbot.fabric.protocol;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.core.util.Json;

/**
 * One protocol frame: a versioned envelope with an explicit type and payload.
 *
 * <p>The envelope is the only structure the transport layer needs to understand.
 * Validation happens in {@link #decode(String)} so both the HTTP and the WebSocket
 * front ends share exactly the same rules.
 *
 * <pre>
 * {
 *   "v": 1,
 *   "type": "tasks.create",
 *   "id": "5f1d2c3b",          // correlation id, optional
 *   "ts": 1735689600000,       // sender timestamp, optional
 *   "data": { }                // type specific payload
 * }
 * </pre>
 */
public record Envelope(int version, MessageType type, String id, long timestamp, JsonObject data) {
	/** Maximum accepted frame size in characters. */
	public static final int MAX_FRAME_CHARS = 1_048_576;

	private static final String FIELD_VERSION = "v";
	private static final String FIELD_TYPE = "type";
	private static final String FIELD_ID = "id";
	private static final String FIELD_TIMESTAMP = "ts";
	private static final String FIELD_DATA = "data";

	/** Every member a frame may carry; anything else is a protocol error. */
	private static final Set<String> FRAME_MEMBERS = Set.of(FIELD_VERSION, FIELD_TYPE, FIELD_ID,
			FIELD_TIMESTAMP, FIELD_DATA);

	/**
	 * Creates an outbound frame with a fresh correlation id.
	 *
	 * @param type the frame type, which must be outbound
	 * @param data the payload, {@code null} becomes an empty object
	 * @return the frame
	 */
	public static Envelope of(MessageType type, JsonObject data) {
		if (type == null || !type.isOutbound()) {
			throw new IllegalArgumentException("type must be an outbound frame type");
		}
		return new Envelope(ProtocolVersion.CURRENT, type, newId(), System.currentTimeMillis(),
				data == null ? new JsonObject() : data);
	}

	/**
	 * Creates a frame that replies to a request.
	 *
	 * @param type the response type, which must be outbound
	 * @param correlationId the request correlation id, may be {@code null}
	 * @param data the payload, {@code null} becomes an empty object
	 * @return the frame
	 */
	public static Envelope reply(MessageType type, String correlationId, JsonObject data) {
		if (type == null || !type.isOutbound()) {
			throw new IllegalArgumentException("type must be an outbound frame type");
		}
		return new Envelope(ProtocolVersion.CURRENT, type, correlationId == null ? newId() : correlationId,
				System.currentTimeMillis(), data == null ? new JsonObject() : data);
	}

	/**
	 * @return a short random correlation id
	 */
	public static String newId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * Decodes and fully validates a frame.
	 *
	 * @param text the raw frame text
	 * @return the decoded envelope
	 * @throws Json.JsonProblem when the frame is malformed, oversized or unsupported
	 */
	public static Envelope decode(String text) {
		if (text == null || text.isBlank()) {
			throw new Json.JsonProblem(FIELD_TYPE, "empty frame");
		}
		if (text.length() > MAX_FRAME_CHARS) {
			throw new Json.JsonProblem(FIELD_TYPE,
					"frame exceeds " + MAX_FRAME_CHARS + " characters (" + text.length() + ")");
		}
		JsonObject object = Json.parseObject(text, "$");
		for (String member : object.keySet()) {
			if (!FRAME_MEMBERS.contains(member)) {
				throw new Json.JsonProblem(member, "unknown frame member");
			}
		}
		int version = Json.optInt(object, FIELD_VERSION, -1);
		if (version < 0) {
			throw new Json.JsonProblem(FIELD_VERSION, "required member is missing");
		}
		if (!ProtocolVersion.isSupported(version)) {
			throw new Json.JsonProblem(FIELD_VERSION,
					"unsupported protocol version " + version + "; this build supports "
							+ ProtocolVersion.SUPPORTED_VERSIONS);
		}
		String rawType = Json.requireString(object, FIELD_TYPE);
		MessageType type = MessageType.parse(rawType);
		if (type == null) {
			throw new Json.JsonProblem(FIELD_TYPE, "unknown frame type '" + rawType + "'");
		}
		String id = Json.optString(object, FIELD_ID).orElse(null);
		// Timestamps are epoch milliseconds, so they must be read as a long: an int read
		// would truncate them and make every frame look like it was sent in 1970.
		long timestamp = Json.optLong(object, FIELD_TIMESTAMP, 0L);
		JsonObject data = object.has(FIELD_DATA) && object.get(FIELD_DATA).isJsonObject()
				? object.getAsJsonObject(FIELD_DATA)
				: new JsonObject();
		return new Envelope(version, type, id, timestamp, data);
	}

	/**
	 * @return the frame as compact JSON text
	 */
	public String encode() {
		JsonObject out = new JsonObject();
		out.addProperty(FIELD_VERSION, version);
		out.addProperty(FIELD_TYPE, type.wireName());
		if (id != null) {
			out.addProperty(FIELD_ID, id);
		}
		out.addProperty(FIELD_TIMESTAMP, timestamp);
		out.add(FIELD_DATA, data == null ? new JsonObject() : data);
		return Json.write(out);
	}

	/**
	 * @return the correlation id when present
	 */
	public Optional<String> correlationId() {
		return Optional.ofNullable(id);
	}
}
