package dev.shardlight.client.app.ui;

import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The design tokens that are computed rather than written in {@code shardlight.css}: the accent ramp. Every
 * other colour token lives in the stylesheet. Contrast follows WCAG 2.x relative luminance.
 * See docs/DESIGN_SYSTEM.md.
 */
public final class DesignTokens {
    /** the accent swatches offered in Settings > Appearance; the first one is the default */
    public static final List<String> ACCENT_PRESETS = List.of("#7C6CF2", "#4F8DF7", "#2BB3A3", "#4CAF50", "#E0A030",
        "#E8663D", "#E04F7A", "#B45CE0");
    public static final Color DEFAULT_ACCENT = Color.web(ACCENT_PRESETS.get(0));

    /** WCAG AA: body text */
    public static final double AA_TEXT = 4.5;
    /** WCAG AA: large text, icons and control boundaries */
    public static final double AA_LARGE = 3.0;

    /** the surfaces accent-coloured text may sit on, per base theme (must match shardlight.css) */
    static final List<Color> DARK_SURFACES = webs("#0e1016", "#0a0c11", "#151922", "#1b202c", "#222838");
    static final List<Color> LIGHT_SURFACES = webs("#f3f4f8", "#e9ebf1", "#ffffff", "#f7f8fb", "#ffffff");
    static final Color DARK_ON = Color.web("#0b0d12");
    static final Color LIGHT_ON = Color.web("#ffffff");

    private DesignTokens() {
    }

    /**
     * The accent ramp for one accent colour and base theme.
     *
     * @param subtle  tinted background for selected rows, accent chips and focus halos
     * @param hover   the accent fill under the pointer
     * @param base    the accent fill (primary buttons, switches, checked boxes)
     * @param pressed the accent fill while pressed
     * @param onAccent text and icons on any of the three fills
     * @param text    accent-coloured text, links and icons on surfaces, and the focus ring
     */
    public record AccentRamp(Color subtle, Color hover, Color base, Color pressed, Color onAccent, Color text) {
        /** the ramp as CSS looked-up colour declarations for the scene root */
        public String css() {
            return String.format(Locale.ROOT,
                "-sl-accent-subtle: %s; -sl-accent-hover: %s; -sl-accent: %s; -sl-accent-pressed: %s; "
                    + "-sl-accent-text: %s; -sl-accent-fg: %s;",
                web(subtle), web(hover), web(base), web(pressed), web(onAccent), web(text));
        }

        public Map<String, Color> tokens() {
            Map<String, Color> m = new LinkedHashMap<>();
            m.put("-sl-accent-subtle", subtle);
            m.put("-sl-accent-hover", hover);
            m.put("-sl-accent", base);
            m.put("-sl-accent-pressed", pressed);
            m.put("-sl-accent-text", onAccent);
            m.put("-sl-accent-fg", text);
            return m;
        }
    }

    /**
     * Builds the ramp so that text on every accent fill reaches 4.5:1 and accent text reaches 4.5:1 on every
     * surface. The chosen colour is kept when it already passes; otherwise its lightness moves the least
     * distance that makes it pass. Hover and pressed move further away from the text colour, so they can only
     * gain contrast.
     */
    public static AccentRamp ramp(Color accent, boolean light) {
        Color a = accent == null ? DEFAULT_ACCENT : Color.color(accent.getRed(), accent.getGreen(), accent.getBlue());
        List<Color> surfaces = light ? LIGHT_SURFACES : DARK_SURFACES;
        // base fill: dark text on a lighter fill, or white text on a darker fill, whichever changes the colour less
        Color withDark = adjustUntil(a, +1, c -> contrast(c, DARK_ON) >= AA_TEXT + 0.1);
        Color withWhite = adjustUntil(a, -1, c -> contrast(c, LIGHT_ON) >= AA_TEXT + 0.1);
        double dDark = Math.abs(lightness(withDark) - lightness(a));
        double dWhite = Math.abs(lightness(withWhite) - lightness(a));
        // a fill that is also a state indicator (checked box, switch) needs 3:1 against the surfaces around it
        boolean darkOk = surfaces.stream().allMatch(s -> contrast(withDark, s) >= AA_LARGE);
        boolean whiteOk = surfaces.stream().allMatch(s -> contrast(withWhite, s) >= AA_LARGE);
        boolean darkText;
        if (darkOk != whiteOk) {
            darkText = darkOk;
        } else {
            darkText = light ? dDark + 0.04 < dWhite : dDark <= dWhite + 0.04;
        }
        Color base = darkText ? withDark : withWhite;
        Color on = darkText ? DARK_ON : LIGHT_ON;
        int away = darkText ? +1 : -1;
        Color hover = shift(base, away * 0.06);
        Color pressed = shift(base, away * 0.12);
        // subtle: the accent mixed into the card surface
        Color surface = surfaces.get(2);
        Color subtle = mix(surface, a, light ? 0.12 : 0.20);
        List<Color> behindText = new ArrayList<>(surfaces);
        behindText.add(subtle);
        int towardContrast = light ? -1 : +1;
        Color text = adjustUntil(a, towardContrast, c -> behindText.stream().allMatch(s -> contrast(c, s) >= AA_TEXT + 0.1));
        return new AccentRamp(subtle, hover, base, pressed, on, text);
    }

    /** the ramp as an inline style for the scene root */
    public static String rampCss(Color accent, boolean light) {
        return ramp(accent, light).css();
    }

    // ------------------------------------------------------------------ colour maths

    /** WCAG relative luminance */
    public static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio, 1 to 21; a translucent foreground is composited over the background first */
    public static double contrast(Color fg, Color bg) {
        Color f = fg.getOpacity() < 1 ? mix(bg, Color.color(fg.getRed(), fg.getGreen(), fg.getBlue()), fg.getOpacity()) : fg;
        double l1 = luminance(f);
        double l2 = luminance(bg);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    /** {@code amount} of {@code top} over {@code bottom} */
    public static Color mix(Color bottom, Color top, double amount) {
        return Color.color(
            bottom.getRed() + (top.getRed() - bottom.getRed()) * amount,
            bottom.getGreen() + (top.getGreen() - bottom.getGreen()) * amount,
            bottom.getBlue() + (top.getBlue() - bottom.getBlue()) * amount);
    }

    public static String web(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
            (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private static List<Color> webs(String... hex) {
        List<Color> out = new ArrayList<>();
        for (String h : hex) {
            out.add(Color.web(h));
        }
        return List.copyOf(out);
    }

    private static Color adjustUntil(Color c, int direction, java.util.function.Predicate<Color> ok) {
        Color cur = c;
        for (int i = 0; i < 100 && !ok.test(cur); i++) {
            cur = shift(cur, direction * 0.01);
        }
        return cur;
    }

    // HSL lightness, so hue and saturation stay put while the colour lightens or darkens

    static double lightness(Color c) {
        double max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
        double min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        return (max + min) / 2;
    }

    static Color shift(Color c, double delta) {
        double r = c.getRed();
        double g = c.getGreen();
        double b = c.getBlue();
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double h = 0;
        double s = 0;
        double d = max - min;
        if (d > 1e-9) {
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) {
                h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
            } else if (max == g) {
                h = ((b - r) / d + 2) / 6;
            } else {
                h = ((r - g) / d + 4) / 6;
            }
        }
        double nl = Math.max(0, Math.min(1, l + delta));
        if (s == 0) {
            return Color.color(nl, nl, nl);
        }
        double q = nl < 0.5 ? nl * (1 + s) : nl + s - nl * s;
        double p = 2 * nl - q;
        return Color.color(hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3));
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        double v;
        if (t < 1.0 / 6) {
            v = p + (q - p) * 6 * t;
        } else if (t < 0.5) {
            v = q;
        } else if (t < 2.0 / 3) {
            v = p + (q - p) * (2.0 / 3 - t) * 6;
        } else {
            v = p;
        }
        return Math.max(0, Math.min(1, v));
    }
}
