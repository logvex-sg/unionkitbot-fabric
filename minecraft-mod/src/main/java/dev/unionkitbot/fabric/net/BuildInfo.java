package dev.unionkitbot.fabric.net;

import dev.unionkitbot.fabric.protocol.ProtocolVersion;

/**
 * Compile-time build information reported to peers.
 *
 * <p>{@link #MOD_ID} and {@link #NAME} are duplicated from
 * {@code fabric.mod.json} because the mod's own build metadata is not available as
 * a runtime constant. {@link #version()} is injected by the Gradle build through a
 * generated properties file, so the value always matches the built artifact.
 */
public final class BuildInfo {
	/** The mod identifier, matching {@code fabric.mod.json}. */
	public static final String MOD_ID = "unionkitbot";

	/** The human readable mod name. */
	public static final String NAME = "UnionKitBot Fabric";

	/** Environment variable that can override the reported version, for debugging. */
	public static final String VERSION_ENV = "UNIONKITBOT_VERSION";

	private static final String FALLBACK_VERSION = "development";

	private static final String RESOLVED_VERSION = resolve();

	private BuildInfo() {
	}

	private static String resolve() {
		String fromEnv = System.getenv(VERSION_ENV);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv.trim();
		}
		try (java.io.InputStream stream = BuildInfo.class
				.getResourceAsStream("/unionkitbot-version.properties")) {
			if (stream == null) {
				return FALLBACK_VERSION;
			}
			java.util.Properties properties = new java.util.Properties();
			properties.load(stream);
			String version = properties.getProperty("version");
			return version == null || version.isBlank() ? FALLBACK_VERSION : version.trim();
		} catch (java.io.IOException e) {
			// A missing or unreadable version resource must not break startup.
			return FALLBACK_VERSION;
		}
	}

	/**
	 * @return the mod version reported in handshakes and status frames
	 */
	public static String version() {
		return RESOLVED_VERSION;
	}

	/**
	 * @return the protocol version this build speaks
	 */
	public static int protocolVersion() {
		return ProtocolVersion.CURRENT;
	}
}
