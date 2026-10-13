package dev.shardlight.client.app.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Region;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;

/**
 * A round progress indicator: a track and an arc that grows clockwise from the top. Negative progress means
 * unknown, shown as a quarter arc that pulses. Size comes from the style class ({@code .progress-ring},
 * {@code .small}, {@code .large}).
 */
public final class ProgressRing extends Region {
    private final Circle track = new Circle();
    private final Arc bar = new Arc();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private javafx.animation.Animation pulse;

    public ProgressRing(double value) {
        getStyleClass().add("progress-ring");
        track.getStyleClass().add("ring-track");
        bar.getStyleClass().add("ring-bar");
        bar.setType(ArcType.OPEN);
        track.setManaged(false);
        bar.setManaged(false);
        getChildren().addAll(track, bar);
        progress.addListener((o, a, b) -> update());
        setProgress(value);
        setAccessibleRole(javafx.scene.AccessibleRole.PROGRESS_INDICATOR);
    }

    public DoubleProperty progressProperty() {
        return progress;
    }

    public void setProgress(double v) {
        progress.set(v);
        update();
    }

    private void update() {
        double v = progress.get();
        if (v < 0) {
            bar.setLength(-90);
            if (pulse == null) {
                pulse = Motion.pulse(bar, 0.35, 0);
            }
        } else {
            if (pulse != null) {
                pulse.stop();
                pulse = null;
                bar.setOpacity(1);
            }
            bar.setLength(-360 * Math.min(1, v));
        }
        setAccessibleText(v < 0 ? null : Math.round(v * 100) + "%");
    }

    @Override
    protected void layoutChildren() {
        double size = Math.min(getWidth(), getHeight());
        double stroke = bar.getStrokeWidth();
        double r = Math.max(1, (size - stroke) / 2);
        double cx = getWidth() / 2;
        double cy = getHeight() / 2;
        track.setCenterX(cx);
        track.setCenterY(cy);
        track.setRadius(r);
        bar.setCenterX(cx);
        bar.setCenterY(cy);
        bar.setRadiusX(r);
        bar.setRadiusY(r);
        bar.setStartAngle(90);
    }
}
