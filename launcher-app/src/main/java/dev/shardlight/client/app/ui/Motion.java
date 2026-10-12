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

    /** move {@code node} vertically to {@code y} (the sidebar's active-item pill, hover lifts) */
    public static Animation moveY(Node node, Speed speed, double y) {
        if (reduced()) {
            node.setTranslateY(y);
            return null;
        }
        TranslateTransition t = new TranslateTransition(speed.duration(), node);
        t.setToY(y);
        t.setInterpolator(ENTER);
        return play(t);
    }

    /** how far cards rise under the pointer, in pixels */
    public static final double LIFT = 2;

    /** cards rise {@link #LIFT} pixels while hovered or keyboard-focused */
    public static void lift(Node node) {
        Runnable update = () -> moveY(node, Speed.FAST, node.isHover() || node.isFocusVisible() ? -LIFT : 0);
        node.hoverProperty().addListener((o, a, b) -> update.run());
        node.focusVisibleProperty().addListener((o, a, b) -> update.run());
    }

    /**
     * Fade {@code node} between full and {@code low} opacity forever (skeletons, loading dots), starting after
     * {@code delayMillis}. With Reduce motion the node stays at a steady middle opacity. Stops when the node
     * leaves the scene.
     */
    public static Animation pulse(Node node, double low, double delayMillis) {
        if (reduced()) {
            node.setOpacity((1 + low) / 2);
            return null;
        }
        FadeTransition f = new FadeTransition(Duration.millis(700), node);
        f.setFromValue(1);
        f.setToValue(low);
        f.setAutoReverse(true);
        f.setCycleCount(Animation.INDEFINITE);
        f.setInterpolator(Interpolator.EASE_BOTH);
        f.setDelay(Duration.millis(delayMillis));
        return loop(node, f);
    }

    /** slide {@code node} from {@code fromX} to {@code toX} over and over (the indeterminate shimmer) */
    public static Animation sweepX(Node node, double fromX, double toX) {
        if (reduced()) {
            node.setTranslateX((fromX + toX) / 2);
            return null;
        }
        TranslateTransition t = new TranslateTransition(Duration.millis(1400), node);
        t.setFromX(fromX);
        t.setToX(toX);
        t.setCycleCount(Animation.INDEFINITE);
        t.setInterpolator(Interpolator.EASE_BOTH);
        return loop(node, t);
    }

    /** endless animations stop once their node is no longer in a scene */
    private static Animation loop(Node node, Animation a) {
        node.sceneProperty().addListener((o, was, now) -> {
            if (now == null) {
                a.stop();
            } else if (a.getStatus() != Animation.Status.RUNNING) {
                a.play();
            }
        });
        if (node.getScene() != null) {
            a.play();
        }
        return a;
    }

    private static Animation play(Animation a) {
        a.play();
        return a;
    }
}
