package dev.shardlight.client.app.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Polygon;

/**
 * The Shardlight mark: a faceted crystal shard catching light. Three facets in the accent and the
 * secondary colour, so the logo follows the user's accent.
 */
public final class ShardLogo {
    private ShardLogo() {
    }

    public static Node create(double size, Color accent, Color accent2) {
        double s = size / 64.0;
        Polygon left = poly(s, 32, 4, 14, 22, 26, 60, 32, 60);
        Polygon right = poly(s, 32, 4, 50, 22, 38, 60, 32, 60);
        Polygon core = poly(s, 32, 12, 24, 23, 30, 52, 34, 52, 40, 23);
        Polygon tip = poly(s, 32, 4, 24, 23, 32, 12, 40, 23, 50, 22, 32, 4);
        left.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
            new Stop(0, accent.deriveColor(0, 1, 1.15, 1)), new Stop(1, accent.deriveColor(0, 1, 0.55, 1))));
        right.setFill(new LinearGradient(1, 0, 0, 1, true, CycleMethod.NO_CYCLE,
            new Stop(0, accent2.deriveColor(0, 1, 1.1, 1)), new Stop(1, accent2.deriveColor(0, 1, 0.5, 1))));
        core.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.WHITE.deriveColor(0, 1, 1, 0.92)), new Stop(1, accent.interpolate(accent2, 0.5).deriveColor(0, 1, 1.2, 0.35))));
        tip.setFill(Color.WHITE.deriveColor(0, 1, 1, 0.18));
        Group g = new Group(left, right, core, tip);
        DropShadow glow = new DropShadow(size * 0.28, accent.deriveColor(0, 1, 1, 0.55));
        g.setEffect(glow);
        g.getStyleClass().add("shard-logo");
        g.setAccessibleText("Shardlight");
        return g;
    }

    private static Polygon poly(double s, double... xy) {
        double[] scaled = new double[xy.length];
        for (int i = 0; i < xy.length; i++) {
            scaled[i] = xy[i] * s;
        }
        return new Polygon(scaled);
    }
}
