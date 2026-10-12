package dev.shardlight.client.content;

import dev.shardlight.client.catalog.CatalogDependency;
import dev.shardlight.client.catalog.CatalogFile;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.DependencyType;
import dev.shardlight.client.catalog.InstallPlan;
import dev.shardlight.client.catalog.PlanIssue;
import dev.shardlight.client.catalog.PlannedFile;
import dev.shardlight.client.catalog.Severity;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.OptionsTxt;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.testutil.TestProjects;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentServiceTest {
    @TempDir
    Path dir;
    AppPaths paths;
    TestProjects projects;
    TestHttpServer server;
    DownloadManager dm;
    ContentService svc;
    Project p;
    Path game;

    @BeforeEach
    void setUp() throws Exception {
        paths = new AppPaths(dir.resolve("data"));
        projects = new TestProjects(paths);
        server = new TestHttpServer();
        dm = new DownloadManager(HttpService.forTests(), paths.files(), 4, 1);
        dm.setBackoffMillis(5);
        svc = new ContentService(paths, projects, dm);
        p = projects.create("demo", "1.21.1", LoaderType.FABRIC, "0.16.5");
        game = projects.gameDir(p);
    }

    @AfterEach
    void tearDown() {
        dm.close();
        server.close();
    }

    // ------------------------------------------------------------------ helpers

    private Path write(String rel, byte[] data) throws IOException {
        Path f = game.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
        return f;
    }

    private Path source(String name, byte[] data) throws IOException {
        Path f = dir.resolve("src").resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
        return f;
    }

    private static String sha1(byte[] b) {
        return Hashing.hash(b, HashAlgo.SHA1);
    }

    private static String sha512(byte[] b) {
        return Hashing.hash(b, HashAlgo.SHA512);
    }

    private ContentEntry entry(String fileName) {
        return projects.get(p.id).orElseThrow().content.stream().filter(e -> fileName.equals(e.fileName)).findFirst()
            .orElseThrow(() -> new AssertionError("no entry " + fileName));
    }

    private List<ContentEntry> stored() {
        return projects.get(p.id).orElseThrow().content;
    }

    private List<String> packs() throws LauncherException {
        return OptionsTxt.read(game.resolve("options.txt")).getList("resourcePacks");
    }

    private List<String> incompatible() throws LauncherException {
        return OptionsTxt.read(game.resolve("options.txt")).getList("incompatibleResourcePacks");
    }

    private void options(String text) throws IOException {
        Files.writeString(game.resolve("options.txt"), text);
    }

    private CatalogVersion version(String projectId, String versionId, String fileName, byte[] data,
                                   List<CatalogDependency> deps) {
        String path = "/files/" + versionId + "/" + fileName;
        server.bytes(path, data);
        CatalogFile f = new CatalogFile(server.uri(path), fileName, data.length, sha1(data), sha512(data), true, false);
        return new CatalogVersion("modrinth", versionId, projectId, versionId, fileName, null, List.of("1.21.1"),
            List.of("fabric"), "release", Instant.parse("2026-01-01T00:00:00Z"), List.of(f), deps);
    }

    private static PlannedFile planned(String title, CatalogVersion v, ContentKind kind, boolean dep, String requiredBy) {
        return new PlannedFile(title, v, v.primaryFile().orElseThrow(), kind, dep, requiredBy,
            URI.create("https://modrinth.com/mod/" + v.projectId()));
    }

    private static InstallPlan plan(PlannedFile... files) {
        return new InstallPlan(List.of(files), List.of(), List.of());
    }

    private List<ContentEntry> install(InstallPlan plan) throws LauncherException {
        return svc.install(p, plan, ProgressListener.NONE, CancellationToken.NONE);
    }

    private static LauncherException fails(ErrorCode code, Executable ex) {
        LauncherException e = assertThrows(LauncherException.class, ex::run);
        assertEquals(code, e.code(), () -> "unexpected " + e);
        return e;
    }

    @FunctionalInterface
    interface Executable {
        void run() throws Exception;
    }

    private List<String> listNames(String folder) throws IOException {
        try (Stream<Path> s = Files.list(game.resolve(folder))) {
            return s.map(x -> x.getFileName().toString()).sorted().toList();
        }
    }

    // ------------------------------------------------------------------ scan

    @Test
    void scanRecordsUnknownFilesAndIgnoresJunk() throws Exception {
        byte[] alpha = TestJars.fabricMod("alpha", "1.0.0");
        write("mods/alpha.jar", alpha);
        write("mods/beta.jar.disabled", TestJars.fabricMod("beta", "2.0.0"));
        write("mods/readme.txt", "hi".getBytes(StandardCharsets.UTF_8));
        write("mods/gamma.jar.part", TestJars.fabricMod("gamma", "1"));
        write("mods/.hidden.jar", TestJars.fabricMod("hidden", "1"));
        write("mods/delta.jar.tmp", TestJars.fabricMod("delta", "1"));
        write("resourcepacks/packA.zip", TestJars.resourcePack(34, "A"));
        write("resourcepacks/folder/pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"F\"}}".getBytes(StandardCharsets.UTF_8));
        write("resourcepacks/notapack/readme.txt", new byte[] {1});
        write("shaderpacks/Shade.zip", TestJars.shaderPack());
        options("fov:0.5\nresourcePacks:[\"vanilla\",\"file/packA.zip\"]\n");

        List<ContentEntry> list = svc.scan(p);
        assertEquals(Set.of("alpha.jar", "beta.jar", "packA.zip", "folder", "Shade.zip"),
            list.stream().map(e -> e.fileName).collect(Collectors.toSet()));
        ContentEntry a = list.stream().filter(e -> e.fileName.equals("alpha.jar")).findFirst().orElseThrow();
        assertEquals(ContentSource.LOCAL, a.source);
        assertEquals(ContentKind.MOD, a.kind);
        assertEquals("alpha", a.modId);
        assertEquals("ALPHA Mod", a.title);
        assertEquals("1.0.0", a.versionNumber);
        assertEquals(sha1(alpha), a.sha1);
        assertEquals(sha512(alpha), a.sha512);
        assertEquals(alpha.length, a.size);
        assertTrue(a.enabled);
        assertNotNull(a.id);
        ContentEntry b = list.stream().filter(e -> e.fileName.equals("beta.jar")).findFirst().orElseThrow();
        assertFalse(b.enabled, "a .disabled mod is recorded disabled under its plain name");
        assertTrue(entry("packA.zip").enabled, "selected in options.txt");
        assertFalse(entry("folder").enabled);
        assertEquals(ContentKind.SHADER_PACK, entry("Shade.zip").kind);
        // stored and handed back to the caller's object
        assertEquals(5, stored().size());
        assertEquals(5, p.content.size());

        // a second scan is stable: same ids, nothing added
        List<String> ids = list.stream().map(e -> e.id).sorted().toList();
        assertEquals(ids, svc.scan(p).stream().map(e -> e.id).sorted().toList());
    }

    @Test
    void scanMarksMissingFilesAndFollowsRenamesOnDisk() throws Exception {
        write("mods/alpha.jar", TestJars.fabricMod("alpha", "1"));
        svc.scan(p);
        String id = entry("alpha.jar").id;

        Files.move(game.resolve("mods/alpha.jar"), game.resolve("mods/alpha.jar.disabled"));
        svc.scan(p);
        assertFalse(entry("alpha.jar").enabled, "disabled by hand");

        Files.delete(game.resolve("mods/alpha.jar.disabled"));
        svc.scan(p);
        assertTrue(entry("alpha.jar").missing);
        List<ContentWarning> w = svc.warnings(p);
        assertTrue(w.stream().anyMatch(x -> x.type() == ContentWarning.Type.MISSING_FILE && x.entryIds().contains(id)));

        // the same name in another case (Windows users rename freely)
        write("mods/ALPHA.jar", TestJars.fabricMod("alpha", "1"));
        List<ContentEntry> list = svc.scan(p);
        assertEquals(1, list.size(), "no duplicate entry for a case-only rename");
        assertEquals(id, list.get(0).id);
        assertEquals("ALPHA.jar", list.get(0).fileName);
        assertFalse(list.get(0).missing);
        assertTrue(list.get(0).enabled);
    }

    @Test
    void scanNoticesFilesReplacedOutsideTheLauncher() throws Exception {
        CatalogVersion v1 = version("projA", "v1", "alpha.jar", TestJars.fabricMod("alpha", "1.0"), List.of());
        install(plan(planned("Alpha", v1, ContentKind.MOD, false, null)));
        assertEquals("v1", entry("alpha.jar").versionId);
        byte[] other = TestJars.fabricMod("alpha", "9.9");
        Path jar = game.resolve("mods/alpha.jar");
        long installedTime = Files.getLastModifiedTime(jar).toMillis();
        Files.write(jar, other);
        // same size as before, so only the file time tells the scan to look again
        Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(installedTime + 5_000));
        svc.scan(p);
        ContentEntry e = entry("alpha.jar");
        assertEquals(sha1(other), e.sha1);
        assertNull(e.versionId, "the catalog version no longer describes the file");
        assertNull(e.downloadUrl);
        assertEquals("9.9", e.versionNumber);
    }

    @Test
    void scanAndWarningsSurviveUnsafeStoredFileNames() throws Exception {
        Path outside = dir.resolve("outside.jar");
        Files.write(outside, TestJars.fabricMod("evil", "1"));
        Project stored = projects.get(p.id).orElseThrow();
        ContentEntry evil = new ContentEntry();
        evil.id = "evil";
        evil.fileName = "../../../../outside.jar";
        evil.title = "Evil";
        stored.content.add(evil);
        projects.save(stored);

        svc.scan(p);
        assertTrue(entry("../../../../outside.jar").missing);
        assertTrue(svc.warnings(p).stream().anyMatch(w -> w.type() == ContentWarning.Type.MISSING_FILE));
        fails(ErrorCode.UNSAFE_PATH, () -> svc.remove(p, "evil"));
        fails(ErrorCode.UNSAFE_PATH, () -> svc.setEnabled(p, "evil", false));
        assertTrue(Files.exists(outside), "nothing outside the project is touched");
        fails(ErrorCode.UNSAFE_PATH, () -> svc.fileOf(p, "evil"));
    }

    @Test
    void unsafeProjectIdIsRefused() {
        Project bad = new Project();
        bad.id = "../x";
        fails(ErrorCode.UNSAFE_PATH, () -> svc.scan(bad));
    }

    // ------------------------------------------------------------------ warnings

    @Test
    void warningsFromMetadata() throws Exception {
        write("mods/dup-a.jar", TestJars.fabricMod("dup", "1"));
        write("mods/dup-b.jar", TestJars.fabricMod("dup", "2"));
        write("mods/forgey.jar", TestJars.forgeMod("forgey", "1"));
        write("mods/old.jar", TestJars.fabricMod("old", "1", "1.20.x", "*"));
        write("mods/server.jar", TestJars.fabricMod("serverstuff", "1", "*", "server"));
        String breaker = "{\"schemaVersion\":1,\"id\":\"breaker\",\"version\":\"1\",\"breaks\":{\"victim\":\"<2\"}}";
        write("mods/breaker.jar", TestJars.zipBytes(Map.of("fabric.mod.json", breaker)));
        write("mods/victim.jar", TestJars.fabricMod("victim", "1.5"));
        write("mods/fine.jar", TestJars.fabricMod("fine", "1", ">=1.21", "client"));
        write("mods/off.jar.disabled", TestJars.forgeMod("off", "1"));
        svc.scan(p);

        List<ContentWarning> w = svc.warnings(p);
        Map<ContentWarning.Type, List<ContentWarning>> byType = w.stream().collect(Collectors.groupingBy(ContentWarning::type));
        assertEquals(1, byType.get(ContentWarning.Type.DUPLICATE_MOD_ID).size());
        assertEquals(Set.of(entry("dup-a.jar").id, entry("dup-b.jar").id),
            Set.copyOf(byType.get(ContentWarning.Type.DUPLICATE_MOD_ID).get(0).entryIds()));
        assertEquals(List.of(entry("forgey.jar").id), byType.get(ContentWarning.Type.WRONG_LOADER).get(0).entryIds());
        assertEquals(1, byType.get(ContentWarning.Type.WRONG_LOADER).size(), "disabled mods are not checked");
        assertEquals(List.of(entry("old.jar").id), byType.get(ContentWarning.Type.MINECRAFT_VERSION).get(0).entryIds());
        assertEquals(List.of(entry("server.jar").id), byType.get(ContentWarning.Type.SERVER_ONLY).get(0).entryIds());
        assertEquals(List.of(entry("breaker.jar").id, entry("victim.jar").id), byType.get(ContentWarning.Type.BREAKS).get(0).entryIds());
        assertFalse(byType.containsKey(ContentWarning.Type.MODS_WITHOUT_LOADER));
        for (ContentWarning x : w) {
            assertFalse(x.message().contains("{0}"), x.message());
        }
        assertTrue(byType.get(ContentWarning.Type.DUPLICATE_MOD_ID).get(0).message().contains("dup"));
    }

    @Test
    void vanillaProjectWithModsIsWarned() throws Exception {
        Project v = projects.create("plain", "1.21.1", LoaderType.VANILLA, null);
        Path g = projects.gameDir(v);
        Files.write(g.resolve("mods/a.jar"), TestJars.fabricMod("a", "1"));
        svc.scan(v);
        List<ContentWarning> w = svc.warnings(v);
        assertEquals(1, w.size(), w.toString());
        assertEquals(ContentWarning.Type.MODS_WITHOUT_LOADER, w.get(0).type());
    }

    @Test
    void quiltProjectAcceptsFabricMods() throws Exception {
        Project q = projects.create("quilty", "1.21.1", LoaderType.QUILT, "0.26.0");
        Files.write(projects.gameDir(q).resolve("mods/a.jar"), TestJars.fabricMod("a", "1"));
        svc.scan(q);
        assertTrue(svc.warnings(q).isEmpty());
    }

    // ------------------------------------------------------------------ enable / disable

    @Test
    void disablingAModRenamesItSoNoLoaderLoadsIt() throws Exception {
        write("mods/alpha.jar", TestJars.fabricMod("alpha", "1"));
        svc.scan(p);
        String id = entry("alpha.jar").id;

        svc.setEnabled(p, id, false);
        assertEquals(List.of("alpha.jar.disabled"), listNames("mods"));
        for (String n : listNames("mods")) {
            assertFalse(n.endsWith(".jar"), "a disabled mod must not end in .jar: " + n);
        }
        assertFalse(entry("alpha.jar").enabled);
        assertFalse(p.content.get(0).enabled, "the caller's project is updated");
        assertEquals(svc.fileOf(p, id).orElseThrow(), game.resolve("mods/alpha.jar.disabled"));
        assertEquals(HistoryEvent.DISABLE, svc.history(p).get(0).action());

        svc.setEnabled(p, id, false); // no-op
        assertEquals(List.of("alpha.jar.disabled"), listNames("mods"));

        svc.setEnabled(p, id, true);
        assertEquals(List.of("alpha.jar"), listNames("mods"));
        assertTrue(entry("alpha.jar").enabled);
    }

    @Test
    void disablingRefusesWhenBothVariantsExist() throws Exception {
        write("mods/alpha.jar", TestJars.fabricMod("alpha", "1"));
        svc.scan(p);
        write("mods/alpha.jar.disabled", TestJars.fabricMod("alpha", "0"));
        fails(ErrorCode.CONFLICT, () -> svc.setEnabled(p, entry("alpha.jar").id, false));
        assertTrue(Files.exists(game.resolve("mods/alpha.jar")));
    }

    @Test
    void enablingAMissingModFails() throws Exception {
        write("mods/alpha.jar", TestJars.fabricMod("alpha", "1"));
        svc.scan(p);
        String id = entry("alpha.jar").id;
        svc.setEnabled(p, id, false);
        Files.delete(game.resolve("mods/alpha.jar.disabled"));
        fails(ErrorCode.NOT_FOUND, () -> svc.setEnabled(p, id, true));
        fails(ErrorCode.NOT_FOUND, () -> svc.setEnabled(p, "nope", true));
    }

    @Test
    void resourcePackEnableDisableEditsOptionsTxt() throws Exception {
        write("resourcepacks/a.zip", TestJars.resourcePack(34, "A"));
        write("resourcepacks/b.zip", TestJars.resourcePack(15, "B"));
        options("version:3955\nfov:0.5\nresourcePacks:[\"vanilla\",\"file/a.zip\",\"file/b.zip\"]\n"
            + "incompatibleResourcePacks:[\"file/b.zip\"]\nlang:en_us\n");
        svc.scan(p);
        assertTrue(entry("a.zip").enabled);
        assertTrue(entry("b.zip").enabled);

        svc.setEnabled(p, entry("b.zip").id, false);
        assertEquals(List.of("vanilla", "file/a.zip"), packs());
        assertEquals(List.of(), incompatible());
        String text = Files.readString(game.resolve("options.txt"));
        assertTrue(text.contains("fov:0.5") && text.contains("lang:en_us") && text.contains("version:3955"), text);
        assertFalse(entry("b.zip").enabled);

        svc.setEnabled(p, entry("b.zip").id, true);
        assertEquals(List.of("vanilla", "file/a.zip", "file/b.zip"), packs(), "enabled packs go on top");
        assertEquals(List.of("b.zip", "a.zip"), svc.resourcePackOrder(p));
    }

    @Test
    void shaderToggleEditsIrisConfigOnlyWhenThePackIsSelected() throws Exception {
        write("shaderpacks/Complementary v5.zip", TestJars.shaderPack());
        write("shaderpacks/other.zip", TestJars.shaderPack());
        svc.scan(p);
        Path iris = game.resolve("config/iris.properties");

        // no config: nothing is created
        svc.setEnabled(p, entry("other.zip").id, false);
        assertFalse(Files.exists(iris));
        assertFalse(entry("other.zip").enabled);

        Files.writeString(iris, "#Iris config\nshaderPack=Complementary v5.zip\nenableShaders=true\nmaxShadowRenderDistance=32\n",
            StandardCharsets.ISO_8859_1);
        svc.setEnabled(p, entry("other.zip").id, true);
        assertTrue(Files.readString(iris).contains("enableShaders=true"), "another pack's toggle doesn't touch the config");

        svc.setEnabled(p, entry("Complementary v5.zip").id, false);
        String text = Files.readString(iris, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("enableShaders=false"), text);
        assertTrue(text.contains("#Iris config") && text.contains("maxShadowRenderDistance=32"), text);
        svc.setEnabled(p, entry("Complementary v5.zip").id, true);
        assertTrue(Files.readString(iris).contains("enableShaders=true"));
    }

    // ------------------------------------------------------------------ remove, rollback, history

    @Test
    void removeKeepsTheFileInHistoryAndRollbackRestoresIt() throws Exception {
        byte[] data = TestJars.fabricMod("alpha", "1");
        Path src = source("alpha.jar", data);
        ContentEntry e = svc.installLocalFile(p, ContentKind.MOD, src, ContentSource.LOCAL, null, null);
        svc.setPinned(p, e.id, true);

        svc.remove(p, e.id);
        assertTrue(listNames("mods").isEmpty());
        assertTrue(stored().isEmpty());
        assertTrue(p.content.isEmpty());
        HistoryEvent removal = svc.history(p).get(0);
        assertEquals(HistoryEvent.REMOVE, removal.action());
        assertEquals(e.id, removal.entryId());
        assertNotNull(removal.storedFile());
        Path kept = projects.projectDir(p.id).resolve(removal.storedFile());
        assertArrayEquals(data, Files.readAllBytes(kept));

        svc.rollback(p, removal.id());
        assertArrayEquals(data, Files.readAllBytes(game.resolve("mods/alpha.jar")));
        ContentEntry back = entry("alpha.jar");
        assertEquals(e.id, back.id, "the entry comes back with its id");
        assertTrue(back.pinned);
        assertFalse(back.missing);
        assertFalse(Files.exists(kept));
        assertEquals(HistoryEvent.ROLLBACK, svc.history(p).get(0).action());
        // the stored file is gone now, so the removal can't be undone twice
        fails(ErrorCode.NOT_FOUND, () -> svc.rollback(p, removal.id()));
        fails(ErrorCode.NOT_FOUND, () -> svc.rollback(p, "no-such-event"));
    }

    @Test
    void removedDisabledModComesBackDisabled() throws Exception {
        ContentEntry e = svc.installLocalFile(p, ContentKind.MOD, source("a.jar", TestJars.fabricMod("a", "1")),
            ContentSource.LOCAL, null, null);
        svc.setEnabled(p, e.id, false);
        svc.remove(p, e.id);
        svc.rollback(p, svc.history(p).get(0).id());
        assertEquals(List.of("a.jar.disabled"), listNames("mods"));
        assertFalse(entry("a.jar").enabled);
    }

    @Test
    void resourcePackRemoveAndRollbackUpdateOptionsTxt() throws Exception {
        options("resourcePacks:[\"vanilla\",\"fabric\"]\n");
        ContentEntry e = svc.installLocalFile(p, ContentKind.RESOURCE_PACK, source("faithful.zip", TestJars.resourcePack(34, "F")),
            ContentSource.LOCAL, null, null);
        assertEquals(List.of("vanilla", "fabric", "file/faithful.zip"), packs());
        assertTrue(e.enabled);

        svc.remove(p, e.id);
        assertEquals(List.of("vanilla", "fabric"), packs());
        svc.rollback(p, svc.history(p).get(0).id());
        assertEquals(List.of("vanilla", "fabric", "file/faithful.zip"), packs());
        assertTrue(entry("faithful.zip").enabled);
        assertTrue(Files.exists(game.resolve("resourcepacks/faithful.zip")));
    }

    @Test
    void rollingBackAnInstallRemovesIt() throws Exception {
        ContentEntry e = svc.installLocalFile(p, ContentKind.SHADER_PACK, source("s.zip", TestJars.shaderPack()),
            ContentSource.LOCAL, null, null);
        HistoryEvent install = svc.history(p).get(0);
        assertEquals(HistoryEvent.INSTALL, install.action());
        assertEquals(e.id, install.entryId());
        svc.rollback(p, install.id());
        assertTrue(listNames("shaderpacks").isEmpty());
        assertTrue(stored().isEmpty());
        // and the rollback itself can be rolled back
        svc.rollback(p, svc.history(p).get(0).id());
        assertEquals(List.of("s.zip"), listNames("shaderpacks"));
        assertEquals(e.id, entry("s.zip").id);
    }

    @Test
    void rollingBackAToggleSwitchesBack() throws Exception {
        ContentEntry e = svc.installLocalFile(p, ContentKind.MOD, source("a.jar", TestJars.fabricMod("a", "1")),
            ContentSource.LOCAL, null, null);
        svc.setEnabled(p, e.id, false);
        HistoryEvent toggle = svc.history(p).get(0);
        assertEquals(HistoryEvent.DISABLE, toggle.action());
        svc.rollback(p, toggle.id());
        assertEquals(List.of("a.jar"), listNames("mods"));
        assertTrue(entry("a.jar").enabled);
    }

    @Test
    void historyIsPrunedOldestFirst() throws Exception {
        svc.setHistoryLimits(3, Long.MAX_VALUE);
        ContentEntry e = svc.installLocalFile(p, ContentKind.MOD, source("a.jar", TestJars.fabricMod("a", "1")),
            ContentSource.LOCAL, null, null);
        for (int i = 0; i < 4; i++) {
            svc.setEnabled(p, e.id, i % 2 == 1);
        }
        List<HistoryEvent> h = svc.history(p);
        assertEquals(3, h.size());
        assertTrue(h.stream().noneMatch(x -> x.action().equals(HistoryEvent.INSTALL)), "the oldest event went first");
    }

    @Test
    void historyByteLimitDeletesStoredFilesOfDroppedEvents() throws Exception {
        svc.setHistoryLimits(100, 10);
        ContentEntry a = svc.installLocalFile(p, ContentKind.MOD, source("a.jar", TestJars.fabricMod("a", "1")),
            ContentSource.LOCAL, null, null);
        ContentEntry b = svc.installLocalFile(p, ContentKind.MOD, source("b.jar", TestJars.fabricMod("b", "1")),
            ContentSource.LOCAL, null, null);
        svc.remove(p, a.id);
        HistoryEvent first = svc.history(p).get(0);
        Path firstStored = projects.projectDir(p.id).resolve(first.storedFile());
        assertTrue(Files.exists(firstStored), "the newest event is always kept");
        svc.remove(p, b.id);
        assertFalse(Files.exists(firstStored), "the older removal was pruned with its file");
        assertTrue(svc.history(p).stream().noneMatch(x -> x.id().equals(first.id())));
        fails(ErrorCode.NOT_FOUND, () -> svc.rollback(p, first.id()));
    }

    @Test
    void historyFileWithHostileStoredPathIsRefused() throws Exception {
        ContentEntry e = svc.installLocalFile(p, ContentKind.MOD, source("a.jar", TestJars.fabricMod("a", "1")),
            ContentSource.LOCAL, null, null);
        svc.remove(p, e.id);
        Path hf = projects.projectDir(p.id).resolve(".history/history.json");
        String json = Files.readString(hf);
        HistoryEvent ev = svc.history(p).get(0);
        Path victim = dir.resolve("victim.jar");
        Files.write(victim, new byte[] {1});
        Files.writeString(hf, json.replace(ev.storedFile(), "../../../victim.jar"));
        fails(ErrorCode.UNSAFE_PATH, () -> svc.rollback(p, ev.id()));
        assertTrue(Files.exists(victim));
    }

    // ------------------------------------------------------------------ install from a plan

    @Test
    void installsAPlanWithDependencies() throws Exception {
        byte[] lib = TestJars.fabricMod("libby", "3.0");
        byte[] root = TestJars.fabricMod("rooty", "1.0");
        byte[] pack = TestJars.resourcePack(34, "P");
        CatalogVersion libV = version("LIB", "libv", "libby-3.0.jar", lib, List.of());
        CatalogVersion rootV = version("ROOT", "rootv", "rooty-1.0.jar", root,
            List.of(new CatalogDependency("LIB", null, null, DependencyType.REQUIRED)));
        CatalogVersion packV = version("PACK", "packv", "pack.zip", pack, List.of());
        List<String> stages = Collections.synchronizedList(new ArrayList<>());
        ProgressListener pl = (stage, detail, done, total) -> stages.add(stage);
        List<ContentEntry> out = svc.install(p, plan(planned("Rooty", rootV, ContentKind.MOD, false, null),
            planned("Libby", libV, ContentKind.MOD, true, "ROOT"),
            planned("Pack", packV, ContentKind.RESOURCE_PACK, false, null)), pl, CancellationToken.NONE);

        assertEquals(3, out.size());
        assertEquals(List.of("libby-3.0.jar", "rooty-1.0.jar"), listNames("mods"));
        assertArrayEquals(lib, Files.readAllBytes(game.resolve("mods/libby-3.0.jar")));
        ContentEntry r = entry("rooty-1.0.jar");
        ContentEntry l = entry("libby-3.0.jar");
        assertEquals(ContentSource.MODRINTH, r.source);
        assertEquals("ROOT", r.projectId);
        assertEquals("rootv", r.versionId);
        assertEquals("Rooty", r.title);
        assertEquals("rooty", r.modId);
        assertEquals(sha1(root), r.sha1);
        assertEquals(sha512(root), r.sha512);
        assertEquals(root.length, r.size);
        assertEquals("https://modrinth.com/mod/ROOT", r.pageUrl);
        assertEquals(server.uri("/files/rootv/rooty-1.0.jar").toString(), r.downloadUrl);
        assertFalse(r.dependency);
        assertTrue(l.dependency);
        assertEquals(List.of(r.id), l.requiredBy);
        assertEquals(List.of("vanilla", "file/pack.zip"), packs(), "a new options.txt with the pack selected");
        assertEquals("resourcePacks:[\"vanilla\",\"file/pack.zip\"]\n", Files.readString(game.resolve("options.txt")));
        assertEquals(3, svc.history(p).size());
        assertTrue(stages.contains("content.stage.downloading"));
        assertTrue(stages.contains("content.stage.installing"));
        assertFalse(Files.exists(projects.projectDir(p.id).resolve(ContentService.STAGING)), "staging is cleaned up");

        // a later install of a mod that needs the already-installed library links it as well
        CatalogVersion other = version("OTHER", "otherv", "other.jar", TestJars.fabricMod("other", "1"),
            List.of(new CatalogDependency("LIB", null, null, DependencyType.REQUIRED)));
        ContentEntry o = install(plan(planned("Other", other, ContentKind.MOD, false, null))).get(0);
        assertEquals(List.of(r.id, o.id), entry("libby-3.0.jar").requiredBy);
    }

    @Test
    void aFailedDownloadLeavesNothingBehind() throws Exception {
        CatalogVersion good = version("GOOD", "goodv", "good.jar", TestJars.fabricMod("good", "1"), List.of());
        byte[] data = TestJars.fabricMod("bad", "1");
        server.bytes("/files/bad.jar", data);
        CatalogFile wrong = new CatalogFile(server.uri("/files/bad.jar"), "bad.jar", data.length, "0".repeat(40), null, true, false);
        CatalogVersion bad = new CatalogVersion("modrinth", "badv", "BAD", "1", "bad", null, List.of(), List.of(), "release",
            null, List.of(wrong), List.of());
        fails(ErrorCode.CHECKSUM_MISMATCH, () -> install(plan(planned("Good", good, ContentKind.MOD, false, null),
            new PlannedFile("Bad", bad, wrong, ContentKind.MOD, true, "GOOD"))));
        assertTrue(listNames("mods").isEmpty());
        assertTrue(stored().isEmpty());
        assertTrue(svc.history(p).isEmpty());
        assertFalse(Files.exists(projects.projectDir(p.id).resolve(ContentService.STAGING)));
    }

    @Test
    void aNameClashDuringPlacementUndoesEverything() throws Exception {
        // the clash only shows up after the download because the catalog gave no SHA-1
        write("mods/taken.jar", TestJars.fabricMod("mine", "1"));
        svc.scan(p);
        CatalogVersion first = version("FIRST", "firstv", "first.jar", TestJars.fabricMod("first", "1"), List.of());
        byte[] data = TestJars.fabricMod("taken", "2");
        server.bytes("/files/taken.jar", data);
        CatalogFile f = new CatalogFile(server.uri("/files/taken.jar"), "taken.jar", data.length, null, sha512(data), true, false);
        CatalogVersion clash = new CatalogVersion("modrinth", "takenv", "TAKEN", "2", "taken", null, List.of(), List.of(),
            "release", null, List.of(f), List.of());
        fails(ErrorCode.CONFLICT, () -> install(plan(planned("First", first, ContentKind.MOD, false, null),
            new PlannedFile("Taken", clash, f, ContentKind.MOD, false, null))));
        assertEquals(List.of("taken.jar"), listNames("mods"), "first.jar was taken out again");
        assertEquals(1, stored().size());
        assertEquals("mine", entry("taken.jar").modId);
    }

    @Test
    void blockedRestrictedAndDuplicatePlansAreRefused() throws Exception {
        CatalogVersion v = version("A", "av", "a.jar", TestJars.fabricMod("a", "1"), List.of());
        InstallPlan blocked = new InstallPlan(List.of(planned("A", v, ContentKind.MOD, false, null)),
            List.of(new PlanIssue(Severity.ERROR, "content.plan.wrongLoader", List.of("A", "Forge", "Fabric"), true)), List.of());
        fails(ErrorCode.CONFLICT, () -> install(blocked));

        CatalogFile restricted = new CatalogFile(null, "r.jar", 10, "a".repeat(40), null, true, true);
        CatalogVersion rv = new CatalogVersion("curseforge", "1", "2", "1", "r", null, List.of(), List.of(), "release", null,
            List.of(restricted), List.of());
        LauncherException e = fails(ErrorCode.DISTRIBUTION_RESTRICTED,
            () -> install(plan(new PlannedFile("R", rv, restricted, ContentKind.MOD, false, null,
                URI.create("https://www.curseforge.com/minecraft/mc-mods/r")))));
        assertTrue(e.userMessage().contains("https://www.curseforge.com/minecraft/mc-mods/r"), e.userMessage());

        fails(ErrorCode.CONFLICT, () -> install(plan(planned("A", v, ContentKind.MOD, false, null),
            planned("A again", v, ContentKind.MOD, false, null))));
        fails(ErrorCode.INVALID_DATA, () -> install(plan(planned("A", v, ContentKind.MODPACK, false, null))));
        assertEquals(0, server.hits("/files/av/a.jar"));
        assertTrue(install(new InstallPlan(List.of(), List.of(), List.of())).isEmpty());
    }

    @Test
    void cancellationStopsTheInstall() throws Exception {
        CatalogVersion v = version("A", "av", "a.jar", TestJars.fabricMod("a", "1"), List.of());
        CancellationToken ct = new CancellationToken();
        ct.cancel();
        LauncherException e = assertThrows(LauncherException.class,
            () -> svc.install(p, plan(planned("A", v, ContentKind.MOD, false, null)), ProgressListener.NONE, ct));
        assertTrue(e.isCancelled(), e.toString());
        assertTrue(listNames("mods").isEmpty());
    }

    @Test
    void installAdoptsAnIdenticalFileAlreadyPresent() throws Exception {
        byte[] data = TestJars.fabricMod("a", "1");
        write("mods/a.jar", data);
        svc.scan(p);
        String id = entry("a.jar").id;
        CatalogVersion v = version("A", "av", "a.jar", data, List.of());
        install(plan(planned("A", v, ContentKind.MOD, false, null)));
        ContentEntry e = entry("a.jar");
        assertEquals(id, e.id);
        assertEquals(ContentSource.MODRINTH, e.source);
        assertEquals("A", e.projectId);
        assertEquals(1, stored().size());
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateKeepsTheOldFileInHistoryAndCanBeRolledBack() throws Exception {
        byte[] v1data = TestJars.fabricMod("alpha", "1.0");
        byte[] v2data = TestJars.fabricMod("alpha", "2.0");
        CatalogVersion v1 = version("ALPHA", "v1", "alpha-1.0.jar", v1data, List.of());
        CatalogVersion v2 = version("ALPHA", "v2", "alpha-2.0.jar", v2data, List.of());
        ContentEntry e = install(plan(planned("Alpha", v1, ContentKind.MOD, false, null))).get(0);
        svc.setPinned(p, e.id, true);
        svc.setEnabled(p, e.id, false);

        ContentEntry u = svc.update(p, e.id, v2, null, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(e.id, u.id);
        assertEquals("v2", u.versionId);
        assertEquals("alpha-2.0.jar", u.fileName);
        assertEquals(sha1(v2data), u.sha1);
        assertFalse(u.enabled, "the entry keeps its enabled state");
        assertTrue(u.pinned);
        assertEquals("Alpha", u.title);
        assertEquals(List.of("alpha-2.0.jar.disabled"), listNames("mods"));
        HistoryEvent ev = svc.history(p).get(0);
        assertEquals(HistoryEvent.UPDATE, ev.action());
        assertEquals("v1", ev.before().versionId);
        assertEquals("v2", ev.after().versionId);
        assertArrayEquals(v1data, Files.readAllBytes(projects.projectDir(p.id).resolve(ev.storedFile())));

        svc.rollback(p, ev.id());
        assertEquals(List.of("alpha-1.0.jar.disabled"), listNames("mods"));
        ContentEntry back = entry("alpha-1.0.jar");
        assertEquals("v1", back.versionId);
        assertEquals(e.id, back.id);
        HistoryEvent rb = svc.history(p).get(0);
        assertEquals(HistoryEvent.ROLLBACK, rb.action());
        assertArrayEquals(v2data, Files.readAllBytes(projects.projectDir(p.id).resolve(rb.storedFile())),
            "the newer file is kept in turn");
    }

    @Test
    void updatingAResourcePackKeepsItsPositionInOptionsTxt() throws Exception {
        options("resourcePacks:[\"vanilla\",\"file/x.zip\",\"file/old.zip\",\"file/y.zip\"]\n"
            + "incompatibleResourcePacks:[\"file/old.zip\"]\n");
        write("resourcepacks/x.zip", TestJars.resourcePack(34, "x"));
        write("resourcepacks/y.zip", TestJars.resourcePack(34, "y"));
        CatalogVersion v1 = version("PACK", "p1", "old.zip", TestJars.resourcePack(15, "old"), List.of());
        CatalogVersion v2 = version("PACK", "p2", "new.zip", TestJars.resourcePack(34, "new"), List.of());
        ContentEntry e = install(plan(planned("Pack", v1, ContentKind.RESOURCE_PACK, false, null))).get(0);
        assertEquals(List.of("vanilla", "file/x.zip", "file/old.zip", "file/y.zip"), packs(), "already selected: untouched");
        svc.update(p, e.id, v2, v2.primaryFile().orElseThrow(), ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(List.of("vanilla", "file/x.zip", "file/new.zip", "file/y.zip"), packs());
        assertEquals(List.of(), incompatible());
    }

    @Test
    void updateOfAShaderFollowsIrisSelection() throws Exception {
        CatalogVersion v1 = version("SH", "s1", "shade-1.zip", TestJars.shaderPack(), List.of());
        CatalogVersion v2 = version("SH", "s2", "shade-2.zip", TestJars.zipBytes(Map.of("shaders/x.fsh", "2")), List.of());
        ContentEntry e = install(plan(planned("Shade", v1, ContentKind.SHADER_PACK, false, null))).get(0);
        Path iris = game.resolve("config/iris.properties");
        Files.writeString(iris, "shaderPack=shade-1.zip\nenableShaders=true\n");
        svc.update(p, e.id, v2, null, ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(Files.readString(iris).contains("shaderPack=shade-2.zip"));
    }

    // ------------------------------------------------------------------ local files

    @Test
    void installLocalFileChecksTypeAndArchive() throws Exception {
        fails(ErrorCode.INVALID_DATA, () -> svc.installLocalFile(p, ContentKind.MOD, source("notes.txt", new byte[] {1}),
            ContentSource.LOCAL, null, null));
        fails(ErrorCode.INVALID_ARCHIVE, () -> svc.installLocalFile(p, ContentKind.MOD, source("fake.jar", new byte[] {1, 2, 3}),
            ContentSource.LOCAL, null, null));
        fails(ErrorCode.INVALID_DATA, () -> svc.installLocalFile(p, ContentKind.RESOURCE_PACK, source("p.jar", TestJars.fabricMod("x", "1")),
            ContentSource.LOCAL, null, null));
        fails(ErrorCode.NOT_FOUND, () -> svc.installLocalFile(p, ContentKind.MOD, dir.resolve("absent.jar"),
            ContentSource.LOCAL, null, null));
        fails(ErrorCode.INVALID_DATA, () -> svc.installLocalFile(p, ContentKind.MODPACK, source("m.zip", TestJars.shaderPack()),
            ContentSource.LOCAL, null, null));
        assertTrue(listNames("mods").isEmpty());
        assertTrue(stored().isEmpty());
    }

    @Test
    void installLocalFileRefusesANameUsedByAnotherFile() throws Exception {
        ContentEntry first = svc.installLocalFile(p, ContentKind.MOD, source("a.jar", TestJars.fabricMod("a", "1")),
            ContentSource.LOCAL, null, null);
        Path other = dir.resolve("other/A.jar");
        TestJars.zip(other, Map.of("fabric.mod.json", TestJars.fabricJson("a", "2", "*", "*")));
        LauncherException e = fails(ErrorCode.CONFLICT,
            () -> svc.installLocalFile(p, ContentKind.MOD, other, ContentSource.LOCAL, null, null));
        assertEquals("content.error.nameClash", e.messageKey());
        assertEquals(List.of("a.jar"), listNames("mods"));
        // also when the existing one is disabled
        svc.setEnabled(p, first.id, false);
        fails(ErrorCode.CONFLICT, () -> svc.installLocalFile(p, ContentKind.MOD, other, ContentSource.LOCAL, null, null));
    }

    @Test
    void installLocalFileReplacesTheSameProjectsEntry() throws Exception {
        byte[] v1 = TestJars.fabricMod("shard_ui", "1.0");
        byte[] v2 = TestJars.fabricMod("shard_ui", "1.1");
        ContentEntry a = svc.installLocalFile(p, ContentKind.MOD, source("v1/shard-ui.jar", v1), ContentSource.SHARDLIGHT, "ui", "1.0");
        ContentEntry b = svc.installLocalFile(p, ContentKind.MOD, source("v2/shard-ui.jar", v2), ContentSource.SHARDLIGHT, "ui", "1.1");
        assertEquals(a.id, b.id);
        assertEquals("1.1", b.versionId);
        assertEquals(ContentSource.SHARDLIGHT, b.source);
        assertArrayEquals(v2, Files.readAllBytes(game.resolve("mods/shard-ui.jar")));
        HistoryEvent ev = svc.history(p).get(0);
        assertEquals(HistoryEvent.UPDATE, ev.action());
        assertArrayEquals(v1, Files.readAllBytes(projects.projectDir(p.id).resolve(ev.storedFile())));
        assertEquals(1, stored().size());
    }

    @Test
    void installLocalFileAdoptsAnIdenticalFileAndSanitizesNames() throws Exception {
        byte[] data = TestJars.fabricMod("a", "1");
        write("mods/a.jar", data);
        ContentEntry e = svc.installLocalFile(p, ContentKind.MOD, source("a.jar", data), ContentSource.LOCAL, null, null);
        assertEquals("a.jar", e.fileName);
        assertEquals(1, stored().size());

        // characters every file system allows but the launcher doesn't keep in names
        ContentEntry odd = svc.installLocalFile(p, ContentKind.MOD, source("we#ird&na[me].jar.disabled", TestJars.fabricMod("b", "1")),
            ContentSource.LOCAL, null, null);
        assertFalse(odd.fileName.matches(".*[#&\\[\\]].*"), odd.fileName);
        assertTrue(odd.fileName.endsWith(".jar"));
        assertTrue(odd.enabled);
        assertTrue(Files.exists(game.resolve("mods").resolve(odd.fileName)));
    }

    @Test
    void recordExistingDescribesFilesAlreadyInPlace() throws Exception {
        write("mods/sodium.jar", TestJars.fabricMod("sodium", "0.6"));
        write("resourcepacks/x.zip", TestJars.resourcePack(34, "x"));
        List<ContentEntry> out = svc.recordExisting(p, List.of(
            new ExistingFile(ContentKind.MOD, "sodium.jar", ContentSource.MODRINTH, "AANobbMI", "v06", "0.6.0", "Sodium",
                "https://modrinth.com/mod/sodium", "https://cdn.modrinth.com/data/AANobbMI/versions/v06/sodium.jar"),
            new ExistingFile(ContentKind.RESOURCE_PACK, "x.zip", ContentSource.MODPACK, null, null, null, null, null, null)));
        assertEquals(2, out.size());
        ContentEntry s = entry("sodium.jar");
        assertEquals("AANobbMI", s.projectId);
        assertEquals("sodium", s.modId);
        assertEquals("Sodium", s.title);
        assertNotNull(s.sha512);
        assertEquals("https://cdn.modrinth.com/data/AANobbMI/versions/v06/sodium.jar", s.downloadUrl);
        assertFalse(entry("x.zip").enabled, "not selected in options.txt");
        fails(ErrorCode.NOT_FOUND, () -> svc.recordExisting(p, List.of(
            new ExistingFile(ContentKind.MOD, "absent.jar", ContentSource.LOCAL, null, null, null, null, null, null))));
    }

    // ------------------------------------------------------------------ pack order

    @Test
    void packOrderRoundTripKeepsBuiltInsAtTheBottom() throws Exception {
        for (String n : List.of("a.zip", "b.zip", "c.zip")) {
            write("resourcepacks/" + n, TestJars.resourcePack(34, n));
        }
        write("resourcepacks/folder/pack.mcmeta", "{\"pack\":{\"pack_format\":34}}".getBytes(StandardCharsets.UTF_8));
        options("fov:0.0\nresourcePacks:[\"vanilla\",\"fabric\",\"file/a.zip\",\"b.zip\",\"file/gone.zip\"]\n"
            + "incompatibleResourcePacks:[\"b.zip\",\"file/gone.zip\"]\nlang:de_de\n");
        svc.scan(p);
        assertEquals(List.of("b.zip", "a.zip"), svc.resourcePackOrder(p), "bare names count, missing packs don't");

        svc.setResourcePackOrder(p, List.of("c.zip", "a.zip", "folder"));
        List<String> l = packs();
        assertEquals("vanilla", l.get(0));
        assertEquals("fabric", l.get(1));
        assertEquals(List.of("vanilla", "fabric", "file/b.zip", "file/gone.zip", "file/folder", "file/a.zip", "file/c.zip"), l);
        assertEquals(List.of("file/b.zip", "file/gone.zip"), incompatible(), "kept, as names of selected packs");
        assertEquals(List.of("c.zip", "a.zip", "folder", "b.zip"), svc.resourcePackOrder(p));
        assertTrue(entry("c.zip").enabled, "listed packs become enabled");
        assertTrue(entry("folder").enabled);
        String text = Files.readString(game.resolve("options.txt"));
        assertTrue(text.startsWith("fov:0.0\n") && text.contains("lang:de_de"), text);

        fails(ErrorCode.NOT_FOUND, () -> svc.setResourcePackOrder(p, List.of("absent.zip")));
        fails(ErrorCode.UNSAFE_PATH, () -> svc.setResourcePackOrder(p, List.of("../x.zip")));
    }

    @Test
    void packOrderCreatesOptionsTxtWithOnlyThePackLine() throws Exception {
        write("resourcepacks/a.zip", TestJars.resourcePack(34, "a"));
        write("resourcepacks/b.zip", TestJars.resourcePack(34, "b"));
        svc.scan(p);
        assertEquals(List.of(), svc.resourcePackOrder(p));
        assertFalse(Files.exists(game.resolve("options.txt")));
        svc.setResourcePackOrder(p, List.of("b.zip", "a.zip"));
        assertEquals("resourcePacks:[\"vanilla\",\"file/a.zip\",\"file/b.zip\"]\n", Files.readString(game.resolve("options.txt")));
        assertEquals(List.of("b.zip", "a.zip"), svc.resourcePackOrder(p));
    }

    @Test
    void disablingAPackDoesNotCreateOptionsTxt() throws Exception {
        write("resourcepacks/a.zip", TestJars.resourcePack(34, "a"));
        svc.scan(p);
        svc.setEnabled(p, entry("a.zip").id, false);
        assertFalse(Files.exists(game.resolve("options.txt")));
    }

    // ------------------------------------------------------------------ robustness

    @Test
    void concurrentInstallsAreAllRecorded() throws Exception {
        int n = 8;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            files.add(source("m" + i + ".jar", TestJars.fabricMod("m" + i, "1")));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ContentEntry>> fs = new ArrayList<>();
            for (Path f : files) {
                // each thread holds its own (stale) Project object, as UI code would
                Project own = Json.copy(p, Project.class);
                fs.add(pool.submit(() -> svc.installLocalFile(own, ContentKind.MOD, f, ContentSource.LOCAL, null, null)));
            }
            for (Future<ContentEntry> f : fs) {
                assertNotNull(f.get().id);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(n, stored().size());
        assertEquals(n, svc.history(p).size());
        assertEquals(n, listNames("mods").size());
    }

    @Test
    void projectWithoutContentListIsRepaired() throws Exception {
        Path pj = projects.projectDir(p.id).resolve("project.json");
        String json = Files.readString(pj).replaceAll("\"content\"\\s*:\\s*\\[\\s*\\]", "\"content\": [null, {\"fileName\":\"x.jar\"}]");
        Files.writeString(pj, json);
        write("mods/x.jar", TestJars.fabricMod("x", "1"));
        List<ContentEntry> list = svc.scan(p);
        assertEquals(1, list.size());
        assertNotNull(list.get(0).id);
        assertEquals(ContentKind.MOD, list.get(0).kind);
        assertEquals(ContentSource.LOCAL, list.get(0).source);
    }
}
