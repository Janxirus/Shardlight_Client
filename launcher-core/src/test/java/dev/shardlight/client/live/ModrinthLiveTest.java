package dev.shardlight.client.live;

import dev.shardlight.client.catalog.CatalogFile;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.ModrinthCatalog;
import dev.shardlight.client.catalog.SearchPage;
import dev.shardlight.client.catalog.SearchQuery;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The launcher's Modrinth client against the real Modrinth API: the answers still have the shape it reads. */
@Tag("live")
class ModrinthLiveTest {
    private final ModrinthCatalog modrinth = new ModrinthCatalog(HttpService.create(), Endpoints.DEFAULT);

    @Test
    void searchFindsSodiumWithTheFieldsTheLauncherShows() throws Exception {
        SearchPage page = modrinth.search(SearchQuery.mods("sodium"), CancellationToken.NONE);
        assertFalse(page.hits().isEmpty());
        CatalogProject sodium = page.hits().stream().filter(p -> "sodium".equals(p.slug())).findFirst().orElseThrow();
        assertFalse(sodium.id().isBlank());
        assertEquals("Sodium", sodium.title());
        assertEquals(ContentKind.MOD, sodium.kind());
        assertNotNull(sodium.pageUrl());
        assertTrue(sodium.loaders().contains("fabric"), sodium.loaders().toString());
    }

    @Test
    void aProjectComesBackBySlug() throws Exception {
        CatalogProject p = modrinth.project("sodium", CancellationToken.NONE);
        assertEquals("sodium", p.slug());
        assertFalse(p.summary() == null || p.summary().isBlank());
        assertTrue(p.downloads() > 0);
    }

    @Test
    void versionsAreNewestFirstWithAFileThatCanBeVerified() throws Exception {
        List<CatalogVersion> versions = modrinth.versions("sodium", null, LoaderType.FABRIC, CancellationToken.NONE);
        assertFalse(versions.isEmpty());
        CatalogVersion newest = versions.get(0);
        assertTrue(newest.loaders().contains("fabric"));
        assertFalse(newest.gameVersions().isEmpty());
        CatalogFile file = newest.primaryFile().orElseThrow();
        assertTrue(file.downloadable());
        assertEquals("cdn.modrinth.com", file.url().getHost());
        assertTrue(file.fileName().endsWith(".jar"), file.fileName());
        assertTrue(file.size() > 0);
        assertTrue(file.sha1().matches("[0-9a-f]{40}"), "sha1");
        assertTrue(file.sha512().matches("[0-9a-f]{128}"), "sha512");
        assertEquals(newest.id(), modrinth.version(newest.id(), CancellationToken.NONE).id());
    }

    @Test
    void aFileIsFoundAgainByItsHash() throws Exception {
        CatalogVersion newest = modrinth.versions("sodium", null, LoaderType.FABRIC, CancellationToken.NONE).get(0);
        String sha1 = newest.primaryFile().orElseThrow().sha1();
        assertEquals(newest.id(), modrinth.versionsByHash(List.of(sha1), CancellationToken.NONE).get(sha1).id());
    }

    @Test
    void fabricApiHasAVersionForMinecraft26_3() throws Exception {
        // every Shardlight module needs it, so the launcher installs it alongside
        List<CatalogVersion> versions = modrinth.versions("fabric-api", "26.3", LoaderType.FABRIC, CancellationToken.NONE);
        assertFalse(versions.isEmpty(), "no Fabric API for 26.3 on Modrinth");
        assertTrue(versions.get(0).primaryFile().orElseThrow().downloadable());
    }

    @Test
    void categoriesAreListed() throws Exception {
        assertFalse(modrinth.categories(ContentKind.MOD, CancellationToken.NONE).isEmpty());
    }

    @Test
    void aProjectThatDoesNotExistIsNotFound() {
        LauncherException e = assertThrows(LauncherException.class,
            () -> modrinth.project("shardlight-live-test-no-such-project", CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
    }
}
