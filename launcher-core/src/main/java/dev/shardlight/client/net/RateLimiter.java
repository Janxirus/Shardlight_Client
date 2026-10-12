package dev.shardlight.client.net;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;

/**
 * A token bucket: at most {@code perMinute} requests per minute, with short bursts allowed. Servers can
 * also tell us to pause ({@link #pauseFor}) through rate-limit headers.
 */
public final class RateLimiter {
    private final double ratePerNano;
    private final double capacity;
    private double tokens;
    private long last;
    private long pausedUntil;
    private final Clock clock;

    /** time source, replaceable in tests */
    public interface Clock {
        long nanoTime();

        void sleepNanos(long nanos) throws InterruptedException;

        Clock SYSTEM = new Clock() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleepNanos(long nanos) throws InterruptedException {
                Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
            }
        };
    }

    public RateLimiter(int perMinute, int burst) {
        this(perMinute, burst, Clock.SYSTEM);
    }

    public RateLimiter(int perMinute, int burst, Clock clock) {
        this.ratePerNano = perMinute / 60e9;
        this.capacity = Math.max(1, burst);
        this.tokens = capacity;
        this.clock = clock;
        this.last = clock.nanoTime();
    }

    /** wait for a free slot */
    public void acquire(CancellationToken cancel) throws LauncherException {
        while (true) {
            long wait;
            synchronized (this) {
                long now = clock.nanoTime();
                tokens = Math.min(capacity, tokens + (now - last) * ratePerNano);
                last = now;
                if (now < pausedUntil) {
                    wait = pausedUntil - now;
                } else if (tokens >= 1) {
                    tokens -= 1;
                    return;
                } else {
                    wait = (long) Math.ceil((1 - tokens) / ratePerNano);
                }
            }
            cancel.check();
            try {
                clock.sleepNanos(Math.min(wait, 1_000_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LauncherException.cancelled();
            }
        }
    }

    /** the server asked for a pause (for example X-Ratelimit-Remaining: 0) */
    public synchronized void pauseFor(long millis) {
        pausedUntil = Math.max(pausedUntil, clock.nanoTime() + millis * 1_000_000L);
        tokens = 0;
    }
}
