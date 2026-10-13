package dev.shardlight.client.skin;

import java.util.Locale;

/** The arm shape a skin is drawn for: classic arms are 4 pixels wide, slim arms 3. */
public enum SkinVariant {
    CLASSIC("classic", 4),
    SLIM("slim", 3);

    private final String api;
    private final int armWidth;

    SkinVariant(String api, int armWidth) {
        this.api = api;
        this.armWidth = armWidth;
    }

    /** the name used in the settings file and by Minecraft services */
    public String api() {
        return api;
    }

    public int armWidth() {
        return armWidth;
    }

    /** accepts "classic", "slim" and the upper-case names Minecraft services uses; anything else is classic */
    public static SkinVariant parse(String s) {
        return s != null && s.trim().toLowerCase(Locale.ROOT).equals("slim") ? SLIM : CLASSIC;
    }
}
