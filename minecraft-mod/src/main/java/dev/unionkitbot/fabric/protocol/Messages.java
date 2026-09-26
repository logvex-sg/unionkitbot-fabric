package dev.unionkitbot.fabric.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.core.task.AgentTask;
import dev.unionkitbot.fabric.core.util.Json;
import dev.unionkitbot.fabric.diag.DiagnosticsLog;

/**
 * Canonical payload shapes for protocol frames.
 *
 * <p>Centralising construction keeps the mod and the Discord bot from drifting:
 * every payload shape exists in exactly one place per side, and both are covered
 * by the shared protocol fixtures.
 */
public final class Messages {
	private Messages() {
	}

	/**
	 * Builds the error payload used by both {@code response.error} and {@code error}.
	 *
	 * @param code a stable machine readable code
	 * @param message a human readable explanation
	 * @param path the offending JSON path, may be {@code null}
	 * @param retryable whether retrying the same request could succeed
	 * @return the payload
	 */
	public static JsonObject error(String code, String message, String path, boolean retryable) {
		JsonObject out = new JsonObject();
		out.addProperty("code", code == null ? "internal_error" : code);
		out.addProperty("message", message == null ? "unspecified failure" : message);
		if (path != null && !path.isBlank()) {
			out.addProperty("path", path);
		}
		out.addProperty("retryable", retryable);
		return out;
	}

	/**
	 * Converts a task snapshot into its wire representation.
	 *
	 * @param snapshot the task snapshot
	 * @return the payload
	 */
	public static JsonObject task(AgentTask.Snapshot snapshot) {
		JsonObject out = new JsonObject();
		out.addProperty("id", snapshot.id());
		out.addProperty("kind", snapshot.kind());
		out.addProperty("priority", snapshot.priority());
		out.addProperty("priorityWeight", snapshot.priorityWeight());
		out.addProperty("status", snapshot.status());
		if (snapshot.owner() != null) {
			out.addProperty("owner", snapshot.owner());
		}
		if (snapshot.failureReason() != null) {
			out.addProperty("failureReason", snapshot.failureReason());
		}
		out.addProperty("attempts", snapshot.attempts());
		out.addProperty("submittedAt", snapshot.submittedAt());
		out.addProperty("startedAt", snapshot.startedAt());
		out.addProperty("finishedAt", snapshot.finishedAt());
		out.addProperty("progress", snapshot.progress());
		out.add("parameters", Json.parse(Json.write(snapshot.parameters()), "parameters"));
		JsonArray notes = new JsonArray();
		snapshot.notes().forEach(notes::add);
		out.add("notes", notes);
		return out;
	}

	/**
	 * Converts a log entry into its wire representation.
	 *
	 * @param entry the log entry
	 * @return the payload
	 */
	public static JsonObject logEntry(DiagnosticsLog.Entry entry) {
		JsonObject out = new JsonObject();
		out.addProperty("sequence", entry.sequence());
		out.addProperty("timestamp", entry.timestamp());
		out.addProperty("level", entry.level().name());
		out.addProperty("category", entry.category());
		out.addProperty("message", entry.message());
		if (entry.cause() != null) {
			out.addProperty("cause", entry.cause());
		}
		return out;
	}

	/**
	 * Converts a structured error into its wire representation.
	 *
	 * @param error the error entry
	 * @return the payload
	 */
	public static JsonObject errorEntry(DiagnosticsLog.ErrorEntry error) {
		JsonObject out = new JsonObject();
		out.addProperty("timestamp", error.timestamp());
		out.addProperty("category", error.category());
		out.addProperty("message", error.message());
		if (error.cause() != null) {
			out.addProperty("cause", error.cause());
		}
		return out;
	}

	/**
	 * Builds a map from alternating key and value pairs, rejecting {@code null} keys.
	 *
	 * @param pairs alternating keys and values
	 * @return an ordered, immutable map
	 */
	public static Map<String, Object> map(Object... pairs) {
		if (pairs == null || pairs.length % 2 != 0) {
			throw new IllegalArgumentException("expected an even number of key/value arguments");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			if (!(pairs[i] instanceof String key) || key.isBlank()) {
				throw new IllegalArgumentException("key at index " + i + " must be a non-blank string");
			}
			out.put(key, pairs[i + 1]);
		}
		return Map.copyOf(out);
	}
}
