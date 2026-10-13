package dev.shardlight.client.loader;

import java.util.Locale;

/** Mod loaders the launcher knows. VANILLA means no loader. */
public enum LoaderType {
    VANILLA("Vanilla", null),
    FABRIC("Fabric", "fabric"),
    QUILT("Quilt", "quilt"),
    FORGE("Forge", "forge"),
    NEOFORGE("NeoForge", "neoforge");

    private final String displayName;
    private final String catalogId;

    LoaderType(String displayName, String catalogId) {
        this.displayName = displayName;
        this.catalogId = catalogId;
    }

    public String displayName() {
        return displayName;
    }

    /** the loader name Modrinth uses ({@code fabric}, {@code quilt}...); null for vanilla */
    public String catalogId() {
        return catalogId;
    }

    /** Quilt can also load most Fabric mods */
    public boolean acceptsModsFor(LoaderType modLoader) {
        return this == modLoader || (this == QUILT && modLoader == FABRIC);
    }

    public static LoaderType parse(String s) {
        if (s == null || s.isBlank()) {
            return VANILLA;
        }
        String v = s.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (LoaderType t : values()) {
            if (t.name().replace("_", "").equals(v)) {
                return t;
            }
        }
        if (v.equals("FABRICLOADER")) {
            return FABRIC;
        }
        if (v.equals("QUILTLOADER")) {
            return QUILT;
        }
        throw new IllegalArgumentException("Unknown loader: " + s);
    }
}
