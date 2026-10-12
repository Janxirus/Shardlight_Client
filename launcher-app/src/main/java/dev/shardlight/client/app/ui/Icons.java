package dev.shardlight.client.app.ui;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/**
 * Shardlight Client's own icon set: simple geometric glyphs on a 24x24 grid, drawn as SVG paths so they
 * scale with the UI and take their colour from CSS ({@code .icon { -fx-fill: ... }}).
 */
public final class Icons {
    public static final String HOME = "M3 11.2 12 3.8l9 7.4V20a1 1 0 0 1-1 1h-5.2v-6.2H9.2V21H4a1 1 0 0 1-1-1z";
    public static final String LIBRARY = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z";
    public static final String DISCOVER = "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16zm4.2 3.8-2.6 5.8-5.8 2.6 2.6-5.8z";
    public static final String SHARD = "M12 1.8 18.6 8.4 14.6 22.2h-5.2L5.4 8.4zM12 4.6 8 8.8l2.9 10.9h2.2L16 8.8z";
    public static final String SERVERS = "M4 3.5h16v7H4zm2.2 2.3v2.4h2.4V5.8zM4 13.5h16v7H4zm2.2 2.3v2.4h2.4v-2.4z";
    public static final String DOWNLOAD = "M11 3h2v9.2l3.6-3.6 1.4 1.4-6 6-6-6 1.4-1.4 3.6 3.6zM4 18.2h16V20.4H4z";
    public static final String PLAY = "M7 4.2v15.6L20 12z";
    public static final String STOP = "M6 6h12v12H6z";
    public static final String PLUS = "M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7z";
    public static final String SEARCH = "M10.2 3a7.2 7.2 0 0 1 5.8 11.5l5 5-1.5 1.5-5-5A7.2 7.2 0 1 1 10.2 3zm0 2a5.2 5.2 0 1 0 0 10.4 5.2 5.2 0 0 0 0-10.4z";
    public static final String FOLDER = "M3 6.5A2.5 2.5 0 0 1 5.5 4h4.3l2 2.2h6.7A2.5 2.5 0 0 1 21 8.7v9.8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 18.5z";
    public static final String TRASH = "M9 3h6l1 2h4v2H4V5h4zM6 9h12l-1 12H7z";
    public static final String MORE = "M10 3.5h4v4h-4zM10 10h4v4h-4zM10 16.5h4v4h-4z";
    public static final String CHECK = "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z";
    public static final String WARNING = "M12 2.5 1.5 21h21zm-1 6.5h2v6h-2zm0 7.5h2v2h-2z";
    public static final String INFO = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1 8v7h2v-7zm0-4v2h2V6z";
    public static final String ERROR = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1 5v7h2V7zm0 9v2h2v-2z";
    public static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z";
    public static final String BACK = "M10 5 3 12l7 7 1.4-1.4L6.8 13H21v-2H6.8l4.6-4.6z";
    public static final String BOLT = "M13.5 2 4 14h6.5l-1 8L19 10h-6.5z";
    public static final String USER = "M12 3a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9zM3.5 21c0-4.6 3.8-7.4 8.5-7.4s8.5 2.8 8.5 7.4z";
    public static final String REFRESH = "M17.7 6.3A8 8 0 1 0 20 12h-2.1a5.9 5.9 0 1 1-1.7-4.2L13 11h7V4z";
    public static final String EXTERNAL = "M14 3h7v7h-2V6.4l-8.3 8.3-1.4-1.4L17.6 5H14zM5 5h6v2H5v12h12v-6h2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z";
    public static final String PACKAGE = "M12 2 21 7v10l-9 5-9-5V7zm0 2.3L5.6 7.8 12 11.3l6.4-3.5zM5 9.5v6.3l6 3.3v-6.3zm14 0-6 3.3v6.3l6-3.3z";
    public static final String IMAGE = "M4 4.5h16a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1zm1 2v9.3l4.2-4.2 3 3 3.3-4.3L19 15.9V6.5zm3.5 1a1.7 1.7 0 1 1 0 3.4 1.7 1.7 0 0 1 0-3.4z";
    public static final String CUBE = "M12 2 20.5 6.8v10.4L12 22l-8.5-4.8V6.8zm0 2.3L6 7.6l6 3.4 6-3.4zM5.5 9.3v6.7l5.5 3.1v-6.7zm13 0-5.5 3.1v6.7l5.5-3.1z";
    public static final String ARCHIVE = "M3 4h18v4.5H3zM4.5 9.5h15V20h-15zm5 2v2h5v-2z";
    public static final String PALETTE = "M12 3c5 0 9 3.6 9 8.1 0 2.6-2 4.4-4.5 4.4h-1.9c-.8 0-1.3.6-1.3 1.3 0 .9.9 1.3.9 2.4 0 1.1-.8 1.8-2.2 1.8A9 9 0 0 1 12 3zM7.2 9.6a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2zm3-3.6a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2zm4.6 0a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2zm2.8 3.6a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z";
    public static final String GAUGE = "M12 4a9 9 0 0 0-9 9c0 2 .7 3.9 1.9 5.4l1.5-1.3A7 7 0 1 1 19 13a7 7 0 0 1-1.4 4.1l1.5 1.3A9 9 0 0 0 12 4zm4.6 4.2-4.9 4.3a1.6 1.6 0 1 0 2.2 2.2z";
    public static final String SPARKLE = "M12 2.5l2 6.5 6.5 2.5-6.5 2.5-2 6.5-2-6.5-6.5-2.5 6.5-2.5z";
    public static final String LOGS = "M5 3h10l4 4v14H5zm2 6v2h10V9zm0 4v2h10v-2zm0 4v2h7v-2z";
    public static final String COPY = "M8 3h11a2 2 0 0 1 2 2v11h-2V5H8zM4 7h11a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zm0 2v11h11V9z";
    public static final String STAR = "M12 2.5l2.9 6.2 6.6.6-5 4.5 1.5 6.6L12 17l-6 3.4 1.5-6.6-5-4.5 6.6-.6z";
    public static final String PIN = "M14.5 2.5 21.5 9.5 19.6 11.4 18.4 10.2 14.6 14l.6 4.4-1.6 1.6-4.2-4.2-4.9 4.9-1.4-1.4 4.9-4.9-4.2-4.2 1.6-1.6 4.4.6 3.8-3.8-1.2-1.2z";
    public static final String UPDATE = "M12 3a9 9 0 1 1-9 9h2a7 7 0 1 0 7-7zm-1 4h2v5.6l3.6 2.1-1 1.7-4.6-2.7z";
    public static final String EYE_OFF = "M3.3 2 22 20.7l-1.4 1.4-3.3-3.3A10.6 10.6 0 0 1 12 20C6.5 20 2.6 16 1 12a12.7 12.7 0 0 1 4.3-5.6L1.9 3.4zm3.4 5.8A10.4 10.4 0 0 0 3.2 12c1.5 3.2 4.6 6 8.8 6 1.3 0 2.5-.3 3.6-.8l-1.8-1.8A4.2 4.2 0 0 1 8.6 10zM12 4c5.5 0 9.4 4 11 8a12.4 12.4 0 0 1-2.9 4.3l-1.4-1.4A10.2 10.2 0 0 0 20.8 12C19.3 8.8 16.2 6 12 6c-.9 0-1.7.1-2.5.3L7.9 4.7C9.2 4.3 10.6 4 12 4z";
    public static final String KEY = "M14.5 3a6.5 6.5 0 1 1-2.2 12.6L10 17.9V20H8v2H4v-3.6l6.4-6.4A6.5 6.5 0 0 1 14.5 3zm1.5 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4z";
    public static final String WORLD = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1.4 2.1a8 8 0 0 0-6.4 6.4h3.5c.2-2.4.9-4.6 2.9-6.4zm2.8 0c2 1.8 2.7 4 2.9 6.4h3.5a8 8 0 0 0-6.4-6.4zm-1.4.6c-1.5 1.5-2.1 3.5-2.3 5.8h4.6c-.2-2.3-.8-4.3-2.3-5.8zm-7.8 7.8a8 8 0 0 0 6.4 6.4c-2-1.8-2.7-4-2.9-6.4zm5.5 0c.2 2.3.8 4.3 2.3 5.8 1.5-1.5 2.1-3.5 2.3-5.8zm6.6 0c-.2 2.4-.9 4.6-2.9 6.4a8 8 0 0 0 6.4-6.4z";
    public static final String GEAR = gearPath();

    private Icons() {
    }

    /** an icon node of {@code size} pixels; colour comes from the .icon style class */
    public static Node of(String path, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFillRule(FillRule.EVEN_ODD);
        p.getStyleClass().add("icon");
        double scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        StackPane box = new StackPane(p);
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

    /** a cog: 8 rounded teeth around a ring */
    private static String gearPath() {
        StringBuilder b = new StringBuilder();
        int teeth = 8;
        double cx = 12;
        double cy = 12;
        double rOut = 10.2;
        double rIn = 7.6;
        for (int i = 0; i < teeth * 4; i++) {
            double a = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2;
            int phase = i % 4;
            double r = (phase == 1 || phase == 2) ? rOut : rIn;
            double x = cx + Math.cos(a) * r;
            double y = cy + Math.sin(a) * r;
            b.append(i == 0 ? "M" : "L").append(fmt(x)).append(' ').append(fmt(y));
        }
        b.append("Z");
        double h = 3.4;
        b.append("M").append(fmt(cx - h)).append(' ').append(fmt(cy))
            .append("a").append(fmt(h)).append(' ').append(fmt(h)).append(" 0 1 0 ").append(fmt(2 * h)).append(" 0")
            .append("a").append(fmt(h)).append(' ').append(fmt(h)).append(" 0 1 0 ").append(fmt(-2 * h)).append(" 0Z");
        return b.toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
