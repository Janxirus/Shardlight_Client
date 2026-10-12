package dev.shardlight.client.app.ui;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every text colour token against every background it is used on, in dark and light, with and without high
 * contrast, checked against WCAG AA: 4.5:1 for body text, 3:1 for large text, icons and state indicators.
 * The static tokens are read from shardlight.css; the accent ramp is computed for every accent preset.
 */
class DesignTokensContrastTest {
    private static final List<String> SURFACES = List.of("-sl-sunken", "-sl-bg", "-sl-surface", "-sl-raised",
        "-sl-overlay-bg", "-sl-field");
    private static final List<String> TEXT = List.of("-sl-text", "-sl-text-2", "-sl-text-3");
    private static final List<String> STATUS = List.of("success", "warning", "danger", "info");

    private record Theme(String name, boolean light, Map<String, Color> tokens) {
    }

    @Test
    void textOnEverySurfacePassesAA() {
        List<String> failures = new ArrayList<>();
        for (Theme t : themes()) {
            List<String> fgs = new ArrayList<>(TEXT);
            for (String s : STATUS) {
                fgs.add("-sl-" + s);
            }
            for (String fg : fgs) {
                for (String bg : SURFACES) {
                    check(failures, t, fg, bg, DesignTokens.AA_TEXT);
                }
            }
            // selected and hovered rows, filled controls
            for (String fg : List.of("-sl-text", "-sl-text-2")) {
                check(failures, t, fg, "-sl-fill", DesignTokens.AA_TEXT);
                check(failures, t, fg, "-sl-fill-strong", DesignTokens.AA_TEXT);
                check(failures, t, fg, "-sl-accent-subtle", DesignTokens.AA_TEXT);
            }
            for (String s : STATUS) {
                check(failures, t, "-sl-" + s, "-sl-" + s + "-subtle", DesignTokens.AA_TEXT);
                check(failures, t, "-sl-text", "-sl-" + s + "-subtle", DesignTokens.AA_TEXT);
            }
            check(failures, t, "-sl-on-danger", "-sl-danger", DesignTokens.AA_TEXT);
            check(failures, t, "-sl-on-danger", "-sl-danger-hover", DesignTokens.AA_TEXT);
            check(failures, t, "-sl-tooltip-text", "-sl-tooltip-bg", DesignTokens.AA_TEXT);
            // non-text: the switch knob against its track, icons in their default colour
            check(failures, t, "-sl-knob", "-sl-switch-off", DesignTokens.AA_LARGE);
            check(failures, t, "-sl-accent-text", "-sl-accent", DesignTokens.AA_LARGE);
            for (String bg : SURFACES) {
                check(failures, t, "-sl-icon", bg, DesignTokens.AA_LARGE);
            }
        }
        assertTrue(failures.isEmpty(), "Contrast below WCAG AA:\n" + String.join("\n", failures));
    }

    @Test
    void accentRampPassesAAForEveryPresetAndRandomColours() {
        List<Color> accents = new ArrayList<>();
        for (String hex : DesignTokens.ACCENT_PRESETS) {
            accents.add(Color.web(hex));
        }
        java.util.Random r = new java.util.Random(26);
        for (int i = 0; i < 200; i++) {
            accents.add(Color.color(r.nextDouble(), r.nextDouble(), r.nextDouble()));
        }
        accents.addAll(List.of(Color.WHITE, Color.BLACK, Color.web("#808080"), Color.web("#ffff00"), Color.web("#0000ff")));
        List<String> failures = new ArrayList<>();
        for (Theme t : themes()) {
            for (Color a : accents) {
                Map<String, Color> tokens = new HashMap<>(t.tokens());
                tokens.putAll(DesignTokens.ramp(a, t.light()).tokens());
                Theme withAccent = new Theme(t.name() + " accent " + DesignTokens.web(a), t.light(), tokens);
                for (String fill : List.of("-sl-accent", "-sl-accent-hover", "-sl-accent-pressed")) {
                    check(failures, withAccent, "-sl-accent-text", fill, DesignTokens.AA_TEXT);
                }
                for (String bg : List.of("-sl-sunken", "-sl-bg", "-sl-surface", "-sl-raised", "-sl-overlay-bg", "-sl-accent-subtle")) {
                    check(failures, withAccent, "-sl-accent-fg", bg, DesignTokens.AA_TEXT);
                }
                check(failures, withAccent, "-sl-text", "-sl-accent-subtle", DesignTokens.AA_TEXT);
                for (String bg : List.of("-sl-bg", "-sl-surface", "-sl-raised")) {
                    check(failures, withAccent, "-sl-accent", bg, DesignTokens.AA_LARGE);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " accent pairs below WCAG AA:\n"
            + String.join("\n", failures.subList(0, Math.min(40, failures.size()))));
    }

    @Test
    void stylesheetDefaultsMatchTheComputedRampForTheDefaultAccent() {
        for (Theme t : themes()) {
            if (t.name().contains("high contrast")) {
                continue;
            }
            DesignTokens.ramp(DesignTokens.DEFAULT_ACCENT, t.light()).tokens().forEach((k, v) ->
                assertEquals(DesignTokens.web(v), DesignTokens.web(t.tokens().get(k)), t.name() + " " + k));
        }
    }

    @Test
    void stylesheetSurfacesMatchTheOnesTheRampIsCheckedAgainst() {
        for (Theme t : themes()) {
            List<Color> expected = t.light() ? DesignTokens.LIGHT_SURFACES : DesignTokens.DARK_SURFACES;
            List<String> names = List.of("-sl-bg", "-sl-sunken", "-sl-surface", "-sl-raised", "-sl-overlay-bg");
            for (int i = 0; i < names.size(); i++) {
                assertEquals(DesignTokens.web(expected.get(i)), DesignTokens.web(t.tokens().get(names.get(i))),
                    t.name() + " " + names.get(i));
            }
        }
    }

    @Test
    void changingTheAccentRecoloursTheWholeRamp() {
        DesignTokens.AccentRamp purple = DesignTokens.ramp(Color.web("#7C6CF2"), false);
        DesignTokens.AccentRamp teal = DesignTokens.ramp(Color.web("#2BB3A3"), false);
        purple.tokens().forEach((k, v) -> {
            if (!k.equals("-sl-accent-text")) {
                assertTrue(!v.equals(teal.tokens().get(k)), k + " did not change with the accent");
            }
        });
        String css = teal.css();
        for (String token : teal.tokens().keySet()) {
            assertTrue(css.contains(token + ":"), token);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void check(List<String> failures, Theme t, String fg, String bg, double min) {
        Color f = t.tokens().get(fg);
        Color b = t.tokens().get(bg);
        if (f == null || b == null) {
            failures.add(t.name() + ": missing token " + (f == null ? fg : bg));
            return;
        }
        double ratio = DesignTokens.contrast(f, b);
        if (ratio < min) {
            failures.add(String.format(java.util.Locale.ROOT, "%s: %s %s on %s %s = %.2f (needs %.1f)", t.name(), fg,
                DesignTokens.web(f), bg, DesignTokens.web(b), ratio, min));
        }
    }

    private static List<Theme> themes() {
        String css = stylesheet();
        Map<String, String> dark = block(css, ".root");
        Map<String, String> light = merge(dark, block(css, ".root.light"));
        Map<String, String> hc = block(css, ".root.high-contrast");
        Map<String, String> lightHc = block(css, ".root.light.high-contrast");
        return List.of(
            new Theme("dark", false, resolve(dark)),
            new Theme("light", true, resolve(light)),
            new Theme("dark high contrast", false, resolve(merge(dark, hc))),
            new Theme("light high contrast", true, resolve(merge(merge(light, hc), lightHc))));
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        Map<String, String> m = new LinkedHashMap<>(a);
        m.putAll(b);
        return m;
    }

    private static String stylesheet() {
        try (InputStream in = DesignTokensContrastTest.class.getResourceAsStream("/app/shardlight.css")) {
            assertTrue(in != null, "shardlight.css not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** the -sl-* declarations of the rule whose selector is exactly {@code selector} */
    private static Map<String, String> block(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)}").matcher(css);
        assertTrue(m.find(), "no " + selector + " block in shardlight.css");
        Map<String, String> out = new LinkedHashMap<>();
        Matcher d = Pattern.compile("(-sl-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(m.group(1));
        while (d.find()) {
            out.put(d.group(1), d.group(2).trim());
        }
        return out;
    }

    private static Map<String, Color> resolve(Map<String, String> raw) {
        Map<String, Color> out = new LinkedHashMap<>();
        for (String k : raw.keySet()) {
            String v = raw.get(k);
            for (int i = 0; i < 10 && v.startsWith("-sl-"); i++) {
                v = raw.get(v);
                assertTrue(v != null, "unresolved token reference in " + k);
            }
            out.put(k, Color.web(v));
        }
        return out;
    }
}
