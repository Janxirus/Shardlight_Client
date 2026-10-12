package dev.shardlight.client.project;

import java.nio.file.Path;

/**
 * One world in a project's {@code saves/} folder, read from its {@code level.dat}.
 *
 * @param folder      folder name inside {@code saves/} (the world's stable handle)
 * @param name        the in-game name ({@code LevelName}); the folder name when unreadable
 * @param lastPlayed  {@code LastPlayed}, epoch milliseconds (folder time when unreadable)
 * @param gameMode    "survival", "creative", "adventure", "spectator" or "unknown"; i18n key
 *                    {@code project.world.mode.<gameMode>}
 * @param hardcore    hardcore world
 * @param cheats      commands allowed
 * @param versionName Minecraft version that last saved it ({@code Version.Name}), or null
 * @param dataVersion {@code DataVersion}, 0 when unknown
 * @param sizeBytes   size of the folder
 * @param icon        the world's {@code icon.png}, or null
 * @param readable    false when {@code level.dat} is missing or damaged (the other fields are then guesses)
 */
public record WorldInfo(String folder, String name, long lastPlayed, String gameMode, boolean hardcore, boolean cheats,
                        String versionName, int dataVersion, long sizeBytes, Path icon, boolean readable) {
}
