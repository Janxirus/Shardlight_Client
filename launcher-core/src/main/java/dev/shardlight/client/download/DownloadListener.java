package dev.shardlight.client.download;

/** Called (from download threads, throttled) whenever an item changes. Keep it fast. */
@FunctionalInterface
public interface DownloadListener {
    void changed(DownloadItem item);
}
