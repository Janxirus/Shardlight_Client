package dev.shardlight.client.project;

import dev.shardlight.client.content.ContentKind;

/**
 * Optional content a template recommends. Suggestions are shown unticked and are never installed unless
 * the player ticks them.
 *
 * @param source      "modrinth", "curseforge" or "shardlight"
 * @param projectSlug the catalog slug (Modrinth slug, or the Shardlight module id)
 * @param kind        mod, resource pack or shader pack
 * @param reasonKey   i18n key of a one-line reason shown next to it
 */
public record SuggestedContent(String source, String projectSlug, ContentKind kind, String reasonKey) {
    public static final String MODRINTH = "modrinth";
    public static final String CURSEFORGE = "curseforge";
    public static final String SHARDLIGHT = "shardlight";
}
