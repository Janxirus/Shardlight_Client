package dev.shardlight.client.minecraft;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything an installed version needs on disk, computed from a {@link ResolvedVersion} for one OS.
 *
 * @param version         the merged version
 * @param client          the client jar put on the classpath
 * @param clientSource    the jar {@code client} is materialized from (a parent's verified jar), or null when
 *                        {@code client} is downloaded directly
 * @param libraries       library and native jars, de-duplicated by path
 * @param classpath       library jars in order, client jar last
 * @param nativeJars      jars whose native libraries are extracted at launch
 * @param nativeExcludes  entry prefixes not to extract
 * @param assetIndex      the asset index file, or null when the version has none
 * @param assetIndexId    asset index id
 * @param loggingFile     the Log4j configuration file, or null
 * @param loggingArgument the JVM argument for it ({@code ${path}} resolved), or null
 */
record InstallPlan(ResolvedVersion version, PlannedFile client, PlannedFile clientSource, List<PlannedFile> libraries,
                   List<Path> classpath, List<Path> nativeJars, List<String> nativeExcludes, PlannedFile assetIndex,
                   String assetIndexId, PlannedFile loggingFile, String loggingArgument) {
}
