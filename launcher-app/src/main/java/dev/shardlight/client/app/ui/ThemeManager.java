package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.settings.LauncherSettings;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.util.Locale;

/**
 * Applies the launcher appearance settings (base, accent, scale, contrast, motion, direction) to a scene.
 * Static colour tokens live in shardlight.css; the accent ramp is computed by {@link DesignTokens} and set
 * on the root, so changing the accent recolours every accent token at once.
 */
public final class ThemeManager {
    private ThemeManager() {
    }

    public static void install(Scene scene) {
        Fonts.load();
        scene.getStylesheets().add(ThemeManager.class.getResource("/app/shardlight.css").toExternalForm());
        scene.getStylesheets().add(Fonts.stylesheet());
    }

    public static void apply(Scene scene, LauncherSettings.Appearance a) {
        Parent root = scene.getRoot();
        root.getStyleClass().removeAll("light", "high-contrast", "compact", "no-effects");
        boolean light = "light".equalsIgnoreCase(a.theme);
        if (light) {
            root.getStyleClass().add("light");
        }
        if (a.highContrast) {
            root.getStyleClass().add("high-contrast");
        }
        if (a.compact) {
            root.getStyleClass().add("compact");
        }
        if (!a.backgroundEffects) {
            root.getStyleClass().add("no-effects");
        }
        double px = 14 * Math.max(0.8, Math.min(1.6, a.scale));
        root.setStyle(String.format(Locale.ROOT, "-fx-font-size: %.1fpx; -fx-font-family: \"%s\"; %s",
            px, Fonts.ui(), DesignTokens.rampCss(accent(a), light)));
        root.setNodeOrientation(I18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        UiSettings.setReducedMotion(a.reducedMotion);
    }

    public static Color accent(LauncherSettings.Appearance a) {
        return parse(a.accent, DesignTokens.DEFAULT_ACCENT);
    }

    public static Color parse(String hex, Color fallback) {
        try {
            return hex == null || hex.isBlank() ? fallback : Color.web(hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String web(Color c) {
        return DesignTokens.web(c);
    }

    /** the secondary accent used by the logo and the Play button gradient */
    public static Color accent2() {
        return Color.web("#46d3c4");
    }
}
