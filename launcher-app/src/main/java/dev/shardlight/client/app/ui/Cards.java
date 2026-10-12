package dev.shardlight.client.app.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The card family. Every card is a {@code .card} that lifts 2 px under the pointer or keyboard focus; cards
 * that do something when clicked get {@link #onOpen}. Text that could run long is kept to one line so cards in
 * a row line up, and action buttons always sit on the card's bottom edge.
 */
public final class Cards {
    private Cards() {
    }

    /** make {@code card} open something on click, Enter or Space */
    public static <T extends Region> T onOpen(T card, Runnable open) {
        card.getStyleClass().add("interactive");
        card.setFocusTraversable(true);
        card.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && !fromButton(e.getTarget(), card)) {
                open.run();
            }
        });
        card.setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) && !fromButton(e.getTarget(), card)) {
                open.run();
                e.consume();
            }
        });
        return card;
    }

    /** true when the event started on a button (or a menu) inside {@code card}, which handles it itself */
    private static boolean fromButton(Object target, Node card) {
        Node n = target instanceof Node t ? t : null;
        while (n != null && n != card) {
            if (n instanceof javafx.scene.control.ButtonBase || n instanceof javafx.scene.control.CheckBox) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private static VBox base(String kind) {
        VBox v = new VBox();
        v.getStyleClass().addAll("card", kind);
        Motion.lift(v);
        return v;
    }

    private static Label oneLine(String text, String... classes) {
        Label l = UiKit.label(text, classes);
        l.setWrapText(false);
        return l;
    }

    /** a project: art, name, version line, one-line description, chips, and actions on the bottom edge */
    public static VBox project(Node art, String name, String meta, String description, List<Node> chips, Node... actions) {
        VBox head = new VBox(oneLine(name, "project-name"), oneLine(meta, "muted", "small"));
        head.getStyleClass().add("card-head-text");
        HBox.setHgrow(head, Priority.ALWAYS);
        HBox top = new HBox(art, head);
        top.getStyleClass().add("card-head");
        FlowPane chipRow = new FlowPane();
        chipRow.getStyleClass().add("chip-row");
        chipRow.getChildren().addAll(chips);
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(actions);
        buttons.getStyleClass().add("card-actions");
        VBox v = base("project-card");
        v.getChildren().addAll(top, chipRow, oneLine(description, "faint", "small"), grow, buttons);
        v.setAccessibleText(name + ", " + meta);
        return v;
    }

    /** a search result for a mod, pack or shader: icon, title, author, summary, chips and one action */
    public static HBox modResult(Node icon, String title, String author, String summary, List<Node> chips, Node action) {
        HBox titleRow = new HBox(oneLine(title, "h3"), oneLine(author, "faint", "small"));
        titleRow.getStyleClass().add("card-title-row");
        Label body = UiKit.label(summary, "muted", "small", "clamp-2");
        body.setMinHeight(Region.USE_COMPUTED_SIZE);
        FlowPane chipRow = new FlowPane();
        chipRow.getStyleClass().add("chip-row");
        chipRow.getChildren().addAll(chips);
        VBox text = new VBox(titleRow, body, chipRow);
        text.getStyleClass().add("card-head-text");
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox h = new HBox(icon, text);
        if (action != null) {
            h.getChildren().add(action);
        }
        h.getStyleClass().addAll("card", "mod-result-card");
        h.setAlignment(Pos.CENTER_LEFT);
        Motion.lift(h);
        h.setAccessibleText(title + ", " + author);
        return h;
    }

    /** a performance preset: icon, name, one line on what it does; selected shows the accent outline */
    public static VBox preset(String icon, String name, String description, boolean selected) {
        StackPane i = UiKit.iconBox(Icons.of(icon, 18), ThemeManager.em(2.286));
        VBox v = base("preset-card");
        if (selected) {
            v.getStyleClass().add("selected");
        }
        Label d = UiKit.label(description, "muted", "small");
        v.getChildren().addAll(i, UiKit.label(name, "preset-name"), d);
        v.setAccessibleText(name);
        return v;
    }

    /** an interface theme: a preview strip in its colours and its name */
    public static VBox theme(String name, Node preview, boolean selected) {
        VBox v = base("theme-card");
        if (selected) {
            v.getStyleClass().add("selected");
        }
        StackPane frame = new StackPane(preview);
        frame.getStyleClass().add("theme-preview");
        HBox label = new HBox(oneLine(name, "strong"));
        if (selected) {
            Node check = Icons.of(Icons.CHECK, 14);
            check.getStyleClass().add("accent-icon");
            label.getChildren().addAll(UiKit.spacer(), check);
        }
        label.setAlignment(Pos.CENTER_LEFT);
        v.getChildren().addAll(frame, label);
        v.setAccessibleText(name);
        return v;
    }

    /** a server: icon, name, address, a status chip and one action */
    public static HBox server(Node icon, String name, String address, Node status, Node action) {
        VBox text = new VBox(oneLine(name, "h3"), oneLine(address, "mono", "faint"));
        text.getStyleClass().add("card-head-text");
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox h = new HBox(icon, text);
        if (status != null) {
            h.getChildren().add(status);
        }
        if (action != null) {
            h.getChildren().add(action);
        }
        h.getStyleClass().addAll("card", "server-card");
        h.setAlignment(Pos.CENTER_LEFT);
        Motion.lift(h);
        h.setAccessibleText(name + ", " + address);
        return h;
    }

    /** a world: its picture across the top, name, a details line and one action */
    public static VBox world(Node picture, String name, String meta, Node action) {
        StackPane pic = new StackPane(picture);
        pic.getStyleClass().add("world-picture");
        HBox foot = new HBox(new VBox(oneLine(name, "strong"), oneLine(meta, "faint", "small")));
        HBox.setHgrow(foot.getChildren().get(0), Priority.ALWAYS);
        if (action != null) {
            foot.getChildren().add(action);
        }
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.getStyleClass().add("card-foot");
        VBox v = base("world-card");
        v.getChildren().addAll(pic, foot);
        v.setAccessibleText(name + ", " + meta);
        return v;
    }

    /** a number with what it counts, and an icon */
    public static HBox stat(String icon, String value, String label) {
        StackPane i = UiKit.iconBox(Icons.of(icon, 18), ThemeManager.em(2.286));
        i.getStyleClass().add("accent-icon");
        VBox text = new VBox(oneLine(value, "stat-value"), oneLine(label, "stat-label"));
        HBox h = new HBox(i, text);
        h.getStyleClass().addAll("card", "stat-card");
        h.setAlignment(Pos.CENTER_LEFT);
        h.setAccessibleText(label + ": " + value);
        return h;
    }
}
