package dev.shardlight.client.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Standard folders and files inside a Minecraft game directory. */
public final class GameDirs {
    public static final String MODS = "mods";
    public static final String CONFIG = "config";
    public static final String RESOURCE_PACKS = "resourcepacks";
    public static final String SHADER_PACKS = "shaderpacks";
    public static final String SAVES = "saves";
    public static final String SCREENSHOTS = "screenshots";
    public static final String LOGS = "logs";
    public static final String CRASH_REPORTS = "crash-reports";
    public static final String OPTIONS = "options.txt";
    public static final String SERVERS = "servers.dat";
    /** suffix that stops a mod jar from loading (Fabric/Quilt/Forge only load *.jar) */
    public static final String DISABLED_SUFFIX = ".disabled";

    public static final List<String> CREATED = List.of(MODS, CONFIG, RESOURCE_PACKS, SHADER_PACKS, SAVES, SCREENSHOTS, LOGS);

    private GameDirs() {
    }

    public static void create(Path gameDir) throws IOException {
        for (String d : CREATED) {
            Files.createDirectories(gameDir.resolve(d));
        }
    }
}
