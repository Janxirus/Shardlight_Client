package dev.shardlight.client.project;

import dev.shardlight.client.game.GameDirs;

import java.util.ArrayList;
import java.util.List;

/**
 * What a project backup contains (and what restoring it replaces).
 *
 * @param worlds        {@code saves/}
 * @param config        {@code config/}
 * @param mods          {@code mods/} (and the matching content list entries)
 * @param resourcePacks {@code resourcepacks/} and {@code shaderpacks/} (and their content list entries)
 * @param screenshots   {@code screenshots/}
 * @param options       {@code options.txt} and {@code servers.dat}
 */
public record BackupScope(boolean worlds, boolean config, boolean mods, boolean resourcePacks, boolean screenshots,
                          boolean options) {
    /** the whole game directory setup and data */
    public static final BackupScope EVERYTHING = new BackupScope(true, true, true, true, true, true);
    /** settings only: {@code config/} plus {@code options.txt} (for example before applying a preset) */
    public static final BackupScope CONFIG_ONLY = new BackupScope(false, true, false, false, false, true);
    /** what a world backup holds */
    public static final BackupScope WORLDS_ONLY = new BackupScope(true, false, false, false, false, false);

    /** the folders and files in the game directory this scope covers */
    List<String> items() {
        List<String> out = new ArrayList<>();
        if (worlds) {
            out.add(GameDirs.SAVES);
        }
        if (config) {
            out.add(GameDirs.CONFIG);
        }
        if (mods) {
            out.add(GameDirs.MODS);
        }
        if (resourcePacks) {
            out.add(GameDirs.RESOURCE_PACKS);
            out.add(GameDirs.SHADER_PACKS);
        }
        if (screenshots) {
            out.add(GameDirs.SCREENSHOTS);
        }
        if (options) {
            out.add(GameDirs.OPTIONS);
            out.add(GameDirs.SERVERS);
        }
        return out;
    }

    /** true for the items that are single files rather than folders */
    static boolean isFileItem(String item) {
        return item.equals(GameDirs.OPTIONS) || item.equals(GameDirs.SERVERS);
    }
}
