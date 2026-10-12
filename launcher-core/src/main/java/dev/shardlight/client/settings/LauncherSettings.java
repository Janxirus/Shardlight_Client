package dev.shardlight.client.settings;

import java.util.ArrayList;
import java.util.List;

/** Launcher-wide settings, stored in settings.json. No secrets. */
public final class LauncherSettings {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public String selectedProjectId;
    public String selectedAccountId;
    /** most recently played first */
    public List<String> recentProjects = new ArrayList<>();
    public boolean firstRunDone;
    public Appearance appearance = new Appearance();
    public Downloads downloads = new Downloads();
    public JavaDefaults java = new JavaDefaults();
    public Privacy privacy = new Privacy();
    public Catalog catalog = new Catalog();
    public Auth auth = new Auth();
    public Updates updates = new Updates();

    public static final class Appearance {
        /** "dark" or "light" */
        public String theme = "dark";
        /** accent colour, #RRGGBB */
        public String accent = "#7C6CF2";
        public boolean compact;
        public boolean reducedMotion;
        public boolean backgroundEffects = true;
        /** UI scale, 0.8 - 1.6 */
        public double scale = 1.0;
        /** BCP-47 language tag; empty = system */
        public String language = "";
        public boolean highContrast;
    }

    public static final class Downloads {
        public int maxConcurrent = 6;
        public int maxRetries = 3;
        public boolean notifyWhenDone = true;
    }

    public static final class JavaDefaults {
        public int maxMemoryMb = 4096;
        public int minMemoryMb = 512;
        /** prefer Mojang's runtime for each version (recommended) */
        public boolean preferMojangRuntime = true;
    }

    public static final class Privacy {
        /** no telemetry exists; kept so a future opt-in can't default to on */
        public boolean telemetry;
        public boolean discordRichPresence;
        public boolean discordShowServer;
    }

    public static final class Catalog {
        /** "modrinth" or "curseforge" */
        public String defaultSource = "modrinth";
        /** CurseForge is used only when an API key is stored in the secret store */
        public boolean curseForgeEnabled;
        public boolean showSnapshotVersions;
    }

    public static final class Auth {
        /**
         * Azure application (client) id for Microsoft sign-in. Must be an app registered by the
         * distributor and approved for Minecraft services; empty = sign-in unavailable.
         */
        public String microsoftClientId = "";
    }

    public static final class Updates {
        public boolean checkOnStart = true;
        /** "stable" or "beta" */
        public String channel = "stable";
        public String skippedVersion;
    }
}
