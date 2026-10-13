package dev.shardlight.client.preset;

/**
 * Shardlight visual/performance presets. BALANCED is the default; ULTRA_PERFORMANCE is the one-click
 * cheapest setting. CUSTOM keeps the user's own per-feature choices.
 */
public enum Preset {
    ULTRA_PERFORMANCE,
    PERFORMANCE,
    BALANCED,
    QUALITY,
    ULTRA_QUALITY,
    CUSTOM;

    public static final Preset DEFAULT = BALANCED;

    /** i18n key of the preset's name */
    public String nameKey() {
        return "preset." + name().toLowerCase(java.util.Locale.ROOT) + ".name";
    }

    /** i18n key of the one-line description */
    public String descriptionKey() {
        return "preset." + name().toLowerCase(java.util.Locale.ROOT) + ".description";
    }
}
