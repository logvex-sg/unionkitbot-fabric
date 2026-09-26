package dev.unionkitbot.fabric.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for the authentication helpers that gate the control API.
 *
 * <p>The important properties are: a missing or wrong secret never authenticates, the
 * comparison does not leak length through early exit, and the mask used in logs never
 * reveals the secret.
 */
class AuthTokensTest {

	@Test
	void acceptsAnExactMatch() {
		assertTrue(AuthTokens.matches("correct-horse-battery", "correct-horse-battery"));
	}

	@Test
	void rejectsWrongOrMissingSecrets() {
		assertFalse(AuthTokens.matches("correct-horse-battery", "wrong"));
		assertFalse(AuthTokens.matches("correct-horse-battery", "correct-horse-batter"));
		assertFalse(AuthTokens.matches("correct-horse-battery", "correct-horse-batteryX"));
		assertFalse(AuthTokens.matches("correct-horse-battery", ""));
		assertFalse(AuthTokens.matches("correct-horse-battery", null));
	}

	@Test
	void rejectsEverythingWhenNoSecretIsConfigured() {
		assertFalse(AuthTokens.matches(null, "anything"));
		assertFalse(AuthTokens.matches("", "anything"));
		assertFalse(AuthTokens.matches("   ", "   "), "a blank secret must not authenticate");
		assertFalse(AuthTokens.matches("secret", null));
	}

	@Test
	void isCaseSensitive() {
		assertFalse(AuthTokens.matches("Secret", "secret"));
		assertFalse(AuthTokens.matches("SECRET", "secret"));
	}

	@Test
	void handlesSecretsOfDifferentLengthsWithoutThrowing() {
		assertFalse(AuthTokens.matches("a", "a".repeat(500)));
		assertFalse(AuthTokens.matches("a".repeat(500), "a"));
	}

	@Test
	void parsesBearerTokens() {
		assertEquals(Optional.of("abc123"), AuthTokens.bearerToken("Bearer abc123"));
		assertEquals(Optional.of("abc123"), AuthTokens.bearerToken("bearer abc123"));
		assertEquals(Optional.of("abc123"), AuthTokens.bearerToken("  Bearer   abc123  "));
	}

	@Test
	void rejectsMalformedAuthorizationHeaders() {
		assertTrue(AuthTokens.bearerToken(null).isEmpty());
		assertTrue(AuthTokens.bearerToken("").isEmpty());
		assertTrue(AuthTokens.bearerToken("Basic abc123").isEmpty());
		assertTrue(AuthTokens.bearerToken("Bearer").isEmpty());
		assertTrue(AuthTokens.bearerToken("Bearer   ").isEmpty());
	}

	@Test
	void masksSecretsForLogging() {
		String secret = "super-secret-value-1234";
		String masked = AuthTokens.mask(secret);
		assertFalse(masked.contains(secret));
		assertFalse(masked.contains("super-secret"));
		assertNotEquals(secret, masked);
	}

	@Test
	void masksShortSecretsWithoutRevealingThem() {
		String masked = AuthTokens.mask("abc");
		assertFalse(masked.contains("abc"));
		assertTrue(masked.contains("*"));
	}

	@Test
	void masksMissingSecrets() {
		assertTrue(AuthTokens.mask(null).contains("none"));
		assertTrue(AuthTokens.mask("").contains("none"));
	}

	@Test
	void secretsWithWhitespaceAreRejectedRatherThanTrimmed() {
		// Trimming a secret silently would weaken it; the caller must be explicit.
		assertFalse(AuthTokens.matches("secret", " secret "));
	}

	@Test
	void queryParametersAreParsed() {
		Map<String, String> query = dev.unionkitbot.fabric.net.http.HttpRequest.parseQuery("a=1&b=two&c=");
		assertEquals("1", query.get("a"));
		assertEquals("two", query.get("b"));
		assertEquals("", query.get("c"));
		assertTrue(query.size() >= 3);
	}

	@Test
	void emptyQueryParsesToAnEmptyMap() {
		assertTrue(dev.unionkitbot.fabric.net.http.HttpRequest.parseQuery("").isEmpty());
		assertTrue(dev.unionkitbot.fabric.net.http.HttpRequest.parseQuery(null).isEmpty());
	}

	@Test
	void httpRequestHeaderLookupIsCaseInsensitive() {
		dev.unionkitbot.fabric.net.http.HttpRequest request = new dev.unionkitbot.fabric.net.http.HttpRequest(
				"GET", "/ws", Map.of(), Map.of("sec-websocket-key", "abc"), "");
		assertEquals(Optional.of("abc"), request.header("Sec-WebSocket-Key"));
		assertEquals(Optional.of("abc"), request.header("SEC-WEBSOCKET-KEY"));
		assertTrue(request.header("missing").isEmpty());
		assertTrue(request.header(null).isEmpty());
		assertTrue(request.param(null).isEmpty());
	}

	@Test
	void httpRequestNormalisesNullFields() {
		dev.unionkitbot.fabric.net.http.HttpRequest request = new dev.unionkitbot.fabric.net.http.HttpRequest(
				null, "/ws", null, null, null);
		assertTrue(request.query().isEmpty());
		assertTrue(request.headers().isEmpty());
		assertEquals("", request.body());
	}
}
