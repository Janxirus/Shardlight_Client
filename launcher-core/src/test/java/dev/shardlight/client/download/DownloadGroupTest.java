package dev.shardlight.client.download;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadGroupTest {
    private static DownloadItem item(String id, String group, DownloadState state, long done, long total, long speed) {
        DownloadItem i = new DownloadItem(id, group, DownloadRequest.of(URI.create("https://example.test/" + id), Path.of(id)));
        i.bytes(done, total);
        i.speed(speed);
        i.state(state);
        i.speed(speed);
        return i;
    }

    @Test
    void groupsByOperationAndCountsFiles() {
        List<DownloadGroup> groups = DownloadGroup.group(List.of(
            item("a", "Game", DownloadState.RUNNING, 500, 1000, 100),
            item("b", "Game", DownloadState.DONE, 200, 200, 0),
            item("c", "Mod", DownloadState.QUEUED, 0, 400, 0),
            item("d", "Old", DownloadState.FAILED, 0, 50, 0)));
        assertEquals(3, groups.size());
        DownloadGroup game = groups.stream().filter(g -> g.name().equals("Game")).findFirst().orElseThrow();
        assertEquals(700, game.done());
        assertEquals(1200, game.total());
        assertEquals(100, game.speed());
        assertEquals(1, game.finished());
        assertTrue(game.active());
        assertEquals(5, game.etaMillis() / 1000, "500 bytes left at 100 bytes a second");
        assertFalse(groups.get(groups.size() - 1).active(), "finished groups come last");
        assertEquals(1, groups.get(groups.size() - 1).failed());
    }

    @Test
    void theBadgeAndTheTopBarSeeTheSameTotals() {
        List<DownloadGroup> groups = DownloadGroup.group(List.of(
            item("a", "Game", DownloadState.RUNNING, 250, 1000, 50),
            item("c", "Mod", DownloadState.QUEUED, 0, 1000, 0),
            item("d", "Done", DownloadState.DONE, 10, 10, 0)));
        DownloadGroup.Totals t = DownloadGroup.totals(groups);
        assertEquals(2, t.active(), "downloads, not files");
        assertEquals(250, t.done());
        assertEquals(2000, t.total());
        assertEquals(0.125, t.fraction(), 1e-9);
        assertEquals(-1, DownloadGroup.totals(List.of()).fraction());
    }

    @Test
    void aGroupIsPausedWhenEveryUnfinishedFileIs() {
        DownloadGroup g = DownloadGroup.group(List.of(
            item("a", "Game", DownloadState.PAUSED, 10, 100, 0),
            item("b", "Game", DownloadState.PAUSED, 0, 100, 0),
            item("c", "Game", DownloadState.DONE, 5, 5, 0))).get(0);
        assertTrue(g.isPaused());
        assertEquals(-1, g.etaMillis());
        DownloadGroup running = DownloadGroup.group(List.of(
            item("a", "Game", DownloadState.PAUSED, 10, 100, 0),
            item("b", "Game", DownloadState.RUNNING, 0, 100, 5))).get(0);
        assertFalse(running.isPaused());
    }
}
