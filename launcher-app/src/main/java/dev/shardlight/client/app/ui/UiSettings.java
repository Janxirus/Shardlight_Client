package dev.shardlight.client.app.ui;

/** Appearance flags components read without a reference to the settings service. */
public final class UiSettings {
    private static volatile boolean reducedMotion;

    private UiSettings() {
    }

    public static boolean reducedMotion() {
        return reducedMotion;
    }

    public static void setReducedMotion(boolean v) {
        reducedMotion = v;
    }
}
