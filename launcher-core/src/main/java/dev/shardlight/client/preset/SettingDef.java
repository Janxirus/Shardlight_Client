package dev.shardlight.client.preset;

import java.util.List;
import java.util.Locale;

/**
 * One setting a preset controls: a key in a Shardlight mod's own config file, or (opt-in) one of
 * Minecraft's video options in {@code options.txt}.
 *
 * @param key            launcher-wide id, {@code <target>.<jsonKey>}, for example {@code ui.themeDecor}
 * @param target         whose file the setting lives in
 * @param jsonKey        the key inside that file (a field of the mod's config class, or an options.txt option)
 * @param kind           the value type
 * @param labelKey       i18n key of a short label
 * @param descriptionKey i18n key of a one-line description (what it does and what it costs)
 * @param group          animation, effects, decorations, hud, map, network or video
 * @param cost           how expensive the feature is when on, or at higher values
 * @param essential      an essential gameplay indicator (health, armour, durability, food/air warnings) that
 *                       no preset may hide
 * @param modDefault     the value the mod (or Minecraft) uses when the key is absent; what Balanced uses
 * @param constraint     the values the mod accepts
 */
public record SettingDef(String key, Target target, String jsonKey, Kind kind, String labelKey, String descriptionKey,
                         String group, Cost cost, boolean essential, Object modDefault, Constraint constraint) {

    /** where a setting is stored */
    public enum Target {
        /** Shardlight UI, {@code config/shardlight.json} */
        UI("ui", "config/shardlight.json"),
        /** Shardlight Map, {@code config/shardmap.json} */
        MAP("map", "config/shardmap.json"),
        /** Shardlight Party, {@code config/shardparty.json} */
        PARTY("party", "config/shardparty.json"),
        /** Shardlight Companion, {@code config/shardcompanion.json} */
        COMPANION("companion", "config/shardcompanion.json"),
        /** Minecraft's own {@code options.txt}; only with the project's vanilla video opt-in */
        VANILLA(null, "options.txt");

        private final String moduleId;
        private final String file;

        Target(String moduleId, String file) {
            this.moduleId = moduleId;
            this.file = file;
        }

        /** the Shardlight module whose config this is, null for VANILLA */
        public String moduleId() {
            return moduleId;
        }

        /** the file, relative to the game directory, with forward slashes */
        public String file() {
            return file;
        }

        /** the target of a module id ({@code ui}...), or null */
        public static Target ofModule(String moduleId) {
            for (Target t : values()) {
                if (t.moduleId != null && t.moduleId.equalsIgnoreCase(moduleId)) {
                    return t;
                }
            }
            return null;
        }
    }

    /** value types */
    public enum Kind { BOOLEAN, INT, DOUBLE, STRING }

    /** how much a feature costs while it is on (or turned up) */
    public enum Cost { LOW, MEDIUM, HIGH }

    /**
     * Accepted values: a closed range for numbers, or a fixed list of allowed values.
     *
     * @param min     smallest number accepted, or null
     * @param max     largest number accepted, or null
     * @param allowed the only values accepted (strings, or numbers for enumerated numeric options); empty = any
     */
    public record Constraint(Double min, Double max, List<Object> allowed) {
        public Constraint {
            allowed = allowed == null ? List.of() : List.copyOf(allowed);
        }

        /** no restriction beyond the type */
        public static Constraint any() {
            return new Constraint(null, null, List.of());
        }

        /** a closed numeric range */
        public static Constraint range(double min, double max) {
            return new Constraint(min, max, List.of());
        }

        /** one of these values */
        public static Constraint oneOf(Object... values) {
            return new Constraint(null, null, List.of(values));
        }

        /** true when the (already normalized) value is allowed */
        public boolean accepts(Object v) {
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return false;
                }
                if ((min != null && d < min) || (max != null && d > max)) {
                    return false;
                }
                return allowed.isEmpty() || allowed.stream().anyMatch(a -> a instanceof Number an && an.doubleValue() == d);
            }
            return allowed.isEmpty() || allowed.contains(v);
        }
    }

    public SettingDef {
        if (key == null || target == null || jsonKey == null || kind == null || cost == null) {
            throw new IllegalArgumentException("incomplete setting " + key);
        }
        constraint = constraint == null ? Constraint.any() : constraint;
    }

    /** true when the value has this setting's type (after normalizing) and the mod accepts it */
    public boolean accepts(Object value) {
        try {
            normalize(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The value in its canonical Java type ({@link Boolean}, {@link Integer}, {@link Double} or
     * {@link String}); numbers read back from JSON as doubles (2.0) become ints where the kind is INT.
     * Throws {@link IllegalArgumentException} when the value doesn't fit the type or the constraint.
     */
    public Object normalize(Object value) {
        Object v = switch (kind) {
            case BOOLEAN -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                    yield Boolean.parseBoolean(s.toLowerCase(Locale.ROOT));
                }
                throw bad(value);
            }
            case INT -> {
                double d = number(value);
                if (d != Math.rint(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
                    throw bad(value);
                }
                yield (int) d;
            }
            case DOUBLE -> number(value);
            case STRING -> {
                if (value instanceof String s) {
                    yield s;
                }
                throw bad(value);
            }
        };
        if (!constraint.accepts(v)) {
            throw bad(value);
        }
        return v;
    }

    private double number(Object value) {
        double d;
        if (value instanceof Number n) {
            d = n.doubleValue();
        } else if (value instanceof String s) {
            try {
                d = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw bad(value);
            }
        } else {
            throw bad(value);
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw bad(value);
        }
        return d;
    }

    private IllegalArgumentException bad(Object value) {
        return new IllegalArgumentException("Invalid value for " + key + ": " + value);
    }

    /** true when two values are the same setting value (numbers compared by value) */
    public boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        try {
            Object x = normalize(a);
            Object y = normalize(b);
            if (x instanceof Double dx && y instanceof Double dy) {
                return Math.abs(dx - dy) < 1e-9;
            }
            return x.equals(y);
        } catch (IllegalArgumentException e) {
            return a.equals(b);
        }
    }
}
