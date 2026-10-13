package dev.shardlight.client.app.ui;

import dev.shardlight.client.app.ui.Motion.Speed;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

/** A heading button that shows or hides its content (collapsed unless asked otherwise). */
public final class Disclosure extends VBox {
    private final BooleanProperty expanded = new SimpleBooleanProperty();
    private final Button header = new Button();
    private final VBox content = new VBox();
    private final String title;

    public Disclosure(String title, boolean open, Node... children) {
        this.title = title;
        getStyleClass().add("disclosure");
        header.getStyleClass().addAll("ghost", "disclosure-header");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setOnAction(e -> expanded.set(!expanded.get()));
        content.getStyleClass().addAll("disclosure-content", "stack");
        content.getChildren().addAll(children);
        getChildren().addAll(header, content);
        expanded.addListener((o, a, b) -> update(true));
        expanded.set(open);
        update(false);
    }

    private void update(boolean animate) {
        boolean open = expanded.get();
        header.setText(title);
        header.setGraphic(Icons.of(open ? Icons.CHEVRON_DOWN : Icons.CHEVRON_RIGHT, 16));
        header.setAccessibleText(title + ", " + (open ? "expanded" : "collapsed"));
        content.setVisible(open);
        content.setManaged(open);
        if (open && animate) {
            Motion.enter(content, Speed.MEDIUM, Motion.ENTER_OFFSET);
        }
    }

    public BooleanProperty expandedProperty() {
        return expanded;
    }

    /** the content box, for adding children later */
    public VBox content() {
        return content;
    }
}
