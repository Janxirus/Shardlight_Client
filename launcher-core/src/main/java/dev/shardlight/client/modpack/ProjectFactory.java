package dev.shardlight.client.modpack;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.project.Project;

/**
 * Creates the project a modpack is imported into (backed by {@code ProjectService.create} in the app).
 * The created project must be saved and have its game directory.
 */
@FunctionalInterface
public interface ProjectFactory {
    /** create and save a new project */
    Project create(String name, String minecraftVersion, LoaderSpec loader) throws LauncherException;

    /**
     * Called when an import fails after {@link #create}: delete or archive the half-imported project.
     * The default keeps it (the user can delete it); implementations backed by a project service should
     * remove it properly.
     */
    default void discard(Project project) throws LauncherException {
        // keep it
    }
}
