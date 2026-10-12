package dev.shardlight.client.app.ui;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** An on/off switch: click, Space or Enter toggles it; screen readers see a toggle button. */
public final class ToggleSwitch extends StackPane {
    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected");
    private final Circle knob = new Circle(7.5);

    /** a switch named by the row it sits in ({@link UiKit#settingRow} sets the accessible name) */
    public ToggleSwitch(boolean initial) {
        this(initial, null);
    }

    public ToggleSwitch(boolean initial, String accessibleName) {
        getStyleClass().add("toggle-switch");
        knob.getStyleClass().add("knob");
        getChildren().add(knob);
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.TOGGLE_BUTTON);
        setAccessibleText(accessibleName);
        selected.set(initial);
        place(false);
        selected.addListener((o, a, b) -> place(!UiSettings.reducedMotion()));
        setOnMouseClicked(e -> {
            if (!isDisabled()) {
                selected.set(!selected.get());
                requestFocus();
            }
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                selected.set(!selected.get());
                e.consume();
            }
        });
    }

    private void place(boolean animate) {
        pseudo();
        double x = selected.get() ? 8.5 : -8.5;
        if (animate) {
            TranslateTransition t = new TranslateTransition(Duration.millis(120), knob);
            t.setToX(x);
            t.play();
        } else {
            knob.setTranslateX(x);
        }
        notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED);
    }

    private void pseudo() {
        getStyleClass().remove("on");
        if (selected.get()) {
            getStyleClass().add("on");
        }
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean v) {
        selected.set(v);
    }

    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        if (attribute == AccessibleAttribute.SELECTED) {
            return selected.get();
        }
        return super.queryAccessibleAttribute(attribute, parameters);
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) {
            selected.set(!selected.get());
        } else {
            super.executeAccessibleAction(action, parameters);
        }
    }
}
