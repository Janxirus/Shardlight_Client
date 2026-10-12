package dev.shardlight.client.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.minecraft.model.VersionJson;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serves realistic Mojang/Fabric metadata from a {@link TestHttpServer}. Version JSON templates (real field
 * names and URLs) are loaded from test resources; every URL is rewritten to the test server, every file a
 * template references is generated, served, and its SHA-1 and size are written back into the JSON, just
 * like the real services publish them.
 */
public final class MinecraftFixtures {
    /** real hosts and the path prefix they get on the test server */
    private static final Map<String, String> HOSTS = new LinkedHashMap<>();

    static {
        HOSTS.put("https://piston-meta.mojang.com/", "piston-meta/");
        HOSTS.put("https://piston-data.mojang.com/", "piston-data/");
        HOSTS.put("https://launchermeta.mojang.com/", "launchermeta/");
        HOSTS.put("https://launcher.mojang.com/", "launcher/");
        HOSTS.put("https://libraries.minecraft.net/", "libraries/");
        HOSTS.put("https://maven.fabricmc.net/", "fabric-maven/");
        HOSTS.put("https://resources.download.minecraft.net/", "resources/");
    }

    public final TestHttpServer server;
    public final Endpoints endpoints;
    /** served bytes by server path */
    public final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<JsonObject> manifestEntries = new ArrayList<>();
    private String latestRelease;
    private String latestSnapshot;
    /** objects per generated asset index */
    public int assetObjects = 24;

    public MinecraftFixtures(TestHttpServer server) {
        this.server = server;
        this.endpoints = Endpoints.local(server.base());
    }

    /** a template from test resources, untouched */
    public static String template(String name) {
        try (InputStream in = MinecraftFixtures.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** rewrite real hosts to the test server */
    public String rewrite(String text) {
        String out = text;
        for (Map.Entry<String, String> e : HOSTS.entrySet()) {
            out = out.replace(e.getKey(), server.base() + e.getValue());
        }
        return out;
    }

    /** load a template, serve every file it references and return the JSON with correct hashes and sizes */
    public String prepare(String templateName) {
        return prepareJson(template(templateName));
    }

    public String prepareJson(String json) {
        JsonObject o = JsonParser.parseString(rewrite(json)).getAsJsonObject();
        if (o.has("downloads")) {
            for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("downloads").entrySet()) {
                JsonObject info = d.getValue().getAsJsonObject();
                byte[] data = d.getKey().equals("client") ? clientJar(o.get("id").getAsString())
                    : bytes(d.getKey(), 2000);
                serve(info, data);
            }
        }
        if (o.has("libraries")) {
            for (JsonElement le : o.getAsJsonArray("libraries")) {
                JsonObject lib = le.getAsJsonObject();
                String name = lib.get("name").getAsString();
                if (lib.has("downloads")) {
                    JsonObject dl = lib.getAsJsonObject("downloads");
                    if (dl.has("artifact")) {
                        serve(dl.getAsJsonObject("artifact"), name.contains(":natives-")
                            ? nativesJar(name, true) : libraryJar(name));
                    }
                    if (dl.has("classifiers")) {
                        for (Map.Entry<String, JsonElement> c : dl.getAsJsonObject("classifiers").entrySet()) {
                            serve(c.getValue().getAsJsonObject(), nativesJar(name + ":" + c.getKey(), false));
                        }
                    }
                } else if (lib.has("url")) {
                    String path = mavenPath(name);
                    String base = lib.get("url").getAsString();
                    byte[] data = libraryJar(name);
                    serveAt(URI.create(base + path).getPath(), data);
                    if (lib.has("sha1")) {
                        lib.addProperty("sha1", Hashing.hash(data, HashAlgo.SHA1));
                    }
                    if (lib.has("sha256")) {
                        lib.addProperty("sha256", Hashing.hash(data, HashAlgo.SHA256));
                    }
                    if (lib.has("sha512")) {
                        lib.addProperty("sha512", Hashing.hash(data, HashAlgo.SHA512));
                    }
                    if (lib.has("md5")) {
                        lib.addProperty("md5", Hashing.hash(data, HashAlgo.MD5));
                    }
                    if (lib.has("size")) {
                        lib.addProperty("size", data.length);
                    }
                }
            }
        }
        if (o.has("assetIndex")) {
            JsonObject ai = o.getAsJsonObject("assetIndex");
            byte[] index = assetIndex(ai.get("id").getAsString(), false, false);
            serve(ai, index);
        }
        if (o.has("logging")) {
            JsonObject file = o.getAsJsonObject("logging").getAsJsonObject("client").getAsJsonObject("file");
            serve(file, ("<Configuration status=\"WARN\"><!-- " + file.get("id").getAsString() + " --></Configuration>")
                .getBytes(StandardCharsets.UTF_8));
        }
        return Json.write(o);
    }

    /** serve {@code data} at the info's url and write its sha1 and size into the info */
    public void serve(JsonObject info, byte[] data) {
        String url = info.get("url").getAsString();
        if (!url.isEmpty()) {
            serveAt(URI.create(url).getPath(), data);
        }
        info.addProperty("sha1", Hashing.hash(data, HashAlgo.SHA1));
        info.addProperty("size", data.length);
        if (info.has("totalSize")) {
            info.addProperty("totalSize", data.length * 10L);
        }
    }

    public void serveAt(String path, byte[] data) {
        files.put(path, data);
        server.bytes(path, data);
    }

    /**
     * Generate an asset index with {@link #assetObjects} objects (two paths share one object, like real
     * indexes) and serve every object under {@code resources/<ab>/<hash>}.
     */
    public byte[] assetIndex(String id, boolean virtual, boolean mapToResources) {
        JsonObject objects = new JsonObject();
        String shared = null;
        for (int i = 0; i < assetObjects; i++) {
            byte[] data = bytes("asset-" + id + "-" + i, 100 + i);
            String h = Hashing.hash(data, HashAlgo.SHA1);
            serveAt(URI.create(endpoints.resources() + h.substring(0, 2) + "/" + h).getPath(), data);
            JsonObject obj = new JsonObject();
            obj.addProperty("hash", h);
            obj.addProperty("size", data.length);
            String path = i % 3 == 0 ? "minecraft/sounds/ambient/cave/cave" + i + ".ogg"
                : i % 3 == 1 ? "minecraft/lang/lang_" + i + ".json" : "icons/icon_" + i + ".png";
            objects.add(path, obj);
            if (shared == null) {
                shared = path;
            }
        }
        if (shared != null) {
            objects.add("minecraft/sounds/duplicate.ogg", objects.get(shared).deepCopy());
        }
        JsonObject index = new JsonObject();
        if (virtual) {
            index.addProperty("virtual", true);
        }
        if (mapToResources) {
            index.addProperty("map_to_resources", true);
        }
        index.add("objects", objects);
        return Json.COMPACT.toJson(index).getBytes(StandardCharsets.UTF_8);
    }

    /** list a version in the manifest and serve its JSON at its piston-meta URL */
    public VersionSummaryJson publish(String id, String type, String json) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        String sha1 = Hashing.hash(data, HashAlgo.SHA1);
        String path = "/piston-meta/v1/packages/" + sha1 + "/" + id + ".json";
        serveAt(path, data);
        JsonObject e = new JsonObject();
        e.addProperty("id", id);
        e.addProperty("type", type);
        e.addProperty("url", server.base() + path.substring(1));
        e.addProperty("time", "2024-12-03T10:12:57+00:00");
        e.addProperty("releaseTime", "2024-12-03T10:12:57+00:00");
        e.addProperty("sha1", sha1);
        e.addProperty("complianceLevel", 1);
        synchronized (manifestEntries) {
            manifestEntries.removeIf(x -> x.get("id").getAsString().equals(id));
            manifestEntries.add(0, e);
            if (type.equals("release") && latestRelease == null) {
                latestRelease = id;
            }
            if (type.equals("snapshot") && latestSnapshot == null) {
                latestSnapshot = id;
            }
        }
        serveManifest();
        return new VersionSummaryJson(id, sha1, path);
    }

    /** what {@link #publish} served */
    public record VersionSummaryJson(String id, String sha1, String path) {
    }

    /** serve version_manifest_v2.json with every published version */
    public String serveManifest() {
        JsonObject m = new JsonObject();
        JsonObject latest = new JsonObject();
        latest.addProperty("release", latestRelease == null ? "1.21.4" : latestRelease);
        latest.addProperty("snapshot", latestSnapshot == null ? (latestRelease == null ? "1.21.4" : latestRelease) : latestSnapshot);
        m.add("latest", latest);
        JsonArray arr = new JsonArray();
        synchronized (manifestEntries) {
            manifestEntries.forEach(arr::add);
        }
        m.add("versions", arr);
        String text = Json.write(m);
        server.json(URI.create(endpoints.versionManifest().toString()).getPath(), text);
        return text;
    }

    /** prepare a template and publish it in the manifest; returns the served JSON */
    public String publishTemplate(String templateName, String type) {
        String json = prepare(templateName);
        publish(VersionJson.parse(json).id(), type, json);
        return json;
    }

    /** write a loader profile into {@code versions/<id>/<id>.json} (what the loader step does) */
    public static void writeVersion(Path versionsDir, String id, String json) throws IOException {
        Path dir = versionsDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    // ---------------------------------------------------------------- generated content

    static String mavenPath(String name) {
        try {
            return MavenCoordinate.parse(name).path();
        } catch (dev.shardlight.client.error.LauncherException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** deterministic pseudo-random bytes */
    public static byte[] bytes(String seed, int size) {
        byte[] b = new byte[size];
        new Random(seed.hashCode()).nextBytes(b);
        return b;
    }

    public static byte[] clientJar(String id) {
        return zip(Map.of("net/minecraft/client/main/Main.class", bytes("main-" + id, 300),
            "version.json", ("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] libraryJar(String name) {
        return zip(Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8),
            name.replace(':', '_').replace('.', '_') + ".class", bytes(name, 200)));
    }

    /** a natives jar: a native file plus META-INF (modern jars nest natives in os/arch folders) */
    public static byte[] nativesJar(String nameAndClassifier, boolean modern) {
        String[] parts = nameAndClassifier.split(":");
        String artifact = parts[1];
        String classifier = parts[parts.length - 1];
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        String file = modern ? classifier.replace("natives-", "") + "/" + artifact + ".native" : artifact + "-" + classifier + ".native";
        entries.put(file, bytes(nameAndClassifier, 400));
        return zip(entries);
    }

    public static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : new java.util.TreeMap<>(entries).entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0);
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }
}
