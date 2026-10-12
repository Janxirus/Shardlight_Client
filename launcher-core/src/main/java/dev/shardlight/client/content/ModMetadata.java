package dev.shardlight.client.content;

import dev.shardlight.client.loader.LoaderType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a mod jar, resource pack or shader pack says about itself.
 *
 * <p>For mods: {@code id}, {@code name}, {@code version} and the dependency maps come from
 * {@code fabric.mod.json}, {@code quilt.mod.json} or {@code (neoforge.)mods.toml}. Dependency values are the
 * raw version predicates (Fabric/Quilt style such as {@code >=1.21}, {@code ~26.3}, {@code 1.21.x}; several
 * alternatives joined with {@code " || "}; Forge/NeoForge style Maven ranges such as {@code [1.21,1.22)}),
 * see {@link VersionPredicates}. {@code environment} is {@code "*"}, {@code "client"}, {@code "server"} or
 * null when the format has no such field.
 *
 * <p>For packs: {@code name} is the file name without extension, {@code description} the flattened
 * {@code pack.description}, {@code packFormat} the declared format (0 when unknown) and
 * {@code minPackFormat}/{@code maxPackFormat} the declared supported range (equal to {@code packFormat}
 * when no range is given).
 *
 * @param id            mod id, null for packs
 * @param name          display name (never null)
 * @param version       version string, null when unknown
 * @param loaders       loaders whose metadata the jar carries (empty for packs)
 * @param environment   "*", "client", "server" or null
 * @param depends       required dependencies: mod id to version predicate
 * @param breaks        mods this one breaks with (Fabric {@code breaks}, Quilt {@code breaks}, Forge "incompatible")
 * @param description   description text, may be empty
 * @param packFormat    pack.mcmeta {@code pack_format}, 0 when unknown or not a pack
 * @param kind          MOD, RESOURCE_PACK, DATAPACK or SHADER_PACK
 * @param recommends    Fabric {@code recommends} (and optional Forge/Quilt dependencies)
 * @param suggests      Fabric {@code suggests}
 * @param conflicts     Fabric {@code conflicts} (works but misbehaves)
 * @param icon          icon path inside the jar, or null
 * @param minPackFormat lowest supported pack format (0 when unknown)
 * @param maxPackFormat highest supported pack format (0 when unknown)
 */
public record ModMetadata(String id, String name, String version, Set<LoaderType> loaders, String environment,
                          Map<String, String> depends, Map<String, String> breaks, String description, int packFormat,
                          ContentKind kind, Map<String, String> recommends, Map<String, String> suggests,
                          Map<String, String> conflicts, String icon, int minPackFormat, int maxPackFormat) {

    public ModMetadata {
        loaders = loaders == null || loaders.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(loaders));
        depends = copy(depends);
        breaks = copy(breaks);
        recommends = copy(recommends);
        suggests = copy(suggests);
        conflicts = copy(conflicts);
        description = description == null ? "" : description;
        name = name == null || name.isBlank() ? (id == null ? "" : id) : name;
        kind = kind == null ? ContentKind.MOD : kind;
    }

    /** the ten-field form (no optional dependency maps, no icon, supported range = packFormat) */
    public ModMetadata(String id, String name, String version, Set<LoaderType> loaders, String environment,
                       Map<String, String> depends, Map<String, String> breaks, String description, int packFormat,
                       ContentKind kind) {
        this(id, name, version, loaders, environment, depends, breaks, description, packFormat, kind,
            Map.of(), Map.of(), Map.of(), null, packFormat, packFormat);
    }

    /** the Minecraft version predicate this mod declares ({@code depends.minecraft}), or null */
    public String minecraftPredicate() {
        return depends.get("minecraft");
    }

    /** true for mod jars */
    public boolean isMod() {
        return kind == ContentKind.MOD;
    }

    /** true when the mod is meant only for dedicated servers */
    public boolean isServerOnly() {
        return "server".equals(environment);
    }

    private static Map<String, String> copy(Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null) {
                out.put(k, v == null ? "*" : v);
            }
        });
        return Collections.unmodifiableMap(out);
    }
}
