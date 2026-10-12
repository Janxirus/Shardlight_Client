package dev.shardlight.client.app.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Every animation in the launcher goes through here, so timing and easing stay consistent and Reduce motion
 * is honoured in one place. Only opacity, translation and scale are animated.
 *
 * <ul>
 *   <li>{@link Speed#FAST} 120 ms: hover and press</li>
 *   <li>{@link Speed#MEDIUM} 200 ms: panels, popovers and toasts</li>
 *   <li>{@link Speed#SLOW} 320 ms: pages and modals</li>
 * </ul>
 * Things that appear ease out (fast start, gentle stop); things that leave ease in. With Reduce motion on,
 * movement and scaling become instant and fades shrink to {@link #REDUCED_FADE}.
 */
public final class Motion {
    public enum Speed {
        FAST(120), MEDIUM(200), SLOW(320);

        private final double millis;

        Speed(double millis) {
            this.millis = millis;
        }

        public Duration duration() {
            return Duration.millis(millis);
        }
    }

    /** ease-out: for things entering */
    public static final Interpolator ENTER = Interpolator.SPLINE(0.0, 0.0, 0.2, 1.0);
    /** ease-in: for things leaving */
    public static final Interpolator EXIT = Interpolator.SPLINE(0.4, 0.0, 1.0, 1.0);
    /** the fade that replaces every animation when Reduce motion is on */
    public static final Duration REDUCED_FADE = Duration.millis(80);

    /** how far pages, panels and toasts travel as they enter, in pixels */
    public static final double ENTER_OFFSET = 8;

    private Motion() {
    }

    public static boolean reduced() {
        return UiSettings.reducedMotion();
    }

    /** the time a fade at {@code speed} takes now (shorter with Reduce motion) */
    public static Duration fadeDuration(Speed speed) {
        return reduced() ? REDUCED_FADE : speed.duration();
    }

    /** the time a move or scale at {@code speed} takes now (zero, i.e. instant, with Reduce motion) */
    public static Duration moveDuration(Speed speed) {
        return reduced() ? Duration.ZERO : speed.duration();
    }

    /** fade {@code node} in, optionally rising {@code dy} pixels into place */
    public static Animation enter(Node node, Speed speed, double dy) {
        FadeTransition f = new FadeTransition(fadeDuration(speed), node);
        f.setFromValue(0);
        f.setToValue(1);
        f.setInterpolator(ENTER);
        if (reduced() || dy == 0) {
            node.setTranslateY(0);
            return play(f);
        }
        node.setTranslateY(dy);
        TranslateTransition t = new TranslateTransition(speed.duration(), node);
        t.setToY(0);
        t.setInterpolator(ENTER);
        return play(new ParallelTransition(f, t));
    }

    /** fade {@code node} in */
    public static Animation fadeIn(Node node, Speed speed) {
        return enter(node, speed, 0);
    }

    /** fade {@code node} out, then run {@code done} (also when Reduce motion is on) */
    public static Animation exit(Node node, Speed speed, Runnable done) {
        FadeTransition f = new FadeTransition(fadeDuration(speed), node);
        f.setToValue(0);
        f.setInterpolator(EXIT);
        if (done != null) {
            f.setOnFinished(e -> done.run());
        }
        return play(f);
    }

    /** a modal or popover growing from {@code from} (e.g. 0.96) to full size while fading in */
    public static Animation pop(Node node, Speed speed, double from) {
        FadeTransition f = new FadeTransition(fadeDuration(speed), node);
        f.setFromValue(0);
        f.setToValue(1);
        f.setInterpolator(ENTER);
        if (reduced()) {
            node.setScaleX(1);
            node.setScaleY(1);
            return play(f);
        }
        node.setScaleX(from);
        node.setScaleY(from);
        ScaleTransition s = new ScaleTransition(speed.duration(), node);
        s.setToX(1);
        s.setToY(1);
        s.setInterpolator(ENTER);
        return play(new ParallelTransition(f, s));
    }

    /** move {@code node} horizontally to {@code x} (switch knobs, sliding indicators) */
    public static Animation moveX(Node node, Speed speed, double x) {
        if (reduced()) {
            node.setTranslateX(x);
            return null;
        }
        TranslateTransition t = new TranslateTransition(speed.duration(), node);
        t.setToX(x);
        t.setInterpolator(ENTER);
        return play(t);
    }

    private static Animation play(Animation a) {
        a.play();
        return a;
    }
}
