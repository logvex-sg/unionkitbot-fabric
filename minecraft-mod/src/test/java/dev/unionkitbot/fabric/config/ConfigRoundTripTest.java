package dev.unionkitbot.fabric.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the on-disk configuration document.
 *
 * <p>These cover a real defect: the saved document carried the redaction
 * placeholder in {@code api.secret}, so every load after the first rejected the
 * whole file with "secret must be at least 16 characters". Editing the file was
 * therefore pointless - settings were read as defaults on the next start, with
 * only a warning to show for it.
 */
class ConfigRoundTripTest {

        @Test
        void theSavedDocumentIsAcceptedOnReload(@TempDir Path directory) {
                ConfigManager first = new ConfigManager(directory);
                String secret = first.load().api().secret();
                assertNotNull(secret);

                ConfigManager second = new ConfigManager(directory);
                second.load();

                assertFalse(second.lastLoadError().isPresent(),
                                "a file this code wrote must load cleanly: "
                                                + second.lastLoadError().orElse(""));
                assertEquals(secret, second.config().api().secret(),
                                "reloading must not rotate the secret");
        }

        @Test
        void editedValuesSurviveAReload(@TempDir Path directory) throws Exception {
                new ConfigManager(directory).load();

                // Edit the file the way an operator would, then restart.
                Path file = directory.resolve(ConfigManager.CONFIG_FILE);
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String edited = text
                                .replace("\"port\": 8765", "\"port\": 9999")
                                .replace("\"enabled\": false", "\"enabled\": true")
                                .replace("\"maxQueueSize\": 128", "\"maxQueueSize\": 512");
                assertFalse(text.equals(edited), "the test edit did not apply; check the template shape");
                Files.writeString(file, edited, StandardCharsets.UTF_8);

                ConfigManager reloaded = new ConfigManager(directory);
                AgentConfig config = reloaded.load();

                assertFalse(reloaded.lastLoadError().isPresent(), reloaded.lastLoadError().orElse(""));
                assertEquals(9999, config.api().port(), "an edited port must survive a reload");
                assertEquals(512, config.limits().maxQueueSize(), "an edited limit must survive a reload");
                assertTrue(config.enabled(), "an edited master switch must survive a reload");
        }

        @Test
        void theSavedDocumentContainsNoSecretPlaceholder(@TempDir Path directory) throws Exception {
                new ConfigManager(directory).load();

                String json = Files.readString(directory.resolve(ConfigManager.CONFIG_FILE), StandardCharsets.UTF_8);
                assertFalse(json.contains(AgentConfig.REDACTED),
                                "the saved document must omit the secret rather than store a placeholder");
                assertFalse(json.contains("\"secret\""),
                                "no secret member belongs in the on-disk document");
        }

        @Test
        void anUnsetHomePointIsWrittenAsAnEmptyObject(@TempDir Path directory) throws Exception {
                new ConfigManager(directory).load();

                String json = Files.readString(directory.resolve(ConfigManager.CONFIG_FILE), StandardCharsets.UTF_8);
                assertFalse(json.contains("\"x\": null"),
                                "explicit nulls in the home point are both noisy and unreadable");
        }

        @Test
        void nullHomeMembersAreTreatedAsUnset(@TempDir Path directory) throws Exception {
                Path file = directory.resolve(ConfigManager.CONFIG_FILE);
                Files.createDirectories(directory);
                Files.writeString(file, """
                                {
                                  "navigation": { "home": { "x": null, "y": null, "z": null } }
                                }
                                """, StandardCharsets.UTF_8);

                ConfigManager manager = new ConfigManager(directory);
                AgentConfig config = manager.load();

                assertTrue(config.navigation().home().isEmpty(),
                                "a home point of nulls means 'not set', not 'malformed'");
                assertFalse(manager.lastLoadError().isPresent(), manager.lastLoadError().orElse(""));
        }

        @Test
        void commentsInTheFileAreAccepted(@TempDir Path directory) throws Exception {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(ConfigManager.CONFIG_FILE), """
                                {
                                  // automation stays off until started
                                  "enabled": true,
                                  /* the API port */
                                  "api": { "port": 9100 },
                                  "limits": { "maxQueueSize": 42 }
                                }
                                """, StandardCharsets.UTF_8);

                ConfigManager manager = new ConfigManager(directory);
                AgentConfig config = manager.load();

                assertFalse(manager.lastLoadError().isPresent(),
                                "comments must not make the file unreadable: " + manager.lastLoadError().orElse(""));
                assertTrue(config.enabled());
                assertEquals(9100, config.api().port());
                assertEquals(42, config.limits().maxQueueSize());
        }

        @Test
        void aCommentMarkerInsideAStringIsPreserved(@TempDir Path directory) throws Exception {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(ConfigManager.CONFIG_FILE), """
                                {
                                  "delivery": { "defaultTargetPlayer": "http://not-a-comment" }
                                }
                                """, StandardCharsets.UTF_8);

                ConfigManager manager = new ConfigManager(directory);
                AgentConfig config = manager.load();

                assertFalse(manager.lastLoadError().isPresent(), manager.lastLoadError().orElse(""));
                assertEquals("http://not-a-comment", config.delivery().defaultTargetPlayer(),
                                "a // inside a string literal is not a comment");
        }

        @Test
        void aFreshInstallGetsTheAnnotatedTemplate(@TempDir Path directory) throws Exception {
                ConfigManager manager = new ConfigManager(directory);
                manager.load();

                String json = Files.readString(directory.resolve(ConfigManager.CONFIG_FILE), StandardCharsets.UTF_8);

                assertTrue(json.contains("//"),
                                "a fresh install should receive comments explaining each option");
                assertFalse(manager.lastLoadError().isPresent(), manager.lastLoadError().orElse(""));
        }

        @Test
        void theBundledTemplateIsValidAndParses(@TempDir Path directory) throws Exception {
                ConfigManager manager = new ConfigManager(directory);
                AgentConfig seeded = manager.load();

                // The template must describe a usable configuration, not just look nice.
                assertTrue(seeded.api().port() > 0);
                assertTrue(seeded.limits().maxQueueSize() > 0);
                assertTrue(seeded.modules().navigation());
        }

        @Test
        void theTemplateIsOnlyWrittenOnce(@TempDir Path directory) throws Exception {
                new ConfigManager(directory).load();
                Path file = directory.resolve(ConfigManager.CONFIG_FILE);
                String first = Files.readString(file, StandardCharsets.UTF_8);

                // Edit a value, as an operator would, then restart.
                Files.writeString(file, first.replace("\"port\": 8765", "\"port\": 8123"), StandardCharsets.UTF_8);

                ConfigManager second = new ConfigManager(directory);
                AgentConfig reloaded = second.load();

                assertEquals(8123, reloaded.api().port(),
                                "an existing file must be read, not replaced by the template");
                assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("8123"));
        }

        @Test
        void anUnreadableFileIsBackedUpRatherThanDiscarded(@TempDir Path directory) throws Exception {
                Path file = directory.resolve(ConfigManager.CONFIG_FILE);
                Files.createDirectories(directory);
                Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

                ConfigManager manager = new ConfigManager(directory);
                manager.load();

                assertTrue(manager.lastLoadError().isPresent(), "a corrupt file must be reported");
                Path backup = directory.resolve(ConfigManager.CONFIG_FILE + ".invalid");
                assertTrue(Files.isRegularFile(backup),
                                "the rejected file must be preserved so the edit is not lost");
                assertTrue(Files.readString(backup, StandardCharsets.UTF_8).contains("this is not json"));
        }

        @Test
        void aLegacyRedactedSecretDoesNotInvalidateTheFile(@TempDir Path directory) throws Exception {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(ConfigManager.CONFIG_FILE), """
                                {
                                  "api": { "host": "127.0.0.1", "port": 8765, "secret": "<redacted>" },
                                  "limits": { "maxQueueSize": 7 }
                                }
                                """, StandardCharsets.UTF_8);

                ConfigManager manager = new ConfigManager(directory);
                AgentConfig config = manager.load();

                assertFalse(manager.lastLoadError().isPresent(),
                                "an already-broken file must self-heal instead of resetting settings: "
                                                + manager.lastLoadError().orElse(""));
                assertEquals(7, config.limits().maxQueueSize(),
                                "the other settings in a legacy file must still be honoured");
                assertTrue(config.api().hasSecret(), "the secret must be resolved from the secret file");
        }
}
