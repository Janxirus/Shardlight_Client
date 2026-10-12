package dev.shardlight.client.preset;

/**
 * Adaptive quality: lowering effects in game when the frame rate drops below a target. Stored per project
 * and recorded in {@code launcher-preset.json} for the mods, but the in-game side is not implemented yet, so
 * it stays off by default and switching it on has no effect in game today.
 *
 * @param enabled           the user's choice (off by default)
 * @param targetFps         the frame rate to hold, {@value #MIN_FPS}-{@value #MAX_FPS}
 * @param implementedInGame whether any installed mod acts on it; false in this version
 */
public record AdaptiveQuality(boolean enabled, int targetFps, boolean implementedInGame) {
    public static final int MIN_FPS = 30;
    public static final int MAX_FPS = 240;
    /** i18n key of the "not implemented in game yet" note */
    public static final String NOT_IMPLEMENTED_KEY = "preset.adaptive.notImplemented";
}
