package dev.shardlight.client.download;

import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: a batch far larger than the virtual-thread carrier pool (an asset index has thousands of
 * objects) must not stall. Waiting for a download slot used to pin carrier threads (monitor wait on a
 * virtual thread), so the running downloads could never be resumed.
 */
class DownloadManagerLargeBatchTest {
    @TempDir
    Path dir;

    @Test
    void thousandsOfQueuedItemsComplete() {
        try (TestHttpServer server = new TestHttpServer();
             DownloadManager dm = new DownloadManager(HttpService.forTests(), null, 3, 1)) {
            List<DownloadRequest> requests = new ArrayList<>();
            for (int i = 0; i < 1500; i++) {
                byte[] data = ("object " + i).getBytes(StandardCharsets.UTF_8);
                server.bytes("/o/" + i, data);
                requests.add(DownloadRequest.sha1(server.uri("/o/" + i), dir.resolve("o/" + i), Hashing.hash(data, HashAlgo.SHA1),
                    data.length));
            }
            assertTimeoutPreemptively(Duration.ofSeconds(90),
                () -> dm.downloadAll("big", requests, "stage", ProgressListener.NONE, CancellationToken.NONE));
            for (int i = 0; i < 1500; i += 149) {
                assertTrue(Files.isRegularFile(dir.resolve("o/" + i)));
            }
            assertEquals(1500, server.requests().size());
        }
    }

    @Test
    void cancellingALargeQueueReturnsPromptly() {
        try (TestHttpServer server = new TestHttpServer();
             DownloadManager dm = new DownloadManager(HttpService.forTests(), null, 1, 0)) {
            server.handle("/slow", ex -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                TestHttpServer.respond(ex, 200, "x");
            });
            List<DownloadRequest> requests = new ArrayList<>();
            for (int i = 0; i < 800; i++) {
                requests.add(DownloadRequest.of(server.uri("/slow"), dir.resolve("s/" + i)));
            }
            CancellationToken cancel = new CancellationToken();
            DownloadJob job = dm.submit("slow", requests, cancel);
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                Thread.sleep(500);
                cancel.cancel();
                try {
                    job.await(ProgressListener.NONE, "stage");
                } catch (dev.shardlight.client.error.LauncherException e) {
                    assertTrue(e.isCancelled(), e.toString() + " " + e.getMessage());
                }
            });
            assertTrue(server.requests().size() < 50);
        }
    }
}
