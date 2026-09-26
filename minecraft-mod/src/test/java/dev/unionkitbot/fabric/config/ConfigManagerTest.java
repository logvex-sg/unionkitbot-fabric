package dev.unionkitbot.fabric.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for configuration loading, secret provisioning and persistence.
 *
 * <p>The secret test covers a real regression: the loader used to leave a fresh
 * install without a secret file, so the control server refused to bind and operators
 * had no value to give the Discord bot.
 */
class ConfigManagerTest {

	@Test
	void generatesAndPersistsASecretOnFirstRun(@TempDir Path directory) throws IOException {
		ConfigManager manager = new ConfigManager(directory);
		AgentConfig loaded = manager.load();

		assertTrue(loaded.api().hasSecret(), "a fresh install must end up with a usable secret");

		Path secretFile = directory.resolve(ConfigManager.SECRET_FILE);
		assertTrue(Files.isRegularFile(secretFile), "the generated secret must be written to disk");

		String onDisk = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
		assertEquals(loaded.api().secret(), onDisk, "the persisted secret must match the loaded one");
		assertTrue(onDisk.length() >= AgentConfig.MIN_SECRET_LENGTH);
	}

	@Test
	void generatedSecretsAreUniqueAndHex(@TempDir Path first, @TempDir Path second) {
		String a = new ConfigManager(first).load().api().secret();
		String b = new ConfigManager(second).load().api().secret();

		assertNotEquals(a, b, "two installs must not share a secret");
		assertTrue(a.matches("[0-9a-f]{64}"), "unexpected secret shape: " + a);
	}

	@Test
	void reusesTheExistingSecretAcrossLoads(@TempDir Path directory) {
		String first = new ConfigManager(directory).load().api().secret();
		String second = new ConfigManager(directory).load().api().secret();

		assertEquals(first, second, "reloading must not rotate the secret and break a connected bot");
	}

	@Test
	void replacesAnUnusablyShortSecret(@TempDir Path directory) throws IOException {
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(ConfigManager.SECRET_FILE), "too-short", StandardCharsets.UTF_8);

		AgentConfig loaded = new ConfigManager(directory).load();

		assertTrue(loaded.api().hasSecret());
		assertNotEquals("too-short", loaded.api().secret());
	}

	@Test
	void writesTheJsonDocumentWithoutTheSecret(@TempDir Path directory) throws IOException {
		ConfigManager manager = new ConfigManager(directory);
		manager.load();

		String json = Files.readString(directory.resolve(ConfigManager.CONFIG_FILE), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"port\""), "the document should still be written");
		assertFalse(json.contains(manager.config().api().secret()),
				"the secret must never be written into the shareable JSON document");
	}
}
