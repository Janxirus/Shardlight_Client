package dev.shardlight.client.nbt;

import java.util.regex.Pattern;

/** SNBT-like text for {@code toString()}; for logs and tests, not a full SNBT implementation. */
final class Snbt {
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9._+-]+");

    private Snbt() {
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    static String key(String k) {
        return PLAIN_KEY.matcher(k).matches() ? k : quote(k);
    }
}
