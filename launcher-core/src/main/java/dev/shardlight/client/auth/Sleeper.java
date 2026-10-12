package dev.shardlight.client.auth;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;

import java.time.Duration;

/** Waits between device-code polls. Injectable so tests don't actually wait. */
@FunctionalInterface
public interface Sleeper {
    /** the real clock: sleeps in short slices and stops early when {@code cancel} fires */
    Sleeper SYSTEM = (duration, cancel) -> {
        long end = System.nanoTime() + duration.toNanos();
        while (true) {
            cancel.check();
            long left = end - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.max(1, Math.min(100, left / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LauncherException.cancelled();
            }
        }
    };

    /** wait {@code duration}; throws {@link LauncherException#cancelled()} when cancelled */
    void sleep(Duration duration, CancellationToken cancel) throws LauncherException;
}
