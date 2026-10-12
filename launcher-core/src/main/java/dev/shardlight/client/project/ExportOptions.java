package dev.shardlight.client.project;

import dev.shardlight.client.game.GameDirs;

import java.util.ArrayList;
import java.util.List;

/**
 * Which game folders {@link ProjectService#exportProject} puts in a {@code .shardlight} file. The project
 * settings, {@code options.txt}, {@code servers.dat} and the icon are always included.
 */
public record ExportOptions(boolean worlds, boolean screenshots, boolean mods, boolean config, boolean resourcePacks,
                            boolean shaderPacks) {
    /** everything */
    public static final ExportOptions EVERYTHING = new ExportOptions(true, true, true, true, true, true);
    /** what a friend needs to play the same setup: mods, config and packs, no worlds or screenshots */
    public static final ExportOptions SHAREABLE = new ExportOptions(false, false, true, true, true, true);

    /** the selected folder names inside the game directory */
    List<String> folders() {
        List<String> out = new ArrayList<>();
        if (worlds) {
            out.add(GameDirs.SAVES);
        }
        if (screenshots) {
            out.add(GameDirs.SCREENSHOTS);
        }
        if (mods) {
            out.add(GameDirs.MODS);
        }
        if (config) {
            out.add(GameDirs.CONFIG);
        }
        if (resourcePacks) {
            out.add(GameDirs.RESOURCE_PACKS);
        }
        if (shaderPacks) {
            out.add(GameDirs.SHADER_PACKS);
        }
        return out;
    }
}
