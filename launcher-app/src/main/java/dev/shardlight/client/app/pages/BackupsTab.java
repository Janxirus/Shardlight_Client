package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.BackupInfo;
import dev.shardlight.client.project.BackupScope;
import dev.shardlight.client.project.Project;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/** Backups of a project or one world: create, restore (with an automatic safety backup first), delete. */
final class BackupsTab {
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox(10);

    BackupsTab(AppContext ctx, String projectId) {
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
        Tasks.quiet(ctx.overlay(), () -> ctx.services().backups().list(p.id), list -> render(p, list));
    }

    private void render(Project p, List<BackupInfo> backups) {
        root.getChildren().clear();
        Button create = UiKit.primary(I18n.tr("ui.backup.new"), Icons.ARCHIVE, () -> create(p));
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolder"),
            () -> ctx.openFolder(ctx.services().paths().backups().resolve(p.id)));
        HBox bar = new HBox(8, create, UiKit.spacer(), folder);
        bar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(bar, UiKit.label(I18n.tr("ui.backup.intro"), "muted", "small"));
        if (backups.isEmpty()) {
            root.getChildren().add(UiKit.emptyState(Icons.ARCHIVE, I18n.tr("ui.backup.emptyTitle"),
                I18n.tr("ui.backup.emptyBody"), null));
            return;
        }
        for (BackupInfo b : backups) {
            root.getChildren().add(row(p, b));
        }
    }

    private Node row(Project p, BackupInfo b) {
        String what = "world".equals(b.kind()) ? I18n.tr("ui.backup.kindWorld", b.worldFolder()) : I18n.tr("ui.backup.kindProject");
        VBox text = new VBox(2, UiKit.label(b.label() == null || b.label().isBlank() ? what : b.label() + "  ·  " + what, "project-name"),
            UiKit.label(I18n.tr("ui.backup.row", ProjectText.ago(b.createdAt()), UiKit.bytes(b.sizeBytes())), "faint", "small"));
        HBox.setHgrow(text, Priority.ALWAYS);
        Button restore = UiKit.button(I18n.tr("ui.backup.restore"), Icons.REFRESH, () -> {
            if (ctx.play().isRunning(p.id)) {
                ctx.overlay().message(I18n.tr("ui.backup.restore"), I18n.tr("ui.project.closeGameFirst"));
                return;
            }
            ctx.overlay().confirm(I18n.tr("ui.backup.restoreTitle"), I18n.tr("ui.backup.restoreMessage", what),
                List.of(I18n.tr("ui.backup.restoreSafety")), I18n.tr("ui.backup.restore"), true,
                () -> Tasks.withProgress(ctx.overlay(), I18n.tr("ui.backup.restoring"), true, (pr, c) -> {
                    ctx.services().backups().restore(b, pr, c);
                    return true;
                }, x -> {
                    ctx.overlay().toast("success", I18n.tr("ui.backup.restored"));
                    ctx.projectsChanged();
                    refresh();
                }));
        });
        Button delete = UiKit.iconButton(Icons.TRASH, I18n.tr("ui.backup.delete"), () -> ctx.overlay().confirm(
            I18n.tr("ui.backup.deleteTitle"), I18n.tr("ui.backup.deleteMessage", what, UiKit.bytes(b.sizeBytes())), List.of(),
            I18n.tr("common.delete"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().backups().delete(b);
                return true;
            }, x -> refresh())));
        HBox row = new HBox(10, UiKit.iconBox(Icons.of(Icons.ARCHIVE, 18), 38), text, restore, delete);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("card", "flat");
        return row;
    }

    private void create(Project p) {
        Dialogs.options(ctx.overlay(), I18n.tr("ui.backup.new"), I18n.tr("ui.backup.chooseWhat"), List.of(
                new Dialogs.Option(I18n.tr("ui.backup.scope.worlds"), null, true),
                new Dialogs.Option(I18n.tr("ui.backup.scope.config"), null, true),
                new Dialogs.Option(I18n.tr("ui.backup.scope.mods"), null, true),
                new Dialogs.Option(I18n.tr("ui.backup.scope.resourcePacks"), null, true),
                new Dialogs.Option(I18n.tr("ui.backup.scope.screenshots"), null, false),
                new Dialogs.Option(I18n.tr("ui.backup.scope.options"), null, true)),
            I18n.tr("ui.backup.create"), false, v -> {
                BackupScope scope = new BackupScope(v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(5));
                Tasks.withProgress(ctx.overlay(), I18n.tr("ui.backup.creating", p.name), true,
                    (pr, c) -> ctx.services().backups().backupProject(p.id, I18n.tr("ui.backup.manualLabel"), scope, pr, c),
                    b -> {
                        ctx.overlay().toast("success", I18n.tr("ui.backup.created", UiKit.bytes(b.sizeBytes())));
                        refresh();
                    });
            });
    }
}
