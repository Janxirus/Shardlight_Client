package dev.shardlight.client.project;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.loader.LoaderSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A project (instance): one isolated Minecraft setup with its own game directory, mods, packs, config,
 * saves, screenshots, logs, Java settings and preset. Stored as {@code projects/<id>/project.json}; the
 * game directory is {@code projects/<id>/minecraft}. Nothing mutable is shared between projects.
 */
public final class Project {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** folder name and stable id: lowercase letters, digits and dashes */
    public String id;
    public String name;
    /** "builtin:<icon id>" or "file:icon.png" (a file inside the project folder) */
    public String icon = "builtin:grass";
    /** the template the project was created from (informational) */
    public String template = "vanilla";
    public String minecraftVersion;
    public LoaderSpec loader = LoaderSpec.vanilla();
    public JavaSettings java = new JavaSettings();
    public WindowSettings window = new WindowSettings();
    public PresetState preset = new PresetState();
    /** selected Shardlight modules ("ui", "map", "party", "companion"); empty = none */
    public Set<String> shardlightModules = new LinkedHashSet<>();
    /** the Shardlight theme pack id the user picked, or null */
    public String shardlightTheme;
    /** installed mods, resource packs and shader packs */
    public List<ContentEntry> content = new ArrayList<>();
    public boolean favorite;
    public long createdAt;
    public long lastPlayedAt;
    public long totalPlayMillis;
    public String notes = "";
    /** set once the game files for this project's version + loader were installed and verified */
    public String installedVersionId;

    public boolean hasShardlight() {
        return shardlightModules != null && !shardlightModules.isEmpty();
    }

    @Override
    public String toString() {
        return name + " (" + id + ", " + minecraftVersion + ", " + loader + ")";
    }
}
