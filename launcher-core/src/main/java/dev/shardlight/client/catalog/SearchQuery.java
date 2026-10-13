package dev.shardlight.client.catalog;

import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.loader.LoaderType;

import java.util.List;

/**
 * A catalog search. Null filters mean "any".
 *
 * @param text             free text, may be blank
 * @param kind             mods, resource packs, shader packs, data packs or modpacks (null = mods)
 * @param minecraftVersion only projects with a version for this Minecraft version
 * @param loader           only projects for this loader (ignored for resource and shader packs)
 * @param categories       every listed category must match (provider category names, see
 *                         {@link CatalogProvider#categories})
 * @param sort             result order (null = relevance)
 * @param offset           index of the first hit (0-based)
 * @param limit            hits per page (providers cap it: Modrinth 100, CurseForge 50)
 * @param environment      only projects that work on the client or on a server (null = any; Modrinth only,
 *                         CurseForge doesn't say)
 */
public record SearchQuery(String text, ContentKind kind, String minecraftVersion, LoaderType loader, List<String> categories,
                          SortOrder sort, int offset, int limit, Environment environment) {
    /** where the content runs */
    public enum Environment {
        CLIENT,
        SERVER
    }

    public SearchQuery {
        text = text == null ? "" : text.strip();
        kind = kind == null ? ContentKind.MOD : kind;
        categories = categories == null ? List.of() : List.copyOf(categories);
        sort = sort == null ? SortOrder.RELEVANCE : sort;
        offset = Math.max(0, offset);
        limit = limit <= 0 ? 20 : limit;
    }

    /** a query without an environment filter */
    public SearchQuery(String text, ContentKind kind, String minecraftVersion, LoaderType loader, List<String> categories,
                       SortOrder sort, int offset, int limit) {
        this(text, kind, minecraftVersion, loader, categories, sort, offset, limit, null);
    }

    /** a first page of 20 mods matching {@code text} */
    public static SearchQuery mods(String text) {
        return new SearchQuery(text, ContentKind.MOD, null, null, List.of(), SortOrder.RELEVANCE, 0, 20);
    }

    /** the same query at another offset */
    public SearchQuery withOffset(int newOffset) {
        return new SearchQuery(text, kind, minecraftVersion, loader, categories, sort, newOffset, limit, environment);
    }
}
