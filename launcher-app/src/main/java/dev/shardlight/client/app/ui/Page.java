package dev.shardlight.client.app.ui;

import javafx.scene.Node;
import javafx.scene.layout.Region;

/** One screen of the app. Views are built once and refreshed in {@link #onShow()}. */
public interface Page {
    /** the page's title (localized) */
    String title();

    /** the page content; scrolls inside the shell unless {@link #scrolls()} is false */
    Region view();

    /** buttons shown on the right of the page title, or null */
    default Node actions() {
        return null;
    }

    /** called every time the page becomes visible */
    default void onShow() {
    }

    /** false when the page manages its own scrolling (lists, tables) */
    default boolean scrolls() {
        return true;
    }
}
