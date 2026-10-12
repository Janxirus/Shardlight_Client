package dev.shardlight.client.modpack;

import java.net.URI;

/**
 * A modpack file the launcher may not download because its author disallows third-party downloads. The
 * user downloads it from {@code pageUrl} and adds it (for example with
 * {@code ContentService.installLocalFile}).
 *
 * @param title    project title
 * @param fileName the expected file name
 * @param pageUrl  the project's web page
 * @param folder   the game-directory folder it belongs in ("mods", "resourcepacks", "shaderpacks")
 * @param projectId catalog project id
 * @param fileId   catalog file id
 */
public record RestrictedFile(String title, String fileName, URI pageUrl, String folder, String projectId, String fileId) {
}
