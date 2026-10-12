package dev.shardlight.client.catalog;

import java.net.URI;

/**
 * One downloadable file of a catalog version.
 *
 * @param url                      download URL; null when the author doesn't allow third-party downloads
 * @param fileName                 the file name the catalog gives (sanitize before using it on disk)
 * @param size                     size in bytes, or -1/0 when unknown
 * @param sha1                     hex SHA-1, may be null
 * @param sha512                   hex SHA-512, may be null (CurseForge has none)
 * @param primary                  the main file of the version
 * @param distributionRestricted   true when the file may only be downloaded from the website; the launcher
 *                                 then never downloads it and sends the user to the project page
 */
public record CatalogFile(URI url, String fileName, long size, String sha1, String sha512, boolean primary,
                          boolean distributionRestricted) {

    /** true when the launcher may download this file itself */
    public boolean downloadable() {
        return url != null && !distributionRestricted;
    }
}
