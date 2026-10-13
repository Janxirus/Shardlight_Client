package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Cards;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.LaunchOptions;
import dev.shardlight.client.project.BackupInfo;
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
    private final VBox root = new VBox();

    WorldsTab(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
        root.getStyleClass().add("stack");
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
        Tasks.quiet(ctx.overlay(), () -> new Loaded(ctx.services().worlds().list(p), ctx.services().backups().list(p.id)),
            loaded -> render(p, loaded.worlds(), loaded.backups()));
    }

    private record Loaded(List<WorldInfo> worlds, List<BackupInfo> backups) {
    }

    private void render(Project p, List<WorldInfo> worlds, List<BackupInfo> backups) {
        root.getChildren().clear();
        Button folder = UiKit.button(I18n.tr("ui.worlds.openFolder"), Icons.FOLDER,
            () -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve("saves")));
        HBox bar = new HBox(UiKit.label(I18n.tr("ui.worlds.count", worlds.size()), "muted"), UiKit.spacer(), folder);
        bar.getStyleClass().add("tab-bar");
        root.getChildren().add(bar);
        if (worlds.isEmpty()) {
            root.getChildren().add(UiKit.emptyState(Icons.WORLD, I18n.tr("ui.worlds.emptyTitle"), I18n.tr("ui.worlds.emptyBody"),
                null));
            return;
        }
        FlowPane grid = new FlowPane();
        grid.getStyleClass().add("world-grid");
        for (WorldInfo w : worlds) {
            grid.getChildren().add(card(p, w, backups.stream().filter(b -> BackupInfo.WORLD.equals(b.kind())
                && w.folder().equals(b.worldFolder())).toList()));
        }
        root.getChildren().add(grid);
    }

    private Node card(Project p, WorldInfo w, List<BackupInfo> backups) {
        Node picture;
        if (w.icon() != null && Files.isRegularFile(w.icon())) {
            ImageView v = new ImageView(new Image(w.icon().toUri().toString(), 0, ThemeManager.em(9), true, false, true));
            v.setFitHeight(ThemeManager.em(9));
            v.setPreserveRatio(true);
            v.setSmooth(false);
            picture = v;
        } else {
            picture = Icons.of(Icons.WORLD, 40);
            picture.getStyleClass().add("faint");
        }
        String title = w.name() == null ? w.folder() : w.name();
        String meta = (w.readable() ? I18n.tr("ui.worlds.mode." + (w.gameMode() == null ? "unknown" : w.gameMode()))
            : I18n.tr("ui.worlds.unreadable")) + "  ·  " + (w.lastPlayed() > 0 ? ProjectText.ago(w.lastPlayed()) : "-")
            + "  ·  " + UiKit.bytes(w.sizeBytes());

        Button play = UiKit.button(I18n.tr("ui.play.play"), Icons.PLAY, () -> playWorld(p, w), "primary", "small");
        play.setAccessibleText(I18n.tr("ui.worlds.playNamed", title));
        play.setDisable(!w.readable());
        Button backup = UiKit.button(I18n.tr("ui.worlds.backup"), Icons.ARCHIVE,
            () -> Tasks.withProgress(ctx.overlay(), I18n.tr("ui.worlds.backingUp", title), true,
                (pr, c) -> ctx.services().backups().backupWorld(p.id, w.folder(), I18n.tr("ui.backup.manualLabel"), pr, c),
                b -> {
                    ctx.overlay().toast("success", I18n.tr("ui.backup.created", UiKit.bytes(b.sizeBytes())));
                    refresh();
                }), "small");
        backup.setAccessibleText(I18n.tr("ui.worlds.backupNamed", title));
        Button restore = UiKit.button(I18n.tr("ui.backup.restore"), Icons.REFRESH, () -> { }, "small");
        restore.setAccessibleText(I18n.tr("ui.worlds.restoreNamed", title));
        if (backups.isEmpty()) {
            restore.setDisable(true);
            UiKit.tooltip(restore, I18n.tr("ui.worlds.noBackups"));
        } else {
            restore.setOnAction(e -> restoreMenu(p, w, backups).show(restore, Side.BOTTOM, 0, 0));
        }
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.worlds.more", title), () -> { });
        more.setOnAction(e -> menu(p, w).show(more, Side.BOTTOM, 0, 0));
        HBox actions = new HBox(play, backup, restore, more);
        actions.getStyleClass().add("card-actions");

        VBox card = Cards.world(picture, title, meta, null);
        FlowPane chips = new FlowPane();
        chips.getStyleClass().add("chip-row");
        if (w.hardcore()) {
            chips.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.hardcore"), "danger"));
        }
        if (w.cheats()) {
            chips.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.cheats"), null));
        }
        if (w.versionName() != null) {
            chips.getChildren().add(UiKit.chip(w.versionName(), null));
        }
        if (w.readable() && ctx.services().worlds().isNewerThan(w, p.minecraftVersion)) {
            chips.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.newer"), "warning", Icons.WARNING));
        }
        chips.getChildren().add(UiKit.chip(I18n.tr("ui.worlds.backupCount", backups.size()), backups.isEmpty() ? null : "success"));
        card.getChildren().addAll(chips, actions);
        return card;
    }

    /** the backups of one world, newest first: pick one to restore */
    private ContextMenu restoreMenu(Project p, WorldInfo w, List<BackupInfo> backups) {
        ContextMenu menu = new ContextMenu();
        for (BackupInfo b : backups.stream().sorted((x, y) -> Long.compare(y.createdAt(), x.createdAt())).limit(10).toList()) {
            String label = (b.label() == null || b.label().isBlank() ? I18n.tr("ui.backup.kindWorld", w.folder()) : b.label())
                + "  ·  " + ProjectText.ago(b.createdAt()) + "  ·  " + UiKit.bytes(b.sizeBytes());
            MenuItem item = new MenuItem(label);
            item.setOnAction(e -> restore(p, w, b));
            menu.getItems().add(item);
        }
        return menu;
    }

    private void restore(Project p, WorldInfo w, BackupInfo b) {
        if (ctx.play().isRunning(p.id)) {
            ctx.overlay().message(I18n.tr("ui.backup.restore"), I18n.tr("ui.project.closeGameFirst"));
            return;
        }
        ctx.overlay().confirm(I18n.tr("ui.backup.restoreTitle"), I18n.tr("ui.backup.restoreMessage", w.name()),
            List.of(I18n.tr("ui.backup.restoreSafety")), I18n.tr("ui.backup.restore"), true,
            () -> Tasks.withProgress(ctx.overlay(), I18n.tr("ui.backup.restoring"), true, (pr, c) -> {
                ctx.services().backups().restore(b, pr, c);
                return true;
            }, x -> {
                ctx.overlay().toast("success", I18n.tr("ui.backup.restored"));
                ctx.projectsChanged();
                refresh();
            }));
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
