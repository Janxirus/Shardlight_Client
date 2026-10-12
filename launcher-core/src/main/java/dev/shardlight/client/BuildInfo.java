package dev.shardlight.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Launcher name and version (filled in by the build). */
public final class BuildInfo {
    public static final String NAME = "Shardlight Client";
    /** brand passed to Minecraft as launcher_name */
    public static final String LAUNCHER_BRAND = "shardlight-client";
    public static final String VERSION = load();
    /** contact in the User-Agent, as Modrinth's API rules ask */
    public static final String CONTACT = "github.com/Janxirus/Shardlight_Client";

    private BuildInfo() {
    }

    public static String userAgent() {
        return "Janxirus/ShardlightClient/" + VERSION + " (" + CONTACT + ")";
    }

    private static String load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/shardlight-client-build.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "");
                if (!v.isBlank() && !v.contains("$")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "0.0.0-dev";
    }
}
