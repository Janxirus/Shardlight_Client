package dev.shardlight.client.project;

import java.nio.file.Path;

/**
 * The outcome of moving a project's game directory.
 *
 * @param project  the project, now pointing at its new location
 * @param leftover the old folder when it couldn't be deleted completely (a program still holds a file in it); the
 *                 project already works from the new location, so this is only a folder to delete by hand. Null when
 *                 everything was cleaned up
 */
public record MoveResult(Project project, Path leftover) {
}
