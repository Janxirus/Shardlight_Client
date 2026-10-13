package dev.shardlight.client.project;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A run of a server's message of the day in one style, as Minecraft draws it.
 *
 * @param text       the characters (may contain a newline)
 * @param colour     {@code #RRGGBB}, or null for the default text colour
 * @param bold       bold
 * @param italic     italic
 * @param underlined underlined
 * @param struck     struck through
 */
public record MotdSpan(String text, String colour, boolean bold, boolean italic, boolean underlined, boolean struck) {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_CHARS = 2048;
    private static final Map<Character, String> LEGACY = Map.ofEntries(
        Map.entry('0', "#000000"), Map.entry('1', "#0000AA"), Map.entry('2', "#00AA00"), Map.entry('3', "#00AAAA"),
        Map.entry('4', "#AA0000"), Map.entry('5', "#AA00AA"), Map.entry('6', "#FFAA00"), Map.entry('7', "#AAAAAA"),
        Map.entry('8', "#555555"), Map.entry('9', "#5555FF"), Map.entry('a', "#55FF55"), Map.entry('b', "#55FFFF"),
        Map.entry('c', "#FF5555"), Map.entry('d', "#FF55FF"), Map.entry('e', "#FFFF55"), Map.entry('f', "#FFFFFF"));
    private static final Map<String, String> NAMED = Map.ofEntries(
        Map.entry("black", "#000000"), Map.entry("dark_blue", "#0000AA"), Map.entry("dark_green", "#00AA00"),
        Map.entry("dark_aqua", "#00AAAA"), Map.entry("dark_red", "#AA0000"), Map.entry("dark_purple", "#AA00AA"),
        Map.entry("gold", "#FFAA00"), Map.entry("gray", "#AAAAAA"), Map.entry("dark_gray", "#555555"),
        Map.entry("blue", "#5555FF"), Map.entry("green", "#55FF55"), Map.entry("aqua", "#55FFFF"),
        Map.entry("red", "#FF5555"), Map.entry("light_purple", "#FF55FF"), Map.entry("yellow", "#FFFF55"),
        Map.entry("white", "#FFFFFF"));

    private record Style(String colour, boolean bold, boolean italic, boolean underlined, boolean struck) {
        static final Style NONE = new Style(null, false, false, false, false);
    }

    /** the spans of a chat component (a string, an object with text, extra and style, or an array) */
    public static List<MotdSpan> fromComponent(JsonElement e) {
        List<MotdSpan> out = new ArrayList<>();
        walk(e, Style.NONE, out, 0, new int[] {0});
        return merge(out);
    }

    /** the spans of text that uses the old {@code §} codes */
    public static List<MotdSpan> fromLegacy(String text) {
        List<MotdSpan> out = new ArrayList<>();
        legacy(text == null ? "" : text, Style.NONE, out, new int[] {0});
        return merge(out);
    }

    /** the characters of all spans, formatting removed */
    public static String plain(List<MotdSpan> spans) {
        StringBuilder b = new StringBuilder();
        spans.forEach(s -> b.append(s.text()));
        return b.toString();
    }

    private static void walk(JsonElement e, Style inherited, List<MotdSpan> out, int depth, int[] budget) {
        if (e == null || e.isJsonNull() || depth > 64 || budget[0] >= MAX_CHARS) {
            return;
        }
        if (e.isJsonPrimitive()) {
            legacy(e.getAsString(), inherited, out, budget);
            return;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                walk(x, inherited, out, depth + 1, budget);
            }
            return;
        }
        if (!e.isJsonObject()) {
            return;
        }
        JsonObject o = e.getAsJsonObject();
        Style style = new Style(colourOf(o, inherited.colour()), flag(o, "bold", inherited.bold()),
            flag(o, "italic", inherited.italic()), flag(o, "underlined", inherited.underlined()),
            flag(o, "strikethrough", inherited.struck()));
        for (String key : new String[] {"text", "fallback", "translate"}) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                legacy(o.get(key).getAsString(), style, out, budget);
                break;
            }
        }
        if (o.has("extra")) {
            walk(o.get("extra"), style, out, depth + 1, budget);
        }
    }

    private static String colourOf(JsonObject o, String inherited) {
        if (!o.has("color") || !o.get("color").isJsonPrimitive()) {
            return inherited;
        }
        String c = o.get("color").getAsString().trim();
        if (HEX.matcher(c).matches()) {
            return c.toUpperCase(Locale.ROOT);
        }
        return NAMED.getOrDefault(c.toLowerCase(Locale.ROOT), inherited);
    }

    private static boolean flag(JsonObject o, String key, boolean inherited) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isBoolean()
            ? o.get(key).getAsBoolean() : inherited;
    }

    /** split {@code text} at its § codes; a colour code resets the other styles, as in the game */
    private static void legacy(String text, Style start, List<MotdSpan> out, int[] budget) {
        Style s = start;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length() && budget[0] < MAX_CHARS; i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                flush(run, s, out);
                char code = Character.toLowerCase(text.charAt(++i));
                String colour = LEGACY.get(code);
                if (colour != null) {
                    s = new Style(colour, false, false, false, false);
                } else {
                    s = switch (code) {
                        case 'l' -> new Style(s.colour(), true, s.italic(), s.underlined(), s.struck());
                        case 'o' -> new Style(s.colour(), s.bold(), true, s.underlined(), s.struck());
                        case 'n' -> new Style(s.colour(), s.bold(), s.italic(), true, s.struck());
                        case 'm' -> new Style(s.colour(), s.bold(), s.italic(), s.underlined(), true);
                        case 'r' -> Style.NONE;
                        default -> s;
                    };
                }
                continue;
            }
            run.append(c);
            budget[0]++;
        }
        flush(run, s, out);
    }

    private static void flush(StringBuilder run, Style s, List<MotdSpan> out) {
        if (!run.isEmpty()) {
            out.add(new MotdSpan(run.toString(), s.colour(), s.bold(), s.italic(), s.underlined(), s.struck()));
            run.setLength(0);
        }
    }

    /** neighbouring spans in the same style become one */
    private static List<MotdSpan> merge(List<MotdSpan> in) {
        List<MotdSpan> out = new ArrayList<>();
        for (MotdSpan s : in) {
            MotdSpan last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && java.util.Objects.equals(last.colour(), s.colour()) && last.bold() == s.bold()
                && last.italic() == s.italic() && last.underlined() == s.underlined() && last.struck() == s.struck()) {
                out.set(out.size() - 1, new MotdSpan(last.text() + s.text(), s.colour(), s.bold(), s.italic(), s.underlined(), s.struck()));
            } else {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
