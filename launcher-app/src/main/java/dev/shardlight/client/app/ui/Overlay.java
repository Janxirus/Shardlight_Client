package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-window modal dialogs, popovers and the one toast system. Dialogs dim the app, take keyboard focus, close
 * with Escape (when cancellable) and block the content behind them; they stack. Popovers float under the
 * control that opened them and close on a click outside or Escape. Toasts (success, info, warning, error)
 * stack up to {@link #MAX_TOASTS} above the Play bar, can carry actions such as Undo, Open or Retry, and
 * every one is kept in the session's notification history.
 */
public final class Overlay {
    /** how many toasts show at once; older ones leave as new ones arrive */
    public static final int MAX_TOASTS = 3;

    /** a button on a toast; running it closes the toast */
    public record Action(String label, Runnable run) {
    }

    /** a toast as kept in the history */
    public record Notice(String kind, String text, Instant at) {
    }

    private final StackPane root;
    private final Node content;
    private final VBox toasts = new VBox();
    private final Deque<StackPane> open = new ArrayDeque<>();
    private final List<Notice> history = new ArrayList<>();
    private final List<Runnable> historyListeners = new CopyOnWriteArrayList<>();
    private Pane popoverLayer;

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
        return show(body, cancellable, wide, null);
    }

    /** as above, with an extra style class on the backdrop layer (the command palette sits near the top) */
    public Dialog show(Node body, boolean cancellable, boolean wide, String layerClass) {
        closePopover();
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
        if (layerClass != null) {
            layer.getStyleClass().add(layerClass);
        }
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

    /**
     * The one dialog frame: a title, the body, and a footer with Cancel on the left and the main action on the
     * right. Buttons flagged {@code setCancelButton(true)} go left; the others go right in the order given, and
     * the primary one becomes the default button, so Enter confirms. A destructive (danger) button is never made
     * the default: Enter would otherwise delete while Cancel has the focus. Esc closes a cancellable dialog.
     */
    public static VBox modal(String title, Node body, Node... buttons) {
        Label t = UiKit.label(title, "modal-title");
        t.setWrapText(true);
        HBox actions = new HBox(10);
        actions.getStyleClass().add("modal-actions");
        actions.setAlignment(Pos.CENTER);
        fillActions(actions, buttons);
        VBox v = new VBox(t, body, actions);
        v.getStyleClass().add("modal");
        v.setAccessibleText(title);
        return v;
    }

    /** (re)fill a modal's footer: cancel buttons left, the rest right, the primary one the default button */
    public static void fillActions(HBox actions, Node... buttons) {
        HBox start = new HBox(10);
        HBox end = new HBox(10);
        start.setAlignment(Pos.CENTER_LEFT);
        end.setAlignment(Pos.CENTER_RIGHT);
        Button main = null;
        for (Node b : buttons) {
            if (b instanceof Button x && x.isCancelButton()) {
                start.getChildren().add(b);
            } else if (b instanceof Button x) {
                end.getChildren().add(b);
                if (x.getStyleClass().contains("primary") && main == null) {
                    main = x;
                }
            } else if (!(b instanceof Region r && r.getChildrenUnmodifiable().isEmpty())) {
                end.getChildren().add(b);
            }
        }
        if (main != null) {
            main.setDefaultButton(true);
        }
        actions.getChildren().setAll(start, UiKit.spacer(), end);
    }

    /**
     * Ask before something destructive. {@code details} lists exactly what will be affected; the confirm
     * button is red when {@code danger}, and the message and list then sit in a red panel, so what is lost is plain to see.
     */
    public void confirm(String title, String message, java.util.List<String> details, String confirmText, boolean danger,
                        Runnable onConfirm) {
        Label text = UiKit.label(message);
        text.setWrapText(true);
        VBox body = new VBox(8, text);
        for (String d : details) {
            Label l = UiKit.label("• " + d, "muted");
            l.setWrapText(true);
            body.getChildren().add(l);
        }
        if (danger) {
            body.getStyleClass().add("modal-lost");
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
        (danger ? cancel : ok).requestFocus();
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

    /** a toast above the Play bar; kind is success, info, warning or error ("danger" means error) */
    public void toast(String kind, String text) {
        toast(kind, text, new Action[0]);
    }

    /** a toast with buttons such as Undo, Open or Retry; each one closes the toast after it runs */
    public void toast(String kind, String text, Action... actions) {
        String k = "error".equals(kind) ? "danger" : kind;
        history.add(new Notice(k, text, Instant.now()));
        historyListeners.forEach(Runnable::run);
        HBox toast = UiKit.banner(k, text);
        toast.getStyleClass().setAll("toast", k);
        toast.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        Runnable dismiss = () -> Motion.exit(toast, Motion.Speed.MEDIUM, () -> toasts.getChildren().remove(toast));
        for (Action a : actions) {
            Button b = UiKit.button(a.label(), null, () -> {
                dismiss.run();
                a.run().run();
            }, "ghost", "small", "toast-action");
            toast.getChildren().add(b);
        }
        Button close = UiKit.iconButton(Icons.CLOSE, I18n.tr("common.close"), dismiss);
        close.getStyleClass().add("small");
        toast.getChildren().add(close);
        HBox.setHgrow(toast, Priority.NEVER);
        toasts.getChildren().add(toast);
        Motion.enter(toast, Motion.Speed.MEDIUM, Motion.ENTER_OFFSET);
        while (toasts.getChildren().size() > MAX_TOASTS) {
            toasts.getChildren().remove(0);
        }
        // stays while the pointer is on it; errors and toasts with actions stay longer
        double seconds = k.equals("danger") ? 9 : actions.length > 0 ? 8 : 5;
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> dismiss.run());
        toast.hoverProperty().addListener((o, was, now) -> {
            if (now) {
                p.stop();
            } else {
                p.playFromStart();
            }
        });
        p.play();
    }

    /** the undo snackbar: says what just happened and offers to take it back */
    public void undo(String text, Runnable undo) {
        toast("info", text, new Action(I18n.tr("ui.toast.undo"), undo));
    }

    /** take every toast off the screen at once (they stay in the history) */
    public void clearToasts() {
        toasts.getChildren().clear();
    }

    /** every toast shown this session, oldest first */
    public List<Notice> history() {
        return Collections.unmodifiableList(history);
    }

    public void clearHistory() {
        history.clear();
        historyListeners.forEach(Runnable::run);
    }

    /** called on the FX thread whenever a toast is added to the history or it is cleared */
    public void onHistoryChanged(Runnable r) {
        historyListeners.add(r);
    }

    /**
     * Show {@code content} floating under {@code anchor} (right edges lined up when {@code alignRight}). A click
     * outside or Escape closes it; so does opening another popover or a dialog.
     */
    public void popover(Node anchor, Region content, boolean alignRight) {
        closePopover();
        content.getStyleClass().add("popover");
        Pane layer = new Pane(content);
        layer.getStyleClass().add("popover-layer");
        layer.setOnMousePressed(e -> {
            if (e.getTarget() == layer) {
                closePopover();
            }
        });
        layer.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closePopover();
                anchor.requestFocus();
                e.consume();
            }
        });
        popoverLayer = layer;
        root.getChildren().add(root.getChildren().indexOf(toasts), layer);
        layer.applyCss();
        content.autosize();
        Bounds a = root.sceneToLocal(anchor.localToScene(anchor.getBoundsInLocal()));
        double w = content.prefWidth(-1);
        double h = content.prefHeight(w);
        content.resize(w, h);
        double gap = ThemeManager.em(0.286);
        double x = alignRight ? a.getMaxX() - w : a.getMinX();
        double y = a.getMaxY() + gap;
        x = Math.max(gap, Math.min(x, root.getWidth() - w - gap));
        if (y + h > root.getHeight() - gap) {
            y = Math.max(gap, a.getMinY() - h - gap);
        }
        content.relocate(x, y);
        Motion.enter(content, Motion.Speed.MEDIUM, -Motion.ENTER_OFFSET / 2);
        content.requestFocus();
    }

    public boolean hasPopover() {
        return popoverLayer != null;
    }

    public void closePopover() {
        if (popoverLayer != null) {
            root.getChildren().remove(popoverLayer);
            popoverLayer = null;
        }
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
