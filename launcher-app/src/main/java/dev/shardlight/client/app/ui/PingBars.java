package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/** Five signal bars for a server's ping, as the game draws them: more bars, faster answer. */
public final class PingBars extends HBox {
    private static final int BARS = 5;

    /** @param latencyMs the round trip in milliseconds; negative = unknown, shown as empty bars */
    public PingBars(long latencyMs, boolean reachable) {
        getStyleClass().add("ping-bars");
        setAlignment(Pos.BOTTOM_LEFT);
        int lit = reachable ? litBars(latencyMs) : 0;
        String tone = lit >= 4 ? "good" : lit == 3 ? "fair" : "poor";
        for (int i = 1; i <= BARS; i++) {
            Region bar = new Region();
            bar.getStyleClass().addAll("ping-bar", "bar-" + i);
            if (i <= lit) {
                bar.getStyleClass().addAll("on", tone);
            }
            getChildren().add(bar);
        }
        setAccessibleText(!reachable ? I18n.tr("ui.multiplayer.pingNone")
            : latencyMs < 0 ? I18n.tr("ui.multiplayer.pingUnknown") : I18n.tr("ui.multiplayer.latency", latencyMs));
    }

    /** the game's thresholds: under 150 ms five bars, then 300, 600 and 1000; unknown shows one */
    static int litBars(long latencyMs) {
        if (latencyMs < 0) {
            return 1;
        }
        if (latencyMs < 150) {
            return 5;
        }
        if (latencyMs < 300) {
            return 4;
        }
        if (latencyMs < 600) {
            return 3;
        }
        return latencyMs < 1000 ? 2 : 1;
    }
}
