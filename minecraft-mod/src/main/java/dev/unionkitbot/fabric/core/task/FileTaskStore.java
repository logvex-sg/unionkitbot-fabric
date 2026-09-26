package dev.unionkitbot.fabric.core.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.core.util.Json;

/**
 * A {@link TaskStore} that keeps the queue in a single JSON file.
 *
 * <p>Persistence is best effort, as the interface requires: a failing write returns
 * {@code false} and records the reason instead of throwing, and a corrupt file is
 * reported and treated as empty rather than destroying the operator's data. Writes go
 * to a temporary file first and are then moved into place, so an interrupted write
 * cannot leave a half-written queue behind.
 */
public final class FileTaskStore implements TaskStore {
	/** Default file name inside the configuration directory. */
	public static final String FILE_NAME = "unionkitbot-tasks.json";

	private final Path file;
	private volatile String lastError;

	/**
	 * @param directory the directory that holds the queue file
	 */
	public FileTaskStore(Path directory) {
		this.file = directory.resolve(FILE_NAME);
	}

	/**
	 * @return the file the queue is stored in
	 */
	public Path file() {
		return file;
	}

	/**
	 * @return the last failure message, or empty when the last operation succeeded
	 */
	public Optional<String> lastError() {
		return Optional.ofNullable(lastError);
	}

	@Override
	public boolean save(List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished) {
		JsonObject root = new JsonObject();
		root.addProperty("version", TaskQueue.STORE_VERSION);
		root.addProperty("savedAt", System.currentTimeMillis());
		JsonArray activeArray = new JsonArray();
		for (AgentTask.Snapshot snapshot : active == null ? List.<AgentTask.Snapshot>of() : active) {
			activeArray.add(encode(snapshot));
		}
		JsonArray finishedArray = new JsonArray();
		for (AgentTask.Snapshot snapshot : finished == null ? List.<AgentTask.Snapshot>of() : finished) {
			finishedArray.add(encode(snapshot));
		}
		root.add("active", activeArray);
		root.add("finished", finishedArray);

		Path temp = file.resolveSibling(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(temp, Json.writePretty(root), StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailure) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			lastError = null;
			return true;
		} catch (IOException e) {
			lastError = "cannot write " + file + ": " + e.getMessage();
			try {
				Files.deleteIfExists(temp);
			} catch (IOException cleanupFailure) {
				lastError = lastError + "; leftover temp file: " + cleanupFailure.getMessage();
			}
			return false;
		}
	}

	@Override
	public Payload load() {
		if (!Files.isRegularFile(file)) {
			lastError = null;
			return Payload.empty();
		}
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject root = Json.parseObject(text, FILE_NAME);
			int version = Json.optInt(root, "version", -1);
			if (version != TaskQueue.STORE_VERSION) {
				lastError = "queue file " + file + " has unsupported version " + version
						+ "; ignoring its contents";
				return Payload.empty();
			}
			List<AgentTask.Snapshot> active = decodeAll(root, "active");
			List<AgentTask.Snapshot> finished = decodeAll(root, "finished");
			lastError = null;
			return new Payload(version, active, finished);
		} catch (IOException e) {
			lastError = "cannot read " + file + ": " + e.getMessage();
			return Payload.empty();
		} catch (Json.JsonProblem e) {
			lastError = "queue file " + file + " is invalid at " + e.path() + ": " + e.getMessage();
			return Payload.empty();
		}
	}

	private static List<AgentTask.Snapshot> decodeAll(JsonObject root, String member) {
		JsonElement element = root.get(member);
		if (element == null || !element.isJsonArray()) {
			return List.of();
		}
		List<AgentTask.Snapshot> out = new ArrayList<>();
		for (JsonElement child : element.getAsJsonArray()) {
			if (!child.isJsonObject()) {
				continue;
			}
			decode(child.getAsJsonObject()).ifPresent(out::add);
		}
		return List.copyOf(out);
	}

	private static JsonObject encode(AgentTask.Snapshot snapshot) {
		JsonObject out = new JsonObject();
		out.addProperty("id", snapshot.id());
		out.addProperty("kind", snapshot.kind());
		out.addProperty("priority", snapshot.priority());
		out.addProperty("status", snapshot.status());
		out.addProperty("attempts", snapshot.attempts());
		out.addProperty("submittedAt", snapshot.submittedAt());
		out.addProperty("progress", snapshot.progress());
		if (snapshot.failureReason() != null) {
			out.addProperty("failureReason", snapshot.failureReason());
		}
		out.add("parameters", Json.parse(Json.write(snapshot.parameters()), "parameters"));
		return out;
	}

	private static Optional<AgentTask.Snapshot> decode(JsonObject object) {
		String id = Json.optString(object, "id").orElse(null);
		String kind = Json.optString(object, "kind").orElse(null);
		if (id == null || kind == null) {
			return Optional.empty();
		}
		String priority = Json.optString(object, "priority").orElse("NORMAL");
		Map<String, Object> parameters = object.has("parameters") && object.get("parameters").isJsonObject()
				? Json.toMap(object.getAsJsonObject("parameters"))
				: Map.of();
		return Optional.of(new AgentTask.Snapshot(
				id,
				kind,
				priority,
				TaskPriority.parse(priority) == null ? TaskPriority.NORMAL.weight() : TaskPriority.parse(priority).weight(),
				Json.optString(object, "status").orElse(TaskStatus.PENDING.name()),
				null,
				Json.optString(object, "failureReason").orElse(null),
				Json.optInt(object, "attempts", 0),
				Json.optInt(object, "submittedAt", 0),
				0L,
				0L,
				Json.optDouble(object, "progress", 0.0d),
				Map.copyOf(new LinkedHashMap<>(parameters)),
				List.of()));
	}
}
