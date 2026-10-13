package dev.shardlight.client.testutil;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A minimal file-backed {@link ProjectAccess} for tests of services that sit on top of projects. */
public final class TestProjects implements ProjectAccess {
    private final AppPaths paths;

    public TestProjects(AppPaths paths) {
        this.paths = paths;
    }

    /** create a project folder with a game directory and save it */
    public Project create(String id, String mcVersion, LoaderType loader, String loaderVersion) throws LauncherException {
        Project p = new Project();
        p.id = id;
        p.name = id;
        p.minecraftVersion = mcVersion;
        p.loader = new LoaderSpec(loader, loaderVersion);
        p.createdAt = System.currentTimeMillis();
        try {
            GameDirs.create(gameDir(p));
        } catch (IOException e) {
            throw LauncherException.io(e, id);
        }
        return save(p);
    }

    @Override
    public Optional<Project> get(String id) {
        Path f = projectDir(id).resolve("project.json");
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.read(Files.readString(f), Project.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Project save(Project project) throws LauncherException {
        AtomicFiles.writeJson(projectDir(project.id).resolve("project.json"), project);
        return Json.copy(project, Project.class);
    }

    @Override
    public Path projectDir(String id) {
        return paths.project(id);
    }
}
