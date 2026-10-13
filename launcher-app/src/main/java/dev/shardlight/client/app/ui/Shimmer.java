package dev.shardlight.client.app.ui;

import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/** An indeterminate progress bar: a soft highlight sweeping along the track while the length is unknown. */
public final class Shimmer extends Region {
    private final Region glow = new Region();
    private final Rectangle clip = new Rectangle();
    private double sweptWidth = -1;

    public Shimmer() {
        getStyleClass().add("shimmer");
        glow.getStyleClass().add("shimmer-glow");
        glow.setManaged(false);
        getChildren().add(glow);
        setClip(clip);
        setAccessibleRole(javafx.scene.AccessibleRole.PROGRESS_INDICATOR);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        clip.setWidth(w);
        clip.setHeight(h);
        clip.setArcWidth(h);
        clip.setArcHeight(h);
        double gw = w * 0.4;
        glow.resizeRelocate(0, 0, gw, h);
        if (w > 0 && Math.abs(w - sweptWidth) > 0.5) {
            sweptWidth = w;
            Motion.sweepX(glow, -gw, w);
        }
    }
}
