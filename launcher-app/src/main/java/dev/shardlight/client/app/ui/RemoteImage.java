package dev.shardlight.client.app.ui;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Icons and screenshots from the catalogs, loaded in the background over HTTPS only and kept in a small
 * memory cache. Until (or unless) the picture arrives, a placeholder glyph is shown.
 */
public final class RemoteImage {
    private static final int MAX_CACHED = 300;
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
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
                box.getChildren().setAll(view);
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
