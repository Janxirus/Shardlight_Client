package dev.shardlight.client.minecraft;

import dev.shardlight.client.minecraft.model.Argument;
import dev.shardlight.client.minecraft.model.AssetIndexInfo;
import dev.shardlight.client.minecraft.model.DownloadInfo;
import dev.shardlight.client.minecraft.model.JavaVersionInfo;
import dev.shardlight.client.minecraft.model.Library;
import dev.shardlight.client.minecraft.model.LoggingInfo;

import java.util.List;
import java.util.Map;

/**
 * A version with its whole {@code inheritsFrom} chain merged (see {@link VersionResolver}).
 *
 * @param id                 the requested version id (passed to the game as {@code version_name})
 * @param chain              the ids from the requested version down to the root vanilla version
 * @param type               release, snapshot, old_beta, old_alpha
 * @param mainClass          the class to start
 * @param minecraftArguments legacy game argument string, or null
 * @param gameArguments      modern game arguments, root version's first
 * @param jvmArguments       modern JVM arguments, root version's first
 * @param libraries          the requested version's libraries first, then its parents', de-duplicated across
 *                           versions by {@code group:artifact[:classifier]}
 * @param downloads          merged {@code downloads} (a child's entry replaces its parent's)
 * @param clientOwnerId      the version whose {@code downloads.client} is used, or null when none has one
 * @param jarId              the client jar lives at {@code versions/<jarId>/<jarId>.jar}
 * @param assetIndex         asset index, or null
 * @param assets             asset index id, or null
 * @param javaVersion        Java requirement, or null
 * @param logging            Log4j configuration, or null
 * @param complianceLevel    Mojang compliance level (0 when unknown)
 * @param releaseTime        ISO-8601 release time, or null
 */
public record ResolvedVersion(String id, List<String> chain, String type, String mainClass, String minecraftArguments,
                              List<Argument> gameArguments, List<Argument> jvmArguments, List<Library> libraries,
                              Map<String, DownloadInfo> downloads, String clientOwnerId, String jarId,
                              AssetIndexInfo assetIndex, String assets, JavaVersionInfo javaVersion, LoggingInfo logging,
                              int complianceLevel, String releaseTime) {
    /** the asset index name used when a version names none (the official launcher's default) */
    public static final String DEFAULT_ASSETS = "legacy";

    public ResolvedVersion {
        chain = List.copyOf(chain);
        gameArguments = List.copyOf(gameArguments);
        jvmArguments = List.copyOf(jvmArguments);
        libraries = List.copyOf(libraries);
        downloads = Map.copyOf(downloads);
    }

    /** the root (vanilla) version of the chain */
    public String rootId() {
        return chain.get(chain.size() - 1);
    }

    /** true when the requested version inherits from another one (loader profiles) */
    public boolean isInherited() {
        return chain.size() > 1;
    }

    /** {@code downloads.client}, or null */
    public DownloadInfo clientDownload() {
        return downloads.get("client");
    }

    /** the asset index id: {@code assetIndex.id}, else {@code assets}, else {@code legacy} */
    public String assetIndexId() {
        if (assetIndex != null && assetIndex.id() != null && !assetIndex.id().isBlank()) {
            return assetIndex.id();
        }
        return assets != null && !assets.isBlank() ? assets : DEFAULT_ASSETS;
    }

    /** the required Java feature release; 8 when the JSON names none (old versions) */
    public int javaMajorVersion() {
        return javaVersion != null && javaVersion.majorVersion() != null && javaVersion.majorVersion() > 0
            ? javaVersion.majorVersion() : 8;
    }

    /** the Mojang runtime component, or null */
    public String javaComponent() {
        return javaVersion == null || javaVersion.component() == null || javaVersion.component().isBlank()
            ? null : javaVersion.component();
    }

    /** true when the version uses the 1.13+ {@code arguments} format */
    public boolean hasModernArguments() {
        return !gameArguments.isEmpty() || !jvmArguments.isEmpty();
    }

    /** true when the game arguments react to quick play features (1.20+) */
    public boolean supportsQuickPlay() {
        for (Argument a : gameArguments) {
            if (Rules.mentionsFeature(a.rules(), Rules.QUICK_PLAY_FEATURES)) {
                return true;
            }
        }
        return false;
    }
}
