package dev.shardlight.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;

import java.lang.reflect.Type;

/** The launcher's shared Gson setup: pretty, no HTML escaping, nulls omitted. */
public final class Json {
    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .setStrictness(Strictness.LENIENT)
        .create();

    /** compact output for network requests */
    public static final Gson COMPACT = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private Json() {
    }

    public static String write(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T read(String json, Class<T> type) throws JsonSyntaxException {
        return GSON.fromJson(json, type);
    }

    public static <T> T read(String json, Type type) throws JsonSyntaxException {
        return GSON.fromJson(json, type);
    }

    public static JsonElement parse(String json) throws JsonSyntaxException {
        return JsonParser.parseString(json);
    }

    /** a deep copy through JSON (for models) */
    public static <T> T copy(T o, Class<T> type) {
        return GSON.fromJson(GSON.toJson(o), type);
    }
}
