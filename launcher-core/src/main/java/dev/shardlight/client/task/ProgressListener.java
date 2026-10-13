package dev.shardlight.client.task;

/**
 * Progress for a long operation. {@code stageKey} is an i18n key naming the current step (for example
 * {@code install.stage.libraries}); {@code done}/{@code total} are units of that step (files or bytes),
 * {@code total <= 0} meaning unknown. Implementations must be thread-safe and fast; the UI hops to its own
 * thread itself.
 */
@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = (stageKey, detail, done, total) -> { };

    void progress(String stageKey, String detail, long done, long total);

    default void stage(String stageKey) {
        progress(stageKey, "", 0, -1);
    }

    /** overall fraction 0..1, or -1 when unknown */
    static double fraction(long done, long total) {
        return total <= 0 ? -1 : Math.max(0, Math.min(1, done / (double) total));
    }
}
