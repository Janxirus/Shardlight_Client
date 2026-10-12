package dev.shardlight.client.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds mod jars, packs and other zips for tests. */
public final class TestJars {
    private TestJars() {
    }

    /** a zip with these entries (String or byte[] values; a name ending in / is a folder) */
    public static byte[] zipBytes(Map<String, ?> entries) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zo = new ZipOutputStream(bos)) {
                for (Map.Entry<String, ?> e : entries.entrySet()) {
                    zo.putNextEntry(new ZipEntry(e.getKey()));
                    Object v = e.getValue();
                    if (v instanceof byte[] b) {
                        zo.write(b);
                    } else if (v != null) {
                        zo.write(v.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    zo.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path zip(Path file, Map<String, ?> entries) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream os = Files.newOutputStream(file)) {
            os.write(zipBytes(entries));
        }
        return file;
    }

    /** fabric.mod.json for a mod */
    public static String fabricJson(String id, String version, String minecraft, String environment) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"name\":\"" + id.toUpperCase()
            + " Mod\",\"environment\":\"" + environment + "\",\"depends\":{\"fabricloader\":\">=0.15\",\"minecraft\":\""
            + minecraft + "\"}}";
    }

    /** a Fabric mod jar's bytes */
    public static byte[] fabricMod(String id, String version) {
        return fabricMod(id, version, "*", "*");
    }

    public static byte[] fabricMod(String id, String version, String minecraft, String environment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fabric.mod.json", fabricJson(id, version, minecraft, environment));
        m.put("dev/example/" + id + "/Main.class", new byte[] {(byte) 0xCA, (byte) 0xFE, 1, 2, 3});
        return zipBytes(m);
    }

    /** a Forge mod jar's bytes (META-INF/mods.toml) */
    public static byte[] forgeMod(String id, String version) {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
            + "\"\nversion=\"" + version + "\"\ndisplayName=\"" + id + "\"\n[[dependencies." + id + "]]\nmodId=\"forge\"\n"
            + "mandatory=true\nversionRange=\"[47,)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        return zipBytes(Map.of("META-INF/mods.toml", toml));
    }

    /** a resource pack zip's bytes */
    public static byte[] resourcePack(int format, String description) {
        return zipBytes(Map.of("pack.mcmeta", "{\"pack\":{\"pack_format\":" + format + ",\"description\":\"" + description + "\"}}",
            "assets/minecraft/textures/block/stone.png", new byte[] {1, 2, 3}));
    }

    /** a shader pack zip's bytes */
    public static byte[] shaderPack() {
        return zipBytes(Map.of("shaders/composite.fsh", "void main() {}", "shaders/shaders.properties", "x=1"));
    }
}
