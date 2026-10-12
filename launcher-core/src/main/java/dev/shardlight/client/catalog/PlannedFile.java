package dev.shardlight.client.catalog;

import dev.shardlight.client.content.ContentKind;

import java.net.URI;

/**
 * One file an install plan will download.
 *
 * @param title      project title
 * @param version    the catalog version
 * @param file       the file of that version to download
 * @param kind       where it goes (mods, resource packs, shader packs)
 * @param dependency true when pulled in as a dependency of another planned file
 * @param requiredBy catalog project id of the planned file that needs this one; null for the root
 * @param pageUrl    the project's web page, may be null
 */
public record PlannedFile(String title, CatalogVersion version, CatalogFile file, ContentKind kind, boolean dependency,
                          String requiredBy, URI pageUrl) {

    /** without a page URL */
    public PlannedFile(String title, CatalogVersion version, CatalogFile file, ContentKind kind, boolean dependency,
                       String requiredBy) {
        this(title, version, file, kind, dependency, requiredBy, null);
    }
}
