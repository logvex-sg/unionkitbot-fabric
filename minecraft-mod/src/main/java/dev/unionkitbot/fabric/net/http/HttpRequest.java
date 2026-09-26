package dev.unionkitbot.fabric.net.http;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed HTTP request.
 *
 * <p>Header lookup is case insensitive, as required by RFC 9110. The body is a
 * {@code String} because the control protocol is JSON only; the reader enforces a
 * hard size limit before decoding so a large or hostile body cannot exhaust the
 * heap.
 *
 * @param method the request method, upper case
 * @param path the path component, without the query string
 * @param query the decoded query parameters
 * @param headers the request headers, lower cased keys
 * @param body the request body, never {@code null}
 */
public record HttpRequest(String method, String path, Map<String, String> query, Map<String, String> headers,
		String body) {

	public HttpRequest {
		query = query == null ? Map.of() : Map.copyOf(query);
		headers = headers == null ? Map.of() : Map.copyOf(headers);
		body = body == null ? "" : body;
	}

	/**
	 * @param name the header name, case insensitive
	 * @return the header value when present
	 */
	public Optional<String> header(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
	}

	/**
	 * @param name the query parameter name
	 * @return the parameter value when present
	 */
	public Optional<String> param(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(query.get(name));
	}

	/**
	 * Parses the query string of a request target.
	 *
	 * @param rawQuery the raw query string, may be {@code null}
	 * @return the decoded parameters
	 */
	public static Map<String, String> parseQuery(String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return Map.of();
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (String pair : rawQuery.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int equals = pair.indexOf('=');
			String key = equals < 0 ? pair : pair.substring(0, equals);
			String value = equals < 0 ? "" : pair.substring(equals + 1);
			String decodedKey = decode(key);
			if (!decodedKey.isBlank()) {
				out.put(decodedKey, decode(value));
			}
		}
		return Map.copyOf(out);
	}

	private static String decode(String value) {
		try {
			return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			// A malformed escape sequence must not abort request handling.
			return value;
		}
	}
}
