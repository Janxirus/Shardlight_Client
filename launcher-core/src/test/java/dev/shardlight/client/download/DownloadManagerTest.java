package dev.shardlight.client.download;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadManagerTest {
    @TempDir
    Path dir;
    TestHttpServer server;
    DownloadManager dm;
    byte[] data;

    @BeforeEach
    void setUp() {
        server = new TestHttpServer();
        dm = new DownloadManager(HttpService.forTests(), dir.resolve("cache"), 4, 2);
        dm.setBackoffMillis(5);
        data = new byte[300_000];
        new Random(42).nextBytes(data);
    }

    @AfterEach
    void tearDown() {
        dm.close();
        server.close();
    }

    @Test
    void downloadsAndVerifies() throws Exception {
        server.bytes("/a.bin", data);
        Path target = dir.resolve("out/a.bin");
        DownloadRequest r = DownloadRequest.sha1(server.uri("/a.bin"), target, Hashing.hash(data, HashAlgo.SHA1), data.length);
        dm.downloadAll("test", List.of(r), "stage", ProgressListener.NONE, CancellationToken.NONE);
        assertArrayEquals(data, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("out/a.bin.part")));
        assertEquals(DownloadState.DONE, dm.items().get(0).state());
    }

    @Test
    void skipsValidExistingFile() throws Exception {
        server.bytes("/a.bin", data);
        Path target = dir.resolve("a.bin");
        Files.write(target, data);
        DownloadRequest r = DownloadRequest.sha1(server.uri("/a.bin"), target, Hashing.hash(data, HashAlgo.SHA1), data.length);
        dm.downloadAll("test", List.of(r), "stage", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(0, server.hits("/a.bin"));
        assertEquals(DownloadState.SKIPPED, dm.items().get(0).state());
    }

    @Test
    void checksumMismatchFailsAndLeavesNoFile() {
        server.bytes("/a.bin", data);
        Path target = dir.resolve("a.bin");
        DownloadRequest r = DownloadRequest.sha1(server.uri("/a.bin"), target, "0".repeat(40), data.length);
        LauncherException e = assertThrows(LauncherException.class,
            () -> dm.downloadAll("test", List.of(r), "stage", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("a.bin.part")));
        assertEquals(3, server.hits("/a.bin")); // 1 try + 2 retries
    }

    @Test
    void retriesServerErrorsThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.handle("/flaky.bin", ex -> {
            if (calls.incrementAndGet() < 3) {
                TestHttpServer.respond(ex, 503, "busy");
            } else {
                TestHttpServer.respond(ex, 200, "application/octet-stream", data);
            }
        });
        Path target = dir.resolve("flaky.bin");
        dm.downloadAll("test", List.of(DownloadRequest.sha1(server.uri("/flaky.bin"), target,
            Hashing.hash(data, HashAlgo.SHA1), data.length)), "stage", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(Files.exists(target));
        assertEquals(3, calls.get());
    }

    @Test
    void notFoundFallsBackToMirror() throws Exception {
        server.bytes("/mirror.bin", data);
        Path target = dir.resolve("m.bin");
        DownloadRequest r = DownloadRequest.sha1(server.uri("/missing.bin"), target, Hashing.hash(data, HashAlgo.SHA1), data.length)
            .withMirrors(List.of(server.uri("/mirror.bin")));
        dm.downloadAll("test", List.of(r), "stage", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(1, server.hits("/missing.bin"));
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test
    void resumesPartialDownloadWithRange() throws Exception {
        server.bytes("/big.bin", data);
        Path target = dir.resolve("big.bin");
        Files.write(dir.resolve("big.bin.part"), java.util.Arrays.copyOf(data, 100_000));
        dm.downloadAll("test", List.of(DownloadRequest.sha1(server.uri("/big.bin"), target,
            Hashing.hash(data, HashAlgo.SHA1), data.length)), "stage", ProgressListener.NONE, CancellationToken.NONE);
        assertArrayEquals(data, Files.readAllBytes(target));
        assertEquals("bytes=100000-", server.requests().get(0).header("Range"));
    }

    @Test
    void sharedCacheAvoidsSecondDownload() throws Exception {
        server.bytes("/mod.jar", data);
        String sha1 = Hashing.hash(data, HashAlgo.SHA1);
        DownloadRequest one = DownloadRequest.sha1(server.uri("/mod.jar"), dir.resolve("p1/mods/mod.jar"), sha1, data.length).cached();
        DownloadRequest two = DownloadRequest.sha1(server.uri("/mod.jar"), dir.resolve("p2/mods/mod.jar"), sha1, data.length).cached();
        dm.downloadAll("one", List.of(one), "stage", ProgressListener.NONE, CancellationToken.NONE);
        dm.downloadAll("two", List.of(two), "stage", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(1, server.hits("/mod.jar"));
        assertArrayEquals(data, Files.readAllBytes(dir.resolve("p2/mods/mod.jar")));
    }

    @Test
    void cancellationStopsQueuedWork() {
        server.handle("/slow.bin", ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            TestHttpServer.respond(ex, 200, "application/octet-stream", data);
        });
        CancellationToken cancel = new CancellationToken();
        DownloadJob job = dm.submit("slow", List.of(DownloadRequest.of(server.uri("/slow.bin"), dir.resolve("slow.bin"))), cancel);
        cancel.cancel();
        LauncherException e = assertThrows(LauncherException.class, () -> job.await(ProgressListener.NONE, "stage"));
        assertTrue(e.isCancelled());
        assertFalse(Files.exists(dir.resolve("slow.bin")));
    }

    @Test
    void rejectsInsecureUrlsInProduction() {
        try (DownloadManager prod = new DownloadManager(HttpService.create(), null, 1, 0)) {
            DownloadRequest r = DownloadRequest.of(java.net.URI.create("http://example.com/a.jar"), dir.resolve("a.jar"));
            LauncherException e = assertThrows(LauncherException.class,
                () -> prod.downloadAll("t", List.of(r), "s", ProgressListener.NONE, CancellationToken.NONE));
            assertEquals(ErrorCode.UNSAFE_PATH, e.code());
        }
    }

    @Test
    void pausingAGroupHoldsItAndResumingFinishesFromThePartFile() throws Exception {
        List<String> ranges = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.handle("/slow.bin", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            ranges.add(range == null ? "" : range);
            int from = range == null ? 0 : Integer.parseInt(range.substring("bytes=".length(), range.indexOf('-')));
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(from > 0 ? 206 : 200, data.length - from);
            try (java.io.OutputStream out = ex.getResponseBody()) {
                for (int at = from; at < data.length; at += 20_000) {
                    out.write(data, at, Math.min(20_000, data.length - at));
                    out.flush();
                    Thread.sleep(25);
                }
            } catch (java.io.IOException | InterruptedException e) {
                // the client paused or went away
            }
        });
        Path target = dir.resolve("slow.bin");
        DownloadJob job = dm.submit("pausable", List.of(DownloadRequest.sha1(server.uri("/slow.bin"), target,
            Hashing.hash(data, HashAlgo.SHA1), data.length)), new CancellationToken());
        DownloadItem item = dm.items().get(0);
        waitFor(() -> item.bytesDone() > 0);
        dm.pauseGroup("pausable");
        waitFor(() -> item.state() == DownloadState.PAUSED);
        long held = item.bytesDone();
        assertTrue(held < data.length, "paused before the end");
        Thread.sleep(300);
        assertEquals(held, item.bytesDone(), "nothing is downloaded while paused");
        assertEquals(DownloadState.PAUSED, item.state());
        assertTrue(dm.isPaused("pausable"));
        dm.resumeGroup("pausable");
        job.await(ProgressListener.NONE, "stage");
        assertArrayEquals(data, Files.readAllBytes(target));
        assertEquals(DownloadState.DONE, item.state());
        assertTrue(ranges.size() >= 2 && ranges.get(1).startsWith("bytes="), "the second request resumes with a Range: " + ranges);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < end, "timed out");
            Thread.sleep(10);
        }
    }
}
