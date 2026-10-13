package dev.shardlight.client.app;

import dev.shardlight.client.app.flows.DropFlow;
import dev.shardlight.client.app.flows.ModpackUpdateFlow;
import dev.shardlight.client.app.flows.ProjectMoveDialog;
import dev.shardlight.client.app.pages.SettingsPage;
import dev.shardlight.client.app.pages.StorageSection;
import dev.shardlight.client.content.DropItem;
import dev.shardlight.client.content.DropKind;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.modpack.UpdateAction;
import dev.shardlight.client.modpack.UpdateItem;
import dev.shardlight.client.modpack.UpdatePlan;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.storage.DiskUsage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * --capture only: the Part 6 views (update preview, drop confirmation, move dialog, Storage). The plan, the dropped
 * files and the clean-up list are made up here; the Storage page itself counts the capture folder's real files.
 */
final class CapturePart6 {
    static final List<String> VIEWS = List.of("update", "update-other", "drop", "move", "storage", "storage-cleanup");

    private CapturePart6() {
    }

    static boolean show(AppContext ctx, String id) {
        Project p = ctx.selectedProject().orElse(null);
        if (p == null) {
            return true;
        }
        ctx.shell().navigate("library");
        switch (id) {
            case "update" -> ModpackUpdateFlow.preview(ctx, p, Path.of("Survival Island-1.4.0.mrpack"), plan(true));
            case "update-other" -> ModpackUpdateFlow.preview(ctx, p, Path.of("Skyblock Remix-2.0.mrpack"), plan(false));
            case "drop" -> {
                List<Project> all = ctx.services().projects().list();
                List<DropItem> placeable = List.of(
                    new DropItem(Path.of("lumen-optimizer-1.2.jar"), DropKind.MOD, null, Set.of(LoaderType.FABRIC)),
                    new DropItem(Path.of("quiet-trails-0.9.jar"), DropKind.MOD, null, Set.of(LoaderType.FORGE)),
                    new DropItem(Path.of("Faithful 64x.zip"), DropKind.RESOURCE_PACK, null, Set.of()),
                    new DropItem(Path.of("Old base.zip"), DropKind.WORLD, null, Set.of()),
                    new DropItem(Path.of("2026-10-10_21.14.02.png"), DropKind.SCREENSHOT, null, Set.of()));
                DropFlow.confirm(ctx, all, p, placeable, List.of(new DropFlow.Refusal("notes.txt", dev.shardlight.client.i18n.I18n.tr("drop.reason.type"))));
            }
            case "move" -> {
                Path parent = Path.of(System.getProperty("java.io.tmpdir")).resolve("Games");
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    // the dialog still shows; it only needs a path
                }
                ProjectMoveDialog.showWith(ctx, p, parent);
            }
            case "storage", "storage-cleanup" -> {
                ctx.shell().navigate("settings");
                if (ctx.shell().current() instanceof SettingsPage sp) {
                    sp.open("storage");
                }
                if (id.equals("storage-cleanup")) {
                    Path v = Path.of("versions");
                    StorageSection.previewCleanup(ctx, new DiskUsage.CleanupPlan(List.of(
                        new DiskUsage.CleanupItem(DiskUsage.Kind.VERSION, "1.21.4", v.resolve("1.21.4"), 214_000_000L),
                        new DiskUsage.CleanupItem(DiskUsage.Kind.VERSION, "fabric-loader-0.16.9-1.21.4", v.resolve("fabric"), 1_200_000L),
                        new DiskUsage.CleanupItem(DiskUsage.Kind.RUNTIME, "java-runtime-gamma", Path.of("runtimes", "gamma"), 312_000_000L)),
                        false));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static UpdatePlan plan(boolean same) {
        LoaderSpec fabric = new LoaderSpec(LoaderType.FABRIC, "0.19.5");
        return new UpdatePlan(same ? "Survival Island" : "Skyblock Remix", "Survival Island", same, "1.3.2", same ? "1.4.0" : "2.0",
            "26.2", "26.3", fabric, fabric, List.of(
            new UpdateItem("mods/lumen-optimizer-1.2.jar", UpdateAction.UPDATE, false),
            new UpdateItem("mods/chestnut-storage-2.1.jar", UpdateAction.UPDATE, false),
            new UpdateItem("mods/tidy-hud-0.8.jar", UpdateAction.ADD, false),
            new UpdateItem("config/lumen.toml", UpdateAction.ADD, false),
            new UpdateItem("mods/old-lights-1.0.jar", UpdateAction.REMOVE, false),
            new UpdateItem("config/tidy-hud.json", UpdateAction.KEEP_EDITED, false),
            new UpdateItem("config/chestnut.toml", UpdateAction.KEEP_CONFLICT, false),
            new UpdateItem("mods/my-minimap-3.4.jar", UpdateAction.KEEP_ADDED, false),
            new UpdateItem("resourcepacks/Faithful 64x.zip", UpdateAction.KEEP_ADDED, false),
            new UpdateItem("shaderpacks/Bliss.zip", UpdateAction.KEEP_ADDED, false),
            new UpdateItem("mods/wayfinder-compass-1.0.jar", UpdateAction.KEEP_REMOVED, false)));
    }
}
