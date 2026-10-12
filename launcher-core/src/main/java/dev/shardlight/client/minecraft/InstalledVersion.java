package dev.shardlight.client.minecraft;

import java.nio.file.Path;
import java.util.List;

/**
 * An installed, launchable version: everything the launch command and the launch step need.
 *
 * @param id               the version id (passed to the game as {@code version_name})
 * @param version          the merged version JSON
 * @param clientJar        the client jar (last on the classpath)
 * @param classpath        library jars in order, then the client jar; no duplicates
 * @param nativeJars       jars whose native libraries are extracted before launch
 * @param nativeExcludes   entry-name prefixes not to extract (always includes {@code META-INF/})
 * @param assetIndexId     asset index id ({@code assets_index_name})
 * @param assetsRoot       {@code game/assets} ({@code assets_root})
 * @param virtualAssets    the index is virtual: legacy versions read {@code assets/virtual/<id>}
 * @param mapToResources   the index maps to resources: copy objects to {@code <gameDir>/resources} before launch
 * @param javaMajorVersion required Java feature release (8 when the JSON names none)
 * @param javaComponent    Mojang runtime component, or null
 * @param loggingArgument  Log4j JVM argument with the config path filled in, or null
 */
public record InstalledVersion(String id, ResolvedVersion version, Path clientJar, List<Path> classpath,
                               List<Path> nativeJars, List<String> nativeExcludes, String assetIndexId, Path assetsRoot,
                               boolean virtualAssets, boolean mapToResources, int javaMajorVersion, String javaComponent,
                               String loggingArgument) {
    public InstalledVersion {
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        nativeJars = nativeJars == null ? List.of() : List.copyOf(nativeJars);
        nativeExcludes = nativeExcludes == null ? List.of() : List.copyOf(nativeExcludes);
    }

    /** {@code assets/virtual/<index id>} (only meaningful for virtual indexes) */
    public Path virtualAssetsDir() {
        return assetsRoot.resolve("virtual").resolve(assetIndexId);
    }

    /** the shared libraries folder ({@code game/libraries}, next to {@code game/assets}) */
    public Path librariesRoot() {
        Path parent = assetsRoot.toAbsolutePath().getParent();
        return parent == null ? assetsRoot.resolveSibling("libraries") : parent.resolve("libraries");
    }

    /** the asset index file */
    public Path assetIndexFile() {
        return assetsRoot.resolve("indexes").resolve(assetIndexId + ".json");
    }
}
