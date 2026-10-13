package dev.shardlight.client.app.ui;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Icons and screenshots from the catalogs, loaded in the background over HTTPS only and kept in a small
 * memory cache. Until (or unless) the picture arrives, a placeholder glyph is shown, tinted with the picture's
 * main colour once that is known; the picture then fades in over it.
 */
public final class RemoteImage {
    private static final int MAX_CACHED = 300;
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private static final Map<String, Color> MAIN_COLOURS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Color> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private RemoteImage() {
    }

    /** a rounded square of {@code size} pixels showing the picture at {@code url} (null = placeholder) */
    public static Node square(URI url, double size, String placeholderGlyph) {
        StackPane box = UiKit.iconBox(Icons.of(placeholderGlyph == null ? Icons.PACKAGE : placeholderGlyph, size * 0.5), size);
        if (url == null || !"https".equalsIgnoreCase(url.getScheme())) {
            return box;
        }
        Color known = MAIN_COLOURS.get(url.toString());
        if (known != null) {
            Region tint = new Region();
            tint.setBackground(new Background(new BackgroundFill(known, new CornerRadii(size * 0.14), null)));
            tint.setOpacity(0.35);
            box.getChildren().add(0, tint);
        }
        Image img = image(url.toString(), size * 2);
        ImageView view = new ImageView(img);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(size * 0.28);
        clip.setArcHeight(size * 0.28);
        view.setClip(clip);
        Runnable swap = () -> {
            if (!img.isError() && img.getProgress() >= 1.0) {
                MAIN_COLOURS.putIfAbsent(url.toString(), mainColour(img));
                box.getChildren().add(view);
                Motion.fadeIn(view, Motion.Speed.MEDIUM).setOnFinished(e -> box.getChildren().setAll(view));
            }
        };
        if (img.getProgress() >= 1.0) {
            swap.run();
        } else {
            img.progressProperty().addListener((o, a, b) -> {
                if (b.doubleValue() >= 1.0) {
                    swap.run();
                }
            });
        }
        return box;
    }

    /** a picture scaled to fit {@code width} pixels wide */
    public static ImageView wide(URI url, double width) {
        ImageView v = new ImageView();
        if (url != null && "https".equalsIgnoreCase(url.getScheme())) {
            v.setImage(new Image(url.toString(), width * 1.5, 0, true, true, true));
        }
        v.setFitWidth(width);
        v.setPreserveRatio(true);
        return v;
    }

    /** the average colour of a coarse grid of the picture's opaque pixels */
    static Color mainColour(Image img) {
        var r = img.getPixelReader();
        if (r == null || img.getWidth() < 1 || img.getHeight() < 1) {
            return Color.GRAY;
        }
        double red = 0;
        double green = 0;
        double blue = 0;
        int n = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                Color c = r.getColor((int) (img.getWidth() * (x + 0.5) / 8), (int) (img.getHeight() * (y + 0.5) / 8));
                if (c.getOpacity() > 0.5) {
                    red += c.getRed();
                    green += c.getGreen();
                    blue += c.getBlue();
                    n++;
                }
            }
        }
        return n == 0 ? Color.GRAY : Color.color(red / n, green / n, blue / n);
    }

    private static synchronized Image image(String url, double px) {
        String key = url + "@" + (int) px;
        Image cached = CACHE.get(key);
        if (cached != null && !cached.isError()) {
            return cached;
        }
        Image img = new Image(url, px, px, true, true, true);
        CACHE.put(key, img);
        return img;
    }
}
