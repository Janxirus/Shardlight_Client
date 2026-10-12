package dev.shardlight.client.download;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A batch of downloads submitted together; completes when every item has finished. */
public final class DownloadJob {
    private final String group;
    private final List<DownloadItem> items;
    private final CompletableFuture<Void> done;
    private final CancellationToken cancel;

    DownloadJob(String group, List<DownloadItem> items, CompletableFuture<Void> done, CancellationToken cancel) {
        this.group = group;
        this.items = List.copyOf(items);
        this.done = done;
        this.cancel = cancel;
    }

    public String group() {
        return group;
    }

    public List<DownloadItem> items() {
        return items;
    }

    public void cancel() {
        cancel.cancel();
    }

    public boolean isDone() {
        return done.isDone();
    }

    public long bytesDone() {
        return items.stream().mapToLong(DownloadItem::bytesDone).sum();
    }

    /** sum of the known sizes (items with unknown size count as 0) */
    public long bytesTotal() {
        return items.stream().mapToLong(i -> Math.max(0, i.bytesTotal())).sum();
    }

    public long finishedCount() {
        return items.stream().filter(i -> i.state().isFinished()).count();
    }

    /**
     * Wait for every item, reporting progress (files done / total) about four times a second. Throws the
     * first failure, or {@link LauncherException#cancelled()} when cancelled.
     */
    public void await(ProgressListener progress, String stageKey) throws LauncherException {
        while (true) {
            try {
                done.get(250, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException e) {
                if (progress != null) {
                    progress.progress(stageKey, group, finishedCount(), items.size());
                }
            } catch (InterruptedException e) {
                cancel.cancel();
                Thread.currentThread().interrupt();
                throw LauncherException.cancelled();
            } catch (ExecutionException e) {
                break;
            }
        }
        if (progress != null) {
            progress.progress(stageKey, group, finishedCount(), items.size());
        }
        LauncherException first = null;
        boolean cancelled = false;
        for (DownloadItem i : items) {
            if (i.state() == DownloadState.FAILED && first == null) {
                first = i.error();
            }
            if (i.state() == DownloadState.CANCELLED) {
                cancelled = true;
            }
        }
        if (first != null) {
            throw first;
        }
        if (cancelled || cancel.isCancelled()) {
            throw LauncherException.cancelled();
        }
    }
}
