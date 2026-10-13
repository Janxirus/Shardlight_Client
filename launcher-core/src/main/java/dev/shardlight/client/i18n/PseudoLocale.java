package dev.shardlight.client.i18n;

import java.util.Locale;

/**
 * Two made-up languages for testing the interface without a translator. {@code en-XA} turns English into longer,
 * accented text between brackets, so clipped text, hard-coded text and text that cannot grow all show at once:
 * "Play" becomes "[Þláý ~~]". {@code ar-XB} is the same text, which also mirrors the layout as a right-to-left language does.
 *
 * <p>Message arguments ({@code {0}}) and quoted text survive, so the result is still a valid pattern.
 */
public final class PseudoLocale {
    /** long and accented, left to right */
    public static final String ACCENTED = "en-XA";
    /** the same text laid out right to left */
    public static final String MIRRORED = "ar-XB";
    private static final String FROM = "AEIOUYCNSZaeiouycnszdgtlr";
    private static final String TO = "ÅÉÎÖÛÝÇÑŠŽåéîöûýçñšžđĝţĺŕ";

    private PseudoLocale() {
    }

    public static boolean is(Locale l) {
        return l != null && ("XA".equals(l.getCountry()) && "en".equals(l.getLanguage())
            || "XB".equals(l.getCountry()) && "ar".equals(l.getLanguage()));
    }

    /** the pseudo version of one message pattern, for the locale that asked */
    public static String transform(String pattern, Locale l) {
        StringBuilder out = new StringBuilder(pattern.length() * 2);
        int letters = 0;
        int depth = 0;
        boolean quoted = false;
        boolean choice = false;
        // inside {0,choice,1#one thing|1<{0} things} only the words after # or < are text
        boolean choiceText = false;
        int argStart = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                    out.append("''");
                    i++;
                } else {
                    quoted = !quoted;
                    out.append(c);
                }
                continue;
            }
            if (quoted) {
                out.append(c);
                continue;
            }
            if (c == '{') {
                depth++;
                if (depth == 1) {
                    argStart = i;
                    choice = false;
                    choiceText = false;
                }
                out.append(c);
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
                if (depth == 0) {
                    choice = false;
                    choiceText = false;
                }
                out.append(c);
            } else if (depth == 1 && choice && c == '|') {
                choiceText = false;
                out.append(c);
            } else if (depth == 1 && choice && !choiceText && (c == '#' || c == '<' || c == '\u2264')) {
                choiceText = true;
                out.append(c);
            } else if (depth == 1 && !choice && c == ',' && pattern.startsWith("choice,", i + 1)
                && pattern.indexOf(',', argStart) == i) {
                choice = true;
                out.append(c);
            } else if (depth == 0 || depth == 1 && choice && choiceText) {
                int at = FROM.indexOf(c);
                out.append(at >= 0 ? TO.charAt(at) : c);
                if (Character.isLetter(c)) {
                    letters++;
                }
            } else {
                out.append(c);
            }
        }
        if (letters == 0) {
            return pattern;
        }
        // about a third longer, as translated text often is; the closing bracket shows whether the end was cut off
        String padding = " " + "~".repeat(Math.max(2, (letters + 2) / 3));
        String text = "[" + out + padding + "]";
        return text;
    }
}
