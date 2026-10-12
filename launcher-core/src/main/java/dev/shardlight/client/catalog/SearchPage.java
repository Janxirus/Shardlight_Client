package dev.shardlight.client.catalog;

import java.util.List;

/**
 * One page of search results.
 *
 * @param hits   the projects on this page
 * @param offset index of the first hit
 * @param limit  page size that was used
 * @param total  total number of hits the provider reports (CurseForge caps paging at 10 000)
 */
public record SearchPage(List<CatalogProject> hits, int offset, int limit, int total) {
    public SearchPage {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /** true when another page exists after this one */
    public boolean hasMore() {
        return offset + hits.size() < total && !hits.isEmpty();
    }
}
