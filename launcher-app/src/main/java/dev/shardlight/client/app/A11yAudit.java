package dev.shardlight.client.app;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Control;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks a shown view for the things a keyboard or screen-reader user needs: every icon-only button has accessible
 * text and a tooltip, every button and link is at least 32 x 32 px (the size of a comfortable click or touch), and
 * nothing that reacts to the mouse is out of reach of the keyboard. {@code --capture} runs it on every view it saves
 * and writes the findings to {@code accessibility-report.txt} (see docs/ACCESSIBILITY.md).
 */
final class A11yAudit {
    /** the smallest comfortable click target, in pixels at the current interface size */
    static final double MIN_TARGET = 32;

    private A11yAudit() {
    }

    static List<String> scan(Node root) {
        List<String> found = new ArrayList<>();
        walk(root, found);
        return found;
    }

    private static void walk(Node n, List<String> found) {
        if (!n.isVisible() || !n.isManaged()) {
            return;
        }
        // table and list rows are keyboard-driven through their own control
        if (n instanceof ListCell<?> || n instanceof TableRow<?>) {
            return;
        }
        if (n instanceof ButtonBase b) {
            checkButton(b, found);
        } else if (n instanceof Hyperlink h) {
            checkSize(h, found);
        } else if (!(n instanceof Control) && n.getOnMouseClicked() != null && !n.isFocusTraversable() && !isDismissLayer(n)
            && !hasFocusableDescendant(n)) {
            found.add("reacts to the mouse but cannot be reached by keyboard: " + describe(n));
        }
        if (n instanceof Parent p) {
            for (Node c : List.copyOf(p.getChildrenUnmodifiable())) {
                walk(c, found);
            }
        }
    }

    private static void checkButton(ButtonBase b, List<String> found) {
        boolean iconOnly = b.getText() == null || b.getText().isBlank();
        if (iconOnly) {
            String name = b.getAccessibleText();
            if (name == null || name.isBlank()) {
                found.add("icon button without accessible text: " + describe(b));
            }
            Tooltip tip = b.getTooltip();
            if (tip == null && !b.getProperties().containsKey("javafx.scene.control.Tooltip")) {
                found.add("icon button without tooltip: " + describe(b));
            }
        }
        checkSize(b, found);
    }

    private static void checkSize(Node n, List<String> found) {
        double w = n.getLayoutBounds().getWidth();
        double h = n.getLayoutBounds().getHeight();
        if (w > 0 && h > 0 && (w < MIN_TARGET - 0.5 || h < MIN_TARGET - 0.5)) {
            found.add(String.format(java.util.Locale.ROOT, "click target %.0f x %.0f px is under %.0f: %s", w, h, MIN_TARGET, describe(n)));
        }
    }

    /** the dimmed layer behind a dialog or the palette closes on a click; the keyboard closes them with Escape */
    private static boolean isDismissLayer(Node n) {
        for (Node p = n; p != null; p = p.getParent()) {
            if (p.getStyleClass().contains("modal-overlay")) {
                return true;
            }
        }
        return false;
    }

    /** a click on padding around a real button is a convenience: the button inside is the keyboard's way in */
    private static boolean hasFocusableDescendant(Node n) {
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                if (c.isFocusTraversable() || hasFocusableDescendant(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Presses Tab from the current focus until it comes back round, and reports if focus gets stuck or a view has
     * nothing the keyboard can reach. Needs the view to be shown.
     */
    static List<String> tabWalk(javafx.scene.Scene scene) {
        List<String> found = new ArrayList<>();
        java.util.Set<Node> seen = new java.util.LinkedHashSet<>();
        Node previous = scene.getFocusOwner();
        Node start = previous;
        if (previous != null) {
            seen.add(previous);
        }
        for (int i = 0; i < 400; i++) {
            Node from = previous != null ? previous : scene.getRoot();
            javafx.event.Event.fireEvent(from, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                javafx.scene.input.KeyCode.TAB, false, false, false, false));
            Node now = scene.getFocusOwner();
            if (now == null || now == previous) {
                // one focusable control (a dialog with a single button) keeps focus on itself: that is not a trap
                if (now == null) {
                    found.add("nothing in this view can be reached with Tab");
                } else if (seen.stream().filter(n -> n != start).count() > 1) {
                    found.add("keyboard focus is stuck on " + describe(now) + " after " + i + " Tab presses");
                }
                break;
            }
            if (!seen.add(now)) {
                break; // came round: no trap
            }
            previous = now;
        }
        return found;
    }

    /** a short stable description: the type, its style classes, its label and the nearest styled ancestor */
    static String describe(Node n) {
        StringBuilder sb = new StringBuilder(n.getClass().getSimpleName());
        if (!n.getStyleClass().isEmpty()) {
            sb.append('.').append(String.join(".", n.getStyleClass()));
        }
        String label = n instanceof Labeled l && l.getText() != null && !l.getText().isBlank() ? l.getText() : n.getAccessibleText();
        if (label != null && !label.isBlank()) {
            sb.append(" \"").append(label.length() > 30 ? label.substring(0, 30) : label).append('"');
        }
        for (Node p = n.getParent(); p != null; p = p.getParent()) {
            if (!p.getStyleClass().isEmpty()) {
                sb.append(" in ").append(p.getClass().getSimpleName()).append('.').append(String.join(".", p.getStyleClass()));
                break;
            }
        }
        return sb.toString();
    }
}
