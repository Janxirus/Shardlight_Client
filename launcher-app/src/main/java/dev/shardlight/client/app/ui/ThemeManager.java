package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.settings.LauncherSettings;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;
import java.util.Locale;

/** Applies the launcher appearance settings (base, accent, scale, contrast, motion, direction) to a scene. */
public final class ThemeManager {
    private static final List<String> PREFERRED_FONTS = List.of(
        "Segoe UI Variable Text", "Segoe UI", "SF Pro Text", "Helvetica Neue", "Inter", "Noto Sans", "Cantarell", "Ubuntu", "DejaVu Sans");
    private static final String FONT = pickFont();

    private ThemeManager() {
    }

    private static String pickFont() {
        List<String> families = Font.getFamilies();
        for (String f : PREFERRED_FONTS) {
            if (families.contains(f)) {
                return f;
            }
        }
        return Font.getDefault().getFamily();
    }

    public static void install(Scene scene) {
        scene.getStylesheets().add(ThemeManager.class.getResource("/app/shardlight.css").toExternalForm());
    }

    public static void apply(Scene scene, LauncherSettings.Appearance a) {
        Parent root = scene.getRoot();
        root.getStyleClass().removeAll("light", "high-contrast");
        if ("light".equalsIgnoreCase(a.theme)) {
            root.getStyleClass().add("light");
        }
        if (a.highContrast) {
            root.getStyleClass().add("high-contrast");
        }
        Color accent = parse(a.accent, Color.web("#8b7cf6"));
        boolean lightAccent = accent.getBrightness() > 0.75 && accent.getSaturation() < 0.35;
        String accentText = lightAccent || "light".equalsIgnoreCase(a.theme) ? "#ffffff" : "#0d0f15";
        if (luminance(accent) > 0.55) {
            accentText = "#0d0f15";
        }
        double px = 14 * Math.max(0.8, Math.min(1.6, a.scale));
        root.setStyle(String.format(Locale.ROOT,
            "-fx-font-size: %.1fpx; -fx-font-family: \"%s\"; -sl-accent: %s; -sl-accent-text: %s;",
            px, FONT, web(accent), accentText));
        root.setNodeOrientation(I18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        UiSettings.setReducedMotion(a.reducedMotion);
    }

    public static Color accent(LauncherSettings.Appearance a) {
        return parse(a.accent, Color.web("#8b7cf6"));
    }

    public static Color parse(String hex, Color fallback) {
        try {
            return hex == null || hex.isBlank() ? fallback : Color.web(hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String web(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
            (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private static double luminance(Color c) {
        return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
    }

    /** the secondary accent used by the logo and the Play button gradient */
    public static Color accent2() {
        return Color.web("#46d3c4");
    }
}
