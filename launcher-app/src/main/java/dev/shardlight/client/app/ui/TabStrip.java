package dev.shardlight.client.app.ui;

import javafx.geometry.Bounds;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A row of tabs with an underline that slides (and stretches) to the selected one. Left and Right move between
 * tabs when one has the focus. The content under the tabs is the page's own business: this only reports the
 * selected index. Tabs never shrink to fit: in a narrow window or a language with long words the row scrolls
 * (mouse wheel, or Left and Right) and keeps the selected tab in view.
 */
public final class TabStrip extends VBox {
    private final HBox tabs = new HBox();
    private final Region bar = new Region();
    private final Pane track = new Pane(bar);
    private final ScrollPane scroll = new ScrollPane();
    private final List<ToggleButton> buttons = new ArrayList<>();
    private final ToggleGroup group = new ToggleGroup();
    private final IntConsumer onSelect;
    private int selected;

    public TabStrip(List<String> labels, int initial, IntConsumer onSelect) {
        this.onSelect = onSelect;
        this.selected = initial;
        getStyleClass().add("tab-strip");
        tabs.getStyleClass().add("tab-strip-tabs");
        track.getStyleClass().add("tab-strip-track");
        bar.getStyleClass().add("tab-strip-bar");
        bar.setPrefWidth(1);
        bar.setMinWidth(1);
        bar.setMaxWidth(1);
        track.setMinHeight(Region.USE_PREF_SIZE);
        for (int i = 0; i < labels.size(); i++) {
            ToggleButton b = new ToggleButton(labels.get(i));
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setToggleGroup(group);
            b.getStyleClass().add("tab-strip-tab");
            b.setSelected(i == initial);
            int index = i;
            b.setOnAction(e -> {
                if (!b.isSelected()) {
                    b.setSelected(true);
                }
                select(index);
            });
            b.setOnKeyPressed(e -> {
                int to = e.getCode() == KeyCode.RIGHT ? index + 1 : e.getCode() == KeyCode.LEFT ? index - 1 : -1;
                if (to >= 0 && to < buttons.size()) {
                    select(to);
                    buttons.get(to).requestFocus();
                    e.consume();
                }
            });
            buttons.add(b);
        }
        tabs.getChildren().addAll(buttons);
        VBox row = new VBox(tabs, track);
        scroll.setContent(row);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPannable(false);
        scroll.getStyleClass().add("tab-strip-scroll");
        scroll.addEventFilter(ScrollEvent.SCROLL, e -> {
            double extra = row.getWidth() - scroll.getViewportBounds().getWidth();
            if (extra > 0) {
                double delta = Math.abs(e.getDeltaX()) > Math.abs(e.getDeltaY()) ? e.getDeltaX() : e.getDeltaY();
                scroll.setHvalue(Math.max(0, Math.min(1, scroll.getHvalue() - delta / extra)));
                e.consume();
            }
        });
        getChildren().add(scroll);
        for (ToggleButton b : buttons) {
            b.focusedProperty().addListener((o, a, now) -> {
                if (now) {
                    reveal(b);
                }
            });
        }
        tabs.layoutBoundsProperty().addListener((o, a, b) -> place(false));
        widthProperty().addListener((o, a, b) -> place(false));
        sceneProperty().addListener((o, a, b) -> place(false));
    }

    public int selected() {
        return selected;
    }

    /** select tab {@code index}: slides the underline and tells the page */
    public void select(int index) {
        if (index < 0 || index >= buttons.size()) {
            return;
        }
        boolean changed = index != selected;
        selected = index;
        buttons.get(index).setSelected(true);
        reveal(buttons.get(index));
        place(changed);
        if (changed) {
            onSelect.accept(index);
        }
    }

    /** scroll the row just far enough that {@code b} shows in full */
    private void reveal(ToggleButton b) {
        double view = scroll.getViewportBounds().getWidth();
        double total = tabs.getWidth();
        if (view <= 0 || total <= view) {
            return;
        }
        double left = scroll.getHvalue() * (total - view);
        Bounds bd = b.getBoundsInParent();
        if (bd.getMinX() < left) {
            scroll.setHvalue(bd.getMinX() / (total - view));
        } else if (bd.getMaxX() > left + view) {
            scroll.setHvalue((bd.getMaxX() - view) / (total - view));
        }
    }

    private void place(boolean animate) {
        ToggleButton b = buttons.get(selected);
        if (b.getWidth() <= 0) {
            return;
        }
        Bounds bd = b.getBoundsInParent();
        if (animate) {
            Motion.slideBar(bar, Motion.Speed.MEDIUM, bd.getMinX(), bd.getWidth());
        } else {
            bar.setTranslateX(bd.getMinX() + bd.getWidth() / 2 - 0.5);
            bar.setScaleX(bd.getWidth());
        }
    }
}
