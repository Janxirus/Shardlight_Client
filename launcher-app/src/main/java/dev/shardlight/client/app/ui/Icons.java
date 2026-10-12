package dev.shardlight.client.app.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

import java.util.Set;

/**
 * Shardlight Client's own icon set, drawn on a 24x24 grid as outlines: a 2-unit stroke with round caps and
 * joins, content kept inside 2.5..21.5 and corner radii of 1 to 2 units. A few glyphs that read better
 * solid ({@link #PLAY}, {@link #STOP}, {@link #MORE}) are filled as well. Icons scale with their size and
 * take their colour from the {@code -sl-icon} looked-up colour, which any ancestor can set:
 * {@code .button.primary { -sl-icon: -sl-accent-text; }}. Dots are zero-length strokes ({@code h.01}).
 */
public final class Icons {
    // navigation
    public static final String HOME = "M3 10.5 12 3l9 7.5M5.5 8.6V19.5a1.5 1.5 0 0 0 1.5 1.5H10v-6h4v6h3a1.5 1.5 0 0 0 1.5-1.5V8.6";
    public static final String LIBRARY = "M12 3 3 7.5l9 4.5 9-4.5zM3 12l9 4.5 9-4.5M3 16.5 12 21l9-4.5";
    public static final String DISCOVER = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM15.5 8.5l-2 5-5 2 2-5z";
    public static final String SHARD = "M12 2.5 18.5 9 12 21.5 5.5 9zM5.5 9h13M9.5 9 12 2.5 14.5 9 12 21.5z";
    public static final String SERVERS = "M5 3.5h14a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 9V5A1.5 1.5 0 0 1 5 3.5z"
        + "M5 13.5h14a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19v-4A1.5 1.5 0 0 1 5 13.5zM7.5 7h.01M7.5 17h.01";
    public static final String DOWNLOAD = "M12 3.5v11M7.5 10l4.5 4.5 4.5-4.5M4 20.5h16";
    public static final String GEAR = gearPath();

    // actions
    public static final String PLAY = "M7.5 4.8v14.4L19 12z";
    public static final String STOP = "M7 7h10v10H7z";
    public static final String PLUS = "M12 5v14M5 12h14";
    public static final String SEARCH = "M10.5 3.5a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20.5 20.5l-5-5";
    public static final String FILTER = "M3.5 5h17l-6.5 7.5V19l-4 2v-8.5z";
    public static final String SORT = "M7 4v16M3.5 7.5 7 4l3.5 3.5M17 20V4M13.5 16.5 17 20l3.5-3.5";
    public static final String GRID = "M4 4h6.5v6.5H4zM13.5 4H20v6.5h-6.5zM4 13.5h6.5V20H4zM13.5 13.5H20V20h-6.5z";
    public static final String LIST = "M8.5 6h12M8.5 12h12M8.5 18h12M4 6h.01M4 12h.01M4 18h.01";
    public static final String SLIDERS = "M4 6.5h9M17 6.5h3M4 12h3M11 12h9M4 17.5h11M19 17.5h1M15 4.5v4M9 10v4M17 15.5v4";
    public static final String FOLDER = "M3.5 7a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z";
    public static final String TRASH = "M4 6.5h16M9.5 6.5v-2a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v2M6 6.5l1 13a1.5 1.5 0 0 0 1.5 1.5h7a1.5 1.5 0 0 0 1.5-1.5l1-13M10 11v6M14 11v6";
    public static final String MORE = "M12 3.6a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2zM12 10.4a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2z"
        + "M12 17.2a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2z";
    public static final String CLOSE = "M6 6l12 12M18 6 6 18";
    public static final String BACK = "M19.5 12h-15M10.5 6l-6 6 6 6";
    public static final String REFRESH = "M20.5 12a8.5 8.5 0 1 1-2.5-6l2.5 2.5M20.5 3.5v5h-5";
    public static final String EXTERNAL = "M14 4h6v6M20 4l-9 9M18 13.5v5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6h5";
    public static final String COPY = "M9.5 8.5h9a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 8 19v-9a1.5 1.5 0 0 1 1.5-1.5z"
        + "M16 8.5V5a1.5 1.5 0 0 0-1.5-1.5h-9A1.5 1.5 0 0 0 4 5v9a1.5 1.5 0 0 0 1.5 1.5H8";
    public static final String PIN = "M9 3.5h6M10 3.5V9l-3 3.5h10L14 9V3.5M12 12.5v8";

    // status
    public static final String CHECK = "M4.5 12.5l5 5 10-11";
    public static final String WARNING = "M10.3 4.2 2.6 18a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0zM12 9.5v4M12 17h.01";
    public static final String INFO = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5.5M12 7.5h.01";
    public static final String ERROR = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7.5v5M12 16.5h.01";
    public static final String UPDATE = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 16.5v-9M8 11.5l4-4 4 4";
    public static final String CLOCK = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3.5 2";
    public static final String SHIELD = "M12 2.8l7.5 3v6c0 4.5-3.2 8-7.5 9.4-4.3-1.4-7.5-4.9-7.5-9.4v-6z";

    // things
    public static final String BOLT = "M13 2.5 4.5 13.5H12l-1 8 8.5-11H12z";
    public static final String USER = "M12 3.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4.5 20.5c0-3.9 3.4-6.5 7.5-6.5s7.5 2.6 7.5 6.5";
    public static final String PACKAGE = "M12 2.8l8.5 4.7v9L12 21.2l-8.5-4.7v-9zM3.5 7.5 12 12.2l8.5-4.7M12 12.2v9M7.8 5.2l8.5 4.7";
    public static final String CUBE = "M12 2.8l8.5 4.7v9L12 21.2l-8.5-4.7v-9zM3.5 7.5 12 12.2l8.5-4.7M12 12.2v9";
    public static final String IMAGE = "M5 4h14a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 19 20H5a1.5 1.5 0 0 1-1.5-1.5v-13A1.5 1.5 0 0 1 5 4z"
        + "M3.5 16l5-5 4 4 2.5-2.5 5 5M15.5 8.5h.01";
    public static final String ARCHIVE = "M3.5 4.5h17v4h-17zM5 8.5v10A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5v-10M10 12.5h4";
    public static final String PALETTE = "M12 3a9 9 0 0 0 0 18c1.2 0 2-.8 2-1.8 0-1.2-1-1.5-1-2.7 0-1 .8-1.5 1.8-1.5H17a4 4 0 0 0 4-4C21 6.5 17 3 12 3z"
        + "M7.5 11h.01M9.5 7h.01M14.5 7h.01M17 10.5h.01";
    public static final String GAUGE = "M6 19a8.5 8.5 0 1 1 12 0M12 13l3.5-4";
    public static final String SPARKLE = "M12 3.5 14 10l6.5 2-6.5 2-2 6.5-2-6.5-6.5-2 6.5-2z";
    public static final String LOGS = "M14 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8zM14 3.5V8h4.5M9 12.5h6M9 16h6";
    public static final String STAR = "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z";
    public static final String HEART = "M12 20s-8-4.6-8-10.5a4.5 4.5 0 0 1 8-2.8 4.5 4.5 0 0 1 8 2.8C20 15.4 12 20 12 20z";
    public static final String EYE_OFF = "M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c5 0 8.5 4.5 9.5 7a13 13 0 0 1-2.6 3.8M6.6 6.6C4.6 8 3.2 10 2.5 12"
        + "c1 2.5 4.5 7 9.5 7a9.6 9.6 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2";
    public static final String KEY = "M7.5 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM11.5 12h9M17.5 12v3.5M20.5 12v2.5";
    public static final String WORLD = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9"
        + "c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z";
    public static final String CPU = "M7 5.5h10A1.5 1.5 0 0 1 18.5 7v10a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 17V7A1.5 1.5 0 0 1 7 5.5z"
        + "M9.5 9.5h5v5h-5zM9.5 2.5v3M14.5 2.5v3M9.5 18.5v3M14.5 18.5v3M2.5 9.5h3M2.5 14.5h3M18.5 9.5h3M18.5 14.5h3";
    public static final String MEMORY = "M4 6.5h16a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 15V8A1.5 1.5 0 0 1 4 6.5z"
        + "M7 10v3M10.5 10v3M13.5 10v3M17 10v3M6 16.5v3M18 16.5v3";
    public static final String WIFI = "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0M12 19.5h.01";

    /** glyphs that are filled as well as outlined */
    private static final Set<String> SOLID = Set.of(PLAY, STOP, MORE);

    private Icons() {
    }

    /** an icon node of {@code size} pixels; colour comes from {@code -sl-icon} */
    public static Node of(String path, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFillRule(FillRule.NON_ZERO);
        p.getStyleClass().add("icon");
        if (SOLID.contains(path)) {
            p.getStyleClass().add("solid");
        }
        // a clear 24x24 square keeps every glyph on the shared grid instead of centring it by its own bounds
        Rectangle grid = new Rectangle(24, 24, Color.TRANSPARENT);
        Group g = new Group(grid, p);
        double scale = size / 24.0;
        g.setScaleX(scale);
        g.setScaleY(scale);
        StackPane box = new StackPane(g);
        box.getStyleClass().add("icon-holder");
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setFocusTraversable(false);
        box.setMouseTransparent(true);
        return box;
    }

    public static Node of(String path) {
        return of(path, 18);
    }

    /** a cog: 8 teeth around a ring with a round hole, as one outline */
    private static String gearPath() {
        StringBuilder b = new StringBuilder();
        int teeth = 8;
        double c = 12;
        double rOut = 9.5;
        double rIn = 7.3;
        for (int i = 0; i < teeth * 4; i++) {
            double a = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2;
            int phase = i % 4;
            double r = (phase == 1 || phase == 2) ? rOut : rIn;
            b.append(i == 0 ? "M" : "L").append(fmt(c + Math.cos(a) * r)).append(' ').append(fmt(c + Math.sin(a) * r));
        }
        b.append("Z");
        double h = 3;
        b.append("M").append(fmt(c - h)).append(' ').append(fmt(c))
            .append("a").append(fmt(h)).append(' ').append(fmt(h)).append(" 0 1 0 ").append(fmt(2 * h)).append(" 0")
            .append("a").append(fmt(h)).append(' ').append(fmt(h)).append(" 0 1 0 ").append(fmt(-2 * h)).append(" 0Z");
        return b.toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
