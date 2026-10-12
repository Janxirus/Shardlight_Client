package dev.shardlight.client.download;

import dev.shardlight.client.error.LauncherException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The downloads of one operation (installing a game version, a mod and its dependencies) as one line on the
 * Downloads page. The page, the sidebar badge and the top bar indicator all read the same groups, so the
 * numbers they show cannot disagree.
 *
 * @param name      the operation's name, "" for downloads made without one
 * @param items     its files
 * @param done      bytes downloaded
 * @param total     bytes expected (what is known so far)
 * @param speed     bytes per second across the files that are running
 * @param finished  files that are done, skipped, failed or cancelled
 * @param failed    files that failed
 * @param cancelled files that were cancelled
 * @param paused    unfinished files that are held back
 */
public record DownloadGroup(String name, List<DownloadItem> items, long done, long total, long speed, int finished, int failed,
                            int cancelled, int paused) {
    /** across all active groups */
    public record Totals(int active, long done, long total, long speed) {
        /** 0..1, or -1 when no size is known */
        public double fraction() {
            return total > 0 ? Math.min(1, done / (double) total) : -1;
        }
    }

    /** true while at least one file is not finished */
    public boolean active() {
        return finished < items.size();
    }

    /** every unfinished file is held back */
    public boolean isPaused() {
        return active() && paused >= items.size() - finished;
    }

    /** 0..1 by bytes, else by files */
    public double fraction() {
        if (total > 0) {
            return Math.min(1, done / (double) total);
        }
        return items.isEmpty() ? 1 : (double) finished / items.size();
    }

    /** milliseconds left at the current speed, or -1 when it can't be told */
    public long etaMillis() {
        if (speed <= 0 || total <= done) {
            return -1;
        }
        return (total - done) * 1000 / speed;
    }

    /** the first failure, or null */
    public LauncherException firstError() {
        return items.stream().filter(i -> i.state() == DownloadState.FAILED && i.error() != null)
            .map(DownloadItem::error).findFirst().orElse(null);
    }

    /** the label of a file that is downloading now, or null */
    public String runningLabel() {
        return items.stream().filter(i -> i.state() == DownloadState.RUNNING || i.state() == DownloadState.VERIFYING)
            .map(i -> i.label() != null && !i.label().isBlank() ? i.label()
                : i.target() == null ? null : i.target().getFileName().toString())
            .filter(l -> l != null).findFirst().orElse(null);
    }

    public long lastFinishedAt() {
        return items.stream().mapToLong(DownloadItem::finishedAt).max().orElse(0);
    }

    /** the items grouped by operation: active groups first, then the most recently finished */
    public static List<DownloadGroup> group(List<DownloadItem> items) {
        Map<String, List<DownloadItem>> byGroup = new LinkedHashMap<>();
        for (DownloadItem i : items) {
            byGroup.computeIfAbsent(i.group() == null ? "" : i.group(), k -> new ArrayList<>()).add(i);
        }
        List<DownloadGroup> out = new ArrayList<>();
        byGroup.forEach((name, list) -> {
            long done = 0;
            long total = 0;
            long speed = 0;
            int finished = 0;
            int failed = 0;
            int cancelled = 0;
            int paused = 0;
            for (DownloadItem i : list) {
                done += Math.max(0, i.bytesDone());
                total += Math.max(0, i.bytesTotal());
                if (i.state() == DownloadState.RUNNING) {
                    speed += i.bytesPerSecond();
                }
                if (i.state().isFinished()) {
                    finished++;
                }
                if (i.state() == DownloadState.FAILED) {
                    failed++;
                }
                if (i.state() == DownloadState.CANCELLED) {
                    cancelled++;
                }
                if (i.state() == DownloadState.PAUSED) {
                    paused++;
                }
            }
            out.add(new DownloadGroup(name, list, done, total, speed, finished, failed, cancelled, paused));
        });
        out.sort(Comparator.comparing((DownloadGroup g) -> !g.active())
            .thenComparing(g -> -g.lastFinishedAt()));
        return out;
    }

    /** the figures the badge and the top bar show: how many downloads are active and how far along they are */
    public static Totals totals(List<DownloadGroup> groups) {
        int active = 0;
        long done = 0;
        long total = 0;
        long speed = 0;
        for (DownloadGroup g : groups) {
            if (g.active()) {
                active++;
                done += g.done();
                total += g.total();
                speed += g.speed();
            }
        }
        return new Totals(active, done, total, speed);
    }
}
