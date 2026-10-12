package dev.shardlight.client.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One version (Modrinth) or file (CurseForge) of a catalog project.
 *
 * @param source        provider id
 * @param id            version id (CurseForge: the numeric file id as text)
 * @param projectId     owning project id
 * @param versionNumber version label ("0.6.3+mc26.3"); CurseForge: the file's display name
 * @param name          display name
 * @param changelog     changelog text, may be null
 * @param gameVersions  supported Minecraft versions
 * @param loaders       supported loaders (lowercase: fabric, quilt, forge, neoforge, iris, minecraft...)
 * @param channel       "release", "beta" or "alpha"
 * @param published     publication time, may be null
 * @param files         downloadable files (at least one normally)
 * @param dependencies  relations to other projects
 */
public record CatalogVersion(String source, String id, String projectId, String versionNumber, String name, String changelog,
                             List<String> gameVersions, List<String> loaders, String channel, Instant published,
                             List<CatalogFile> files, List<CatalogDependency> dependencies) {
    public CatalogVersion {
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        files = files == null ? List.of() : List.copyOf(files);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        channel = channel == null ? "release" : channel;
    }

    /** the primary file, else the first one */
    public Optional<CatalogFile> primaryFile() {
        for (CatalogFile f : files) {
            if (f.primary()) {
                return Optional.of(f);
            }
        }
        return files.isEmpty() ? Optional.empty() : Optional.of(files.get(0));
    }

    /** 0 release, 1 beta, 2 alpha (lower is more stable) */
    public int channelRank() {
        return switch (channel) {
            case "release" -> 0;
            case "beta" -> 1;
            default -> 2;
        };
    }
}
