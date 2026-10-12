package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * In-window modal dialogs and toasts. Dialogs dim the app, take keyboard focus, close with Escape (when
 * cancellable) and block the content behind them; they stack.
 */
public final class Overlay {
    private final StackPane root;
    private final Node content;
    private final VBox toasts = new VBox();
    private final Deque<StackPane> open = new ArrayDeque<>();

    public Overlay(StackPane root, Node content) {
        this.root = root;
        this.content = content;
        toasts.getStyleClass().add("toast-host");
        toasts.setPickOnBounds(false);
        toasts.setMouseTransparent(false);
        StackPane.setAlignment(toasts, Pos.BOTTOM_RIGHT);
        toasts.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        root.getChildren().add(toasts);
    }

    /** a dialog handle */
    public final class Dialog {
        private final StackPane layer;
        private Runnable onClose;

        Dialog(StackPane layer) {
            this.layer = layer;
        }

        public void close() {
            if (!root.getChildren().contains(layer)) {
                return;
            }
            root.getChildren().remove(layer);
            open.remove(layer);
            content.setDisable(!open.isEmpty());
            if (onClose != null) {
                onClose.run();
            }
        }

        public Dialog onClose(Runnable r) {
            onClose = r;
            return this;
        }
    }

    /** show {@code body} as a modal; {@code cancellable} lets Escape and the backdrop close it */
    public Dialog show(Node body, boolean cancellable, boolean wide) {
        VBox card = body instanceof VBox v && v.getStyleClass().contains("modal") ? v : new VBox(body);
        if (!card.getStyleClass().contains("modal")) {
            card.getStyleClass().add("modal");
        }
        if (wide) {
            card.getStyleClass().add("wide");
        }
        card.setMaxHeight(VBox.USE_PREF_SIZE);
        StackPane layer = new StackPane(card);
        layer.getStyleClass().add("modal-overlay");
        Dialog d = new Dialog(layer);
        if (cancellable) {
            layer.setOnMouseClicked(e -> {
                if (e.getTarget() == layer) {
                    d.close();
                }
            });
            layer.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    d.close();
                    e.consume();
                }
            });
        }
        content.setDisable(true);
        open.push(layer);
        root.getChildren().add(root.getChildren().indexOf(toasts), layer);
        Motion.fadeIn(layer, Motion.Speed.MEDIUM);
        Motion.pop(card, Motion.Speed.SLOW, 0.96);
        layer.requestFocus();
        card.requestFocus();
        return d;
    }

    /** standard modal layout: title, body, buttons on the right */
    public static VBox modal(String title, Node body, Node... buttons) {
        Label t = UiKit.label(title, "modal-title");
        HBox actions = new HBox(10);
        actions.getStyleClass().add("modal-actions");
        actions.getChildren().addAll(buttons);
        VBox v = new VBox(t, body, actions);
        v.getStyleClass().add("modal");
        v.setAccessibleText(title);
        return v;
    }

    /**
     * Ask before something destructive. {@code details} lists exactly what will be affected; the confirm
     * button is red when {@code danger}.
     */
    public void confirm(String title, String message, java.util.List<String> details, String confirmText, boolean danger,
                        Runnable onConfirm) {
        VBox body = new VBox(8, UiKit.label(message));
        for (String d : details) {
            body.getChildren().add(UiKit.label("• " + d, "muted"));
        }
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button ok = new Button(confirmText);
        ok.getStyleClass().add(danger ? "danger" : "primary");
        VBox m = modal(title, body, cancel, ok);
        Dialog d = show(m, true, false);
        cancel.setOnAction(e -> d.close());
        ok.setOnAction(e -> {
            d.close();
            onConfirm.run();
        });
        cancel.requestFocus();
    }

    /** a simple message with an OK button */
    public void message(String title, String message) {
        Button ok = new Button(I18n.tr("common.ok"));
        ok.getStyleClass().add("primary");
        ok.setDefaultButton(true);
        Dialog d = show(modal(title, UiKit.label(message), ok), true, false);
        ok.setOnAction(e -> d.close());
        ok.requestFocus();
    }

    /** a toast in the bottom-right corner; kind is info, success, warning or danger */
    public void toast(String kind, String text) {
        HBox banner = UiKit.banner(kind, text);
        banner.getStyleClass().setAll("toast", kind);
        Button close = UiKit.iconButton(Icons.CLOSE, I18n.tr("common.close"), () -> toasts.getChildren().remove(banner));
        banner.getChildren().add(close);
        HBox.setHgrow(banner, Priority.NEVER);
        toasts.getChildren().add(banner);
        Motion.enter(banner, Motion.Speed.MEDIUM, Motion.ENTER_OFFSET);
        while (toasts.getChildren().size() > 4) {
            toasts.getChildren().remove(0);
        }
        PauseTransition p = new PauseTransition(Duration.seconds(kind.equals("danger") ? 9 : 5));
        p.setOnFinished(e -> Motion.exit(banner, Motion.Speed.MEDIUM, () -> toasts.getChildren().remove(banner)));
        p.play();
    }

    public boolean hasOpenDialog() {
        return !open.isEmpty();
    }

    /** close the topmost dialog (used by --capture between pages) */
    public void closeTop() {
        StackPane top = open.peek();
        if (top != null) {
            root.getChildren().remove(top);
            open.remove(top);
            content.setDisable(!open.isEmpty());
        }
    }
}
