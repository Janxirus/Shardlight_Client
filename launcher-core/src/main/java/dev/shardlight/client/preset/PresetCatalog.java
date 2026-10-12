package dev.shardlight.client.preset;

import dev.shardlight.client.preset.SettingDef.Constraint;
import dev.shardlight.client.preset.SettingDef.Cost;
import dev.shardlight.client.preset.SettingDef.Kind;
import dev.shardlight.client.preset.SettingDef.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every setting the presets control and each preset's value for it. Only keys that exist in the mods'
 * config classes (Shardlight UI 4.1 {@code SoftGlassConfig}, Map 1.5 {@code MapConfig}, Party 1.2
 * {@code PartyConfig}, Companion 1.2 {@code CompanionConfig}) are used, with the ranges the mods accept
 * (their own sanitizing and settings sliders). BALANCED is always the mod's own default.
 *
 * <p>Vanilla video options use the {@code options.txt} names listed in {@link VanillaOptions}, checked against
 * the Minecraft 26.3 client; they are only written with the project's opt-in and only for a checked version.
 */
public final class PresetCatalog {
    private static final List<SettingDef> SETTINGS;
    private static final Map<String, SettingDef> BY_KEY;
    private static final Map<String, EnumMap<Preset, Object>> VALUES;

    static {
        Table t = new Table();
        // ------------------------------------------------------------ Shardlight UI (config/shardlight.json)
        //                                                      default   ULTRA_PERF PERF    QUALITY  ULTRA_Q
        t.bool(Target.UI, "performanceMode", "effects", Cost.LOW, false, true, false, false, false);
        t.bool(Target.UI, "reducedEffects", "effects", Cost.LOW, false, true, true, false, false);
        t.bool(Target.UI, "menuEmbers", "effects", Cost.HIGH, true, false, false, true, true);
        t.bool(Target.UI, "themedParticles", "effects", Cost.LOW, true, false, false, true, true);
        t.bool(Target.UI, "clickFx", "effects", Cost.MEDIUM, true, false, false, true, true);
        t.bool(Target.UI, "buttonFx", "effects", Cost.MEDIUM, true, false, false, true, true);
        t.bool(Target.UI, "damageNumbers", "effects", Cost.MEDIUM, true, false, true, true, true);
        t.bool(Target.UI, "themeFrames", "decorations", Cost.LOW, true, false, true, true, true);
        t.bool(Target.UI, "themeDecor", "decorations", Cost.HIGH, true, false, false, true, true);
        t.bool(Target.UI, "rarityTooltips", "decorations", Cost.LOW, true, false, true, true, true);
        t.bool(Target.UI, "itemRarityBorders", "decorations", Cost.LOW, false, false, false, true, true);
        t.bool(Target.UI, "reducedMotion", "animation", Cost.LOW, false, true, false, false, false);
        t.num(Target.UI, "animationIntensity", Kind.DOUBLE, "animation", Cost.LOW, Constraint.range(0, 2), 1.0, 0.0, 0.5, 1.2, 1.5);
        t.bool(Target.UI, "screenAnimation", "animation", Cost.LOW, true, false, true, true, true);
        t.num(Target.UI, "screenAnimMillis", Kind.INT, "animation", Cost.LOW, Constraint.range(80, 500), 220, 120, 160, 260, 300);
        t.add(Target.UI, "hoverAnim", Kind.STRING, "animation", Cost.LOW, false,
            Constraint.oneOf("off", "pop", "lift", "glow", "all"), "pop", "off", "pop", "all", "all");
        t.num(Target.UI, "hoverAnimMillis", Kind.INT, "animation", Cost.LOW, Constraint.range(40, 400), 110, 80, 90, 130, 150);
        t.num(Target.UI, "hoverAnimIntensity", Kind.DOUBLE, "animation", Cost.LOW, Constraint.range(0, 2), 1.0, 0.0, 0.6, 1.2, 1.4);
        t.bool(Target.UI, "smoothHotbar", "animation", Cost.LOW, true, false, true, true, true);
        t.bool(Target.UI, "smoothScroll", "animation", Cost.LOW, true, false, true, true, true);
        t.bool(Target.UI, "buttonLift", "animation", Cost.LOW, true, false, false, true, true);
        t.bool(Target.UI, "tooltipEaseIn", "animation", Cost.LOW, true, false, false, true, true);
        t.bool(Target.UI, "chatSmoothLines", "animation", Cost.LOW, true, false, true, true, true);
        t.bool(Target.UI, "joinSlideIn", "animation", Cost.LOW, true, false, false, true, true);
        t.bool(Target.UI, "pickupPopups", "hud", Cost.LOW, true, false, true, true, true);
        // essential indicators: on in every preset
        t.essential(Target.UI, "lowHealthVignette");
        t.essential(Target.UI, "durabilityHud");
        t.essential(Target.UI, "armorHud");
        t.essential(Target.UI, "toolWarning");
        t.essential(Target.UI, "elytraWarning");
        t.essential(Target.UI, "foodAirWarnings");

        // ------------------------------------------------------------ Shardlight Map (config/shardmap.json)
        t.num(Target.MAP, "scanSpeed", Kind.INT, "map", Cost.MEDIUM, Constraint.range(1, 32), 6, 2, 4, 8, 12);
        t.num(Target.MAP, "radarRange", Kind.INT, "map", Cost.LOW, Constraint.range(16, 128), 48, 32, 32, 64, 96);
        t.bool(Target.MAP, "terrainShading", "map", Cost.LOW, true, false, true, true, true);
        t.bool(Target.MAP, "biomeColors", "map", Cost.LOW, true, true, true, true, true);
        t.bool(Target.MAP, "trail", "map", Cost.LOW, true, false, true, true, true);
        t.bool(Target.MAP, "smoothZoom", "animation", Cost.LOW, true, false, false, true, true);
        t.bool(Target.MAP, "nightTint", "effects", Cost.LOW, true, false, true, true, true);
        t.bool(Target.MAP, "beacons", "effects", Cost.MEDIUM, true, false, false, true, true);

        // ------------------------------------------------------------ Shardlight Party (config/shardparty.json)
        t.num(Target.PARTY, "shareEvery", Kind.INT, "network", Cost.LOW, Constraint.range(10, 120), 15, 30, 20, 15, 10);
        t.bool(Target.PARTY, "worldMarkers", "hud", Cost.LOW, true, true, true, true, true);

        // ------------------------------------------------------------ Shardlight Companion (config/shardcompanion.json)
        t.bool(Target.COMPANION, "zoomSmooth", "animation", Cost.LOW, true, false, true, true, true);
        t.bool(Target.COMPANION, "centreGuides", "decorations", Cost.LOW, true, false, true, true, true);

        // ------------------------------------------------------------ Minecraft video options (options.txt, opt-in)
        // keep in step with preset/vanilla-options-26.3.txt (VanillaOptionsTest)
        // Minecraft 26 replaced graphicsMode (0, 1, 2) with the graphicsPreset name. Balanced keeps the game's own
        // Fancy; every other preset sets individual options, so the game is told it is Custom rather than a named
        // preset whose values these settings no longer match.
        t.add(Target.VANILLA, "graphicsPreset", Kind.STRING, "video", Cost.LOW, false,
            Constraint.oneOf("fast", "fancy", "fabulous", "custom"), "fancy", "custom", "custom", "custom", "custom");
        t.num(Target.VANILLA, "renderDistance", Kind.INT, "video", Cost.HIGH, Constraint.range(2, 32), 12, 8, 10, 16, 24);
        t.num(Target.VANILLA, "simulationDistance", Kind.INT, "video", Cost.HIGH, Constraint.range(5, 32), 12, 6, 8, 12, 16);
        t.num(Target.VANILLA, "particles", Kind.INT, "video", Cost.MEDIUM, Constraint.oneOf(0, 1, 2), 0, 2, 1, 0, 0);
        t.bool(Target.VANILLA, "entityShadows", "video", Cost.MEDIUM, true, false, false, true, true);
        t.bool(Target.VANILLA, "ao", "video", Cost.LOW, true, false, true, true, true);
        t.num(Target.VANILLA, "biomeBlendRadius", Kind.INT, "video", Cost.MEDIUM, Constraint.range(0, 7), 2, 0, 1, 3, 5);
        t.num(Target.VANILLA, "maxFps", Kind.INT, "video", Cost.LOW, Constraint.range(10, 260), 120, 260, 260, 120, 120);
        t.bool(Target.VANILLA, "enableVsync", "video", Cost.LOW, true, false, false, true, true);
        t.num(Target.VANILLA, "mipmapLevels", Kind.INT, "video", Cost.LOW, Constraint.range(0, 4), 4, 0, 2, 4, 4);
        t.add(Target.VANILLA, "renderClouds", Kind.STRING, "video", Cost.MEDIUM, false,
            Constraint.oneOf("true", "fast", "false"), "true", "false", "fast", "true", "true");
        t.num(Target.VANILLA, "entityDistanceScaling", Kind.DOUBLE, "video", Cost.MEDIUM, Constraint.range(0.5, 5.0),
            1.0, 0.75, 1.0, 1.25, 1.5);

        SETTINGS = List.copyOf(t.defs);
        Map<String, SettingDef> byKey = new LinkedHashMap<>();
        for (SettingDef d : SETTINGS) {
            if (byKey.put(d.key(), d) != null) {
                throw new IllegalStateException("duplicate setting " + d.key());
            }
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
        VALUES = Collections.unmodifiableMap(t.values);
    }

    private PresetCatalog() {
    }

    /** every setting, grouped by target in file order */
    public static List<SettingDef> settings() {
        return SETTINGS;
    }

    /** one setting by key, for example {@code map.scanSpeed} */
    public static Optional<SettingDef> setting(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }

    /**
     * The full value table of a preset (every setting, in {@link #settings()} order). CUSTOM has no table of
     * its own: it is BALANCED plus the project's overrides, see {@link PresetEngine#values(dev.shardlight.client.project.Project, Preset)}.
     */
    public static Map<String, Object> values(Preset preset) {
        Preset p = preset == null || preset == Preset.CUSTOM ? Preset.BALANCED : preset;
        Map<String, Object> out = new LinkedHashMap<>();
        for (SettingDef d : SETTINGS) {
            out.put(d.key(), VALUES.get(d.key()).get(p));
        }
        return Collections.unmodifiableMap(out);
    }

    /** one preset's value for one setting */
    public static Object value(SettingDef def, Preset preset) {
        Preset p = preset == null || preset == Preset.CUSTOM ? Preset.BALANCED : preset;
        return VALUES.get(def.key()).get(p);
    }

    /** builds the table and checks every value against its setting */
    private static final class Table {
        final List<SettingDef> defs = new ArrayList<>();
        final Map<String, EnumMap<Preset, Object>> values = new LinkedHashMap<>();

        void bool(Target target, String jsonKey, String group, Cost cost, boolean def,
                  boolean ultraPerformance, boolean performance, boolean quality, boolean ultraQuality) {
            add(target, jsonKey, Kind.BOOLEAN, group, cost, false, Constraint.any(), def, ultraPerformance, performance,
                quality, ultraQuality);
        }

        void essential(Target target, String jsonKey) {
            add(target, jsonKey, Kind.BOOLEAN, "hud", Cost.LOW, true, Constraint.any(), true, true, true, true, true);
        }

        void num(Target target, String jsonKey, Kind kind, String group, Cost cost, Constraint c, Object def,
                 Object ultraPerformance, Object performance, Object quality, Object ultraQuality) {
            add(target, jsonKey, kind, group, cost, false, c, def, ultraPerformance, performance, quality, ultraQuality);
        }

        void add(Target target, String jsonKey, Kind kind, String group, Cost cost, boolean essential, Constraint c,
                 Object def, Object ultraPerformance, Object performance, Object quality, Object ultraQuality) {
            String prefix = target == Target.VANILLA ? "vanilla" : target.moduleId();
            String key = prefix + "." + jsonKey;
            SettingDef d = new SettingDef(key, target, jsonKey, kind, "preset.setting." + key + ".label",
                "preset.setting." + key + ".description", group, cost, essential, null, c);
            d = new SettingDef(key, target, jsonKey, kind, d.labelKey(), d.descriptionKey(), group, cost, essential,
                d.normalize(def), c);
            EnumMap<Preset, Object> v = new EnumMap<>(Preset.class);
            v.put(Preset.ULTRA_PERFORMANCE, d.normalize(ultraPerformance));
            v.put(Preset.PERFORMANCE, d.normalize(performance));
            v.put(Preset.BALANCED, d.modDefault());
            v.put(Preset.QUALITY, d.normalize(quality));
            v.put(Preset.ULTRA_QUALITY, d.normalize(ultraQuality));
            defs.add(d);
            values.put(key, v);
        }
    }
}
