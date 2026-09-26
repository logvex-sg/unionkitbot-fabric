package dev.unionkitbot.fabric.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.core.util.Json;

/**
 * Loads, stores and observes {@link AgentConfig}.
 *
 * <p>The API secret is intentionally excluded from the on-disk document. Operators
 * provide it through {@code config/unionkitbot.secret} or the
 * {@code UNIONKITBOT_API_SECRET} environment variable, which keeps it out of any
 * file that might be shared when reporting a bug.
 */
public final class ConfigManager {
	/** File name of the JSON configuration document. */
	public static final String CONFIG_FILE = "unionkitbot.json";

	/** File name holding the API secret. */
	public static final String SECRET_FILE = "unionkitbot.secret";

	/** Environment variable that overrides the secret file. */
	public static final String SECRET_ENV = "UNIONKITBOT_API_SECRET";

	private final Path directory;
	private final AtomicReference<AgentConfig> current = new AtomicReference<>(AgentConfig.defaults());
	private final java.util.List<Consumer<AgentConfig>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

	private String lastLoadError;

	/**
	 * @param directory the directory that holds the configuration files
	 */
	public ConfigManager(Path directory) {
		this.directory = Objects.requireNonNull(directory, "directory");
	}

	/**
	 * @return the directory holding the configuration files
	 */
	public Path directory() {
		return directory;
	}

	/**
	 * @return the active configuration, never {@code null}
	 */
	public AgentConfig config() {
		return current.get();
	}

	/**
	 * Registers a listener invoked whenever the configuration changes.
	 *
	 * @param listener the listener
	 */
	public void addListener(Consumer<AgentConfig> listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	/**
	 * @return the last load error message, or empty when loading succeeded
	 */
	public Optional<String> lastLoadError() {
		return Optional.ofNullable(lastLoadError);
	}

	/**
	 * Loads the configuration from disk. A missing file yields defaults, which are
	 * written back so operators have a template to edit. A corrupt file is reported
	 * and left untouched so no data is destroyed.
	 *
	 * @return the loaded configuration
	 */
	public AgentConfig load() {
		Path file = directory.resolve(CONFIG_FILE);
		AgentConfig loaded = AgentConfig.defaults();
		if (Files.isRegularFile(file)) {
			try {
				String text = Files.readString(file, StandardCharsets.UTF_8);
				JsonObject object = Json.parseObject(text, CONFIG_FILE);
				loaded = AgentConfig.fromJson(object);
				lastLoadError = null;
			} catch (IOException e) {
				lastLoadError = "cannot read " + file + ": " + e.getMessage();
			} catch (Json.JsonProblem e) {
				lastLoadError = "invalid configuration at " + e.path() + ": " + e.getMessage();
			}
		} else {
			lastLoadError = null;
		}
		loaded = applySecret(loaded);
		current.set(loaded);
		if (!Files.isRegularFile(file)) {
			save();
		}
		notifyListeners(loaded);
		return loaded;
	}

	private AgentConfig applySecret(AgentConfig base) {
		String fromEnv = System.getenv(SECRET_ENV);
		if (fromEnv != null && fromEnv.trim().length() >= AgentConfig.MIN_SECRET_LENGTH) {
			return withApiSecret(base, fromEnv.trim());
		}
		Path secretFile = directory.resolve(SECRET_FILE);
		if (!Files.isRegularFile(secretFile)) {
			return base;
		}
		try {
			String secret = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
			if (secret.length() >= AgentConfig.MIN_SECRET_LENGTH) {
				return withApiSecret(base, secret);
			}
			lastLoadError = "secret in " + secretFile + " is shorter than " + AgentConfig.MIN_SECRET_LENGTH
					+ " characters; it was ignored";
			return base;
		} catch (IOException e) {
			lastLoadError = "cannot read " + secretFile + ": " + e.getMessage();
			return base;
		}
	}

	private static AgentConfig withApiSecret(AgentConfig base, String secret) {
		AgentConfig.ApiConfig api = base.api().withSecret(secret);
		return new AgentConfig(base.enabled(), base.logLevel(), api, base.modules(), base.limits(),
				base.navigation(), base.delivery(), base.scan(), base.recovery());
	}

	/**
	 * Applies a partial configuration update, persists it and notifies listeners.
	 *
	 * @param patch the partial configuration
	 * @return the resulting configuration
	 * @throws Json.JsonProblem when the patch is invalid
	 */
	public AgentConfig update(JsonObject patch) {
		AgentConfig updated = current.get().patched(patch);
		current.set(updated);
		save();
		notifyListeners(updated);
		return updated;
	}

	/**
	 * Replaces the whole configuration.
	 *
	 * @param replacement the new configuration
	 */
	public void replace(AgentConfig replacement) {
		AgentConfig safe = Objects.requireNonNull(replacement, "replacement");
		current.set(safe);
		notifyListeners(safe);
	}

	/**
	 * Writes the current configuration to disk. The secret is never written.
	 *
	 * @return {@code true} when the write succeeded
	 */
	public boolean save() {
		Path file = directory.resolve(CONFIG_FILE);
		Path temp = directory.resolve(CONFIG_FILE + ".tmp");
		try {
			Files.createDirectories(directory);
			String text = Json.writePretty(current.get().toJson());
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailure) {
				// Some filesystems refuse ATOMIC_MOVE; a plain replace is still safe here.
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			lastLoadError = "cannot write " + file + ": " + e.getMessage();
			try {
				Files.deleteIfExists(temp);
			} catch (IOException cleanupFailure) {
				lastLoadError = lastLoadError + "; leftover temp file " + temp + ": " + cleanupFailure.getMessage();
			}
			return false;
		}
	}

	private void notifyListeners(AgentConfig config) {
		for (Consumer<AgentConfig> listener : listeners) {
			try {
				listener.accept(config);
			} catch (RuntimeException ignored) {
				// A misbehaving listener must not abort configuration loading.
				lastLoadError = "configuration listener failed: " + ignored.getClass().getSimpleName();
			}
		}
	}
}
