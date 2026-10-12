package dev.shardlight.client.app.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** Numbered steps joined by lines: finished steps show a check, the current one is accented. */
public final class StepProgress extends HBox {
    private final List<String> steps;
    private int current;

    public StepProgress(List<String> steps, int current) {
        this.steps = List.copyOf(steps);
        getStyleClass().add("step-progress");
        setAlignment(Pos.TOP_CENTER);
        setCurrent(current);
    }

    public void setCurrent(int index) {
        current = index;
        getChildren().clear();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                Region line = new Region();
                line.getStyleClass().addAll("step-line", i <= current ? "done" : "todo");
                line.setMaxHeight(Region.USE_PREF_SIZE);
                // the box's top padding puts the line level with the middle of the dots
                VBox lineBox = new VBox(line);
                lineBox.getStyleClass().add("step-line-box");
                HBox.setHgrow(lineBox, Priority.ALWAYS);
                getChildren().add(lineBox);
            }
            String state = i < current ? "done" : i == current ? "current" : "todo";
            StackPane dot = new StackPane(i < current ? Icons.of(Icons.CHECK, 14) : new Label(String.valueOf(i + 1)));
            dot.getStyleClass().addAll("step-dot", state);
            Label name = UiKit.label(steps.get(i), "step-name", state, "small");
            VBox step = new VBox(dot, name);
            step.getStyleClass().add("step");
            step.setAlignment(Pos.TOP_CENTER);
            step.setAccessibleText(name.getText());
            getChildren().add(step);
        }
    }

    public int current() {
        return current;
    }
}
