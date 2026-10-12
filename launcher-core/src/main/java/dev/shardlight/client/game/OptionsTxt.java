package dev.shardlight.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minecraft's {@code options.txt}: {@code key:value} lines. Unknown lines and the original order are
 * kept, so editing one key never disturbs the player's other settings. Minecraft accepts a file with only
 * some keys and fills in the rest the next time it saves.
 *
 * <p>Resource packs: {@code resourcePacks} is a JSON array, lowest priority first (the last entry wins);
 * user packs are written as {@code "file/<name>"}.
 */
public final class OptionsTxt {
    private final List<String> lines = new ArrayList<>();

    public static OptionsTxt read(Path file) throws LauncherException {
        OptionsTxt o = new OptionsTxt();
        if (Files.exists(file)) {
            try {
                o.lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw LauncherException.io(e, file.toString());
            }
        }
        return o;
    }

    public static OptionsTxt parse(String text) {
        OptionsTxt o = new OptionsTxt();
        o.lines.addAll(text.lines().toList());
        return o;
    }

    public void write(Path file) throws LauncherException {
        AtomicFiles.writeString(file, text());
    }

    public String text() {
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    public Optional<String> get(String key) {
        for (String l : lines) {
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).equals(key)) {
                return Optional.of(l.substring(c + 1));
            }
        }
        return Optional.empty();
    }

    /** set (or add at the end) one key */
    public void set(String key, String value) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).equals(key)) {
                lines.set(i, key + ":" + value);
                return;
            }
        }
        lines.add(key + ":" + value);
    }

    public void remove(String key) {
        lines.removeIf(l -> {
            int c = l.indexOf(':');
            return c > 0 && l.substring(0, c).equals(key);
        });
    }

    /** every key/value pair, in file order */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : lines) {
            int c = l.indexOf(':');
            if (c > 0) {
                m.put(l.substring(0, c), l.substring(c + 1));
            }
        }
        return m;
    }

    /** a JSON string-array option ({@code resourcePacks}, {@code incompatibleResourcePacks}); empty when absent/broken */
    public List<String> getList(String key) {
        Optional<String> v = get(key);
        List<String> out = new ArrayList<>();
        if (v.isEmpty() || v.get().isBlank()) {
            return out;
        }
        try {
            JsonElement e = JsonParser.parseString(v.get());
            if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (x.isJsonPrimitive()) {
                        out.add(x.getAsString());
                    }
                }
            }
        } catch (JsonParseException | IllegalStateException ignored) {
            // a hand-edited, broken value: treat as empty
        }
        return out;
    }

    public void setList(String key, List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        set(key, a.toString());
    }
}
