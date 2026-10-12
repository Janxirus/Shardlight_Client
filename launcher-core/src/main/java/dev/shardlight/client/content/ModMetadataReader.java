package dev.shardlight.client.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.shardlight.client.loader.LoaderType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads what a content file says about itself, without extracting anything:
 * <ul>
 *   <li>mod jars: {@code fabric.mod.json}, {@code quilt.mod.json}, {@code META-INF/neoforge.mods.toml},
 *       {@code META-INF/mods.toml} (with {@code ${file.jarVersion}} taken from the manifest's
 *       {@code Implementation-Version}); a jar carrying several of them reports every loader;</li>
 *   <li>resource packs and data packs: {@code pack.mcmeta} (zip or folder);</li>
 *   <li>shader packs: a {@code shaders/} folder at the root (zip or folder).</li>
 * </ul>
 * {@link #read} never throws: unreadable or malformed files give {@link Optional#empty()} and a log line.
 * Metadata files are read with a size cap, so a hostile archive can't exhaust memory. Thread-safe.
 */
public final class ModMetadataReader {
    private static final Logger LOG = Logger.getLogger(ModMetadataReader.class.getName());
    /** largest metadata file we read */
    static final int MAX_METADATA_BYTES = 1 << 20;
    /** stop scanning archive entries after this many (for pack/shader detection) */
    private static final int MAX_SCANNED_ENTRIES = 200_000;
    private static final int MAX_TEXT_DEPTH = 16;

    /** a reader; stateless */
    public ModMetadataReader() {
    }

    /** the metadata of a jar, zip or pack folder; empty when there is none or it can't be read */
    public Optional<ModMetadata> read(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        try {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                return readFolder(file);
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
                return readZip(zip, file);
            }
        } catch (IOException | RuntimeException e) {
            LOG.info("Couldn't read metadata of " + file.getFileName() + ": " + e);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ archives

    private Optional<ModMetadata> readZip(ZipFile zip, Path file) throws IOException {
        List<ModMetadata> found = new ArrayList<>();
        String fabric = text(zip, "fabric.mod.json");
        if (fabric != null) {
            parseQuietly(file, "fabric.mod.json", () -> fabric(fabric)).ifPresent(found::add);
        }
        String quilt = text(zip, "quilt.mod.json");
        if (quilt != null) {
            parseQuietly(file, "quilt.mod.json", () -> quilt(quilt)).ifPresent(found::add);
        }
        String neo = text(zip, "META-INF/neoforge.mods.toml");
        String forge = text(zip, "META-INF/mods.toml");
        if (neo != null || forge != null) {
            String jarVersion = jarVersion(zip);
            if (neo != null) {
                parseQuietly(file, "neoforge.mods.toml", () -> modsToml(neo, true, jarVersion)).ifPresent(found::add);
            }
            if (forge != null) {
                parseQuietly(file, "mods.toml", () -> modsToml(forge, false, jarVersion)).ifPresent(found::add);
            }
        }
        if (!found.isEmpty()) {
            return Optional.of(merge(found));
        }
        String mcmeta = text(zip, "pack.mcmeta");
        boolean assets = false;
        boolean data = false;
        boolean shaders = false;
        Enumeration<? extends ZipEntry> en = zip.entries();
        int n = 0;
        while (en.hasMoreElements() && n++ < MAX_SCANNED_ENTRIES) {
            String name = en.nextElement().getName().replace('\\', '/');
            assets |= name.startsWith("assets/");
            data |= name.startsWith("data/");
            shaders |= name.startsWith("shaders/");
        }
        String base = stripExtension(file.getFileName().toString());
        if (mcmeta != null) {
            boolean dataOnly = !assets && data;
            return parseQuietly(file, "pack.mcmeta", () -> pack(mcmeta, base, dataOnly));
        }
        if (shaders) {
            return Optional.of(shader(base));
        }
        return Optional.empty();
    }

    private Optional<ModMetadata> readFolder(Path dir) throws IOException {
        String base = dir.getFileName().toString();
        Path mcmeta = dir.resolve("pack.mcmeta");
        if (Files.isRegularFile(mcmeta, LinkOption.NOFOLLOW_LINKS)) {
            String t = textFile(mcmeta);
            boolean dataOnly = !Files.isDirectory(dir.resolve("assets")) && Files.isDirectory(dir.resolve("data"));
            return parseQuietly(dir, "pack.mcmeta", () -> pack(t, base, dataOnly));
        }
        if (Files.isDirectory(dir.resolve("shaders"), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(shader(base));
        }
        return Optional.empty();
    }

    @FunctionalInterface
    private interface Parser {
        ModMetadata parse();
    }

    private static Optional<ModMetadata> parseQuietly(Path file, String what, Parser p) {
        try {
            return Optional.ofNullable(p.parse());
        } catch (RuntimeException e) {
            LOG.info("Malformed " + what + " in " + file.getFileName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** a jar with several metadata files: the first one names the mod, loaders are combined */
    private static ModMetadata merge(List<ModMetadata> all) {
        ModMetadata first = all.get(0);
        if (all.size() == 1) {
            return first;
        }
        Set<LoaderType> loaders = EnumSet.noneOf(LoaderType.class);
        all.forEach(m -> loaders.addAll(m.loaders()));
        return new ModMetadata(first.id(), first.name(), first.version(), loaders, first.environment(), first.depends(),
            first.breaks(), first.description(), first.packFormat(), first.kind(), first.recommends(), first.suggests(),
            first.conflicts(), first.icon(), first.minPackFormat(), first.maxPackFormat());
    }

    private static String text(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null || e.isDirectory()) {
            return null;
        }
        if (e.getSize() > MAX_METADATA_BYTES) {
            throw new IOException(name + " is too large");
        }
        try (InputStream in = zip.getInputStream(e)) {
            return decode(in.readNBytes(MAX_METADATA_BYTES + 1), name);
        }
    }

    private static String textFile(Path p) throws IOException {
        if (Files.size(p) > MAX_METADATA_BYTES) {
            throw new IOException(p.getFileName() + " is too large");
        }
        return decode(Files.readAllBytes(p), p.getFileName().toString());
    }

    private static String decode(byte[] data, String name) throws IOException {
        if (data.length > MAX_METADATA_BYTES) {
            throw new IOException(name + " is too large");
        }
        String s = new String(data, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private static String jarVersion(ZipFile zip) {
        try {
            ZipEntry e = zip.getEntry("META-INF/MANIFEST.MF");
            if (e == null || e.getSize() > MAX_METADATA_BYTES) {
                return null;
            }
            try (InputStream in = zip.getInputStream(e)) {
                byte[] b = in.readNBytes(MAX_METADATA_BYTES + 1);
                if (b.length > MAX_METADATA_BYTES) {
                    return null;
                }
                Manifest mf = new Manifest(new ByteArrayInputStream(b));
                String v = mf.getMainAttributes().getValue("Implementation-Version");
                return v == null || v.isBlank() ? null : v.trim();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ fabric.mod.json

    static ModMetadata fabric(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = str(o, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("no id");
        }
        String env = str(o, "environment");
        return new ModMetadata(id, str(o, "name"), str(o, "version"), EnumSet.of(LoaderType.FABRIC),
            env == null ? "*" : env.toLowerCase(Locale.ROOT), fabricDeps(o, "depends"), fabricDeps(o, "breaks"),
            str(o, "description"), 0, ContentKind.MOD, fabricDeps(o, "recommends"), fabricDeps(o, "suggests"),
            fabricDeps(o, "conflicts"), fabricIcon(o.get("icon")), 0, 0);
    }

    private static Map<String, String> fabricDeps(JsonObject o, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> d : e.getAsJsonObject().entrySet()) {
            out.put(d.getKey(), predicate(d.getValue()));
        }
        return out;
    }

    /** a predicate string, or an array of alternatives joined with " || " */
    private static String predicate(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return "*";
        }
        if (v.isJsonPrimitive()) {
            return v.getAsString();
        }
        if (v.isJsonArray()) {
            List<String> alts = new ArrayList<>();
            for (JsonElement a : v.getAsJsonArray()) {
                if (a.isJsonPrimitive()) {
                    alts.add(a.getAsString());
                }
            }
            return alts.isEmpty() ? "*" : String.join(" || ", alts);
        }
        if (v.isJsonObject()) {
            // Quilt: {"any": [...]} or {"all": [...]}
            JsonObject o = v.getAsJsonObject();
            if (o.has("any")) {
                return predicate(o.get("any"));
            }
            if (o.has("all") && o.get("all").isJsonArray()) {
                List<String> all = new ArrayList<>();
                for (JsonElement a : o.getAsJsonArray("all")) {
                    if (a.isJsonPrimitive()) {
                        all.add(a.getAsString());
                    }
                }
                return all.isEmpty() ? "*" : String.join(" ", all);
            }
        }
        return "*";
    }

    private static String fabricIcon(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject()) {
            // {"16": "a.png", "128": "b.png"}: take the largest
            String best = null;
            int bestSize = -1;
            for (Map.Entry<String, JsonElement> s : e.getAsJsonObject().entrySet()) {
                try {
                    int size = Integer.parseInt(s.getKey().trim());
                    if (size > bestSize && s.getValue().isJsonPrimitive()) {
                        bestSize = size;
                        best = s.getValue().getAsString();
                    }
                } catch (NumberFormatException ignored) {
                    // not a size key
                }
            }
            return best;
        }
        return null;
    }

    // ------------------------------------------------------------------ quilt.mod.json

    static ModMetadata quilt(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject ql = root.getAsJsonObject("quilt_loader");
        if (ql == null) {
            throw new IllegalStateException("no quilt_loader");
        }
        String id = str(ql, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("no id");
        }
        JsonObject meta = ql.has("metadata") && ql.get("metadata").isJsonObject() ? ql.getAsJsonObject("metadata") : new JsonObject();
        String env = null;
        if (root.has("minecraft") && root.get("minecraft").isJsonObject()) {
            env = str(root.getAsJsonObject("minecraft"), "environment");
        }
        if (env != null) {
            env = env.toLowerCase(Locale.ROOT);
            env = env.equals("dedicated_server") ? "server" : env;
        }
        Map<String, String> depends = new LinkedHashMap<>();
        Map<String, String> optional = new LinkedHashMap<>();
        quiltDeps(ql.get("depends"), depends, optional);
        Map<String, String> breaks = new LinkedHashMap<>();
        quiltDeps(ql.get("breaks"), breaks, breaks);
        return new ModMetadata(id, str(meta, "name"), str(ql, "version"), EnumSet.of(LoaderType.QUILT),
            env == null ? "*" : env, depends, breaks, str(meta, "description"), 0, ContentKind.MOD, optional,
            Map.of(), Map.of(), fabricIcon(meta.get("icon")), 0, 0);
    }

    private static void quiltDeps(JsonElement e, Map<String, String> required, Map<String, String> optional) {
        if (e == null || !e.isJsonArray()) {
            return;
        }
        for (JsonElement d : e.getAsJsonArray()) {
            if (d.isJsonPrimitive()) {
                String s = d.getAsString();
                // "modid" or "modid@version" (the latter is the Quilt shorthand)
                int at = s.indexOf('@');
                required.put(at > 0 ? s.substring(0, at) : s, at > 0 ? s.substring(at + 1) : "*");
            } else if (d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                String id = str(o, "id");
                if (id == null) {
                    continue;
                }
                int colon = id.indexOf(':');
                String modId = colon > 0 ? id.substring(colon + 1) : id; // "maven.group:modid"
                boolean opt = o.has("optional") && o.get("optional").isJsonPrimitive() && o.get("optional").getAsBoolean();
                (opt ? optional : required).put(modId, predicate(o.get("versions")));
            } else if (d.isJsonArray()) {
                // an array inside depends means "any of these"; record each as a soft requirement
                for (JsonElement alt : d.getAsJsonArray()) {
                    if (alt.isJsonObject() && str(alt.getAsJsonObject(), "id") != null) {
                        optional.put(str(alt.getAsJsonObject(), "id"), predicate(alt.getAsJsonObject().get("versions")));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ mods.toml

    @SuppressWarnings("unchecked") // TomlReader only creates String-keyed maps and Object lists
    static ModMetadata modsToml(String toml, boolean neoforgeFile, String jarVersion) {
        Map<String, Object> doc = TomlReader.parse(toml);
        Object modsObj = doc.get("mods");
        if (!(modsObj instanceof List<?> mods) || mods.isEmpty() || !(mods.get(0) instanceof Map<?, ?>)) {
            throw new IllegalStateException("no [[mods]]");
        }
        Map<String, Object> mod = (Map<String, Object>) mods.get(0);
        String id = tomlStr(mod, "modId");
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("no modId");
        }
        String version = tomlStr(mod, "version");
        if (version != null && version.contains("${file.jarVersion}")) {
            version = jarVersion == null ? null : version.replace("${file.jarVersion}", jarVersion);
        }
        Map<String, String> depends = new LinkedHashMap<>();
        Map<String, String> breaks = new LinkedHashMap<>();
        Map<String, String> optional = new LinkedHashMap<>();
        boolean mentionsNeoForge = false;
        boolean mentionsForge = false;
        Object depsObj = doc.get("dependencies");
        if (depsObj instanceof Map<?, ?> deps) {
            Object list = deps.get(id);
            if (list instanceof List<?> l) {
                for (Object d : l) {
                    if (!(d instanceof Map<?, ?> dm)) {
                        continue;
                    }
                    Map<String, Object> dep = (Map<String, Object>) dm;
                    String depId = tomlStr(dep, "modId");
                    if (depId == null) {
                        continue;
                    }
                    mentionsNeoForge |= depId.equals("neoforge");
                    mentionsForge |= depId.equals("forge");
                    String range = tomlStr(dep, "versionRange");
                    range = range == null || range.isBlank() ? "*" : range;
                    String type = tomlStr(dep, "type");
                    Object mandatory = dep.get("mandatory");
                    if (type != null) {
                        switch (type.toLowerCase(Locale.ROOT)) {
                            case "required" -> depends.put(depId, range);
                            case "incompatible" -> breaks.put(depId, range);
                            default -> optional.put(depId, range); // optional, discouraged
                        }
                    } else if (Boolean.FALSE.equals(mandatory)) {
                        optional.put(depId, range);
                    } else {
                        depends.put(depId, range);
                    }
                }
            }
        }
        LoaderType loader = neoforgeFile || (mentionsNeoForge && !mentionsForge) ? LoaderType.NEOFORGE : LoaderType.FORGE;
        String description = tomlStr(mod, "description");
        return new ModMetadata(id, tomlStr(mod, "displayName"), version, EnumSet.of(loader), null, depends, breaks,
            description == null ? null : description.strip(), 0, ContentKind.MOD, optional, Map.of(), Map.of(),
            tomlStr(mod, "logoFile") != null ? tomlStr(mod, "logoFile") : tomlStr(doc, "logoFile"), 0, 0);
    }

    private static String tomlStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : v instanceof Number || v instanceof Boolean ? String.valueOf(v) : null;
    }

    // ------------------------------------------------------------------ packs

    static ModMetadata pack(String json, String baseName, boolean dataOnly) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject pack = root.getAsJsonObject("pack");
        if (pack == null) {
            throw new IllegalStateException("no pack section");
        }
        int format = formatNumber(pack.get("pack_format"));
        int min = 0;
        int max = 0;
        JsonElement sf = pack.get("supported_formats");
        if (sf != null && !sf.isJsonNull()) {
            if (sf.isJsonPrimitive()) {
                min = max = formatNumber(sf);
            } else if (sf.isJsonArray() && sf.getAsJsonArray().size() >= 1) {
                JsonArray a = sf.getAsJsonArray();
                min = formatNumber(a.get(0));
                max = formatNumber(a.get(a.size() - 1));
            } else if (sf.isJsonObject()) {
                min = formatNumber(sf.getAsJsonObject().get("min_inclusive"));
                max = formatNumber(sf.getAsJsonObject().get("max_inclusive"));
            }
        }
        // 1.21.9+: min_format / max_format (an int or [major, minor])
        int minFormat = formatNumber(pack.get("min_format"));
        int maxFormat = formatNumber(pack.get("max_format"));
        if (minFormat > 0) {
            min = min == 0 ? minFormat : Math.min(min, minFormat);
        }
        if (maxFormat > 0) {
            max = Math.max(max, maxFormat);
        }
        if (format == 0) {
            format = max > 0 ? max : min;
        }
        if (min == 0) {
            min = format;
        }
        if (max == 0) {
            max = format;
        }
        String description = flatten(pack.get("description"), 0);
        return new ModMetadata(null, baseName, null, Set.of(), null, Map.of(), Map.of(), description, format,
            dataOnly ? ContentKind.DATAPACK : ContentKind.RESOURCE_PACK, Map.of(), Map.of(), Map.of(),
            null, min, max);
    }

    private static ModMetadata shader(String baseName) {
        return new ModMetadata(null, baseName, null, Set.of(), null, Map.of(), Map.of(), "", 0, ContentKind.SHADER_PACK);
    }

    /** a pack format: a number, or [major, minor] (the major part) */
    private static int formatNumber(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return 0;
        }
        if (e.isJsonArray()) {
            return e.getAsJsonArray().isEmpty() ? 0 : formatNumber(e.getAsJsonArray().get(0));
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            return d < 0 || d > Integer.MAX_VALUE ? 0 : (int) d;
        }
        if (e.isJsonPrimitive()) {
            try {
                return Math.max(0, (int) Double.parseDouble(e.getAsString().trim()));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return 0;
    }

    /** a text component (string, object with text/extra/translate, or array) as plain text */
    static String flatten(JsonElement e, int depth) {
        if (e == null || e.isJsonNull() || depth > MAX_TEXT_DEPTH) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            return stripFormatting(p.getAsString());
        }
        StringBuilder b = new StringBuilder();
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                b.append(flatten(x, depth + 1));
            }
            return b.toString();
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("text")) {
            b.append(flatten(o.get("text"), depth + 1));
        } else if (o.has("translate")) {
            String fallback = str(o, "fallback");
            b.append(fallback != null ? fallback : str(o, "translate"));
        }
        if (o.has("extra") && o.get("extra").isJsonArray()) {
            b.append(flatten(o.get("extra"), depth + 1));
        }
        return b.toString();
    }

    /** remove legacy section-sign colour codes */
    private static String stripFormatting(String s) {
        return s.replaceAll("§.", "");
    }

    private static String stripExtension(String name) {
        String n = name;
        if (n.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
            n = n.substring(0, n.length() - ".disabled".length());
        }
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
