package dev.shardlight.client.task;

import dev.shardlight.client.error.LauncherException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Cooperative cancellation for long operations (installs, downloads, backups). */
public final class CancellationToken {
    /** a token that is never cancelled */
    public static final CancellationToken NONE = new CancellationToken();

    private volatile boolean cancelled;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public void cancel() {
        if (this == NONE || cancelled) {
            return;
        }
        cancelled = true;
        for (Runnable r : callbacks) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a callback failing must not stop the others
            }
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** throw {@link LauncherException#cancelled()} when cancelled; call between steps */
    public void check() throws LauncherException {
        if (cancelled) {
            throw LauncherException.cancelled();
        }
    }

    /** run {@code r} when cancelled (immediately if already cancelled) */
    public void onCancel(Runnable r) {
        if (this == NONE) {
            return;
        }
        callbacks.add(r);
        if (cancelled) {
            r.run();
        }
    }
}
