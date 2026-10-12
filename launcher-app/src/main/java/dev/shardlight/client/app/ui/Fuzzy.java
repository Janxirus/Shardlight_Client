package dev.shardlight.client.app.ui;

import java.util.Locale;

/**
 * Fuzzy matching for the command palette: every character of the query must appear in the text in order.
 * Matches score higher when they start the text or a word, run together, and cover more of the text.
 */
public final class Fuzzy {
    private Fuzzy() {
    }

    /** the score of {@code text} for {@code query}, or -1 when it doesn't match (an empty query matches with 0) */
    public static int score(String query, String text) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        if (text == null || text.isEmpty()) {
            return -1;
        }
        String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
        String t = text.toLowerCase(Locale.ROOT);
        int score = 0;
        int ti = 0;
        int run = 0;
        int first = -1;
        for (int qi = 0; qi < q.length(); qi++) {
            char c = q.charAt(qi);
            int found = t.indexOf(c, ti);
            if (found < 0) {
                return -1;
            }
            if (first < 0) {
                first = found;
            }
            boolean wordStart = found == 0 || !Character.isLetterOrDigit(t.charAt(found - 1));
            run = found == ti && qi > 0 ? run + 1 : 0;
            score += 1 + (wordStart ? 8 : 0) + run * 4;
            ti = found + 1;
        }
        if (first == 0) {
            score += 12;
        }
        if (t.replace(" ", "").contains(q)) {
            score += 20;
        }
        score -= Math.min(20, (t.length() - q.length()) / 4);
        return Math.max(1, score);
    }
}
