package dev.shardlight.client.app;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Performance check, off unless {@code -Dshardlight.measure=FILE} is given: measures the time from the start of the
 * Java process until Home has been drawn and can be used, waits while the app idles, then writes the numbers as
 * JSON and exits. {@code tools/perf/measure.sh} runs it several times and reports the medians.
 */
final class StartupMeasure {
    private static final long IDLE_MILLIS = 8000;

    private StartupMeasure() {
    }

    static void afterShow(Stage stage) {
        String file = System.getProperty("shardlight.measure");
        if (file == null || file.isBlank()) {
            return;
        }
        // "interactive" = the first frame after the window is shown has been drawn
        new AnimationTimer() {
            private int frames;

            @Override
            public void handle(long now) {
                if (++frames < 2) {
                    return;
                }
                stop();
                long startupMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
                PauseTransition idle = new PauseTransition(Duration.millis(IDLE_MILLIS));
                idle.setOnFinished(e -> {
                    System.gc();
                    Runtime rt = Runtime.getRuntime();
                    long heapMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
                    String json = String.format(Locale.ROOT, "{\"startupMs\":%d,\"heapMb\":%d,\"rssMb\":%d}%n", startupMs, heapMb, rssMb());
                    try {
                        Files.writeString(Path.of(file), json);
                    } catch (IOException ex) {
                        System.err.println("Can't write " + file + ": " + ex.getMessage());
                    }
                    Platform.exit();
                });
                idle.play();
            }
        }.start();
    }

    /** resident memory of this process in MB (Linux only; -1 elsewhere) */
    private static long rssMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) >> 10;
                }
            }
        } catch (IOException | RuntimeException e) {
            // not Linux
        }
        return -1;
    }
}
