package dev.unionkitbot.fabric.net;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Constant-time pre-shared-secret comparison.
 *
 * <p>The control API authenticates with a single shared secret rather than a
 * username and password. Comparing it with {@link MessageDigest#isEqual} instead of
 * {@link String#equals} removes the timing signal that would otherwise let a local
 * attacker recover the secret byte by byte.
 */
public final class AuthTokens {
	private AuthTokens() {
	}

	/**
	 * Compares a presented secret with the configured one in constant time.
	 *
	 * @param expected the configured secret, may be {@code null} or empty
	 * @param presented the secret supplied by the client, may be {@code null}
	 * @return {@code true} only when a usable secret is configured and matches
	 */
	public static boolean matches(String expected, String presented) {
		if (expected == null || expected.isBlank() || presented == null) {
			return false;
		}
		byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
		byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expectedBytes, presentedBytes);
	}

	/**
	 * Extracts a bearer token from an {@code Authorization} header.
	 *
	 * @param headerValue the header value, may be {@code null}
	 * @return the token when the header is a well-formed bearer credential
	 */
	public static Optional<String> bearerToken(String headerValue) {
		if (headerValue == null) {
			return Optional.empty();
		}
		String trimmed = headerValue.trim();
		if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return Optional.empty();
		}
		String token = trimmed.substring(7).trim();
		return token.isEmpty() ? Optional.empty() : Optional.of(token);
	}

	/**
	 * Masks a secret for logging so it can never be reconstructed from a log line.
	 *
	 * @param secret the secret, may be {@code null}
	 * @return a short masked description
	 */
	public static String mask(String secret) {
		if (secret == null || secret.isEmpty()) {
			return "<none>";
		}
		if (secret.length() <= 4) {
			return "****";
		}
		return "****" + secret.substring(secret.length() - 2);
	}
}
