package dev.shardlight.client.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small TOML reader, enough for Forge's {@code META-INF/mods.toml} and NeoForge's
 * {@code META-INF/neoforge.mods.toml}: tables, arrays of tables ({@code [[mods]]},
 * {@code [[dependencies.examplemod]]}), dotted and quoted keys, basic/literal/multi-line strings, integers,
 * floats, booleans, arrays and inline tables. Dates are kept as strings. Malformed input throws
 * {@link TomlException}.
 *
 * <p>Values are {@link String}, {@link Long}, {@link Double}, {@link Boolean}, {@code List<Object>} or
 * {@code Map<String, Object>}; an array of tables is a {@code List} of maps.
 */
final class TomlReader {
    private static final int MAX_DEPTH = 32;

    /** malformed TOML */
    static final class TomlException extends RuntimeException {
        TomlException(String message, int pos) {
            super(message + " at offset " + pos);
        }
    }

    private final String s;
    private int i;

    private TomlReader(String text) {
        this.s = text.startsWith("﻿") ? text.substring(1) : text;
    }

    /** parse a whole document */
    static Map<String, Object> parse(String text) {
        return new TomlReader(text).document();
    }

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cur = root;
        while (true) {
            skipBlankLinesAndComments();
            if (eof()) {
                return root;
            }
            char c = s.charAt(i);
            if (c == '[') {
                boolean array = i + 1 < s.length() && s.charAt(i + 1) == '[';
                i += array ? 2 : 1;
                skipSpaces();
                List<String> path = keyPath();
                skipSpaces();
                if (array) {
                    expect("]]");
                    cur = appendArrayTable(root, path);
                } else {
                    expect("]");
                    cur = table(root, path);
                }
                endOfLine();
            } else {
                List<String> path = keyPath();
                skipSpaces();
                expect("=");
                skipSpaces();
                Object v = value(0);
                put(cur, path, v);
                endOfLine();
            }
        }
    }

    // ------------------------------------------------------------------ structure

    @SuppressWarnings("unchecked") // values are only ever created by this reader with these types
    private Map<String, Object> table(Map<String, Object> root, List<String> path) {
        Map<String, Object> m = root;
        for (String k : path) {
            Object o = m.get(k);
            if (o == null) {
                Map<String, Object> n = new LinkedHashMap<>();
                m.put(k, n);
                m = n;
            } else if (o instanceof Map<?, ?> mm) {
                m = (Map<String, Object>) mm;
            } else if (o instanceof List<?> l && !l.isEmpty() && l.get(l.size() - 1) instanceof Map<?, ?> last) {
                m = (Map<String, Object>) last;
            } else {
                throw new TomlException("key " + k + " is not a table", i);
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked") // values are only ever created by this reader with these types
    private Map<String, Object> appendArrayTable(Map<String, Object> root, List<String> path) {
        Map<String, Object> parent = table(root, path.subList(0, path.size() - 1));
        String last = path.get(path.size() - 1);
        Object o = parent.get(last);
        List<Object> list;
        if (o == null) {
            list = new ArrayList<>();
            parent.put(last, list);
        } else if (o instanceof List<?> l) {
            list = (List<Object>) l;
        } else {
            throw new TomlException("key " + last + " is not an array of tables", i);
        }
        Map<String, Object> n = new LinkedHashMap<>();
        list.add(n);
        return n;
    }

    @SuppressWarnings("unchecked") // values are only ever created by this reader with these types
    private void put(Map<String, Object> cur, List<String> path, Object v) {
        Map<String, Object> m = cur;
        for (int k = 0; k < path.size() - 1; k++) {
            Object o = m.get(path.get(k));
            if (o == null) {
                Map<String, Object> n = new LinkedHashMap<>();
                m.put(path.get(k), n);
                m = n;
            } else if (o instanceof Map<?, ?> mm) {
                m = (Map<String, Object>) mm;
            } else {
                throw new TomlException("key " + path.get(k) + " is not a table", i);
            }
        }
        String key = path.get(path.size() - 1);
        if (m.containsKey(key)) {
            throw new TomlException("duplicate key " + key, i);
        }
        m.put(key, v);
    }

    private List<String> keyPath() {
        List<String> parts = new ArrayList<>();
        while (true) {
            skipSpaces();
            parts.add(key());
            skipSpaces();
            if (!eof() && s.charAt(i) == '.') {
                i++;
                continue;
            }
            return parts;
        }
    }

    private String key() {
        if (eof()) {
            throw new TomlException("expected a key", i);
        }
        char c = s.charAt(i);
        if (c == '"') {
            return basicString();
        }
        if (c == '\'') {
            return literalString();
        }
        int start = i;
        while (!eof()) {
            char k = s.charAt(i);
            if (Character.isLetterOrDigit(k) || k == '_' || k == '-') {
                i++;
            } else {
                break;
            }
        }
        if (start == i) {
            throw new TomlException("expected a key", i);
        }
        return s.substring(start, i);
    }

    // ------------------------------------------------------------------ values

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw new TomlException("nesting too deep", i);
        }
        if (eof()) {
            throw new TomlException("expected a value", i);
        }
        char c = s.charAt(i);
        if (c == '"') {
            return s.startsWith("\"\"\"", i) ? multiLineBasic() : basicString();
        }
        if (c == '\'') {
            return s.startsWith("'''", i) ? multiLineLiteral() : literalString();
        }
        if (c == '[') {
            return array(depth);
        }
        if (c == '{') {
            return inlineTable(depth);
        }
        return bare();
    }

    private List<Object> array(int depth) {
        i++; // [
        List<Object> out = new ArrayList<>();
        while (true) {
            skipWhitespaceNewlinesComments();
            if (eof()) {
                throw new TomlException("unterminated array", i);
            }
            if (s.charAt(i) == ']') {
                i++;
                return out;
            }
            out.add(value(depth + 1));
            skipWhitespaceNewlinesComments();
            if (eof()) {
                throw new TomlException("unterminated array", i);
            }
            char c = s.charAt(i);
            if (c == ',') {
                i++;
            } else if (c != ']') {
                throw new TomlException("expected , or ] in array", i);
            }
        }
    }

    private Map<String, Object> inlineTable(int depth) {
        i++; // {
        Map<String, Object> out = new LinkedHashMap<>();
        skipSpaces();
        if (!eof() && s.charAt(i) == '}') {
            i++;
            return out;
        }
        while (true) {
            List<String> path = keyPath();
            skipSpaces();
            expect("=");
            skipSpaces();
            put(out, path, value(depth + 1));
            skipSpaces();
            if (eof()) {
                throw new TomlException("unterminated inline table", i);
            }
            char c = s.charAt(i);
            i++;
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new TomlException("expected , or } in inline table", i);
            }
            skipSpaces();
        }
    }

    private Object bare() {
        int start = i;
        while (!eof()) {
            char c = s.charAt(i);
            if (c == ',' || c == ']' || c == '}' || c == '#' || c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                break;
            }
            i++;
        }
        String t = s.substring(start, i);
        if (t.isEmpty()) {
            throw new TomlException("expected a value", i);
        }
        if (t.equals("true")) {
            return Boolean.TRUE;
        }
        if (t.equals("false")) {
            return Boolean.FALSE;
        }
        String n = t.replace("_", "");
        try {
            if (n.startsWith("0x")) {
                return Long.parseLong(n.substring(2), 16);
            }
            if (n.startsWith("0o")) {
                return Long.parseLong(n.substring(2), 8);
            }
            if (n.startsWith("0b")) {
                return Long.parseLong(n.substring(2), 2);
            }
            return Long.parseLong(n.startsWith("+") ? n.substring(1) : n);
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        if (n.matches("[+-]?(\\d+(\\.\\d+)?([eE][+-]?\\d+)?|inf|nan)")) {
            return n.endsWith("inf") ? (n.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY)
                : n.endsWith("nan") ? Double.NaN : Double.parseDouble(n);
        }
        if (t.matches("\\d{4}-\\d{2}-\\d{2}.*|\\d{2}:\\d{2}.*")) {
            return t; // date/time: kept as text
        }
        throw new TomlException("unknown value " + t, start);
    }

    private String basicString() {
        i++; // "
        StringBuilder b = new StringBuilder();
        while (true) {
            if (eof()) {
                throw new TomlException("unterminated string", i);
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\n') {
                throw new TomlException("newline in string", i);
            }
            if (c == '\\') {
                escape(b);
            } else {
                b.append(c);
            }
        }
    }

    private String literalString() {
        i++; // '
        int end = s.indexOf('\'', i);
        int nl = s.indexOf('\n', i);
        if (end < 0 || (nl >= 0 && nl < end)) {
            throw new TomlException("unterminated string", i);
        }
        String out = s.substring(i, end);
        i = end + 1;
        return out;
    }

    private String multiLineBasic() {
        i += 3;
        skipFirstNewline();
        StringBuilder b = new StringBuilder();
        while (true) {
            if (eof()) {
                throw new TomlException("unterminated string", i);
            }
            if (s.startsWith("\"\"\"", i)) {
                i += 3;
                // up to two extra quotes right before the closing delimiter belong to the content
                int extra = 0;
                while (extra < 2 && !eof() && s.charAt(i) == '"') {
                    b.append('"');
                    i++;
                    extra++;
                }
                return b.toString();
            }
            char c = s.charAt(i++);
            if (c == '\\') {
                // line-ending backslash: trim the newline and following whitespace
                int j = i;
                while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == '\n' || s.charAt(j) == '\r')) {
                    i = j;
                    while (!eof() && Character.isWhitespace(s.charAt(i))) {
                        i++;
                    }
                } else {
                    escape(b);
                }
            } else {
                b.append(c);
            }
        }
    }

    private String multiLineLiteral() {
        i += 3;
        skipFirstNewline();
        int end = s.indexOf("'''", i);
        if (end < 0) {
            throw new TomlException("unterminated string", i);
        }
        // up to two extra quotes right before the closing delimiter belong to the content
        for (int extra = 0; extra < 2 && end + 3 < s.length() && s.charAt(end + 3) == '\''; extra++) {
            end++;
        }
        String out = s.substring(i, end);
        i = end + 3;
        return out;
    }

    private void skipFirstNewline() {
        if (s.startsWith("\r\n", i)) {
            i += 2;
        } else if (!eof() && s.charAt(i) == '\n') {
            i++;
        }
    }

    private void escape(StringBuilder b) {
        if (eof()) {
            throw new TomlException("bad escape", i);
        }
        char e = s.charAt(i++);
        switch (e) {
            case 'b' -> b.append('\b');
            case 't' -> b.append('\t');
            case 'n' -> b.append('\n');
            case 'f' -> b.append('\f');
            case 'r' -> b.append('\r');
            case 'e' -> b.append('\u001B');
            case '"' -> b.append('"');
            case '\\' -> b.append('\\');
            case 'u' -> b.appendCodePoint(hex(4));
            case 'U' -> b.appendCodePoint(hex(8));
            default -> throw new TomlException("bad escape \\" + e, i);
        }
    }

    private int hex(int n) {
        if (i + n > s.length()) {
            throw new TomlException("bad unicode escape", i);
        }
        try {
            int cp = Integer.parseInt(s.substring(i, i + n), 16);
            if (!Character.isValidCodePoint(cp)) {
                throw new TomlException("bad unicode escape", i);
            }
            i += n;
            return cp;
        } catch (NumberFormatException e) {
            throw new TomlException("bad unicode escape", i);
        }
    }

    // ------------------------------------------------------------------ whitespace

    private boolean eof() {
        return i >= s.length();
    }

    private void expect(String t) {
        if (!s.startsWith(t, i)) {
            throw new TomlException("expected " + t, i);
        }
        i += t.length();
    }

    private void skipSpaces() {
        while (!eof() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
    }

    private void skipComment() {
        if (!eof() && s.charAt(i) == '#') {
            while (!eof() && s.charAt(i) != '\n') {
                i++;
            }
        }
    }

    private void skipBlankLinesAndComments() {
        skipWhitespaceNewlinesComments();
    }

    private void skipWhitespaceNewlinesComments() {
        while (!eof()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else if (c == '#') {
                skipComment();
            } else {
                return;
            }
        }
    }

    private void endOfLine() {
        skipSpaces();
        skipComment();
        if (eof()) {
            return;
        }
        if (s.charAt(i) == '\r') {
            i++;
        }
        if (eof() || s.charAt(i) == '\n') {
            if (!eof()) {
                i++;
            }
            return;
        }
        throw new TomlException("expected end of line", i);
    }
}
