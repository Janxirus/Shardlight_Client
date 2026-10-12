package dev.shardlight.client.project;

import dev.shardlight.client.error.LauncherException;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The small slice of project storage that content, Shardlight and launch services need. Implemented by
 * {@code ProjectService}; tests can use a simple file-backed version.
 */
public interface ProjectAccess {
    Optional<Project> get(String id);

    /** save atomically and notify listeners; returns the saved copy */
    Project save(Project project) throws LauncherException;

    /** {@code projects/<id>} */
    Path projectDir(String id);

    /** the game directory passed to Minecraft: {@code projects/<id>/minecraft}, or the project's own location */
    default Path gameDir(Project p) {
        if (p.gameDirLocation != null && !p.gameDirLocation.isBlank()) {
            return Path.of(p.gameDirLocation);
        }
        return projectDir(p.id).resolve("minecraft");
    }
}
