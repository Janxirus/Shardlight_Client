package dev.shardlight.client.app.ui;

import javafx.scene.Node;
import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays items out in columns, each item going under the shortest column so pictures of different shapes pack
 * without gaps. Each item is given a height/width ratio when added and is sized to fit its column; the number of
 * columns follows the width.
 */
public final class MasonryPane extends Pane {
    private final List<Node> items = new ArrayList<>();
    private final List<Double> ratios = new ArrayList<>();
    private final double minColumnEm;
    private final double gapEm;

    public MasonryPane(double minColumnEm, double gapEm) {
        this.minColumnEm = minColumnEm;
        this.gapEm = gapEm;
        getStyleClass().add("masonry");
    }

    /** add {@code node}, whose height is {@code ratio} times its width */
    public void add(Node node, double ratio) {
        items.add(node);
        ratios.add(Math.max(0.2, Math.min(5, ratio)));
        getChildren().add(node);
        requestLayout();
    }

    public void clear() {
        items.clear();
        ratios.clear();
        getChildren().clear();
    }

    private int columns(double width) {
        double min = ThemeManager.em(minColumnEm);
        double gap = ThemeManager.em(gapEm);
        return Math.max(1, (int) ((width + gap) / (min + gap)));
    }

    /** where each item goes for a pane {@code width} wide: {x, y, w, h} per item, then the total height */
    private double[][] place(double width) {
        double gap = ThemeManager.em(gapEm);
        int cols = columns(width);
        double colW = (width - gap * (cols - 1)) / cols;
        double[] heights = new double[cols];
        double[][] out = new double[items.size() + 1][];
        for (int i = 0; i < items.size(); i++) {
            int c = 0;
            for (int k = 1; k < cols; k++) {
                if (heights[k] < heights[c]) {
                    c = k;
                }
            }
            double h = colW * ratios.get(i);
            out[i] = new double[] {c * (colW + gap), heights[c], colW, h};
            heights[c] += h + gap;
        }
        double total = 0;
        for (double h : heights) {
            total = Math.max(total, h - gap);
        }
        out[items.size()] = new double[] {0, 0, width, Math.max(0, total)};
        return out;
    }

    @Override
    protected double computePrefHeight(double width) {
        double w = width > 0 ? width : getWidth() > 0 ? getWidth() : ThemeManager.em(60);
        return snappedTopInset() + place(w - snappedLeftInset() - snappedRightInset())[items.size()][3] + snappedBottomInset();
    }

    @Override
    protected void layoutChildren() {
        double[][] spots = place(getWidth() - snappedLeftInset() - snappedRightInset());
        for (int i = 0; i < items.size(); i++) {
            double[] s = spots[i];
            items.get(i).resizeRelocate(snappedLeftInset() + s[0], snappedTopInset() + s[1], s[2], s[3]);
        }
    }
}
