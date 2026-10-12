package dev.shardlight.client.app.ui;

import dev.shardlight.client.project.MotdSpan;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.List;

/**
 * A server's message of the day in the colours it asked for, on the dark panel the game draws it on. Colours
 * the panel would swallow (dark blue, black) are lightened until they can be read.
 */
public final class MotdText {
    /** the panel behind the text, the same as {@code -sl-motd-bg} in the stylesheet */
    public static final Color BACKGROUND = Color.web("#121214");
    private static final Color DEFAULT = Color.web("#e8e8ee");

    private MotdText() {
    }

    /** {@code c}, or the nearest lighter colour that reaches 4.5:1 on the panel */
    public static Color readable(Color c) {
        Color out = c;
        for (int i = 0; i <= 20 && DesignTokens.contrast(out, BACKGROUND) < DesignTokens.AA_TEXT; i++) {
            out = c.interpolate(Color.WHITE, i / 20.0);
        }
        return out;
    }

    /** the spans as a text flow of at most two lines; {@code fallback} shows when there are none */
    public static TextFlow of(List<MotdSpan> spans, String fallback) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("motd");
        boolean any = false;
        for (MotdSpan s : spans) {
            if (s.text().isEmpty()) {
                continue;
            }
            any = true;
            Text t = new Text(s.text());
            t.getStyleClass().add("motd-text");
            t.setFill(s.colour() == null ? DEFAULT : readable(Color.web(s.colour())));
            Font base = t.getFont();
            t.setFont(Font.font(base.getFamily(), s.bold() ? FontWeight.BOLD : FontWeight.NORMAL,
                s.italic() ? FontPosture.ITALIC : FontPosture.REGULAR, base.getSize()));
            t.setUnderline(s.underlined());
            t.setStrikethrough(s.struck());
            flow.getChildren().add(t);
        }
        if (!any && fallback != null) {
            Text t = new Text(fallback);
            t.setFill(DEFAULT);
            flow.getChildren().add(t);
        }
        flow.setAccessibleText(spans.isEmpty() ? fallback : MotdSpan.plain(spans));
        return flow;
    }
}
