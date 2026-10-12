package dev.shardlight.client.download;

/** Life cycle of one download. */
public enum DownloadState {
    QUEUED,
    RUNNING,
    VERIFYING,
    /** downloaded and verified */
    DONE,
    /** already present and valid (or copied from the shared cache), nothing downloaded */
    SKIPPED,
    FAILED,
    CANCELLED;

    public boolean isFinished() {
        return this == DONE || this == SKIPPED || this == FAILED || this == CANCELLED;
    }

    public boolean isSuccess() {
        return this == DONE || this == SKIPPED;
    }
}
