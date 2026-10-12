package dev.shardlight.client.project;

import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.preset.Preset;

import java.util.Set;

/**
 * What the "new project" dialog collected. Only {@code name} and {@code minecraftVersion} are required;
 * null values get defaults (builtin icon, the "custom" template, vanilla, no Shardlight modules, no theme,
 * {@link Preset#DEFAULT}, default Java settings). {@link ProjectTemplate#request} builds one from a
 * template.
 *
 * @param name              display name, 1-64 characters with at least one letter or digit
 * @param icon              builtin icon id ("grass" or "builtin:grass"), or null
 * @param templateId        a {@link ProjectTemplates} id, or null for "custom"
 * @param minecraftVersion  the Minecraft version id, for example "1.21.4" or "26.1"
 * @param loader            loader and loader version, or null for vanilla
 * @param shardlightModules Shardlight modules to install later ("ui", "map", "party", "companion"), or null
 * @param shardlightTheme   Shardlight theme pack id, or null
 * @param preset            visual preset, or null for Balanced
 * @param java              Java settings (copied), or null for defaults
 */
public record NewProjectRequest(String name, String icon, String templateId, String minecraftVersion, LoaderSpec loader,
                                Set<String> shardlightModules, String shardlightTheme, Preset preset, JavaSettings java) {

    /** a vanilla project with every other choice left at its default */
    public static NewProjectRequest vanilla(String name, String minecraftVersion) {
        return new NewProjectRequest(name, null, "vanilla", minecraftVersion, LoaderSpec.vanilla(), Set.of(), null,
            Preset.DEFAULT, null);
    }
}
