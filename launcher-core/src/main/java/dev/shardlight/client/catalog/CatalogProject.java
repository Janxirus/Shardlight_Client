package dev.shardlight.client.catalog;

import dev.shardlight.client.content.ContentKind;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * A project (mod, pack, shader, modpack) as a catalog describes it.
 *
 * @param source       provider id ("modrinth", "curseforge")
 * @param id           provider project id (Modrinth base62 id, CurseForge numeric mod id as text)
 * @param slug         URL name
 * @param title        display name
 * @param summary      one-line description
 * @param body         long description (Modrinth markdown; CurseForge HTML converted to plain text); null in
 *                     search results
 * @param author       owner name, may be null
 * @param iconUrl      icon, may be null
 * @param downloads    total downloads
 * @param follows      followers (Modrinth) or thumbs-up count (CurseForge)
 * @param categories   display categories (loaders removed)
 * @param gameVersions Minecraft versions with at least one file
 * @param loaders      loaders with at least one file (lowercase catalog names)
 * @param kind         content kind
 * @param clientSide   "required", "optional", "unsupported", "unknown" or null (CurseForge)
 * @param serverSide   same for servers
 * @param pageUrl      the project's web page
 * @param gallery      screenshot URLs
 * @param license      license id or name, may be null
 * @param updated      last update time, may be null
 */
public record CatalogProject(String source, String id, String slug, String title, String summary, String body,
                             String author, URI iconUrl, long downloads, long follows, List<String> categories,
                             List<String> gameVersions, List<String> loaders, ContentKind kind, String clientSide,
                             String serverSide, URI pageUrl, List<URI> gallery, String license, Instant updated) {
    public CatalogProject {
        categories = categories == null ? List.of() : List.copyOf(categories);
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        gallery = gallery == null ? List.of() : List.copyOf(gallery);
        title = title == null ? (slug == null ? id : slug) : title;
        summary = summary == null ? "" : summary;
    }
}
