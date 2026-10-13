package dev.shardlight.client.app.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Screen;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The launcher's own artwork: Home backdrop, default project covers and empty-state illustrations, all drawn by
 * {@code tools/art/make_art.py} (see docs/CREDITS.md). Every picture ships at 1x and 2x; the 2x file is used on
 * high-density screens and where a picture is shown large.
 */
public final class Art {
    private static final Map<String, Image> CACHE = new HashMap<>();
    private static final Map<String, String> EMPTY = new HashMap<>();

    static {
        EMPTY.put(Icons.LIBRARY, "library");
        EMPTY.put(Icons.SEARCH, "search");
        EMPTY.put(Icons.PACKAGE, "content");
        EMPTY.put(Icons.DOWNLOAD, "downloads");
        EMPTY.put(Icons.SERVERS, "servers");
        EMPTY.put(Icons.WORLD, "world");
        EMPTY.put(Icons.IMAGE, "image");
        EMPTY.put(Icons.ARCHIVE, "archive");
        EMPTY.put(Icons.TRASH, "trash");
        EMPTY.put(Icons.USER, "user");
        EMPTY.put(Icons.SHARD, "shard");
    }

    private Art() {
    }

    /** the Home backdrop: soft facets on a transparent picture, so it works on the dark and the light window */
    public static Image hero() {
        return load("hero-home", hiDpi());
    }

    /** the default cover of a project template, or empty when the template has none (the art is then generated) */
    public static Optional<Image> cover(String template, double widthPx) {
        if (template == null || template.isBlank() || !template.matches("[a-z0-9-]+")) {
            return Optional.empty();
        }
        String name = "covers/" + template;
        if (Art.class.getResource("/app/art/" + name + ".png") == null) {
            return Optional.empty();
        }
        return Optional.of(load(name, hiDpi() || widthPx > 480));
    }

    /** the illustration for an empty state that would otherwise show this icon, if there is one */
    public static Optional<ImageView> emptyIllustration(String icon, double widthEm) {
        String name = EMPTY.get(icon);
        if (name == null) {
            return Optional.empty();
        }
        ImageView v = new ImageView(load("empty/" + name, true));
        v.setPreserveRatio(true);
        v.setSmooth(true);
        v.setFitWidth(ThemeManager.em(widthEm));
        v.setMouseTransparent(true);
        v.getStyleClass().add("empty-art");
        return Optional.of(v);
    }

    /** the splash picture: dark facets, the shard and the name */
    public static Image splash() {
        // shown once at start-up, so it is not kept in the cache (the memory goes back after the splash closes)
        String name = "splash" + (hiDpi() ? "@2x" : "");
        URL url = Art.class.getResource("/app/art/" + name + ".png");
        return new Image((url != null ? url : Art.class.getResource("/app/art/splash.png")).toExternalForm(), false);
    }

    private static synchronized Image load(String name, boolean twoX) {
        String key = name + (twoX ? "@2x" : "");
        return CACHE.computeIfAbsent(key, k -> {
            URL url = Art.class.getResource("/app/art/" + k + ".png");
            if (url == null && twoX) {
                url = Art.class.getResource("/app/art/" + name + ".png");
            }
            return new Image(url.toExternalForm(), false);
        });
    }

    private static boolean hiDpi() {
        try {
            return Screen.getPrimary().getOutputScaleX() >= 1.5;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
