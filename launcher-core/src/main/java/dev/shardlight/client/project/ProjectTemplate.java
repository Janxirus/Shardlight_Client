package dev.shardlight.client.project;

import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.Preset;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A starting point for a new project. Only the Shardlight template preselects Shardlight modules; every
 * other template starts without them, and {@link #suggestions()} are only offered, never installed.
 *
 * @param id                 stable id stored in {@link Project#template}
 * @param nameKey            i18n key of the template's name
 * @param descriptionKey     i18n key of its one-line description
 * @param suggestedLoader    the loader the template proposes
 * @param preset             the visual preset it proposes
 * @param preselectedModules Shardlight modules ticked by default (empty except for shardlight-default)
 * @param shardlightTheme    Shardlight theme pack id it proposes, or null
 * @param suggestions        optional content the player may tick
 * @param custom             true for the template that asks for every choice
 */
public record ProjectTemplate(String id, String nameKey, String descriptionKey, LoaderType suggestedLoader, Preset preset,
                              Set<String> preselectedModules, String shardlightTheme, List<SuggestedContent> suggestions,
                              boolean custom) {
    public ProjectTemplate {
        preselectedModules = Set.copyOf(preselectedModules == null ? Set.of() : preselectedModules);
        suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
    }

    /**
     * A creation request with this template's choices. {@code loaderVersion} is ignored for vanilla.
     * The modules keep a stable order (ui, map, party, companion).
     */
    public NewProjectRequest request(String name, String minecraftVersion, String loaderVersion) {
        Set<String> modules = new LinkedHashSet<>();
        for (String m : ProjectTemplates.MODULE_ORDER) {
            if (preselectedModules.contains(m)) {
                modules.add(m);
            }
        }
        LoaderSpec loader = suggestedLoader == null || suggestedLoader == LoaderType.VANILLA
            ? LoaderSpec.vanilla() : new LoaderSpec(suggestedLoader, loaderVersion);
        return new NewProjectRequest(name, null, id, minecraftVersion, loader, modules, shardlightTheme, preset, null);
    }
}
