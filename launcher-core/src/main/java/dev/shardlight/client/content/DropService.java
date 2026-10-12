package dev.shardlight.client.content;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ScreenshotService;
import dev.shardlight.client.project.WorldService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;

import java.util.Objects;

/**
 * Puts a dropped file where {@link DropRouter} says it belongs in a project: mods, resource packs and shader packs
 * through the content service (so they are recorded and checked like any install), worlds into {@code saves/} and
 * pictures into {@code screenshots/}. Modpacks and project exports make new projects and are handled by their own
 * flows.
 */
public final class DropService {
    private final ContentService content;
    private final WorldService worlds;
    private final ScreenshotService screenshots;

    public DropService(ContentService content, WorldService worlds, ScreenshotService screenshots) {
        this.content = Objects.requireNonNull(content);
        this.worlds = Objects.requireNonNull(worlds);
        this.screenshots = Objects.requireNonNull(screenshots);
    }

    /** the folder inside the project a drop ends up in, shown in the confirmation */
    public static String folderOf(DropItem item) {
        return item.kind().folder();
    }

    /** place the file in {@code project}; fails with {@code INVALID_DATA} for kinds that don't go into a project */
    public void deliver(Project project, DropItem item, ProgressListener progress, CancellationToken cancel) throws LauncherException {
        switch (item.kind()) {
            case MOD -> content.installLocalFile(project, ContentKind.MOD, item.file(), ContentSource.LOCAL, null, null);
            case RESOURCE_PACK -> content.installLocalFile(project, ContentKind.RESOURCE_PACK, item.file(), ContentSource.LOCAL, null, null);
            case SHADER_PACK -> content.installLocalFile(project, ContentKind.SHADER_PACK, item.file(), ContentSource.LOCAL, null, null);
            case WORLD -> worlds.importWorld(project, item.file(), progress, cancel);
            case SCREENSHOT -> screenshots.importFile(project, item.file());
            default -> throw new LauncherException(ErrorCode.INVALID_DATA, "drop.error.notForProject", item.fileName());
        }
    }
}
