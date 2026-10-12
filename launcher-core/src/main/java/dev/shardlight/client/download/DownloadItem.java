package dev.shardlight.client.download;

import dev.shardlight.client.error.LauncherException;

import java.net.URI;
import java.nio.file.Path;

/**
 * Live state of one download, for the Downloads page. Fields are updated by the download thread; read
 * them through the getters (volatile) and re-read on every {@link DownloadListener} call.
 */
public final class DownloadItem {
    private final String id;
    private final String group;
    private final DownloadRequest request;
    private volatile DownloadState state = DownloadState.QUEUED;
    private volatile long bytesDone;
    private volatile long bytesTotal;
    private volatile long bytesPerSecond;
    private volatile int attempts;
    private volatile LauncherException error;
    private volatile URI currentUrl;
    private volatile long finishedAt;

    DownloadItem(String id, String group, DownloadRequest request) {
        this.id = id;
        this.group = group;
        this.request = request;
        this.bytesTotal = request.size();
        this.currentUrl = request.url();
    }

    public String id() {
        return id;
    }

    /** the operation this download belongs to (for example "Install 26.3") */
    public String group() {
        return group;
    }

    public DownloadRequest request() {
        return request;
    }

    public String label() {
        return request.label();
    }

    public Path target() {
        return request.target();
    }

    public DownloadState state() {
        return state;
    }

    public long bytesDone() {
        return bytesDone;
    }

    /** -1 when unknown */
    public long bytesTotal() {
        return bytesTotal;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    public int attempts() {
        return attempts;
    }

    /** the failure, for FAILED items */
    public LauncherException error() {
        return error;
    }

    public URI currentUrl() {
        return currentUrl;
    }

    public long finishedAt() {
        return finishedAt;
    }

    /** 0..1, or -1 when the size is unknown */
    public double fraction() {
        long t = bytesTotal;
        return t <= 0 ? -1 : Math.min(1, bytesDone / (double) t);
    }

    void state(DownloadState s) {
        state = s;
        if (s.isFinished()) {
            finishedAt = System.currentTimeMillis();
            bytesPerSecond = 0;
        }
    }

    void bytes(long done, long total) {
        bytesDone = done;
        if (total > 0) {
            bytesTotal = total;
        }
    }

    void speed(long bps) {
        bytesPerSecond = bps;
    }

    void attempt() {
        attempts++;
    }

    void error(LauncherException e) {
        error = e;
    }

    void currentUrl(URI u) {
        currentUrl = u;
    }

    @Override
    public String toString() {
        return label() + " [" + state + " " + bytesDone + "/" + bytesTotal + "]";
    }
}
