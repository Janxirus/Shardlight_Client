package dev.shardlight.client.shardlight;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One optional Shardlight mod as listed in the bundled catalog ({@code shardlight/catalog.json}).
 *
 * @param id                short module id used by the launcher and in {@code project.shardlightModules}:
 *                          {@code ui}, {@code map}, {@code party} or {@code companion}
 * @param modId             the mod id in the jar's {@code fabric.mod.json} ({@code softglass}, {@code shardmap}...)
 * @param nameKey           i18n key of the module's name
 * @param descriptionKey    i18n key of a one-line description
 * @param minecraftVersions supported Minecraft versions; {@code "26.3"} also covers patch releases such as 26.3.1
 * @param loaders           supported loaders by catalog id ({@code fabric})
 * @param minLoaderVersion  oldest Fabric Loader the jar accepts
 * @param javaMajor         Java feature version the jar needs
 * @param requires          catalog dependencies the launcher installs alongside (Fabric API)
 * @param worksBetterWith   other module ids that add features when installed too (informational only)
 * @param configFile        the mod's own config file, relative to the game directory
 * @param modrinthSlug      Modrinth project slug once published; null until then
 * @param pageUrl           where the user can read about the module
 * @param sources           where release jars can be downloaded from
 */
public record ShardlightModule(String id, String modId, String nameKey, String descriptionKey,
                               List<String> minecraftVersions, List<String> loaders, String minLoaderVersion,
                               int javaMajor, List<Dependency> requires, List<String> worksBetterWith,
                               String configFile, String modrinthSlug, String pageUrl, List<Source> sources) {

    /**
     * A dependency installed from a catalog.
     *
     * @param source    catalog name, {@code modrinth}
     * @param project   catalog project slug or id, for example {@code fabric-api}
     * @param reasonKey i18n key explaining why it is needed
     */
    public record Dependency(String source, String project, String reasonKey) {
        /** {@code source:project}, the form used in {@link ApplyResult#catalogDependenciesNeeded()} */
        public String coordinate() {
            return source + ":" + project;
        }
    }

    /**
     * A place release jars come from.
     *
     * @param type         {@code github-release}
     * @param repo         {@code owner/name}
     * @param assetPattern glob for the release asset name, for example {@code *.jar}
     */
    public record Source(String type, String repo, String assetPattern) {
    }

    public ShardlightModule {
        minecraftVersions = minecraftVersions == null ? List.of() : List.copyOf(minecraftVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        requires = requires == null ? List.of() : List.copyOf(requires);
        worksBetterWith = worksBetterWith == null ? List.of() : List.copyOf(worksBetterWith);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** true when this module was built for {@code minecraftVersion} (exact, or a patch release of a listed version) */
    public boolean supportsMinecraft(String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return false;
        }
        String v = minecraftVersion.trim();
        for (String s : minecraftVersions) {
            if (v.equals(s) || v.startsWith(s + ".")) {
                return true;
            }
        }
        return false;
    }

    /** true when the module runs on the loader with this catalog id ({@code fabric}) */
    public boolean supportsLoader(String loaderCatalogId) {
        return loaderCatalogId != null && loaders.contains(loaderCatalogId.toLowerCase(Locale.ROOT));
    }

    /** the GitHub release source, when the module has one */
    public Optional<Source> githubRelease() {
        return sources.stream().filter(s -> "github-release".equals(s.type())).findFirst();
    }
}
