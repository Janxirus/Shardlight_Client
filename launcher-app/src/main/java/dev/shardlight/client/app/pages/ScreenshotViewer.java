package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Screenshot;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** A screenshot at full size over the window. Left and Right (or the arrows) move through them, Escape closes. */
final class ScreenshotViewer {
    private final AppContext ctx;
    private final List<Screenshot> shots;
    private final Consumer<Screenshot> onDelete;
    private final ImageView image = new ImageView();
    private final Label title = UiKit.label("", "strong", "on-art");
    private final Label subtitle = UiKit.label("", "small", "on-art");
    private final StackPane stage = new StackPane(image);
    private Overlay.Dialog dialog;
    private int index;

    private ScreenshotViewer(AppContext ctx, List<Screenshot> shots, int index, Consumer<Screenshot> onDelete) {
        this.ctx = ctx;
        this.shots = shots;
        this.index = index;
        this.onDelete = onDelete;
    }

    static void show(AppContext ctx, List<Screenshot> shots, int index, Consumer<Screenshot> onDelete) {
        new ScreenshotViewer(ctx, shots, index, onDelete).open();
    }

    private void open() {
        Button prev = UiKit.iconButton(Icons.BACK, I18n.tr("ui.shots.previous"), () -> step(-1));
        Button next = UiKit.iconButton(Icons.CHEVRON_RIGHT, I18n.tr("ui.shots.next"), () -> step(1));
        prev.getStyleClass().add("art-action");
        next.getStyleClass().add("art-action");
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.shots.openFolder"),
            () -> ctx.openFolder(shots.get(index).file().getParent()));
        Button delete = UiKit.iconButton(Icons.TRASH, I18n.tr("ui.shots.trash"), () -> {
            Screenshot s = shots.get(index);
            dialog.close();
            onDelete.accept(s);
        });
        Button close = UiKit.iconButton(Icons.CLOSE, I18n.tr("common.close"), () -> dialog.close());
        for (Button b : List.of(folder, delete, close)) {
            b.getStyleClass().add("art-action");
        }
        HBox top = new HBox(new VBox(title, subtitle), UiKit.spacer(), folder, delete, close);
        top.getStyleClass().add("viewer-bar");
        HBox.setHgrow(top, Priority.ALWAYS);

        image.setPreserveRatio(true);
        image.setSmooth(true);
        stage.getStyleClass().add("viewer-stage");
        image.fitWidthProperty().bind(stage.widthProperty());
        image.fitHeightProperty().bind(stage.heightProperty());
        StackPane.setAlignment(prev, Pos.CENTER_LEFT);
        StackPane.setAlignment(next, Pos.CENTER_RIGHT);
        stage.getChildren().addAll(prev, next);
        VBox.setVgrow(stage, Priority.ALWAYS);

        VBox viewer = new VBox(top, stage);
        viewer.getStyleClass().addAll("modal", "viewer");
        viewer.setFocusTraversable(true);
        viewer.prefWidthProperty().bind(ctx.scene().widthProperty().multiply(0.94));
        viewer.prefHeightProperty().bind(ctx.scene().heightProperty().multiply(0.9));
        viewer.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case LEFT -> step(-1);
                case RIGHT -> step(1);
                case HOME -> go(0);
                case END -> go(shots.size() - 1);
                default -> {
                    return;
                }
            }
            e.consume();
        });
        viewer.setAccessibleText(I18n.tr("ui.shots.viewer"));
        dialog = ctx.overlay().show(viewer, true, false, "viewer-layer");
        go(index);
        Platform.runLater(viewer::requestFocus);
    }

    private void step(int delta) {
        go(Math.floorMod(index + delta, shots.size()));
    }

    private void go(int i) {
        index = Math.max(0, Math.min(shots.size() - 1, i));
        Screenshot s = shots.get(index);
        image.setImage(new Image(s.file().toUri().toString(), 0, 0, true, true, true));
        title.setText(s.file().getFileName().toString());
        subtitle.setText(I18n.tr("ui.shots.position", index + 1, shots.size()) + "   ·   " + ProjectText.ago(s.takenAt())
            + "   ·   " + UiKit.bytes(s.sizeBytes()));
    }
}
