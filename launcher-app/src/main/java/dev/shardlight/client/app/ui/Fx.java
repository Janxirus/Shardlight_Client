package dev.shardlight.client.app.ui;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Threading helpers: blocking service calls run on background threads, results come back on the FX thread. */
public final class Fx {
    private static final Logger LOG = Logger.getLogger(Fx.class.getName());
    private static final ExecutorService BG = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ui-bg-", 0).factory());

    private Fx() {
    }

    /** run on the FX thread (now when already there) */
    public static void ui(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** run {@code work} in the background; {@code ok} or {@code fail} then run on the FX thread */
    public static <T> CompletableFuture<T> run(Callable<T> work, Consumer<T> ok, Consumer<Throwable> fail) {
        CompletableFuture<T> f = new CompletableFuture<>();
        BG.execute(() -> {
            try {
                T v = work.call();
                f.complete(v);
                if (ok != null) {
                    Platform.runLater(() -> ok.accept(v));
                }
            } catch (Throwable t) {
                f.completeExceptionally(t);
                LOG.log(t instanceof Exception ? Level.FINE : Level.SEVERE, "Background task failed", t);
                if (fail != null) {
                    Platform.runLater(() -> fail.accept(t));
                }
            }
        });
        return f;
    }

    /** fire-and-forget background work with an error callback */
    public static void bg(ThrowingRunnable work, Consumer<Throwable> fail) {
        run(() -> {
            work.run();
            return null;
        }, null, fail);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
