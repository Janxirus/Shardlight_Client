package dev.shardlight.client.minecraft.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.shardlight.client.util.Json;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * One item of {@code arguments.game} or {@code arguments.jvm}. In JSON it is either a plain string or
 * {@code {"rules": [...], "value": "x"}} / {@code {"rules": [...], "value": ["x", "y"]}}.
 *
 * @param values the argument strings (placeholders unresolved)
 * @param rules  when they apply; empty = always
 */
@JsonAdapter(Argument.Adapter.class)
public record Argument(List<String> values, List<Rule> rules) {
    public Argument {
        values = ModelCopies.list(values);
        rules = ModelCopies.list(rules);
    }

    /** an unconditional single argument */
    public static Argument of(String value) {
        return new Argument(List.of(value), List.of());
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }

    /** Gson adapter for the string-or-object shape. */
    public static final class Adapter extends TypeAdapter<Argument> {
        private static final Type RULES = new TypeToken<List<Rule>>() { }.getType();

        public Adapter() {
            // used by Gson through @JsonAdapter
        }

        @Override
        public void write(JsonWriter out, Argument a) throws IOException {
            if (a == null) {
                out.nullValue();
                return;
            }
            if (a.rules().isEmpty() && a.values().size() == 1) {
                out.value(a.values().get(0));
                return;
            }
            JsonObject o = new JsonObject();
            o.add("rules", Json.GSON.toJsonTree(a.rules(), RULES));
            if (a.values().size() == 1) {
                o.addProperty("value", a.values().get(0));
            } else {
                JsonArray arr = new JsonArray();
                a.values().forEach(arr::add);
                o.add("value", arr);
            }
            Json.COMPACT.toJson(o, out);
        }

        @Override
        public Argument read(JsonReader in) throws IOException {
            JsonToken t = in.peek();
            if (t == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (t == JsonToken.STRING || t == JsonToken.NUMBER || t == JsonToken.BOOLEAN) {
                return Argument.of(t == JsonToken.BOOLEAN ? String.valueOf(in.nextBoolean()) : in.nextString());
            }
            JsonElement e = JsonParser.parseReader(in);
            if (!e.isJsonObject()) {
                throw new JsonParseException("argument must be a string or an object");
            }
            JsonObject o = e.getAsJsonObject();
            List<Rule> rules = o.has("rules") && o.get("rules").isJsonArray()
                ? Json.GSON.fromJson(o.get("rules"), RULES) : List.of();
            List<String> values = new ArrayList<>();
            JsonElement v = o.get("value");
            if (v != null && v.isJsonPrimitive()) {
                values.add(v.getAsString());
            } else if (v != null && v.isJsonArray()) {
                for (JsonElement x : v.getAsJsonArray()) {
                    if (x != null && x.isJsonPrimitive()) {
                        values.add(x.getAsString());
                    }
                }
            }
            return new Argument(values, rules);
        }
    }
}
