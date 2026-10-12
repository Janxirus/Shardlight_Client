package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.LaunchOptions;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.WorldInfo;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/** The worlds of a project: play one directly, back it up, copy, rename, or move it to the project's trash. */
final class WorldsTab {
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox(10);

    WorldsTab(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
    }

    VBox view() {
        return root;
    }

    void refresh() {
        Optional<Project> found = ctx.services().projects().get(projectId);
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        Tasks.quiet(ctx.overlay(), () -> ctx.services().worlds().list(p), worlds -> render(p, worlds));
    }

    private void render(Project p, List<WorldInfo> worlds) {
        root.getChildren().clear();
        Button folder = UiKit.button(I18n.tr("ui.worlds.openFolder"), Icons.FOLDER,
            () -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve("saves")));
        HBox bar = new HBox(8, UiKit.label(I18n.tr("ui.worlds.count", worlds.size()), "muted"), UiKit.spacer(), folder);
        bar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(bar);
        if (worlds.isEmpty()) {
            root.getChildren().add(UiKit.emptyState(Icons.WORLD, I18n.tr("ui.worlds.emptyTitle"), I18n.tr("ui.worlds.emptyBody"),
                null));
            return;
        }
        for (WorldInfo w : worlds) {
            root.getChildren().add(row(p, w));
        }
    }

    private Node row(Project p, WorldInfo w) {
        Node icon;
        if (w.icon() != null && Files.isRegularFile(w.icon())) {
            ImageView v = new ImageView(new Image(w.icon().toUri().toString(), 48, 48, true, false, true));
            v.setFitWidth(48);
            v.setFitHeight(48);
            icon = v;
        } else {
            icon = UiKit.iconBox(dev.shardlight.client.app.ui.Icons.of(Icons.WORLD, 22), 48);
        }
        FlowPane meta = new FlowPane(6, 4);
        if (!w.readable()) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.unreadable"), "danger"));
        } else {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.mode." + (w.gameMode() == null ? "unknown" : w.gameMode())), null));
            if (w.hardcore()) {
                meta.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.hardcore"), "danger"));
            }
            if (w.cheats()) {
                meta.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.cheats"), null));
            }
            if (w.versionName() != null) {
                meta.getChildren().add(UiKit.chip(w.versionName(), null));
            }
            if (ctx.services().worlds().isNewerThan(w, p.minecraftVersion)) {
                meta.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.newer"), "warning", Icons.WARNING));
            }
        }
        VBox text = new VBox(3, UiKit.label(w.name() == null ? w.folder() : w.name(), "project-name"), meta,
            UiKit.label(I18n.tr("ui.worlds.row", w.lastPlayed() > 0 ? ProjectText.ago(w.lastPlayed()) : "-",
                UiKit.bytes(w.sizeBytes()), w.folder()), "faint", "small"));
        HBox.setHgrow(text, Priority.ALWAYS);
        Button play = UiKit.primary(I18n.tr("ui.worlds.play"), Icons.PLAY, () -> playWorld(p, w));
        play.setDisable(!w.readable());
        Button backup = UiKit.button(I18n.tr("ui.worlds.backup"), Icons.ARCHIVE, () -> Tasks.withProgress(ctx.overlay(),
            I18n.tr("ui.worlds.backingUp", w.name()), true,
            (pr, c) -> ctx.services().backups().backupWorld(p.id, w.folder(), I18n.tr("ui.backup.manualLabel"), pr, c),
            b -> ctx.overlay().toast("success", I18n.tr("ui.backup.created", UiKit.bytes(b.sizeBytes())))));
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.worlds.more", w.name()), () -> { });
        more.setOnAction(e -> menu(p, w).show(more, Side.BOTTOM, 0, 0));
        HBox row = new HBox(12, icon, text, play, backup, more);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("card", "flat");
        return row;
    }

    private void playWorld(Project p, WorldInfo w) {
        if (ctx.services().worlds().isNewerThan(w, p.minecraftVersion)) {
            ctx.overlay().confirm(I18n.tr("ui.worlds.newerTitle"), I18n.tr("ui.worlds.newerMessage", w.versionName(),
                p.minecraftVersion), List.of(I18n.tr("ui.worlds.newerBackup")), I18n.tr("ui.worlds.playAnyway"), true,
                () -> ctx.play().play(p, new LaunchOptions(w.folder(), null, false)));
            return;
        }
        ctx.play().play(p, new LaunchOptions(w.folder(), null, false));
    }

    private ContextMenu menu(Project p, WorldInfo w) {
        MenuItem rename = new MenuItem(I18n.tr("ui.worlds.rename"));
        rename.setOnAction(e -> Dialogs.text(ctx.overlay(), I18n.tr("ui.worlds.rename"), I18n.tr("ui.worlds.nameLabel"),
            w.name(), I18n.tr("ui.worlds.rename"), v -> v.isBlank() ? I18n.tr("ui.project.nameEmpty") : null,
            v -> Tasks.quiet(ctx.overlay(), () -> ctx.services().worlds().rename(p, w.folder(), v), x -> refresh())));
        MenuItem copy = new MenuItem(I18n.tr("ui.worlds.duplicate"));
        copy.setOnAction(e -> Dialogs.text(ctx.overlay(), I18n.tr("ui.worlds.duplicate"), I18n.tr("ui.worlds.nameLabel"),
            I18n.tr("ui.project.copyName", w.name()), I18n.tr("ui.worlds.duplicate"),
            v -> v.isBlank() ? I18n.tr("ui.project.nameEmpty") : null,
            v -> Tasks.withProgress(ctx.overlay(), I18n.tr("ui.worlds.copying", w.name()), false,
                (pr, c) -> ctx.services().worlds().duplicate(p, w.folder(), v), x -> refresh())));
        MenuItem folder = new MenuItem(I18n.tr("ui.library.openFolder"));
        folder.setOnAction(e -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve("saves").resolve(w.folder())));
        MenuItem trash = new MenuItem(I18n.tr("ui.worlds.trash"));
        trash.getStyleClass().add("danger");
        trash.setOnAction(e -> ctx.overlay().confirm(I18n.tr("ui.worlds.trashTitle", w.name()),
            I18n.tr("ui.worlds.trashMessage"), List.of(I18n.tr("ui.worlds.trashSize", UiKit.bytes(w.sizeBytes()))),
            I18n.tr("ui.worlds.trash"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().worlds().moveToTrash(p, w.folder());
                return true;
            }, x -> {
                ctx.overlay().toast("success", I18n.tr("ui.worlds.trashed", w.name()));
                refresh();
            })));
        return new ContextMenu(rename, copy, folder, new javafx.scene.control.SeparatorMenuItem(), trash);
    }
}
