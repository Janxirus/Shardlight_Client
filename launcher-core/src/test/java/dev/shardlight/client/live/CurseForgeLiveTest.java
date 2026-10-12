package dev.shardlight.client.live;

import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.CurseForgeCatalog;
import dev.shardlight.client.catalog.SearchPage;
import dev.shardlight.client.catalog.SearchQuery;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher's CurseForge client against the real CurseForge API. Skipped unless the secret
 * {@code SHARDLIGHT_CURSEFORGE_API_KEY} is set in the environment; the key is only ever passed to the client.
 */
@Tag("live")
class CurseForgeLiveTest {
    private CurseForgeCatalog curseForge;

    @BeforeEach
    void setUp() {
        String key = System.getenv("SHARDLIGHT_CURSEFORGE_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "SHARDLIGHT_CURSEFORGE_API_KEY is not set");
        curseForge = new CurseForgeCatalog(HttpService.create(), Endpoints.DEFAULT, () -> Optional.of(key.trim()));
    }

    @Test
    void searchFindsSodium() throws Exception {
        SearchPage page = curseForge.search(SearchQuery.mods("sodium"), CancellationToken.NONE);
        assertFalse(page.hits().isEmpty());
        CatalogProject first = page.hits().get(0);
        assertFalse(first.id().isBlank());
        assertFalse(first.title().isBlank());
    }

    @Test
    void aProjectHasFilesWithAnIdAndAName() throws Exception {
        CatalogProject sodium = curseForge.search(SearchQuery.mods("sodium"), CancellationToken.NONE).hits().get(0);
        List<CatalogVersion> files = curseForge.versions(sodium.id(), null, LoaderType.FABRIC, CancellationToken.NONE);
        assertFalse(files.isEmpty());
        CatalogVersion newest = files.get(0);
        assertFalse(newest.id().isBlank());
        assertTrue(newest.primaryFile().isPresent());
        assertFalse(newest.primaryFile().get().fileName().isBlank());
    }
}
