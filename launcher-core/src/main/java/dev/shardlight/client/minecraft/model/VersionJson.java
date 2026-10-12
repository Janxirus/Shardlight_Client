package dev.shardlight.client.minecraft.model;

import com.google.gson.JsonParseException;
import dev.shardlight.client.util.Json;

import java.util.List;
import java.util.Map;

/**
 * A version JSON ({@code versions/<id>/<id>.json}) as written by Mojang or by a loader installer (Fabric,
 * Quilt, Forge, NeoForge profiles inherit from a vanilla version through {@code inheritsFrom}). Unknown
 * fields are ignored; absent lists and maps are empty.
 *
 * @param id                 version id
 * @param inheritsFrom       parent version id, or null
 * @param type               release, snapshot, old_beta, old_alpha (loaders copy the parent's)
 * @param mainClass          the class to start
 * @param jar                use {@code versions/<jar>/<jar>.jar} as the client jar (older loader profiles)
 * @param minecraftArguments legacy (pre-1.13) game arguments, one space-separated string
 * @param arguments          modern game and JVM arguments
 * @param libraries          libraries, in classpath order
 * @param downloads          {@code client}, {@code server}, {@code client_mappings}...
 * @param assetIndex         the asset index to use
 * @param assets             the asset index id (same as {@code assetIndex.id})
 * @param javaVersion        required Java version and Mojang runtime component
 * @param logging            Log4j configuration
 * @param complianceLevel    Mojang's compliance level (0 or 1)
 * @param releaseTime        ISO-8601 release time
 * @param time               ISO-8601 last-update time
 */
public record VersionJson(String id, String inheritsFrom, String type, String mainClass, String jar,
                          String minecraftArguments, Arguments arguments, List<Library> libraries,
                          Map<String, DownloadInfo> downloads, AssetIndexInfo assetIndex, String assets,
                          JavaVersionInfo javaVersion, LoggingInfo logging, Integer complianceLevel,
                          String releaseTime, String time) {
    public VersionJson {
        libraries = ModelCopies.list(libraries);
        downloads = ModelCopies.map(downloads);
    }

    /** parse a version JSON; throws {@link JsonParseException} for malformed text */
    public static VersionJson parse(String json) throws JsonParseException {
        try {
            VersionJson v = Json.read(json, VersionJson.class);
            if (v == null) {
                throw new JsonParseException("empty version JSON");
            }
            return v;
        } catch (IllegalStateException | NumberFormatException | ClassCastException e) {
            throw new JsonParseException(e.getMessage(), e);
        }
    }

    public String toJson() {
        return Json.write(this);
    }

    public boolean hasArguments() {
        return arguments != null && (!arguments.game().isEmpty() || !arguments.jvm().isEmpty());
    }

    public List<Argument> gameArguments() {
        return arguments == null ? List.of() : arguments.game();
    }

    public List<Argument> jvmArguments() {
        return arguments == null ? List.of() : arguments.jvm();
    }
}
