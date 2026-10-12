package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionManifestServiceTest {
    static final String MANIFEST = """
        {
          "latest": {"release": "1.21.4", "snapshot": "25w02a"},
          "versions": [
            {"id": "25w02a", "type": "snapshot", "url": "https://piston-meta.mojang.com/v1/packages/0b3f2f6bb9c0ec5a1b0f02a8d7e8e4e2c1a3b5d7/25w02a.json",
             "time": "2025-01-08T13:13:01+00:00", "releaseTime": "2025-01-08T13:06:01+00:00",
             "sha1": "0b3f2f6bb9c0ec5a1b0f02a8d7e8e4e2c1a3b5d7", "complianceLevel": 1},
            {"id": "1.21.4", "type": "release", "url": "https://piston-meta.mojang.com/v1/packages/a3bcba436caa849622fd7e1e5b89489ed6c9ac63/1.21.4.json",
             "time": "2024-12-03T10:24:48+00:00", "releaseTime": "2024-12-03T10:12:57+00:00",
             "sha1": "a3bcba436caa849622fd7e1e5b89489ed6c9ac63", "complianceLevel": 1},
            {"id": "../../evil", "type": "release", "url": "https://example.com/evil.json", "sha1": "x"},
            {"id": "bad-url", "type": "release", "url": "ht tp://bad url", "sha1": "a3bcba436caa849622fd7e1e5b89489ed6c9ac63"},
            {"id": "b1.7.3", "type": "old_beta", "url": "https://piston-meta.mojang.com/v1/packages/0a1b2c3d4e5f60718293a4b5c6d7e8f901234567/b1.7.3.json",
             "time": "2010-07-08T22:00:00+00:00", "releaseTime": "2011-07-07T22:00:00+00:00",
             "sha1": "NOT-A-SHA1", "complianceLevel": 0}
          ]
        }""";

    @TempDir
    Path dir;
    TestHttpServer server;
    AppPaths paths;
    VersionManifestService service;

    @BeforeEach
    void setUp() {
        server = new TestHttpServer();
        paths = new AppPaths(dir);
        service = new VersionManifestService(HttpService.forTests(), Endpoints.local(server.base()), paths);
        service.setFailureQuietMillis(0);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void fetchesParsesAndCaches() throws Exception {
        server.json("/mojang/version_manifest_v2.json", MANIFEST);
        List<VersionSummary> versions = service.versions(false, CancellationToken.NONE);
        assertEquals(List.of("25w02a", "1.21.4", "b1.7.3"), versions.stream().map(VersionSummary::id).toList(),
            "unsafe ids and broken URLs are skipped");
        VersionSummary release = versions.get(1);
        assertTrue(release.isRelease());
        assertEquals(URI.create("https://piston-meta.mojang.com/v1/packages/a3bcba436caa849622fd7e1e5b89489ed6c9ac63/1.21.4.json"),
            release.url());
        assertEquals("a3bcba436caa849622fd7e1e5b89489ed6c9ac63", release.sha1());
        assertEquals(Instant.parse("2024-12-03T10:12:57Z"), release.releaseTime());
        assertEquals(1, release.complianceLevel());
        assertTrue(versions.get(0).isSnapshot());
        assertNull(versions.get(2).sha1(), "a malformed sha1 is dropped");
        assertEquals("1.21.4", service.latestRelease(CancellationToken.NONE));
        assertEquals("25w02a", service.latestSnapshot(CancellationToken.NONE));
        assertTrue(Files.exists(paths.cache().resolve("version_manifest_v2.json")));
        assertEquals("old_beta", service.find("b1.7.3", CancellationToken.NONE).orElseThrow().type());
        assertTrue(service.find("1.99", CancellationToken.NONE).isEmpty());
        assertTrue(service.find(null, CancellationToken.NONE).isEmpty());
        assertEquals(1, server.hits("/mojang/version_manifest_v2.json"), "kept in memory");
        service.versions(true, CancellationToken.NONE);
        assertEquals(2, server.hits("/mojang/version_manifest_v2.json"), "refresh fetches again");
    }

    @Test
    void fallsBackToTheCacheWhenOffline() throws Exception {
        server.json("/mojang/version_manifest_v2.json", MANIFEST);
        service.versions(false, CancellationToken.NONE);
        VersionManifestService offline = new VersionManifestService(HttpService.forTests(),
            Endpoints.local(URI.create("http://127.0.0.1:9/")), paths);
        assertEquals(3, offline.versions(false, CancellationToken.NONE).size());
        assertEquals("1.21.4", offline.latestRelease(CancellationToken.NONE));
        server.text("/mojang/version_manifest_v2.json", 500, "down");
        assertEquals(3, service.versions(true, CancellationToken.NONE).size(), "server errors fall back too");
        server.json("/mojang/version_manifest_v2.json", "{ this is not json");
        assertEquals(3, service.versions(true, CancellationToken.NONE).size(), "garbage falls back too");
        assertEquals(3, service.versions(false, CancellationToken.NONE).size());
    }

    @Test
    void failsWhenNeitherNetworkNorCacheWork() throws Exception {
        server.text("/mojang/version_manifest_v2.json", 404, "nope");
        LauncherException e = assertThrows(LauncherException.class, () -> service.versions(false, CancellationToken.NONE));
        assertEquals("install.error.manifestUnavailable", e.messageKey());
        Files.createDirectories(paths.cache());
        Files.writeString(paths.cache().resolve("version_manifest_v2.json"), "corrupt{");
        assertThrows(LauncherException.class, () -> service.versions(true, CancellationToken.NONE));
        server.json("/mojang/version_manifest_v2.json", "{\"latest\":{}}");
        LauncherException invalid = assertThrows(LauncherException.class, () -> service.versions(true, CancellationToken.NONE));
        assertEquals(ErrorCode.INVALID_DATA, invalid.code());
    }

    @Test
    void recentFailureIsRememberedBriefly() throws Exception {
        server.text("/mojang/version_manifest_v2.json", 404, "nope");
        service.setFailureQuietMillis(60_000);
        assertThrows(LauncherException.class, () -> service.find("1.21.4", CancellationToken.NONE));
        assertThrows(LauncherException.class, () -> service.find("1.21.4", CancellationToken.NONE));
        assertEquals(1, server.hits("/mojang/version_manifest_v2.json"), "the second call didn't wait for the network");
        server.json("/mojang/version_manifest_v2.json", MANIFEST);
        assertEquals(3, service.versions(true, CancellationToken.NONE).size(), "refresh ignores the remembered failure");
    }

    @Test
    void cancellationIsNotMaskedByTheCache() throws Exception {
        server.json("/mojang/version_manifest_v2.json", MANIFEST);
        service.versions(false, CancellationToken.NONE);
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        LauncherException e = assertThrows(LauncherException.class, () -> service.versions(true, cancel));
        assertTrue(e.isCancelled());
    }

    @Test
    void latestFallsBackToTheList() throws Exception {
        server.json("/mojang/version_manifest_v2.json", """
            {"versions":[{"id":"25w03a","type":"snapshot","url":"https://a/b.json","sha1":"x"},
                         {"id":"1.21.4","type":"release","url":"https://a/c.json"}]}""");
        assertEquals("1.21.4", service.latestRelease(CancellationToken.NONE));
        assertEquals("25w03a", service.latestSnapshot(CancellationToken.NONE));
        assertEquals(Instant.EPOCH, service.find("1.21.4", CancellationToken.NONE).orElseThrow().releaseTime());
    }

    @Test
    void concurrentCallersShareOneFetch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.handle("/mojang/version_manifest_v2.json", ex -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            TestHttpServer.respond(ex, 200, "application/json", MANIFEST.getBytes());
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<VersionSummary>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> service.versions(false, CancellationToken.NONE)));
            }
            Thread.sleep(200);
            release.countDown();
            for (Future<List<VersionSummary>> f : futures) {
                assertEquals(3, f.get().size());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, server.hits("/mojang/version_manifest_v2.json"));
        assertFalse(service.versions(false, CancellationToken.NONE).isEmpty());
    }
}
