package dev.shardlight.client.app.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Minecraft log into lines with a level. A log line looks like {@code [20:14:02] [main/INFO]: text}; the
 * lines that follow it without a header of their own (stack traces, mod lists) belong to it and share its level.
 */
final class LogLines {
    /** how serious a line is */
    enum Level {
        ERROR, WARN, INFO, DEBUG;

        String styleClass() {
            return "log-" + name().toLowerCase(Locale.ROOT);
        }
    }

    record Line(String text, Level level) {
    }

    private static final Pattern HEADER = Pattern.compile("^\\[[^\\]]*\\]\\s*\\[[^\\]/]*/?(FATAL|ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\]");

    private LogLines() {
    }

    static List<Line> parse(String text) {
        List<Line> out = new ArrayList<>();
        Level current = Level.INFO;
        for (String line : text.split("\\R", -1)) {
            Level own = levelOf(line);
            if (own != null) {
                current = own;
            }
            out.add(new Line(line, current));
        }
        if (!out.isEmpty() && out.get(out.size() - 1).text().isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** the level named in the line's own header, or null when the line has none */
    static Level levelOf(String line) {
        Matcher m = HEADER.matcher(line);
        if (!m.find()) {
            return null;
        }
        return switch (m.group(1)) {
            case "FATAL", "ERROR" -> Level.ERROR;
            case "WARN", "WARNING" -> Level.WARN;
            case "DEBUG", "TRACE" -> Level.DEBUG;
            default -> Level.INFO;
        };
    }

    /** the lines that contain {@code query} (ignoring case); all of them when it is blank */
    static List<Line> filter(List<Line> lines, String query) {
        if (query == null || query.isBlank()) {
            return lines;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        return lines.stream().filter(l -> l.text().toLowerCase(Locale.ROOT).contains(q)).toList();
    }
}
