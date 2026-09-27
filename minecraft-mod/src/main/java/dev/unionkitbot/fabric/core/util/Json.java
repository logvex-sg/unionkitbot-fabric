package dev.unionkitbot.fabric.core.util;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Small, strict JSON helper built on Gson.
 *
 * <p>Every parse failure is converted into a {@link JsonProblem} carrying a path
 * and a reason, so the protocol layer can report a structured error to Discord
 * instead of an opaque stack trace.
 */
public final class Json {
	private static final Gson GSON = new GsonBuilder()
			.disableHtmlEscaping()
			.serializeNulls()
			.create();

	private Json() {
	}

	/**
	 * Raised when a payload cannot be parsed or fails validation.
	 */
	public static class JsonProblem extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private final String path;

		/**
		 * @param path the JSON path where the problem was detected
		 * @param message the reason
		 */
		public JsonProblem(String path, String message) {
			super(message);
			this.path = path;
		}

		/**
		 * @return the JSON path where the problem was detected
		 */
		public String path() {
			return path;
		}
	}

	/**
	 * Raised when a payload is syntactically valid JSON but semantically invalid,
	 * for example when a value falls outside its permitted range.
	 */
	public static final class ValidationFailure extends JsonProblem {
		private static final long serialVersionUID = 1L;

		/**
		 * @param path the JSON path where the problem was detected
		 * @param message the reason
		 */
		public ValidationFailure(String path, String message) {
			super(path, message);
		}
	}

	/**
	 * Serialises a value to compact JSON.
	 *
	 * @param value the value to serialise
	 * @return the JSON text
	 */
	public static String write(Object value) {
		return GSON.toJson(value);
	}

	/**
	 * Serialises a value to indented JSON.
	 *
	 * @param value the value to serialise
	 * @return the JSON text
	 */
	public static String writePretty(Object value) {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(value);
	}

	/**
	 * Parses text into a JSON object, allowing {@code //} and {@code /* *}{@code /}
	 * comments.
	 *
	 * <p>Used for the configuration document only. The protocol layer keeps the
	 * strict {@link #parseObject(String, String)} so a malformed frame is still
	 * reported rather than quietly repaired.
	 *
	 * @param text the JSON text, optionally containing comments
	 * @param path the path to report on failure
	 * @return the parsed object
	 * @throws JsonProblem when the text is not a JSON object
	 */
	public static JsonObject parseObjectWithComments(String text, String path) {
		return parseObject(stripComments(text), path);
	}

	/**
	 * Removes {@code //} and {@code /* *}{@code /} comments from JSON text.
	 *
	 * <p>Comments are a convenience for a file people are expected to edit by hand.
	 * Gson's lenient mode does not handle them, and rather than enable a mode that
	 * would also accept malformed documents, they are removed here. Comment
	 * sequences inside string literals are preserved, so a value such as a URL is
	 * not truncated.
	 *
	 * @param text the text to process
	 * @return the text with comments replaced by whitespace
	 */
	public static String stripComments(String text) {
		if (text == null || text.isEmpty()) {
			return text == null ? "" : text;
		}
		StringBuilder out = new StringBuilder(text.length());
		boolean inString = false;
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char current = text.charAt(i);
			char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';

			if (inString) {
				out.append(current);
				if (escaped) {
					escaped = false;
				} else if (current == '\\') {
					escaped = true;
				} else if (current == '"') {
					inString = false;
				}
				continue;
			}

			if (current == '"') {
				inString = true;
				out.append(current);
				continue;
			}
			if (current == '/' && next == '/') {
				while (i < text.length() && text.charAt(i) != '\n') {
					i++;
				}
				out.append('\n');
				continue;
			}
			if (current == '/' && next == '*') {
				i += 2;
				while (i < text.length() && !(text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/')) {
					// Newlines are kept so a parse error still points at a sane line.
					if (text.charAt(i) == '\n') {
						out.append('\n');
					}
					i++;
				}
				i++;
				continue;
			}
			out.append(current);
		}
		return out.toString();
	}

	/**
	 * Parses text into a JSON object.
	 *
	 * @param text the JSON text
	 * @param path the path to report on failure
	 * @return the parsed object
	 * @throws JsonProblem when the text is not a JSON object
	 */
	public static JsonObject parseObject(String text, String path) {
		JsonElement element = parse(text, path);
		if (!element.isJsonObject()) {
			throw new JsonProblem(path, "expected a JSON object");
		}
		return element.getAsJsonObject();
	}

	/**
	 * Parses arbitrary JSON text.
	 *
	 * @param text the JSON text
	 * @param path the path to report on failure
	 * @return the parsed element
	 * @throws JsonProblem when the text is not valid JSON
	 */
	public static JsonElement parse(String text, String path) {
		if (text == null || text.isBlank()) {
			throw new JsonProblem(path, "empty payload");
		}
		try {
			return JsonParser.parseString(text);
		} catch (JsonParseException e) {
			throw new JsonProblem(path, "malformed JSON: " + e.getMessage());
		}
	}

	/**
	 * Requires a non-blank string member.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @return the string value
	 * @throws JsonProblem when the member is missing or not a string
	 */
	public static String requireString(JsonObject object, String member) {
		JsonElement element = require(object, member);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new JsonProblem(member, "expected a string");
		}
		String value = element.getAsString();
		if (value.isBlank()) {
			throw new JsonProblem(member, "must not be blank");
		}
		return value;
	}

	/**
	 * Requires a member to be present and non-null.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @return the member value
	 * @throws JsonProblem when the member is missing or null
	 */
	public static JsonElement require(JsonObject object, String member) {
		if (object == null) {
			throw new JsonProblem(member, "missing containing object");
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull()) {
			throw new JsonProblem(member, "required member is missing");
		}
		return element;
	}

	/**
	 * Requires a finite number member.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @return the numeric value
	 * @throws JsonProblem when the member is missing or not a finite number
	 */
	public static double requireDouble(JsonObject object, String member) {
		JsonElement element = require(object, member);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new JsonProblem(member, "expected a number");
		}
		double value = element.getAsDouble();
		if (!Double.isFinite(value)) {
			throw new JsonProblem(member, "expected a finite number");
		}
		return value;
	}

	/**
	 * Reads an optional string member.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @return the value when present and non-blank
	 */
	public static Optional<String> optString(JsonObject object, String member) {
		if (object == null) {
			return Optional.empty();
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return Optional.empty();
		}
		String value = element.getAsString();
		return value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	/**
	 * Reads an optional boolean member.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @param fallback the value to use when the member is absent or invalid
	 * @return the resolved boolean
	 */
	public static boolean optBoolean(JsonObject object, String member, boolean fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isString()) {
			return Boolean.parseBoolean(primitive.getAsString());
		}
		return fallback;
	}

	/**
	 * Reads an optional integral member.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @param fallback the value to use when the member is absent or invalid
	 * @return the resolved integer
	 */
	public static int optInt(JsonObject object, String member, int fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isNumber()) {
			return primitive.getAsInt();
		}
		if (primitive.isString()) {
			try {
				return Integer.parseInt(primitive.getAsString().trim());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	/**
	 * Reads an optional integral member.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @param fallback the value to use when the member is absent or invalid
	 * @return the resolved long
	 */
	public static long optLong(JsonObject object, String member, long fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isNumber()) {
			return primitive.getAsLong();
		}
		if (primitive.isString()) {
			try {
				return Long.parseLong(primitive.getAsString().trim());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	/**
	 * Reads an optional double member.
	 *
	 * @param object the containing object
	 * @param member the member name
	 * @param fallback the value to use when the member is absent or invalid
	 * @return the resolved double
	 */
	public static double optDouble(JsonObject object, String member, double fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(member);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isNumber()) {
			double value = primitive.getAsDouble();
			return Double.isFinite(value) ? value : fallback;
		}
		if (primitive.isString()) {
			try {
				double value = Double.parseDouble(primitive.getAsString().trim());
				return Double.isFinite(value) ? value : fallback;
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	/**
	 * Converts a JSON object into a plain map, preserving nested structures.
	 *
	 * @param object the source object, may be {@code null}
	 * @return an immutable map
	 */
	public static Map<String, Object> toMap(JsonObject object) {
		if (object == null || object.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			out.put(entry.getKey(), toValue(entry.getValue()));
		}
		return Map.copyOf(out);
	}

	private static Object toValue(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				return primitive.getAsBoolean();
			}
			if (primitive.isNumber()) {
				double value = primitive.getAsDouble();
				if (value == Math.rint(value) && Math.abs(value) < 9.007199254740992E15) {
					return primitive.getAsLong();
				}
				return value;
			}
			return primitive.getAsString();
		}
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			List<Object> values = new java.util.ArrayList<>(array.size());
			for (JsonElement child : array) {
				values.add(toValue(child));
			}
			return List.copyOf(values);
		}
		return toMap(element.getAsJsonObject());
	}
}
