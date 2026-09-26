package dev.unionkitbot.fabric.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.core.util.Json;
import org.junit.jupiter.api.Test;

/**
 * Tests for envelope encoding, decoding and validation.
 *
 * <p>These cover the rules the control API relies on: a frame is only accepted when
 * its version is supported, its type is known and its size is sane. Anything else is
 * rejected with a {@link Json.JsonProblem} carrying a JSON path, which the dispatcher
 * turns into a structured error rather than a stack trace.
 */
class EnvelopeTest {

	private static final int MAX_FRAME_CHARS = 1_048_576;
	private static final int MAX_FIELD_CHARS = 256;

	@Test
	void roundTripsAnEvent() {
		JsonObject data = new JsonObject();
		data.addProperty("state", "IDLE");
		Envelope decoded = Envelope.decode(Envelope.of(MessageType.STATE_CHANGED, data).encode());

		assertEquals(MessageType.STATE_CHANGED, decoded.type());
		assertEquals(ProtocolVersion.CURRENT, decoded.version());
		assertEquals("IDLE", decoded.data().get("state").getAsString());
		assertNotNull(decoded.id());
		assertTrue(decoded.timestamp() > 0L);
		assertEquals(8, decoded.id().length(), "correlation ids are short and fixed width");
	}

	@Test
	void roundTripsAReplyWithCorrelation() {
		Envelope decoded = Envelope.decode(
				Envelope.reply(MessageType.RESPONSE_OK, "abc-123", new JsonObject()).encode());
		assertEquals(MessageType.RESPONSE_OK, decoded.type());
		assertEquals("abc-123", decoded.correlationId().orElse(null));
	}

	@Test
	void rejectsMalformedJson() {
		assertThrows(Json.JsonProblem.class, () -> Envelope.decode("{not json"));
		assertThrows(Json.JsonProblem.class, () -> Envelope.decode("[]"));
		assertThrows(Json.JsonProblem.class, () -> Envelope.decode(""));
		assertThrows(Json.JsonProblem.class, () -> Envelope.decode("   "));
		assertThrows(Json.JsonProblem.class, () -> Envelope.decode(null));
	}

	@Test
	void rejectsUnknownFrameMembers() {
		Json.JsonProblem failure = assertThrows(Json.JsonProblem.class, () -> Envelope
				.decode("{\"v\":1,\"type\":\"status.get\",\"id\":\"a1\",\"ts\":1,\"data\":{},\"extra\":1}"));
		assertTrue(failure.getMessage().contains("unknown frame member"),
				"unexpected message: " + failure.getMessage());
	}

	@Test
	void rejectsMissingType() {
		Json.JsonProblem failure = assertThrows(Json.JsonProblem.class,
				() -> Envelope.decode("{\"v\":1}"));
		assertEquals("type", failure.path());
	}

	@Test
	void rejectsUnknownType() {
		Json.JsonProblem failure = assertThrows(Json.JsonProblem.class,
				() -> Envelope.decode("{\"v\":1,\"type\":\"do.something.unsafe\"}"));
		assertEquals("type", failure.path());
		assertTrue(failure.getMessage().contains("unknown frame type"));
	}

	@Test
	void rejectsMissingProtocolVersion() {
		Json.JsonProblem failure = assertThrows(Json.JsonProblem.class,
				() -> Envelope.decode("{\"type\":\"session.ping\"}"));
		assertEquals("v", failure.path());
	}

	@Test
	void rejectsUnsupportedProtocolVersion() {
		Json.JsonProblem failure = assertThrows(Json.JsonProblem.class,
				() -> Envelope.decode("{\"v\":99,\"type\":\"session.ping\"}"));
		assertEquals("v", failure.path());
		assertTrue(failure.getMessage().contains("unsupported protocol version"));
	}

	@Test
	void rejectsZeroProtocolVersion() {
		Json.JsonProblem failure = assertThrows(Json.JsonProblem.class,
				() -> Envelope.decode("{\"v\":0,\"type\":\"session.ping\"}"));
		assertEquals("v", failure.path());
	}

	@Test
	void rejectsOversizedFrames() {
		String frame = "{\"v\":1,\"type\":\"session.ping\",\"data\":{\"padding\":\""
				+ "x".repeat(MAX_FRAME_CHARS + 100) + "\"}}";
		Json.JsonProblem failure = assertThrows(Json.JsonProblem.class, () -> Envelope.decode(frame));
		assertTrue(failure.getMessage().contains("exceeds"));
	}

	@Test
	void rejectsNonObjectData() {
		Envelope decoded = Envelope.decode("{\"v\":1,\"type\":\"session.ping\",\"data\":[1,2,3]}");
		assertNotNull(decoded.data(), "non-object data is ignored rather than trusted");
		assertTrue(decoded.data().isEmpty());
	}

	@Test
	void normalisesMissingDataToAnEmptyObject() {
		Envelope decoded = Envelope.decode("{\"v\":1,\"type\":\"session.ping\"}");
		assertNotNull(decoded.data());
		assertTrue(decoded.data().isEmpty());
	}

	@Test
	void directionFlagsPartitionTheTypeSpace() {
		for (MessageType type : MessageType.values()) {
			assertFalse(type.isInbound() && type.isOutbound(),
					type + " must be exactly one of inbound or outbound");
			assertTrue(type.isInbound() || type.isOutbound(),
					type + " must be either inbound or outbound");
		}
	}

	@Test
	void wireNamesAreUniqueAndNamespaced() {
		Set<String> names = new HashSet<>();
		for (MessageType type : MessageType.values()) {
			assertTrue(names.add(type.wireName()), "duplicate wire name " + type.wireName());
			assertTrue(type.wireName().contains("."), "wire name must be namespaced: " + type.wireName());
			assertEquals(type, MessageType.parse(type.wireName()));
		}
	}

	@Test
	void parseIsCaseInsensitiveAndRejectsUnknown() {
		assertEquals(MessageType.STATUS_GET, MessageType.parse("  STATUS.GET  "));
		assertEquals(null, MessageType.parse("something.else"));
		assertEquals(null, MessageType.parse(null));
	}

	@Test
	void requestsAreInboundAndEventsAreOutbound() {
		assertTrue(MessageType.START.isInbound());
		assertTrue(MessageType.STOP.isInbound());
		assertTrue(MessageType.STATUS_GET.isInbound());
		assertTrue(MessageType.HEARTBEAT.isOutbound());
		assertTrue(MessageType.ERROR.isOutbound());
		assertTrue(MessageType.STATE_CHANGED.isOutbound());
	}

	@Test
	void errorPayloadsAreStructured() {
		JsonObject error = Messages.error("unauthorized", "a valid API secret is required", "secret", false);
		assertEquals("unauthorized", error.get("code").getAsString());
		assertEquals("secret", error.get("path").getAsString());
		assertFalse(error.get("retryable").getAsBoolean());
		assertEquals("a valid API secret is required", error.get("message").getAsString());
	}

	@Test
	void errorPayloadsOmitBlankPaths() {
		JsonObject error = Messages.error("internal_error", "boom", "  ", true);
		assertFalse(error.has("path"));
		assertTrue(error.get("retryable").getAsBoolean());
	}

	@Test
	void errorPayloadsFallBackToSafeDefaults() {
		JsonObject error = Messages.error(null, null, null, false);
		assertEquals("internal_error", error.get("code").getAsString());
		assertEquals("unspecified failure", error.get("message").getAsString());
	}

	@Test
	void protocolMetadataReportsVersionAndSupport() {
		JsonObject metadata = ProtocolVersion.metadata("unionkitbot-fabric", "0.1.0");
		assertEquals(ProtocolVersion.CURRENT, metadata.get("protocolVersion").getAsInt());
		assertEquals("0.1.0", metadata.get("peerVersion").getAsString());
		assertEquals(ProtocolVersion.NAME, metadata.get("protocol").getAsString());
		assertTrue(metadata.has("supportedProtocolVersions"));
	}

	@Test
	void protocolVersionSupportMatchesTheDeclaredList() {
		for (int version : ProtocolVersion.SUPPORTED_VERSIONS) {
			assertTrue(ProtocolVersion.isSupported(version));
		}
		assertFalse(ProtocolVersion.isSupported(ProtocolVersion.CURRENT + 1));
		assertFalse(ProtocolVersion.isSupported(-1));
		assertFalse(ProtocolVersion.isSupported(null));
	}

	@Test
	void authenticationHeaderNamesAreNamespaced() {
		assertTrue(ProtocolVersion.AUTH_HEADER.startsWith("X-UnionKitBot"));
		assertTrue(ProtocolVersion.VERSION_HEADER.startsWith("X-UnionKitBot"));
		assertTrue(ProtocolVersion.WS_SUBPROTOCOL.contains("unionkitbot"));
	}

	@Test
	void correlationIdsAreUniquePerFrame() {
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < 500; i++) {
			assertTrue(ids.add(Envelope.newId()), "correlation ids must be unique");
		}
	}

	@Test
	void aFrameCannotClaimToBeBothRequestAndEvent() {
		Envelope decoded = Envelope.decode("{\"v\":1,\"type\":\"status.get\"}");
		assertTrue(decoded.type().isInbound());
		assertFalse(decoded.type().isOutbound());
	}

	@Test
	void constantsUsedByTestsMatchTheImplementation() {
		assertEquals(MAX_FRAME_CHARS, Envelope.MAX_FRAME_CHARS);
		assertTrue(MAX_FIELD_CHARS > 0);
	}
}
