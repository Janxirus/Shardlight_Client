package dev.shardlight.client.content;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.util.AtomicFiles;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The active shader pack in Iris's {@code config/iris.properties} (and Oculus's {@code oculus.properties}):
 * {@code shaderPack=<file name>} and {@code enableShaders=true|false}. Files are only edited when they
 * already exist and name the pack concerned; nothing is created. Lines, comments and order are kept; values
 * are written in {@link Properties} escaping (ISO-8859-1 with {@code \\uXXXX}), as Iris reads them.
 */
final class ShaderSettings {
    static final List<String> FILES = List.of("iris.properties", "oculus.properties");
    static final String PACK = "shaderPack";
    static final String ENABLED = "enableShaders";

    private ShaderSettings() {
    }

    /** true when an existing Iris/Oculus config names {@code pack} as the selected shader pack */
    static boolean isActive(Path gameDir, String pack) throws LauncherException {
        for (String f : FILES) {
            Path p = gameDir.resolve(GameDirs.CONFIG).resolve(f);
            if (Files.isRegularFile(p) && pack.equals(load(p).getProperty(PACK))) {
                return true;
            }
        }
        return false;
    }

    /** set enableShaders where {@code pack} is the selected pack */
    static void setEnabledIfActive(Path gameDir, String pack, boolean enabled) throws LauncherException {
        for (String f : FILES) {
            Path p = gameDir.resolve(GameDirs.CONFIG).resolve(f);
            if (Files.isRegularFile(p) && pack.equals(load(p).getProperty(PACK))) {
                write(p, ENABLED, String.valueOf(enabled));
            }
        }
    }

    /** point the selection at the new file name after an update or rollback */
    static void renameIfActive(Path gameDir, String oldPack, String newPack) throws LauncherException {
        if (oldPack.equals(newPack)) {
            return;
        }
        for (String f : FILES) {
            Path p = gameDir.resolve(GameDirs.CONFIG).resolve(f);
            if (Files.isRegularFile(p) && oldPack.equals(load(p).getProperty(PACK))) {
                write(p, PACK, newPack);
            }
        }
    }

    static Properties load(Path p) throws LauncherException {
        Properties props = new Properties();
        try {
            props.load(new StringReader(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)));
        } catch (IOException | IllegalArgumentException e) {
            throw LauncherException.io(e, p.toString());
        }
        return props;
    }

    private static void write(Path p, String key, String value) throws LauncherException {
        String text;
        try {
            text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw LauncherException.io(e, p.toString());
        }
        List<String> lines = new ArrayList<>(text.lines().toList());
        String line = escape(key, true) + "=" + escape(value, false);
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            if (key.equals(keyOf(lines.get(i)))) {
                if (!done) {
                    lines.set(i, line);
                    done = true;
                } else {
                    lines.remove(i--);
                }
            }
        }
        if (!done) {
            lines.add(line);
        }
        AtomicFiles.writeBytes(p, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    /** the unescaped key of a simple properties line, or null for comments/blank lines */
    private static String keyOf(String line) {
        String t = line.stripLeading();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) {
            return null;
        }
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length()) {
                k.append(t.charAt(++i));
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                k.append(c);
            }
        }
        return k.toString();
    }

    /** {@link Properties#store} escaping, ASCII only */
    static String escape(String s, boolean isKey) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\t' -> b.append("\\t");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\f' -> b.append("\\f");
                case '=', ':', '#', '!' -> b.append('\\').append(c);
                case ' ' -> b.append(i == 0 || isKey ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format("\\u%04X", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
