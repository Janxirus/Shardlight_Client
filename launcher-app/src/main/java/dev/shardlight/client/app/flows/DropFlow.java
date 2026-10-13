package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.content.DropItem;
import dev.shardlight.client.content.DropKind;
import dev.shardlight.client.content.DropRouter;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Files dropped on the window. Each one is looked at ({@link DropRouter}), then a short confirmation names every file
 * with the project and folder it will go to, and what can't be added with the reason. Nothing is copied before the
 * player agrees. A modpack or project file makes a new project, so it is handed to the import flow on its own.
 */
public final class DropFlow {
    private static final String ACTIVE = "drop-active";

    private DropFlow() {
    }

    /** let the window accept files */
    public static void install(AppContext ctx, Scene scene) {
        PauseTransition idle = new PauseTransition(Duration.millis(260));
        idle.setOnFinished(e -> scene.getRoot().getStyleClass().remove(ACTIVE));
        scene.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!scene.getRoot().getStyleClass().contains(ACTIVE)) {
                    scene.getRoot().getStyleClass().add(ACTIVE);
                }
                idle.playFromStart();
                e.consume();
            }
        });
        scene.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                idle.stop();
                scene.getRoot().getStyleClass().remove(ACTIVE);
                List<Path> files = db.getFiles().stream().map(f -> f.toPath()).toList();
                e.setDropCompleted(true);
                e.consume();
                javafx.application.Platform.runLater(() -> handle(ctx, files));
            }
        });
    }

    /** what to do with the files (the entry point of a drop; also called by the screenshots) */
    public static void handle(AppContext ctx, List<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        if (ctx.overlay().hasOpenDialog()) {
            ctx.overlay().toast("info", I18n.tr("ui.drop.busy"));
            return;
        }
        List<DropItem> items = DropRouter.classify(files);
        List<DropItem> accepted = items.stream().filter(i -> i.kind().accepted()).toList();
        long makers = accepted.stream().filter(i -> i.kind().makesProject()).count();
        if (makers == 1 && accepted.size() == 1) {
            ProjectActions.importDropped(ctx, accepted.get(0).file());
            return;
        }
        List<Refusal> refused = new ArrayList<>();
        List<DropItem> placeable = new ArrayList<>();
        for (DropItem i : items) {
            if (i.kind().intoProject()) {
                placeable.add(i);
            } else if (i.kind().makesProject()) {
                refused.add(new Refusal(i.fileName(), I18n.tr("ui.drop.oneAtATime")));
            } else {
                refused.add(new Refusal(i.fileName(), i.reason() == null ? I18n.tr("drop.reason.type") : I18n.tr(i.reason())));
            }
        }
        if (placeable.isEmpty()) {
            showRefused(ctx, refused);
            return;
        }
        List<Project> projects = ctx.services().projects().list();
        if (projects.isEmpty()) {
            ctx.overlay().message(I18n.tr("ui.drop.add"), I18n.tr("ui.drop.noProject"));
            return;
        }
        confirm(ctx, projects, ctx.selectedProject().orElse(projects.get(0)), placeable, refused);
    }

    /** a dropped file that can't be added, with the reason */
    public record Refusal(String name, String reason) {
    }

    private static void showRefused(AppContext ctx, List<Refusal> refused) {
        VBox body = new VBox(8);
        body.setPrefWidth(ThemeManager.scaled(480));
        refused.forEach(r -> body.getChildren().add(refusalRow(r)));
        Button ok = UiKit.primary(I18n.tr("common.ok"), null, () -> { });
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.drop.refused"), body, ok), true, false);
        ok.setOnAction(e -> d.close());
    }

    private static Node refusalRow(Refusal r) {
        Label name = UiKit.label(r.name(), "strong");
        Label why = UiKit.label(r.reason(), "muted", "small");
        why.setWrapText(true);
        VBox text = new VBox(2, name, why);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, Icons.of(Icons.WARNING, 16), text);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("warning-icon");
        return row;
    }

    /** the confirmation dialog; returns the dialog so the screenshots can find it */
    public static Overlay.Dialog confirm(AppContext ctx, List<Project> projects, Project initial, List<DropItem> placeable,
                                         List<Refusal> refused) {
        ComboBox<Project> picker = new ComboBox<>();
        picker.getItems().setAll(projects);
        picker.setValue(projects.stream().filter(p -> p.id.equals(initial.id)).findFirst().orElse(projects.get(0)));
        picker.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(Project p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.name);
            }
        });
        picker.setButtonCell(picker.getCellFactory().call(null));
        picker.setAccessibleText(I18n.tr("ui.drop.project"));
        picker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(picker, Priority.ALWAYS);
        HBox pickRow = new HBox(10, UiKit.label(I18n.tr("ui.drop.project"), "strong"), picker);
        pickRow.setAlignment(Pos.CENTER_LEFT);
        pickRow.setVisible(projects.size() > 1);
        pickRow.setManaged(projects.size() > 1);

        VBox rows = new VBox(8);
        VBox warnings = new VBox(8);
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        String addLabel = placeable.size() == 1 ? I18n.tr("ui.drop.add") : I18n.tr("ui.drop.addMany", placeable.size());
        Button add = UiKit.primary(addLabel, Icons.PLUS, () -> { });
        Runnable fill = () -> {
            Project p = picker.getValue();
            rows.getChildren().clear();
            warnings.getChildren().clear();
            for (DropItem item : placeable) {
                rows.getChildren().add(placeRow(item, p));
                if (item.wrongLoaderFor(p.loader)) {
                    warnings.getChildren().add(UiKit.banner("warning", I18n.tr("ui.drop.wrongLoader", item.fileName(),
                        item.loaders().stream().map(l -> l.displayName()).sorted().reduce((a, b) -> a + " / " + b).orElse(""),
                        p.name, p.loader == null || p.loader.isVanilla() ? I18n.tr("ui.drop.vanilla") : p.loader.type.displayName())));
                }
            }
        };
        fill.run();
        picker.valueProperty().addListener((o, a, b) -> fill.run());

        VBox body = new VBox(12, pickRow, rows, warnings);
        body.setPrefWidth(ThemeManager.scaled(540));
        if (!refused.isEmpty()) {
            VBox no = new VBox(8, UiKit.label(I18n.tr("ui.drop.refused"), "h3"));
            refused.forEach(r -> no.getChildren().add(refusalRow(r)));
            body.getChildren().add(no);
        }
        String title = placeable.size() == 1
            ? I18n.tr("ui.drop.titleOne", placeable.get(0).fileName(), picker.getValue().name)
            : I18n.tr("ui.drop.titleMany", placeable.size(), picker.getValue().name);
        VBox modal = Overlay.modal(title, body, cancel, add);
        Overlay.Dialog d = ctx.overlay().show(modal, true, false);
        cancel.setOnAction(e -> d.close());
        add.setOnAction(e -> {
            d.close();
            deliver(ctx, picker.getValue(), placeable);
        });
        picker.valueProperty().addListener((o, a, b) -> {
            if (modal.getChildren().get(0) instanceof Label l) {
                l.setText(placeable.size() == 1 ? I18n.tr("ui.drop.titleOne", placeable.get(0).fileName(), b.name)
                    : I18n.tr("ui.drop.titleMany", placeable.size(), b.name));
            }
        });
        add.requestFocus();
        return d;
    }

    /** one line: the file, then the project and folder it goes to */
    private static Node placeRow(DropItem item, Project p) {
        String folder = I18n.tr("ui.drop.folder." + item.kind().folder());
        Label name = UiKit.label(item.fileName(), "strong");
        name.setMaxWidth(Double.MAX_VALUE);
        Label where = UiKit.label(p.name + "  ›  " + folder + " (" + item.kind().folder() + "/)", "muted", "small");
        VBox text = new VBox(2, name, where);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, UiKit.iconBox(Icons.of(iconFor(item.kind()), 18), 32), text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String iconFor(DropKind kind) {
        return switch (kind) {
            case MOD -> Icons.PACKAGE;
            case RESOURCE_PACK -> Icons.PALETTE;
            case SHADER_PACK -> Icons.SPARKLE;
            case WORLD -> Icons.WORLD;
            case SCREENSHOT -> Icons.IMAGE;
            default -> Icons.ARCHIVE;
        };
    }

    private static void deliver(AppContext ctx, Project p, List<DropItem> items) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.drop.adding", p.name), true, (pr, c) -> {
            List<String> failures = new ArrayList<>();
            int done = 0;
            for (DropItem item : items) {
                c.check();
                try {
                    ctx.services().drops().deliver(ctx.services().projects().get(p.id).orElse(p), item, pr, c);
                    done++;
                } catch (Exception e) {
                    if (Tasks.isCancelled(e)) {
                        throw e;
                    }
                    failures.add(item.fileName() + ": " + Tasks.message(e));
                }
            }
            return new Outcome(done, failures);
        }, outcome -> {
            ctx.projectsChanged();
            if (outcome.failures().isEmpty()) {
                ctx.overlay().toast("success", outcome.done() == 1 ? I18n.tr("ui.drop.doneOne", items.get(0).fileName(), p.name)
                    : I18n.tr("ui.drop.doneMany", outcome.done(), p.name));
            } else {
                ctx.overlay().message(I18n.tr("ui.drop.refused"), I18n.tr("ui.drop.partial", outcome.done(), items.size(),
                    String.join("\n", outcome.failures())));
            }
        });
    }

    private record Outcome(int done, List<String> failures) {
    }
}
