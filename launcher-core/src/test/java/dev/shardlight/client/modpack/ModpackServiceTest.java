package dev.shardlight.client.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.catalog.CatalogRegistry;
import dev.shardlight.client.catalog.CurseForgeCatalog;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentService;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.testutil.TestProjects;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Importing and exporting .mrpack and CurseForge modpacks against local servers. */
class ModpackServiceTest {
    static final String MR_PROJECT = "AANobbMI";
    static final String MR_VERSION = "abcd1234";

    @TempDir
    Path dir;
    AppPaths paths;
    TestProjects projects;
    TestHttpServer server;
    DownloadManager downloads;
    ContentService content;
    final List<String> discarded = new ArrayList<>();
    int created;

    ProjectFactory factory = new ProjectFactory() {
        @Override
        public Project create(String name, String minecraftVersion, LoaderSpec loader) throws LauncherException {
            created++;
            Project p = projects.create("pack" + created, minecraftVersion, loader.type, loader.version);
            p.name = name;
            return projects.save(p);
        }

        @Override
        public void discard(Project project) {
            discarded.add(project.id);
        }
    };

    @BeforeEach
    void setUp() {
        paths = new AppPaths(dir.resolve("data"));
        projects = new TestProjects(paths);
        server = new TestHttpServer();
        downloads = new DownloadManager(HttpService.forTests(), paths.files(), 4, 1);
        downloads.setBackoffMillis(5);
        content = new ContentService(paths, projects, downloads);
    }

    @AfterEach
    void tearDown() {
        downloads.close();
        server.close();
    }

    ModpackService service(CatalogRegistry catalogs) {
        return new ModpackService(paths, projects, factory, content, downloads, catalogs, Set.of("127.0.0.1"));
    }

    // ---------------------------------------------------------------- files

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0); // a fixed time keeps the bytes, and their hash, the same on every call
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static Map<String, byte[]> unzip(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] mod(String id, String environment) throws IOException {
        return zip(Map.of("fabric.mod.json", utf8("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"name\":\"" + id
            + "\",\"environment\":\"" + environment + "\"}")));
    }

    /** a modrinth.index.json file entry for bytes served at {@code path} on the test server */
    JsonObject fileEntry(String packPath, String serverPath, byte[] data, String client) {
        server.bytes(serverPath, data);
        JsonObject f = new JsonObject();
        f.addProperty("path", packPath);
        JsonObject h = new JsonObject();
        h.addProperty("sha1", Hashing.hash(data, HashAlgo.SHA1));
        h.addProperty("sha512", Hashing.hash(data, HashAlgo.SHA512));
        f.add("hashes", h);
        JsonObject env = new JsonObject();
        env.addProperty("client", client);
        env.addProperty("server", "required");
        f.add("env", env);
        JsonArray dl = new JsonArray();
        dl.add(server.uri(serverPath).toString());
        f.add("downloads", dl);
        f.addProperty("fileSize", data.length);
        return f;
    }

    Path mrpack(String name, List<JsonObject> files, Map<String, byte[]> extra) throws IOException {
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", "2.0.0");
        index.addProperty("name", "Speedy Pack");
        index.addProperty("summary", "Fast and light");
        JsonArray arr = new JsonArray();
        files.forEach(arr::add);
        index.add("files", arr);
        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", "26.3");
        deps.addProperty("fabric-loader", "0.19.5");
        index.add("dependencies", deps);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ModpackService.MRPACK_INDEX, utf8(index.toString()));
        entries.putAll(extra);
        Path f = dir.resolve("in").resolve(name);
        Files.createDirectories(f.getParent());
        return Files.write(f, zip(entries));
    }

    Path goodPack() throws IOException {
        return mrpack("speedy.mrpack", List.of(
            fileEntry("mods/sodium.jar", "/data/" + MR_PROJECT + "/versions/" + MR_VERSION + "/sodium.jar", mod("sodium", "client"), "required"),
            fileEntry("mods/lib.jar", "/files/lib.jar", mod("lib", "*"), "required"),
            fileEntry("mods/server-only.jar", "/files/server-only.jar", mod("serveronly", "server"), "unsupported")),
            Map.of("overrides/config/sodium-options.json", utf8("{\"quality\":\"fast\"}"),
                "client-overrides/options.txt", utf8("renderDistance:8\n"),
                "overrides/config/shared.txt", utf8("from overrides"),
                "client-overrides/config/shared.txt", utf8("from client-overrides")));
    }

    // ---------------------------------------------------------------- mrpack

    @Test
    void inspectReadsTheManifestWithoutImporting() throws Exception {
        ModpackInfo info = service(null).inspect(goodPack());
        assertEquals(ModpackInfo.MRPACK, info.format());
        assertEquals("Speedy Pack", info.name());
        assertEquals("2.0.0", info.version());
        assertEquals("26.3", info.minecraftVersion());
        assertEquals(LoaderType.FABRIC, info.loader().type);
        assertEquals("0.19.5", info.loader().version);
        assertEquals(2, info.fileCount(), "server-only files aren't counted");
        assertTrue(info.problems().isEmpty(), info.problems().toString());
        assertEquals(0, created);
    }

    @Test
    void importingAnMrpackDownloadsVerifiesAndAppliesOverrides() throws Exception {
        ImportReport r = service(null).importMrpackDetailed(goodPack(), null, null, CancellationToken.NONE);
        Project p = r.project();
        Path game = projects.gameDir(p);
        assertEquals("Speedy Pack", p.name);
        assertEquals(LoaderType.FABRIC, p.loader.type);
        assertTrue(Files.isRegularFile(game.resolve("mods/sodium.jar")));
        assertTrue(Files.isRegularFile(game.resolve("mods/lib.jar")));
        assertFalse(Files.exists(game.resolve("mods/server-only.jar")));
        assertEquals(1, r.warnings().size(), "the server-only file is mentioned");
        assertEquals("{\"quality\":\"fast\"}", Files.readString(game.resolve("config/sodium-options.json")));
        assertEquals("from client-overrides", Files.readString(game.resolve("config/shared.txt")), "client overrides win");
        assertEquals("renderDistance:8\n", Files.readString(game.resolve("options.txt")));

        ContentEntry sodium = p.content.stream().filter(e -> "sodium.jar".equals(e.fileName)).findFirst().orElseThrow();
        assertEquals(ContentSource.MODRINTH, sodium.source, "a Modrinth CDN link tells the project and version");
        assertEquals(MR_PROJECT, sodium.projectId);
        assertEquals(MR_VERSION, sodium.versionId);
        ContentEntry lib = p.content.stream().filter(e -> "lib.jar".equals(e.fileName)).findFirst().orElseThrow();
        assertEquals(ContentSource.MODPACK, lib.source);
        assertTrue(discarded.isEmpty());
    }

    @Test
    void unsafeOrUntrustedPacksAreRefusedBeforeAProjectIsMade() throws Exception {
        Path escape = mrpack("escape.mrpack", List.of(fileEntry("../../evil.jar", "/files/evil.jar", mod("evil", "*"), "required")), Map.of());
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> service(null).importMrpack(escape, null, null, null)).code());

        JsonObject foreign = fileEntry("mods/x.jar", "/files/x.jar", mod("x", "*"), "required");
        foreign.getAsJsonArray("downloads").set(0, new com.google.gson.JsonPrimitive("https://evil.example.com/x.jar"));
        Path untrusted = mrpack("untrusted.mrpack", List.of(foreign), Map.of());
        assertEquals("modpack.error.noAllowedUrl", assertThrows(LauncherException.class,
            () -> service(null).importMrpack(untrusted, null, null, null)).messageKey());
        assertFalse(service(null).inspect(untrusted).problems().isEmpty());

        JsonObject a = fileEntry("mods/a.jar", "/files/a.jar", mod("a", "*"), "required");
        JsonObject b = fileEntry("MODS/A.jar", "/files/a.jar", mod("a", "*"), "required");
        Path duplicate = mrpack("dup.mrpack", List.of(a, b), Map.of());
        assertEquals("modpack.error.duplicatePath", assertThrows(LauncherException.class,
            () -> service(null).importMrpack(duplicate, null, null, null)).messageKey());

        assertEquals(0, created, "nothing was created for packs that fail the checks");
        assertEquals(ErrorCode.INVALID_ARCHIVE, assertThrows(LauncherException.class,
            () -> service(null).inspect(Files.write(dir.resolve("plain.zip"), zip(Map.of("readme.txt", utf8("hi")))))).code());
    }

    @Test
    void aTamperedDownloadDiscardsTheHalfMadeProject() throws Exception {
        Path pack = goodPack();
        server.bytes("/files/lib.jar", mod("lib-evil", "*"));
        assertThrows(LauncherException.class, () -> service(null).importMrpack(pack, "Custom name", null, null));
        assertEquals(1, created);
        assertEquals(List.of("pack1"), discarded);
    }

    @Test
    void overridesThatEscapeAreRefused() throws Exception {
        Path pack = mrpack("slip.mrpack", List.of(), Map.of("overrides/../../outside.txt", utf8("x")));
        assertThrows(LauncherException.class, () -> service(null).importMrpack(pack, null, null, null));
        assertFalse(Files.exists(dir.resolve("outside.txt")));
        assertFalse(Files.exists(paths.root().resolve("outside.txt")));
    }

    @Test
    void anExportedPackCanBeImportedAgain() throws Exception {
        ModpackService modpacks = service(null);
        Project p = modpacks.importMrpack(goodPack(), null, null, CancellationToken.NONE);
        Path game = projects.gameDir(p);
        Files.writeString(game.resolve("mods/handmade.jar"), "not from Modrinth");
        Files.writeString(game.resolve("mods/disabled.jar.disabled"), "off");

        Path out = dir.resolve("out/export.mrpack");
        ExportReport report = modpacks.exportMrpackDetailed(p, new MrpackExportOptions("Exported", "3.1.0", "summary", true, false,
            false, false), out);
        Map<String, byte[]> zip = unzip(out);
        JsonObject index = JsonParser.parseString(new String(zip.get(ModpackService.MRPACK_INDEX), StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertEquals("Exported", index.get("name").getAsString());
        assertEquals("0.19.5", index.getAsJsonObject("dependencies").get("fabric-loader").getAsString());
        List<String> paths = new ArrayList<>();
        index.getAsJsonArray("files").forEach(f -> paths.add(f.getAsJsonObject().get("path").getAsString()));
        assertEquals(Set.of("mods/sodium.jar", "mods/lib.jar"), Set.copyOf(paths));
        JsonObject sodium = index.getAsJsonArray("files").asList().stream().map(f -> f.getAsJsonObject())
            .filter(f -> f.get("path").getAsString().equals("mods/sodium.jar")).findFirst().orElseThrow();
        assertEquals("unsupported", sodium.getAsJsonObject("env").get("server").getAsString(), "a client-only mod says so");
        assertTrue(zip.containsKey("overrides/config/sodium-options.json"));
        assertFalse(zip.containsKey("overrides/mods/handmade.jar"), "local files need the user's permission");
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("handmade")), report.warnings().toString());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("disabled")), report.warnings().toString());

        Project again = modpacks.importMrpack(out, null, null, CancellationToken.NONE);
        assertTrue(Files.isRegularFile(projects.gameDir(again).resolve("mods/sodium.jar")));
        assertEquals("{\"quality\":\"fast\"}", Files.readString(projects.gameDir(again).resolve("config/sodium-options.json")));

        Path withLocal = dir.resolve("out/with-local.mrpack");
        modpacks.exportMrpack(p, new MrpackExportOptions("Exported", "3.1.0", "", false, false, false, true), withLocal);
        assertTrue(unzip(withLocal).containsKey("overrides/mods/handmade.jar"), "included once the user confirms");
    }

    // ---------------------------------------------------------------- CurseForge

    Path curseForgePack() throws IOException {
        String manifest = """
            {"minecraft":{"version":"26.3","modLoaders":[{"id":"fabric-0.19.5","primary":true}]},
             "manifestType":"minecraftModpack","manifestVersion":1,"name":"Curse Pack","version":"1.2","author":"someone",
             "files":[{"projectID":100,"fileID":1001,"required":true},{"projectID":200,"fileID":2001,"required":true},
                      {"projectID":300,"fileID":3001,"required":false}],
             "overrides":"overrides"}
            """;
        Path f = dir.resolve("in/curse.zip");
        Files.createDirectories(f.getParent());
        return Files.write(f, zip(Map.of("manifest.json", utf8(manifest), "overrides/config/a.txt", utf8("cf override"))));
    }

    @Test
    void curseForgePacksNeedTheCatalog() throws Exception {
        Path pack = curseForgePack();
        ModpackInfo info = service(null).inspect(pack);
        assertEquals(ModpackInfo.CURSEFORGE, info.format());
        assertEquals(2, info.fileCount(), "required files only");
        assertFalse(info.problems().isEmpty(), "says that CurseForge isn't set up");
        assertEquals(ErrorCode.API_KEY_MISSING, assertThrows(LauncherException.class,
            () -> service(null).importCurseForge(pack, null, null, null)).code());
        assertEquals(0, created);
    }

    @Test
    void curseForgeImportsReportFilesTheLauncherMayNotDownload() throws Exception {
        byte[] open = mod("openmod", "*");
        server.bytes("/cdn/open-1.0.jar", open);
        server.json("/curseforge/v1/mods/files", "{\"data\":["
            + "{\"id\":1001,\"modId\":100,\"displayName\":\"Open 1.0\",\"fileName\":\"open-1.0.jar\",\"fileLength\":" + open.length
            + ",\"downloadUrl\":\"" + server.uri("/cdn/open-1.0.jar") + "\",\"gameVersions\":[\"26.3\",\"Fabric\"],"
            + "\"hashes\":[{\"algo\":1,\"value\":\"" + Hashing.hash(open, HashAlgo.SHA1) + "\"}]},"
            + "{\"id\":2001,\"modId\":200,\"displayName\":\"Closed 2.0\",\"fileName\":\"closed-2.0.jar\",\"fileLength\":10,"
            + "\"downloadUrl\":null,\"gameVersions\":[\"26.3\"],\"hashes\":[]}]}");
        server.json("/curseforge/v1/mods", "{\"data\":["
            + "{\"id\":100,\"name\":\"Open\",\"classId\":6,\"allowModDistribution\":true,\"links\":{\"websiteUrl\":\"https://www.curseforge.com/minecraft/mc-mods/open\"}},"
            + "{\"id\":200,\"name\":\"Closed\",\"classId\":6,\"allowModDistribution\":false,\"links\":{\"websiteUrl\":\"https://www.curseforge.com/minecraft/mc-mods/closed\"}}]}");
        CurseForgeCatalog cf = new CurseForgeCatalog(HttpService.forTests(), Endpoints.local(server.base()),
            () -> Optional.of("test-key-for-curseforge-0123456789"));

        ImportReport r = service(new CatalogRegistry(cf)).importCurseForgeDetailed(curseForgePack(), null, null, CancellationToken.NONE);

        Path game = projects.gameDir(r.project());
        assertTrue(Files.isRegularFile(game.resolve("mods/open-1.0.jar")));
        assertFalse(Files.exists(game.resolve("mods/closed-2.0.jar")));
        assertEquals(1, r.restricted().size());
        RestrictedFile closed = r.restricted().get(0);
        assertEquals("Closed", closed.title());
        assertEquals("closed-2.0.jar", closed.fileName());
        assertEquals("https://www.curseforge.com/minecraft/mc-mods/closed", String.valueOf(closed.pageUrl()));
        assertEquals("mods", closed.folder());
        assertEquals(1, r.warnings().size(), "the optional file is mentioned");
        assertEquals("cf override", Files.readString(game.resolve("config/a.txt")));
        ContentEntry e = r.project().content.stream().filter(x -> "open-1.0.jar".equals(x.fileName)).findFirst().orElseThrow();
        assertEquals(ContentSource.CURSEFORGE, e.source);
        assertEquals("100", e.projectId);
        assertEquals("1001", e.versionId);

        Path out = dir.resolve("out/curse-export.zip");
        ExportReport export = service(null).exportCurseForgeDetailed(r.project(), "Again", "1.3", out);
        JsonObject manifest = JsonParser.parseString(new String(unzip(out).get("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, manifest.getAsJsonArray("files").size());
        assertEquals(1001, manifest.getAsJsonArray("files").get(0).getAsJsonObject().get("fileID").getAsLong());
        assertEquals("fabric-0.19.5", manifest.getAsJsonObject("minecraft").getAsJsonArray("modLoaders").get(0).getAsJsonObject()
            .get("id").getAsString());
        assertTrue(unzip(out).containsKey("modlist.html"));
        assertTrue(export.warnings().isEmpty(), export.warnings().toString());

        Path mrOut = dir.resolve("out/from-curse.mrpack");
        ExportReport mr = service(null).exportMrpackDetailed(r.project(), new MrpackExportOptions("x", "1", "", false, false, false, true),
            mrOut);
        assertFalse(unzip(mrOut).containsKey("overrides/mods/open-1.0.jar"), "CurseForge files are never redistributed");
        assertTrue(mr.warnings().stream().anyMatch(w -> w.contains("Open")), mr.warnings().toString());
    }

    @Test
    void theCreatorNeedsATitleAndAPngIcon() throws Exception {
        Project p = factory.create("Source", "26.3", new LoaderSpec(LoaderType.FABRIC, "0.19.5"));
        ModpackService modpacks = service(null);
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> modpacks.createModpack(p,
            new ModpackCreatorOptions(ModpackCreatorOptions.Format.MRPACK, " ", "1", "", null, dir.resolve("x.mrpack"),
                false, false, false, false))).code());
        Path notPng = Files.writeString(dir.resolve("icon.png"), "not a png");
        assertThrows(LauncherException.class, () -> modpacks.createModpack(p, new ModpackCreatorOptions(
            ModpackCreatorOptions.Format.MRPACK, "Title", "1", "", notPng, dir.resolve("x.mrpack"), false, false, false, false)));

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1,
            8, 6, 0, 0, 0, 0x1f, 0x15, (byte) 0xc4, (byte) 0x89};
        Path icon = Files.write(dir.resolve("real.png"), png);
        ExportReport r = modpacks.createModpackDetailed(p, new ModpackCreatorOptions(ModpackCreatorOptions.Format.CURSEFORGE, "Title",
            "1", "", icon, dir.resolve("made.zip"), false, false, false, false));
        assertTrue(unzip(r.file()).containsKey("overrides/icon.png"));
        assertTrue(r.warnings().stream().anyMatch(w -> !w.isBlank()), "the report says where the icon went");
    }
}
