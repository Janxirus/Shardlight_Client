package dev.shardlight.client.app.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JavaFX CSS drops everything from a rule it cannot parse, so catch the usual mistakes before a screenshot does. */
class StylesheetTest {
    private static String css() throws Exception {
        try (InputStream in = StylesheetTest.class.getResourceAsStream("/app/shardlight.css")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    @Test
    void usesNoSelectorsJavaFxCannotParse() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String line : css().split("\n")) {
            String t = line.strip();
            if (!t.endsWith("{")) {
                continue;
            }
            String selector = t.substring(0, t.length() - 1);
            // JavaFX has no sibling combinators and no :not(), :is(), :where(), :nth-child() or :last-child
            if (selector.matches(".*\\s[+~]\\s.*") || selector.matches(".*:(not|is|where|nth-child|last-child).*")) {
                bad.add(t);
            }
        }
        assertTrue(bad.isEmpty(), "Selectors JavaFX CSS cannot parse:\n" + String.join("\n", bad));
    }

    @Test
    void bracesBalance() throws Exception {
        String css = css();
        assertEquals(css.chars().filter(c -> c == '{').count(), css.chars().filter(c -> c == '}').count(), "unbalanced braces");
    }
}
