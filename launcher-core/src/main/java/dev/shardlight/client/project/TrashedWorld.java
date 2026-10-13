package dev.shardlight.client.project;

/**
 * A world moved to the project's trash ({@code projects/<id>/.trash/worlds/<trashName>}).
 *
 * @param trashName folder name in the trash: {@code <folder>-<yyyyMMdd-HHmmss>}
 * @param folder    the folder name it had in {@code saves/}
 * @param name      its in-game name
 * @param trashedAt when it was moved, epoch milliseconds
 * @param sizeBytes size of the folder
 */
public record TrashedWorld(String trashName, String folder, String name, long trashedAt, long sizeBytes) {
}
