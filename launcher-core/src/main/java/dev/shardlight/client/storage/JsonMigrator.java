package dev.shardlight.client.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Upgrades stored JSON documents step by step. Each document carries {@code schemaVersion}; a step
 * migrates version N to N+1 in place. Documents newer than this launcher are left untouched (and the
 * caller should avoid overwriting them).
 */
public final class JsonMigrator {
    private static final Logger LOG = Logger.getLogger(JsonMigrator.class.getName());
    private final String what;
    private final int current;
    private final List<Consumer<JsonObject>> steps = new ArrayList<>();

    /** {@code current} is the schema version this launcher writes */
    public JsonMigrator(String what, int current) {
        this.what = what;
        this.current = current;
        for (int i = 0; i < current; i++) {
            steps.add(o -> { });
        }
    }

    /** register the step that turns version {@code from} into {@code from + 1} */
    public JsonMigrator step(int from, Consumer<JsonObject> migrate) {
        steps.set(from, migrate);
        return this;
    }

    /**
     * The document's {@code schemaVersion}, 0 when it has none. Throws {@link JsonParseException} when the
     * value isn't a whole number of zero or more, so callers treat the file as unreadable.
     */
    public static int versionOf(JsonObject o) {
        JsonElement v = o.get("schemaVersion");
        if (v == null || v.isJsonNull()) {
            return 0;
        }
        if (v.isJsonPrimitive() && !v.getAsJsonPrimitive().isBoolean()) {
            try {
                int n = new BigDecimal(v.getAsString().trim()).intValueExact();
                if (n >= 0) {
                    return n;
                }
            } catch (NumberFormatException | ArithmeticException e) {
                // reported below
            }
        }
        throw new JsonParseException("schemaVersion is not a whole number: " + v);
    }

    /** true when the document was written by a newer launcher */
    public boolean isNewer(JsonObject o) {
        return versionOf(o) > current;
    }

    /** migrate in place; returns true when anything changed */
    public boolean migrate(JsonObject o) {
        int v = versionOf(o);
        if (v >= current) {
            return false;
        }
        while (v < current) {
            steps.get(v).accept(o);
            v++;
            o.addProperty("schemaVersion", v);
        }
        LOG.info("Migrated " + what + " to schema " + current);
        return true;
    }
}
