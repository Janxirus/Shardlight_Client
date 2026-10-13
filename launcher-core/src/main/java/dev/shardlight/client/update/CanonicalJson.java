package dev.shardlight.client.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The canonical JSON form the update signature is computed over: object keys sorted (by UTF-16 code units,
 * as in RFC 8785; for the ASCII keys of the manifest this is plain alphabetical order), no whitespace at
 * all, strings with only {@code "} {@code \} and control characters escaped ({@code \b \f \n \r \t} short,
 * the rest as a backslash, {@code u} and four lowercase hex digits), other characters written as themselves
 * in UTF-8, numbers as integers without plus sign, fraction or exponent, and {@code true},
 * {@code false}, {@code null}. Non-integer numbers are refused so that no two implementations can disagree
 * on their spelling. This is exactly what Python's
 * {@code json.dumps(latest, sort_keys=True, separators=(",", ":"), ensure_ascii=False)} produces for such
 * data (see docs/RELEASING.md).
 */
public final class CanonicalJson {
    private CanonicalJson() {
    }

    /** the canonical text; throws {@link IllegalArgumentException} for fractional numbers or broken strings */
    public static String canonicalize(JsonElement e) {
        StringBuilder sb = new StringBuilder();
        write(e, sb, 0);
        return sb.toString();
    }

    /** the canonical text as UTF-8 bytes, the input of the signature */
    public static byte[] bytes(JsonElement e) {
        return canonicalize(e).getBytes(StandardCharsets.UTF_8);
    }

    private static void write(JsonElement e, StringBuilder sb, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("JSON nested too deeply");
        }
        if (e == null || e.isJsonNull()) {
            sb.append("null");
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(o.entrySet());
            entries.sort(Map.Entry.comparingByKey());
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : entries) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(en.getKey(), sb);
                sb.append(':');
                write(en.getValue(), sb, depth + 1);
            }
            sb.append('}');
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            sb.append('[');
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(a.get(i), sb, depth + 1);
            }
            sb.append(']');
        } else {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                sb.append(p.getAsBoolean());
            } else if (p.isNumber()) {
                sb.append(integer(p.getAsString()));
            } else {
                string(p.getAsString(), sb);
            }
        }
    }

    private static String integer(String lexical) {
        String s = lexical.trim();
        if (s.isEmpty() || s.length() > 64 || s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
            throw new IllegalArgumentException("Only integers are allowed in signed data: " + lexical);
        }
        try {
            return new BigDecimal(s).toBigIntegerExact().toString();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("Not an integer: " + lexical, ex);
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                            throw new IllegalArgumentException("Unpaired surrogate in string");
                        }
                        sb.append(c).append(s.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("Unpaired surrogate in string");
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
