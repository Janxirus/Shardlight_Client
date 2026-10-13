package dev.shardlight.client.project;

import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.Preset;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in project templates. Shardlight content is optional everywhere: only
 * {@code shardlight-default}, which the player picks deliberately, preselects Shardlight modules and a
 * theme. Every suggestion is optional and installed only when the player ticks it.
 */
public final class ProjectTemplates {
    /** Shardlight module ids in display order */
    public static final List<String> MODULE_ORDER = List.of("ui", "map", "party", "companion");
    /** the theme the Shardlight template proposes */
    public static final String SHARDLIGHT_DEFAULT_THEME = "ember";

    public static final String VANILLA = "vanilla";
    public static final String SHARDLIGHT_DEFAULT = "shardlight-default";
    public static final String ULTRA_PERFORMANCE = "ultra-performance";
    public static final String BALANCED = "balanced";
    public static final String CREATIVE_BUILDING = "creative-building";
    public static final String PVP = "pvp";
    public static final String EXPLORATION = "exploration";
    public static final String MODDED_SURVIVAL = "modded-survival";
    public static final String CUSTOM = "custom";

    private static final List<ProjectTemplate> ALL = List.of(
        template(VANILLA, LoaderType.VANILLA, Preset.BALANCED, Set.of(), null, List.of(), false),
        template(SHARDLIGHT_DEFAULT, LoaderType.FABRIC, Preset.BALANCED, Set.copyOf(MODULE_ORDER), SHARDLIGHT_DEFAULT_THEME,
            List.of(), false),
        template(ULTRA_PERFORMANCE, LoaderType.FABRIC, Preset.ULTRA_PERFORMANCE, Set.of(), null, List.of(
            modrinth("sodium", "performance"),
            modrinth("lithium", "performance"),
            modrinth("ferrite-core", "memory")), false),
        template(BALANCED, LoaderType.FABRIC, Preset.BALANCED, Set.of(), null, List.of(), false),
        template(CREATIVE_BUILDING, LoaderType.FABRIC, Preset.BALANCED, Set.of(), null, List.of(
            modrinth("worldedit", "building"),
            modrinth("sodium", "performance")), false),
        template(PVP, LoaderType.FABRIC, Preset.PERFORMANCE, Set.of(), null, List.of(
            modrinth("sodium", "performance"),
            modrinth("entityculling", "fps"),
            modrinth("immediatelyfast", "fps")), false),
        template(EXPLORATION, LoaderType.FABRIC, Preset.BALANCED, Set.of(), null, List.of(
            new SuggestedContent(SuggestedContent.SHARDLIGHT, "map", ContentKind.MOD, reason("map")),
            modrinth("sodium", "performance")), false),
        template(MODDED_SURVIVAL, LoaderType.FABRIC, Preset.BALANCED, Set.of(), null, List.of(
            modrinth("appleskin", "hunger"),
            modrinth("jade", "info"),
            modrinth("sodium", "performance")), false),
        template(CUSTOM, LoaderType.VANILLA, Preset.DEFAULT, Set.of(), null, List.of(), true));

    private ProjectTemplates() {
    }

    /** every template in display order */
    public static List<ProjectTemplate> all() {
        return ALL;
    }

    /** the template with this id (case-insensitive) */
    public static Optional<ProjectTemplate> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String k = id.strip().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(t -> t.id().equals(k)).findFirst();
    }

    private static ProjectTemplate template(String id, LoaderType loader, Preset preset, Set<String> modules, String theme,
                                            List<SuggestedContent> suggestions, boolean custom) {
        return new ProjectTemplate(id, "project.template." + id + ".name", "project.template." + id + ".description",
            loader, preset, modules, theme, suggestions, custom);
    }

    private static SuggestedContent modrinth(String slug, String reason) {
        return new SuggestedContent(SuggestedContent.MODRINTH, slug, ContentKind.MOD, reason(reason));
    }

    private static String reason(String reason) {
        return "project.suggestion.reason." + reason;
    }
}
