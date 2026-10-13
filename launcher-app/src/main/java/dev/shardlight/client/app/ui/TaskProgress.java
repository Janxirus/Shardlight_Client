package dev.shardlight.client.app.ui;

import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Long tasks report here so the Windows taskbar button shows their progress. Several can run at once (a
 * download, a game starting); the button shows their average, or a moving bar when none knows how far along
 * it is. Call from the FX thread.
 */
public final class TaskProgress {
    private static final Map<String, Double> ACTIVE = new LinkedHashMap<>();
    private static Stage stage;

    private TaskProgress() {
    }

    public static void attach(Stage s) {
        stage = s;
        push();
    }

    /** {@code fraction} is 0..1, or negative when unknown */
    public static void set(String source, double fraction) {
        ACTIVE.put(source, fraction);
        push();
    }

    public static void clear(String source) {
        if (ACTIVE.remove(source) != null) {
            push();
        }
    }

    private static void push() {
        if (stage == null) {
            return;
        }
        if (ACTIVE.isEmpty()) {
            WindowsChrome.taskbar(stage, WindowsChrome.Progress.NONE, 0);
            return;
        }
        double sum = 0;
        int known = 0;
        for (double f : ACTIVE.values()) {
            if (f >= 0) {
                sum += f;
                known++;
            }
        }
        if (known == 0) {
            WindowsChrome.taskbar(stage, WindowsChrome.Progress.INDETERMINATE, 0);
        } else {
            WindowsChrome.taskbar(stage, WindowsChrome.Progress.NORMAL, sum / known);
        }
    }
}
