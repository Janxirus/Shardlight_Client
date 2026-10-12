package dev.shardlight.client.project;

/**
 * A project moved to the launcher's trash ({@code trash/<trashId>/}); it can be restored or deleted.
 *
 * @param trashId    folder name inside {@code trash/}: {@code <projectId>-<yyyyMMdd-HHmmss>}
 * @param projectId  the id the project had
 * @param name       its display name
 * @param archivedAt when it was moved, epoch milliseconds
 * @param sizeBytes  size of the folder
 */
public record ArchivedProject(String trashId, String projectId, String name, long archivedAt, long sizeBytes) {
}
