package dev.shardlight.client.app.ui;

import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** runs on the FX thread, or skips the test where no toolkit can start (no display) */
    private static void onFx(Runnable r) throws Exception {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // fine
        } catch (UnsupportedOperationException | UnsatisfiedLinkError | NoClassDefFoundError e) {
            Assumptions.assumeTrue(false, "no JavaFX toolkit here");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void nothingAnimatesWhileMinimisedAndItResumesAfter() throws Exception {
        onFx(() -> {
            Region node = new Region();
            Scene scene = new Scene(new Group(node), 50, 50);
            Animation a = Motion.pulse(node, 0.4, 0);
            assertEquals(Animation.Status.RUNNING, a.getStatus());
            Motion.setSuspended(true);
            assertTrue(Motion.suspended());
            assertEquals(Animation.Status.PAUSED, a.getStatus());
            // an animation started while minimised does not run
            Region other = new Region();
            Group g = new Group(other);
            new Scene(g, 50, 50);
            Animation b = Motion.pulse(other, 0.4, 0);
            assertEquals(Animation.Status.STOPPED, b.getStatus());
            Motion.setSuspended(false);
            assertFalse(Motion.suspended());
            assertEquals(Animation.Status.RUNNING, a.getStatus());
            assertEquals(scene, node.getScene());
            a.stop();
            assertEquals(g, other.getParent());
        });
    }
}
