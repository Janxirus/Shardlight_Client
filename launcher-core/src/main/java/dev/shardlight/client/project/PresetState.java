package dev.shardlight.client.project;

import dev.shardlight.client.preset.Preset;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A project's Shardlight preset. Custom overrides are kept separately so switching to Ultra Performance
 * and back to Custom restores them exactly.
 */
public final class PresetState {
    public Preset active = Preset.DEFAULT;
    /** setting key -> value the user chose in Custom mode (JSON primitives) */
    public Map<String, Object> customOverrides = new LinkedHashMap<>();
    /** also tune Minecraft's own video settings (options.txt); off unless the user opts in */
    public boolean applyVanillaVideoSettings;
    /** adaptive quality (in-game, needs mod support); off by default until validated */
    public boolean adaptiveQuality;
    public int adaptiveTargetFps = 60;
    /** preset last written into the game's config files, and when */
    public Preset lastApplied;
    public long lastAppliedAt;
}
