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

    /** {@code projects/<id>/minecraft}, the game directory passed to Minecraft */
    default Path gameDir(Project p) {
        return projectDir(p.id).resolve("minecraft");
    }
}
