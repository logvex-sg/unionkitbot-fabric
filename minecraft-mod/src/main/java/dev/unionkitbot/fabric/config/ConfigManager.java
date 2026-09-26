package dev.unionkitbot.fabric.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import dev.unionkitbot.fabric.core.util.Json;

/**
 * Loads, stores and observes {@link AgentConfig}.
 *
 * <p>The API secret is intentionally excluded from the on-disk JSON document. It is
 * generated on first run and kept in {@code config/unionkitbot.secret}, so the JSON
 * can be shared when reporting a bug without leaking a credential. The
 * {@code UNIONKITBOT_API_SECRET} environment variable overrides the file.
 */
public final class ConfigManager {
	/** File name of the JSON configuration document. */
	public static final String CONFIG_FILE = "unionkitbot.json";

	/** File name holding the API secret. */
	public static final String SECRET_FILE = "unionkitbot.secret";

	/** Environment variable that overrides the secret file. */
	public static final String SECRET_ENV = "UNIONKITBOT_API_SECRET";

	/** Length in bytes of a generated secret; 32 bytes is 256 bits of entropy. */
	private static final int GENERATED_SECRET_BYTES = 32;

	/**
	 * Alphabet for generated secrets. Lowercase hex only: the value has to survive
	 * being copied by hand into a bot's {@code .env}, so it avoids characters that
	 * are easy to misread or that a shell or dotenv parser would treat specially.
	 */
	private static final char[] SECRET_ALPHABET = "0123456789abcdef".toCharArray();

	private static final SecureRandom RANDOM = new SecureRandom();

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
		loaded = resolveSecret(loaded);
		current.set(loaded);
		if (!Files.isRegularFile(file)) {
			save();
		}
		notifyListeners(loaded);
		return loaded;
	}

	/**
	 * Resolves the API secret, generating and persisting one on first run.
	 *
	 * <p>Without this the control API can never bind on a fresh install: the server
	 * refuses to start when no secret is configured, so a secret that was never
	 * written would leave operators with no file to copy and no API to connect to.
	 * The environment variable wins over the file, and the generated value is written
	 * with owner-only permissions where the filesystem supports them.
	 *
	 * @param base the configuration to attach the secret to
	 * @return the configuration with a secret when one could be resolved or created
	 */
	private AgentConfig resolveSecret(AgentConfig base) {
		String fromEnv = System.getenv(SECRET_ENV);
		if (fromEnv != null && fromEnv.trim().length() >= AgentConfig.MIN_SECRET_LENGTH) {
			return withApiSecret(base, fromEnv.trim());
		}
		Path secretFile = directory.resolve(SECRET_FILE);
		if (Files.isRegularFile(secretFile)) {
			try {
				String secret = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
				if (secret.length() >= AgentConfig.MIN_SECRET_LENGTH) {
					return withApiSecret(base, secret);
				}
				lastLoadError = "secret in " + secretFile + " is shorter than "
						+ AgentConfig.MIN_SECRET_LENGTH + " characters; a replacement was generated";
			} catch (IOException e) {
				lastLoadError = "cannot read " + secretFile + ": " + e.getMessage();
				return base;
			}
		}
		return createSecret(base, secretFile);
	}

	/**
	 * Generates a secret, writes it to disk and attaches it to the configuration.
	 *
	 * @param base the configuration to attach the secret to
	 * @param secretFile the destination path
	 * @return the configuration with the generated secret, or {@code base} when the
	 *         file could not be written
	 */
	private AgentConfig createSecret(AgentConfig base, Path secretFile) {
		String generated = generateSecret();
		try {
			Files.createDirectories(directory);
			Files.writeString(secretFile, generated + System.lineSeparator(), StandardCharsets.UTF_8);
			restrictToOwner(secretFile);
		} catch (IOException e) {
			lastLoadError = "cannot write " + secretFile + ": " + e.getMessage() + "; set " + SECRET_ENV
					+ " to enable the control API";
			return base;
		}
		return withApiSecret(base, generated);
	}

	/**
	 * Removes group and other permissions from a secret file where the filesystem
	 * supports POSIX permissions. A failure here is not fatal: the secret is still
	 * usable, and on Windows the permissions model is different anyway.
	 *
	 * @param secretFile the file to restrict
	 */
	private void restrictToOwner(Path secretFile) {
		try {
			Files.setPosixFilePermissions(secretFile, java.util.Set.of(
					java.nio.file.attribute.PosixFilePermission.OWNER_READ,
					java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException | AccessDeniedException e) {
			// Not a POSIX filesystem, or the platform refused the change. The secret is
			// still written; only the hardening step is skipped.
		} catch (IOException e) {
			lastLoadError = "wrote " + secretFile + " but could not restrict its permissions: " + e.getMessage();
		}
	}

	/**
	 * @return a fresh random secret in lowercase hex
	 */
	private static String generateSecret() {
		byte[] bytes = new byte[GENERATED_SECRET_BYTES];
		RANDOM.nextBytes(bytes);
		char[] out = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int value = bytes[i] & 0xFF;
			out[i * 2] = SECRET_ALPHABET[value >>> 4];
			out[i * 2 + 1] = SECRET_ALPHABET[value & 0x0F];
		}
		return new String(out);
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
