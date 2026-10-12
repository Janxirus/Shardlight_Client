package dev.shardlight.client.catalog;

/**
 * A dependency of a catalog version. Either id may be null (Modrinth lists some dependencies by version
 * only; CurseForge only by project).
 *
 * @param projectId depended-on project id, may be null
 * @param versionId exact version id, may be null
 * @param fileName  Modrinth's file name hint for external dependencies, may be null
 * @param type      relation
 */
public record CatalogDependency(String projectId, String versionId, String fileName, DependencyType type) {
}
