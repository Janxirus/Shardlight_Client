package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

/** Small factory methods for the design system's building blocks. */
public final class UiKit {
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
        tooltip(b, accessibleName);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static void tooltip(Node n, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(Duration.millis(350));
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

    public static VBox emptyState(String icon, String title, String body, Node action) {
        VBox v = new VBox(10);
        v.setAlignment(Pos.CENTER);
        v.setPadding(new Insets(36));
        Node i = Icons.of(icon, 40);
        i.getStyleClass().add("faint");
        Label t = label(title, "h3");
        Label b = label(body, "muted");
        b.setMaxWidth(440);
        b.setStyle("-fx-text-alignment: center;");
        v.getChildren().addAll(i, t, b);
        if (action != null) {
            v.getChildren().add(action);
        }
        v.getStyleClass().add("card");
        return v;
    }

    /** a segmented control; returns the group, calls onSelect with the chosen index */
    public static HBox segmented(List<String> options, int selected, Consumer<Integer> onSelect) {
        HBox box = new HBox();
        box.getStyleClass().add("segmented");
        ToggleGroup g = new ToggleGroup();
        for (int i = 0; i < options.size(); i++) {
            ToggleButton b = new ToggleButton(options.get(i));
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
        return box;
    }

    public static ProgressBar progress(double value) {
        ProgressBar p = new ProgressBar(value);
        p.setMaxWidth(Double.MAX_VALUE);
        return p;
    }

    /** a settings row: title + description on the left, a control on the right */
    public static HBox settingRow(String title, String description, Node control) {
        Label t = label(title);
        t.setStyle("-fx-font-weight: bold;");
        VBox text = new VBox(2, t);
        if (description != null && !description.isBlank()) {
            text.getChildren().add(label(description, "muted", "small"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox h = new HBox(16, text, control);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(6, 0, 6, 0));
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

    public static String duration(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 60) {
            return I18n.tr("ui.duration.minutes", minutes);
        }
        return I18n.tr("ui.duration.hours", minutes / 60, minutes % 60);
    }
}
