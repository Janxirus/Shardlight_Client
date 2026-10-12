package dev.shardlight.client.content;

/**
 * A file that is already in a project's content folder and should be recorded with known catalog details
 * (used by modpack imports). See {@link ContentService#recordExisting}.
 *
 * @param kind          content kind (decides the folder)
 * @param fileName      the file name inside the kind's folder (without {@code .disabled})
 * @param source        where it came from
 * @param projectId     catalog project id, may be null
 * @param versionId     catalog version/file id, may be null
 * @param versionNumber version label, may be null (then read from the file's metadata)
 * @param title         display title, may be null (then read from the file's metadata)
 * @param pageUrl       project page, may be null
 * @param downloadUrl   where the file was downloaded from, may be null
 */
public record ExistingFile(ContentKind kind, String fileName, ContentSource source, String projectId, String versionId,
                           String versionNumber, String title, String pageUrl, String downloadUrl) {
}
