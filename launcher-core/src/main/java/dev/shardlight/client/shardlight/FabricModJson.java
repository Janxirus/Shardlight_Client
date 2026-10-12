package dev.shardlight.client.shardlight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of a jar's {@code fabric.mod.json} the Shardlight library needs: the mod id, its version and
 * its dependency ranges. Built jars carry a real version; a jar straight from source still has the
 * literal {@code ${version}}, which {@link #isBuilt()} reports.
 *
 * @param id      the mod id
 * @param version the version string as written
 * @param depends dependency id to accepted ranges (Fabric's string or array form)
 */
public record FabricModJson(String id, String version, Map<String, List<String>> depends) {
    /** where Fabric expects the file inside a jar */
    public static final String ENTRY = "fabric.mod.json";

    public FabricModJson {
        depends = depends == null ? Map.of() : Map.copyOf(depends);
    }

    /** parse the file; throws {@link ErrorCode#INVALID_DATA} when it isn't a usable fabric.mod.json */
    public static FabricModJson parse(byte[] data, String where) throws LauncherException {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) {
                throw new JsonParseException("not an object");
            }
            JsonObject o = e.getAsJsonObject();
            String id = string(o, "id");
            String version = string(o, "version");
            if (id == null || id.isBlank()) {
                throw new JsonParseException("no mod id");
            }
            Map<String, List<String>> depends = new LinkedHashMap<>();
            if (o.has("depends") && o.get("depends").isJsonObject()) {
                for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("depends").entrySet()) {
                    List<String> ranges = new ArrayList<>();
                    if (d.getValue().isJsonArray()) {
                        for (JsonElement r : d.getValue().getAsJsonArray()) {
                            if (r.isJsonPrimitive()) {
                                ranges.add(r.getAsString());
                            }
                        }
                    } else if (d.getValue().isJsonPrimitive()) {
                        ranges.add(d.getValue().getAsString());
                    }
                    depends.put(d.getKey(), List.copyOf(ranges));
                }
            }
            return new FabricModJson(id.trim(), version == null ? "" : version.trim(), depends);
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "shardlight.import.badModJson", where);
        }
    }

    /** false for a jar that still has an unexpanded {@code ${version}} placeholder or no version */
    public boolean isBuilt() {
        return !version.isBlank() && !version.contains("${");
    }

    /** the accepted Minecraft ranges, empty when the jar doesn't say */
    public List<String> minecraftRanges() {
        return depends.getOrDefault("minecraft", List.of());
    }

    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }
}
