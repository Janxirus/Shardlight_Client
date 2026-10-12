package dev.shardlight.client.minecraft.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Null-tolerant immutable copies for the model records (JSON arrays and objects may hold nulls). */
final class ModelCopies {
    private ModelCopies() {
    }

    static <T> List<T> list(List<T> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(in.size());
        for (T t : in) {
            if (t != null) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    static <K, V> Map<K, V> map(Map<K, V> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<K, V> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return Collections.unmodifiableMap(out);
    }
}
