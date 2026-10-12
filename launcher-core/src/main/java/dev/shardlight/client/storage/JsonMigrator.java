package dev.shardlight.client.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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

    /** the document's {@code schemaVersion}; 0 when it is missing or not a whole number */
    public static int versionOf(JsonObject o) {
        JsonElement v = o.get("schemaVersion");
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        try {
            return Math.max(0, new BigDecimal(v.getAsString()).intValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            return 0;
        }
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
