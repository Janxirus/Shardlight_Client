package dev.shardlight.client.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Null-tolerant accessors for JSON from catalog APIs and modpack manifests: a missing field, a JSON null or
 * a value of another type gives the default instead of an exception.
 */
public final class JsonFields {
    private JsonFields() {
    }

    /** a string field, or null */
    public static String str(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** a numeric field as long (also from numeric strings), or {@code def} */
    public static long lng(JsonObject o, String key, long def) {
        if (o == null) {
            return def;
        }
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return def;
        }
        try {
            return e.getAsJsonPrimitive().isNumber() ? e.getAsNumber().longValue() : Long.parseLong(e.getAsString().trim());
        } catch (NumberFormatException ex) {
            try {
                return (long) Double.parseDouble(e.getAsString().trim());
            } catch (NumberFormatException ex2) {
                return def;
            }
        }
    }

    /** a boolean field, or {@code def} */
    public static boolean bool(JsonObject o, String key, boolean def) {
        Boolean b = boolOrNull(o, key);
        return b == null ? def : b;
    }

    /** a boolean field, or null when absent or not a boolean */
    public static Boolean boolOrNull(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        String s = e.getAsString().trim();
        return s.equalsIgnoreCase("true") ? Boolean.TRUE : s.equalsIgnoreCase("false") ? Boolean.FALSE : null;
    }

    /** an object field, or null */
    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** an array field, or an empty array */
    public static JsonArray arr(JsonObject o, String key) {
        if (o == null) {
            return new JsonArray();
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** the objects of an array field (other elements skipped) */
    public static List<JsonObject> objects(JsonObject o, String key) {
        return objects(arr(o, key));
    }

    /** the objects of an array (other elements skipped) */
    public static List<JsonObject> objects(JsonArray a) {
        List<JsonObject> out = new ArrayList<>();
        if (a != null) {
            for (JsonElement e : a) {
                if (e != null && e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    /** the strings of an array field (other elements skipped) */
    public static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(o, key)) {
            if (e != null && e.isJsonPrimitive()) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    /** an absolute http(s) URI, or null when blank or malformed */
    public static URI uri(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(s.trim());
            String scheme = u.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) && u.getHost() != null
                ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** an ISO-8601 time, or null */
    public static Instant instant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s.trim()).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** the element as an object, or INVALID_DATA naming {@code host} */
    public static JsonObject requireObject(JsonElement e, String host) throws LauncherException {
        if (e == null || !e.isJsonObject()) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "error.invalidResponse", String.valueOf(host));
        }
        return e.getAsJsonObject();
    }

    /** the element as an array, or INVALID_DATA naming {@code host} */
    public static JsonArray requireArray(JsonElement e, String host) throws LauncherException {
        if (e == null || !e.isJsonArray()) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "error.invalidResponse", String.valueOf(host));
        }
        return e.getAsJsonArray();
    }
}
