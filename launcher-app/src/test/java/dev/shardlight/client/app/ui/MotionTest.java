package dev.shardlight.client.app.ui;

import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotionTest {
    @AfterEach
    void reset() {
        UiSettings.setReducedMotion(false);
    }

    @Test
    void threeSpeeds() {
        assertEquals(Duration.millis(120), Motion.Speed.FAST.duration());
        assertEquals(Duration.millis(200), Motion.Speed.MEDIUM.duration());
        assertEquals(Duration.millis(320), Motion.Speed.SLOW.duration());
    }

    @Test
    void reduceMotionMakesMovesInstantAndFadesShort() {
        UiSettings.setReducedMotion(true);
        for (Motion.Speed s : Motion.Speed.values()) {
            assertEquals(Duration.ZERO, Motion.moveDuration(s));
            assertEquals(Motion.REDUCED_FADE, Motion.fadeDuration(s));
        }
        UiSettings.setReducedMotion(false);
        assertEquals(Duration.millis(320), Motion.moveDuration(Motion.Speed.SLOW));
        assertEquals(Duration.millis(200), Motion.fadeDuration(Motion.Speed.MEDIUM));
    }
}
