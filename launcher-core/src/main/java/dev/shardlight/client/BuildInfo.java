package dev.shardlight.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Launcher name and version, plus the values a distributor sets when building a release (the Microsoft
 * application id, an optional CurseForge API key, the update manifest URL and its public key). In a plain
 * developer build those are empty and the features that need them say so.
 */
public final class BuildInfo {
    public static final String NAME = "Shardlight Client";
    /** brand passed to Minecraft as launcher_name */
    public static final String LAUNCHER_BRAND = "shardlight-client";
    private static final Properties PROPS = load();
    public static final String VERSION = value("version").orElse("0.0.0-dev");
    /** contact in the User-Agent, as Modrinth's API rules ask */
    public static final String CONTACT = "github.com/Janxirus/Shardlight_Client";

    private BuildInfo() {
    }

    public static String userAgent() {
        return "Janxirus/ShardlightClient/" + VERSION + " (" + CONTACT + ")";
    }

    /** the Azure application (client) id baked into this build, if any */
    public static Optional<String> microsoftClientId() {
        return value("msaClientId");
    }

    /** the Discord application id baked into this build, if any (it is public) */
    public static Optional<String> discordApplicationId() {
        return value("discordApplicationId");
    }

    /** a CurseForge API key baked into this build, if any (never logged) */
    public static Optional<String> curseForgeApiKey() {
        return value("curseForgeApiKey");
    }

    public static Optional<String> updateManifestUrl() {
        return value("updateManifestUrl");
    }

    /** base64 X.509 Ed25519 public key that signs the update manifest */
    public static Optional<String> updatePublicKey() {
        return value("updatePublicKey");
    }

    private static Optional<String> value(String key) {
        String v = PROPS.getProperty(key, "").trim();
        return v.isEmpty() || v.contains("${") ? Optional.empty() : Optional.of(v);
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/shardlight-client-build.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // a developer build without the generated file
        }
        return p;
    }
}
