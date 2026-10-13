package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Small factory methods for the design system's building blocks. Bigger components live next to it:
 * {@link Fields} (search, number, slider, form fields), {@link Cards}, {@link Lists}, {@link ProgressRing},
 * {@link Shimmer}, {@link StepProgress}; dialogs, popovers and toasts are in {@link Overlay}.
 */
public final class UiKit {
    /** tooltips wait this long before showing */
    public static final Duration TOOLTIP_DELAY = Duration.millis(400);
    private static final String DISABLED_REASON = "sl.disabledReason";
    private static final String REASON_UPDATER = "sl.reasonUpdater";
    private static final String LOADING_GRAPHIC = "sl.loadingGraphic";

    private UiKit() {
    }

    /** a localized label */
    public static Label t(String key, String... styleClasses) {
        return label(I18n.tr(key), styleClasses);
    }

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    public static Button button(String text, String icon, Runnable action, String... styleClasses) {
        Button b = new Button(text);
        if (icon != null) {
            b.setGraphic(Icons.of(icon, 16));
        }
        b.getStyleClass().addAll(styleClasses);
        b.setOnAction(e -> action.run());
        b.setMnemonicParsing(false);
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    public static Button primary(String text, String icon, Runnable action) {
        return button(text, icon, action, "primary");
    }

    /** an icon-only button with an accessible name and tooltip */
    public static Button iconButton(String icon, String accessibleName, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.of(icon, 16));
        b.getStyleClass().addAll("icon-only", "ghost");
        b.setAccessibleText(accessibleName);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setMinWidth(Region.USE_PREF_SIZE);
        tooltip(b, accessibleName);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static void tooltip(Node n, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(TOOLTIP_DELAY);
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        Tooltip.install(n, tip);
    }

    public static VBox card(Node... children) {
        VBox v = new VBox(children);
        v.getStyleClass().add("card");
        return v;
    }

    public static HBox row(double spacing, Node... children) {
        HBox h = new HBox(spacing, children);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static VBox column(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Label chip(String text, String variant) {
        Label l = new Label(text);
        l.getStyleClass().add("chip");
        if (variant != null) {
            l.getStyleClass().add(variant);
        }
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    public static Label chip(String text, String variant, String icon) {
        Label l = chip(text, variant);
        if (icon != null) {
            l.setGraphic(Icons.of(icon, 13));
        }
        return l;
    }

    /** a coloured message strip; kind is info, warning, danger or success */
    public static HBox banner(String kind, String text, Node... actions) {
        String icon = switch (kind) {
            case "warning" -> Icons.WARNING;
            case "danger" -> Icons.ERROR;
            case "success" -> Icons.CHECK;
            default -> Icons.INFO;
        };
        Label l = label(text);
        HBox.setHgrow(l, Priority.ALWAYS);
        l.setMaxWidth(Double.MAX_VALUE);
        HBox h = new HBox(Icons.of(icon, 18), l);
        h.getChildren().addAll(actions);
        h.getStyleClass().addAll("banner", kind);
        h.setAccessibleText(text);
        return h;
    }

    /** a coloured strip around any content (several lines, links...) */
    public static HBox banner(String kind, Node content, Node... actions) {
        String icon = switch (kind) {
            case "warning" -> Icons.WARNING;
            case "danger" -> Icons.ERROR;
            case "success" -> Icons.CHECK;
            default -> Icons.INFO;
        };
        HBox.setHgrow(content, Priority.ALWAYS);
        if (content instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        HBox h = new HBox(Icons.of(icon, 18), content);
        h.getChildren().addAll(actions);
        h.getStyleClass().addAll("banner", kind);
        return h;
    }

    public static VBox stat(String value, String label) {
        Label v = label(value, "stat-value");
        Label l = label(label, "stat-label");
        VBox box = new VBox(2, v, l);
        box.setAccessibleText(label + ": " + value);
        return box;
    }

    public static VBox section(String title, Node... children) {
        VBox v = new VBox(10);
        v.getChildren().add(label(title, "h3"));
        v.getChildren().addAll(children);
        return v;
    }

    /** an empty state with a title: icon, title, one sentence, one action (may be null) */
    public static VBox emptyState(String icon, String title, String body, Node action) {
        VBox v = empty(Icons.of(icon, 40), body, action);
        v.getChildren().add(1, label(title, "h3"));
        return v;
    }

    /**
     * The empty state: an illustration slot (an icon or a picture), one sentence saying what goes here, and one
     * action (may be null).
     */
    public static VBox empty(Node illustration, String sentence, Node action) {
        VBox v = new VBox();
        v.getStyleClass().addAll("card", "empty-state");
        illustration.getStyleClass().add("faint");
        StackPane art = new StackPane(illustration);
        art.getStyleClass().add("empty-illustration");
        Label b = label(sentence, "muted", "empty-text");
        v.getChildren().addAll(art, b);
        if (action != null) {
            v.getChildren().add(action);
        }
        return v;
    }

    /** something failed to load: what went wrong and a Retry button */
    public static VBox errorState(String message, Runnable retry) {
        VBox v = empty(Icons.of(Icons.ERROR, 40), message,
            retry == null ? null : button(I18n.tr("ui.state.retry"), Icons.REFRESH, retry));
        v.getStyleClass().add("error-state");
        return v;
    }

    /** the network is unreachable: what still works and a Retry button */
    public static VBox offlineState(Runnable retry) {
        VBox v = empty(Icons.of(Icons.WIFI_OFF, 40), I18n.tr("ui.state.offline"),
            retry == null ? null : button(I18n.tr("ui.state.retry"), Icons.REFRESH, retry));
        v.getStyleClass().add("offline-state");
        return v;
    }

    /** a grey placeholder shape that pulses while content loads; shape is line, short, block or circle */
    public static Region skeleton(String shape) {
        Region r = new Region();
        r.getStyleClass().addAll("skeleton", shape);
        r.setMouseTransparent(true);
        Motion.pulse(r, 0.45, 0);
        return r;
    }

    /** a card-shaped skeleton: a round picture, a title line, a shorter line and a block */
    public static VBox skeletonCard() {
        HBox head = new HBox(skeleton("circle"), new VBox(skeleton("line"), skeleton("short")));
        head.getStyleClass().add("skeleton-head");
        ((VBox) head.getChildren().get(1)).getStyleClass().add("skeleton-lines");
        HBox.setHgrow(head.getChildren().get(1), Priority.ALWAYS);
        VBox v = new VBox(head, skeleton("block"));
        v.getStyleClass().addAll("card", "skeleton-card");
        v.setAccessibleText(I18n.tr("ui.state.loading"));
        return v;
    }

    /** a small count bubble (0 hides it) */
    public static Label badge(int count) {
        Label l = new Label();
        l.getStyleClass().addAll("badge", "tabular");
        setBadge(l, count);
        return l;
    }

    public static void setBadge(Label badge, int count) {
        badge.setText(count > 99 ? "99+" : String.valueOf(count));
        badge.setVisible(count > 0);
        badge.setManaged(count > 0);
    }

    /** an active filter that can be removed with its x button (or Delete / Backspace when focused) */
    public static HBox filterChip(String text, Runnable onRemove) {
        Label l = new Label(text);
        Button x = iconButton(Icons.CLOSE, I18n.tr("ui.chip.remove", text), onRemove);
        x.getStyleClass().add("chip-remove");
        HBox h = new HBox(l, x);
        h.getStyleClass().add("filter-chip");
        h.setAlignment(Pos.CENTER_LEFT);
        h.setMinWidth(Region.USE_PREF_SIZE);
        return h;
    }

    /** a copy button: copies {@code text} and shows a check mark for 1.5 s */
    public static Button copyButton(Supplier<String> text, String accessibleName) {
        Button b = iconButton(Icons.COPY, accessibleName, () -> { });
        b.setOnAction(e -> {
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(text.get());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            copied(b);
        });
        return b;
    }

    /** swap {@code b}'s icon for a check mark for 1.5 s (after it copied something) */
    public static void copied(Button b) {
        Node old = b.getGraphic();
        if (b.getStyleClass().contains("copied")) {
            return;
        }
        Node check = Icons.of(Icons.CHECK, 16);
        b.setGraphic(check);
        b.getStyleClass().add("copied");
        Motion.fadeIn(check, Motion.Speed.FAST);
        PauseTransition p = new PauseTransition(Duration.millis(1500));
        p.setOnFinished(e -> {
            b.setGraphic(old);
            b.getStyleClass().remove("copied");
        });
        p.play();
    }

    /**
     * Wrap {@code c} so that while it is disabled with a reason (see {@link #disable}) hovering it says why.
     * Disabled controls get no mouse events, so the tooltip sits on the wrapper.
     */
    public static StackPane explained(Control c) {
        StackPane wrap = new StackPane(c);
        wrap.getStyleClass().add("explained");
        wrap.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Tooltip tip = new Tooltip();
        tip.setShowDelay(TOOLTIP_DELAY);
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        Runnable update = () -> {
            Object r = c.getProperties().get(DISABLED_REASON);
            if (c.isDisabled() && r instanceof String reason) {
                tip.setText(reason);
                Tooltip.install(wrap, tip);
                c.setAccessibleHelp(reason);
            } else {
                Tooltip.uninstall(wrap, tip);
                c.setAccessibleHelp(null);
            }
        };
        c.getProperties().put(REASON_UPDATER, update);
        c.disabledProperty().addListener((o, a, b) -> update.run());
        update.run();
        return wrap;
    }

    /** disable {@code c} and say why ({@code reason} null enables it again) */
    public static void disable(Control c, String reason) {
        c.getProperties().put(DISABLED_REASON, reason);
        c.setDisable(reason != null);
        if (c.getProperties().get(REASON_UPDATER) instanceof Runnable r) {
            r.run();
        }
    }

    /**
     * Show {@code b} as busy: its label gives way to three pulsing dots while it keeps its width, and it ignores
     * clicks until loading ends.
     */
    public static void loading(Button b, boolean on) {
        if (on == b.getStyleClass().contains("loading")) {
            return;
        }
        if (on) {
            b.setMinWidth(b.getWidth());
            b.setPrefWidth(b.getWidth());
            b.getProperties().put(LOADING_GRAPHIC, b.getGraphic() == null ? b : b.getGraphic());
            b.setGraphic(dots());
            b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            b.getStyleClass().add("loading");
            b.setMouseTransparent(true);
            b.setAccessibleHelp(I18n.tr("ui.state.loading"));
        } else {
            Object g = b.getProperties().remove(LOADING_GRAPHIC);
            b.setGraphic(g instanceof Node n && n != b ? n : null);
            b.setContentDisplay(ContentDisplay.LEFT);
            b.getStyleClass().remove("loading");
            b.setMouseTransparent(false);
            b.setMinWidth(Region.USE_COMPUTED_SIZE);
            b.setPrefWidth(Region.USE_COMPUTED_SIZE);
            b.setAccessibleHelp(null);
        }
    }

    /** three dots pulsing one after another */
    public static HBox dots() {
        HBox h = new HBox();
        h.getStyleClass().add("loading-dots");
        h.setAlignment(Pos.CENTER);
        for (int i = 0; i < 3; i++) {
            Region d = new Region();
            d.getStyleClass().add("loading-dot");
            Motion.pulse(d, 0.25, i * 160);
            h.getChildren().add(d);
        }
        return h;
    }

    /** a segmented control; returns the group, calls onSelect with the chosen index */
    public static HBox segmented(List<String> options, int selected, Consumer<Integer> onSelect) {
        HBox box = new HBox();
        box.getStyleClass().add("segmented");
        fillSegments(box, options, selected, onSelect);
        return box;
    }

    /** like {@link #segmented}, for narrow places: the options move to a second row when the first is full */
    public static FlowPane segmentedWrapping(List<String> options, int selected, Consumer<Integer> onSelect) {
        FlowPane box = new FlowPane(2, 2);
        box.getStyleClass().add("segmented");
        fillSegments(box, options, selected, onSelect);
        return box;
    }

    private static void fillSegments(Pane box, List<String> options, int selected, Consumer<Integer> onSelect) {
        ToggleGroup g = new ToggleGroup();
        for (int i = 0; i < options.size(); i++) {
            ToggleButton b = new ToggleButton(options.get(i));
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setToggleGroup(g);
            b.setSelected(i == selected);
            int idx = i;
            b.setOnAction(e -> {
                if (!b.isSelected()) {
                    b.setSelected(true); // keep one selected
                }
                onSelect.accept(idx);
            });
            box.getChildren().add(b);
        }
    }

    public static ProgressBar progress(double value) {
        ProgressBar p = new ProgressBar(value);
        p.setMaxWidth(Double.MAX_VALUE);
        return p;
    }

    /** a settings row: title + description on the left, a control on the right */
    public static HBox settingRow(String title, String description, Node control) {
        Label t = label(title, "strong");
        VBox text = new VBox(2, t);
        if (description != null && !description.isBlank()) {
            text.getChildren().add(label(description, "muted", "small"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox h = new HBox(16, text, control);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("setting-row");
        control.setAccessibleText(title);
        if (control instanceof Region r) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        return h;
    }

    /** a square, rounded icon box (project icons, file types) */
    public static StackPane iconBox(Node content, double size) {
        StackPane p = new StackPane(content);
        p.getStyleClass().add("icon-box");
        p.setMinSize(size, size);
        p.setPrefSize(size, size);
        p.setMaxSize(size, size);
        return p;
    }

    public static String bytes(long b) {
        if (b < 0) {
            return I18n.tr("common.unknown");
        }
        if (b < 1024) {
            return b + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(I18n.locale(), v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    /** time left: seconds under a minute, else minutes and hours */
    public static String eta(long millis) {
        if (millis < 60_000) {
            return I18n.tr("ui.duration.seconds", Math.max(1, (millis + 500) / 1000));
        }
        return duration(millis);
    }

    public static String duration(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 60) {
            return I18n.tr("ui.duration.minutes", minutes);
        }
        return I18n.tr("ui.duration.hours", minutes / 60, minutes % 60);
    }
}
