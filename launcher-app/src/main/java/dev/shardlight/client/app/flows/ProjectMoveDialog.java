package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Moving a project's game files to another folder (another drive, usually) or back to the launcher's folder. The dialog
 * shows where the files are now and where they will be, how big the project is and how much room is there; the work
 * itself (copy, check, switch, clean up, with rollback) is {@code ProjectService.moveGameDir}.
 */
public final class ProjectMoveDialog {
    private final AppContext ctx;
    private final Project project;
    private final boolean external;
    private final Path home;
    private final Label target = UiKit.label("", "mono");
    private final Label problem = UiKit.label("", "danger-text", "small");
    private final Label size = UiKit.label(I18n.tr("ui.move.sizeCounting"), "muted", "small");
    private Path parent;
    private boolean toHome;
    private Button run;
    private long sizeBytes = -1;

    private ProjectMoveDialog(AppContext ctx, Project project) {
        this.ctx = ctx;
        this.project = project;
        this.external = project.gameDirLocation != null && !project.gameDirLocation.isBlank();
        this.home = ctx.services().projects().defaultGameDir(project.id);
    }

    public static Overlay.Dialog show(AppContext ctx, Project project) {
        return new ProjectMoveDialog(ctx, project).open(null);
    }

    /** the dialog with a folder already chosen (the screenshots) */
    public static Overlay.Dialog showWith(AppContext ctx, Project project, Path chosen) {
        return new ProjectMoveDialog(ctx, project).open(chosen);
    }

    private Overlay.Dialog open(Path chosen) {
        var projects = ctx.services().projects();
        Label now = UiKit.label(projects.gameDir(project).toString(), "mono");
        now.setWrapText(true);
        HBox nowRow = new HBox(8, UiKit.chip(external ? I18n.tr("ui.projectPage.settings") : I18n.tr("ui.move.here"), external ? "accent" : "info"), now);
        nowRow.setAlignment(Pos.CENTER_LEFT);
        VBox current = new VBox(4, UiKit.label(I18n.tr("ui.move.current"), "caption", "faint"), nowRow);

        target.setWrapText(true);
        Button choose = UiKit.button(I18n.tr("ui.move.choose"), Icons.FOLDER, this::choose);
        HBox pick = new HBox(10, target, UiKit.spacer(), choose);
        pick.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(target, Priority.ALWAYS);
        VBox where = new VBox(4, UiKit.label(I18n.tr("ui.move.new"), "caption", "faint"), pick);
        VBox body = new VBox(14, current, where);
        if (external) {
            Button back = UiKit.button(I18n.tr("ui.move.toLauncher"), Icons.BACK, () -> {
                toHome = true;
                parent = null;
                refresh();
            });
            body.getChildren().add(back);
        }
        body.getChildren().addAll(problem, size, UiKit.banner("info", I18n.tr("ui.move.safety")));
        body.setPrefWidth(560);

        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        run = UiKit.primary(I18n.tr("ui.move.run"), Icons.FORWARD, this::move);
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.move.title", project.name), body, cancel, run), true, false);
        cancel.setOnAction(e -> d.close());
        this.dialog = d;
        if (chosen != null) {
            parent = chosen;
        }
        refresh();
        Fx.run(() -> Files.exists(projects.gameDir(project)) ? sizeOf(projects.gameDir(project)) : 0L, bytes -> {
            sizeBytes = bytes;
            refresh();
        }, err -> size.setText(""));
        return d;
    }

    private Overlay.Dialog dialog;

    private void choose() {
        Path start = parent != null ? parent : external ? Path.of(project.gameDirLocation).getParent() : null;
        Path picked = Dialogs.chooseFolder(ctx.stage(), I18n.tr("ui.move.chooseTitle", project.name), start);
        if (picked != null) {
            parent = picked;
            toHome = false;
            refresh();
        }
    }

    private void refresh() {
        var projects = ctx.services().projects();
        String error = null;
        target.getStyleClass().remove("faint");
        if (toHome) {
            target.setText(home.toString());
        } else if (parent == null) {
            target.setText(I18n.tr("ui.move.pickFirst"));
            target.getStyleClass().add("faint");
        } else {
            target.setText(projects.suggestGameDir(parent, project.id).toString());
            if (parent.toAbsolutePath().normalize().startsWith(ctx.services().paths().root())) {
                error = I18n.tr("ui.move.insideData");
            }
        }
        boolean running = ctx.play().isRunning(project.id);
        problem.setText(running ? I18n.tr("ui.move.running") : error == null ? "" : error);
        long free = parent != null && !toHome ? free(parent) : toHome ? free(ctx.services().paths().root()) : -1;
        size.setText(sizeBytes < 0 ? I18n.tr("ui.move.sizeCounting")
            : I18n.tr("ui.move.size", UiKit.bytes(sizeBytes), free < 0 ? I18n.tr("ui.storage.unknown") : UiKit.bytes(free)));
        run.setDisable(running || error != null || !(toHome || parent != null));
    }

    private void move() {
        if (dialog != null) {
            dialog.close();
        }
        Path newParent = toHome ? null : parent;
        String destination = toHome ? home.toString() : ctx.services().projects().suggestGameDir(parent, project.id).toString();
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.move.moving", project.name), true,
            (pr, c) -> ctx.services().projects().moveGameDir(project.id, newParent, pr, c),
            result -> {
                ctx.overlay().toast("success", toHome ? I18n.tr("ui.move.doneHome", project.name)
                    : I18n.tr("ui.move.done", project.name, destination));
                if (result.leftover() != null) {
                    Path old = result.leftover();
                    ctx.overlay().toast("warning", I18n.tr("ui.move.leftover", old, project.name),
                        new Overlay.Action(I18n.tr("ui.move.openOld"), () -> ctx.openFolder(old)));
                }
                ctx.projectsChanged();
            });
    }

    private static long free(Path where) {
        Path probe = where.toAbsolutePath();
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        try {
            return probe == null ? -1 : Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private static long sizeOf(Path root) throws IOException {
        long[] sum = {0};
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                try {
                    sum[0] += Files.size(f);
                } catch (IOException ignored) {
                    // a file that vanished while counting
                }
            });
        }
        return sum[0];
    }
}
