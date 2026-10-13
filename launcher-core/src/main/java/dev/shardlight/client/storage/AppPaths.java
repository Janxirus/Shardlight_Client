package dev.shardlight.client.storage;

import dev.shardlight.client.util.OsInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the launcher keeps its data. Default root:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\ShardlightClient}</li>
 *   <li>macOS: {@code ~/Library/Application Support/ShardlightClient}</li>
 *   <li>Linux: {@code $XDG_DATA_HOME/shardlight-client} or {@code ~/.local/share/shardlight-client}</li>
 * </ul>
 * Overridable with {@code --data-dir}, the {@code shardlight.home} system property or the
 * {@code SHARDLIGHT_HOME} environment variable (portable installs, tests).
 *
 * <pre>
 * settings.json, accounts.json     launcher settings and account list (no secrets)
 * logs/                            launcher logs
 * cache/                           metadata caches and in-progress downloads
 * game/versions, libraries, assets the shared, verified Minecraft files (same layout as .minecraft)
 * game/runtimes/                   Java runtimes downloaded from Mojang
 * files/                           shared verified download cache for mods and packs (by SHA-1)
 * content/                         the imported Shardlight content library (module jars, theme packs)
 * projects/&lt;id&gt;/                    one folder per project: project.json, icon, minecraft/ (game dir)
 * backups/&lt;project id&gt;/             project and world backups
 * trash/                           archived (removed) projects, restorable
 * </pre>
 */
public final class AppPaths {
    private final Path root;

    public AppPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** the default location for this OS, honouring the overrides */
    public static AppPaths defaults() {
        String prop = System.getProperty("shardlight.home");
        if (prop != null && !prop.isBlank()) {
            return new AppPaths(Path.of(prop));
        }
        String env = System.getenv("SHARDLIGHT_HOME");
        if (env != null && !env.isBlank()) {
            return new AppPaths(Path.of(env));
        }
        return new AppPaths(defaultRoot(OsInfo.current()));
    }

    static Path defaultRoot(OsInfo os) {
        String home = System.getProperty("user.home");
        return switch (os.os()) {
            case WINDOWS -> {
                String appData = System.getenv("APPDATA");
                yield appData != null && !appData.isBlank() ? Path.of(appData, "ShardlightClient")
                    : Path.of(home, "AppData", "Roaming", "ShardlightClient");
            }
            case MACOS -> Path.of(home, "Library", "Application Support", "ShardlightClient");
            default -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                yield xdg != null && !xdg.isBlank() ? Path.of(xdg, "shardlight-client")
                    : Path.of(home, ".local", "share", "shardlight-client");
            }
        };
    }

    public Path root() {
        return root;
    }

    public Path settingsFile() {
        return root.resolve("settings.json");
    }

    public Path accountsFile() {
        return root.resolve("accounts.json");
    }

    public Path logs() {
        return root.resolve("logs");
    }

    public Path cache() {
        return root.resolve("cache");
    }

    /** shared Minecraft files, laid out like .minecraft so loader installers understand it */
    public Path game() {
        return root.resolve("game");
    }

    public Path versions() {
        return game().resolve("versions");
    }

    public Path libraries() {
        return game().resolve("libraries");
    }

    public Path assets() {
        return game().resolve("assets");
    }

    public Path runtimes() {
        return game().resolve("runtimes");
    }

    /** shared verified download cache, files named by SHA-1 ({@code files/ab/abcdef...}) */
    public Path files() {
        return root.resolve("files");
    }

    public Path content() {
        return root.resolve("content");
    }

    public Path projects() {
        return root.resolve("projects");
    }

    public Path project(String id) {
        return projects().resolve(id);
    }

    public Path backups() {
        return root.resolve("backups");
    }

    public Path trash() {
        return root.resolve("trash");
    }

    /** the player's local skin library (created when the first skin is saved) */
    public Path skins() {
        return root.resolve("skins");
    }

    /** translation files the player added: {@code <area>_<language>.properties} (created on request) */
    public Path languages() {
        return root.resolve("languages");
    }

    public void createDirectories() throws IOException {
        for (Path p : new Path[] {root, logs(), cache(), versions(), libraries(), assets(), runtimes(), files(),
            content(), projects(), backups(), trash()}) {
            Files.createDirectories(p);
        }
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
