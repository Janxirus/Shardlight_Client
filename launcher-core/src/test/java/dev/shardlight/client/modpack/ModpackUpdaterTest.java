package dev.shardlight.client.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.shardlight.client.content.ContentService;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.shardlight.client.modpack.ModpackServiceTest.mod;
import static dev.shardlight.client.modpack.ModpackServiceTest.utf8;
import static dev.shardlight.client.modpack.ModpackServiceTest.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Updating a modpack project: every merge case, the backup, and rolling back. */
class ModpackUpdaterTest {
    @TempDir
    Path dir;
    AppPaths paths;
    FailingProjects projects;
    TestHttpServer server;
    DownloadManager downloads;
    ModpackService service;
    ModpackUpdater updater;
    final List<String> events = new ArrayList<>();
    Path gameDirAtBackup;
    final Map<String, String> filesAtBackup = new LinkedHashMap<>();

    /** a project store whose save can be made to fail */
    static final class FailingProjects implements dev.shardlight.client.project.ProjectAccess {
        final TestProjects inner;
        boolean failSave;

        FailingProjects(AppPaths paths) {
            inner = new TestProjects(paths);
        }

        @Override
        public java.util.Optional<Project> get(String id) {
            return inner.get(id);
        }

        @Override
        public Project save(Project project) throws LauncherException {
            if (failSave) {
                throw new LauncherException(ErrorCode.IO, "error.io", "project.json", "disk full");
            }
            return inner.save(project);
        }

        @Override
        public Path projectDir(String id) {
            return inner.projectDir(id);
        }
    }

    @BeforeEach
    void setUp() {
        paths = new AppPaths(dir.resolve("data"));
        projects = new FailingProjects(paths);
        server = new TestHttpServer();
        downloads = new DownloadManager(HttpService.forTests(), paths.files(), 4, 1);
        downloads.setBackoffMillis(5);
        ContentService content = new ContentService(paths, projects, downloads);
        ProjectFactory factory = new ProjectFactory() {
            @Override
            public Project create(String name, String minecraftVersion, LoaderSpec loader) throws LauncherException {
                Project p = projects.inner.create("pack", minecraftVersion, loader.type, loader.version);
                p.name = name;
                return projects.save(p);
            }
        };
        service = new ModpackService(paths, projects, factory, content, downloads, null, Set.of("127.0.0.1"));
        updater = new ModpackUpdater(service, projects, content, downloads, (p, pl, ct) -> {
            events.add("backup");
            gameDirAtBackup = projects.gameDir(p);
            snapshotInto(filesAtBackup, gameDirAtBackup);
        });
    }

    @AfterEach
    void tearDown() {
        downloads.close();
        server.close();
    }

    // ---------------------------------------------------------------- building packs

    JsonObject entry(String path, byte[] data) {
        String serverPath = "/files/" + Hashing.hash(data, HashAlgo.SHA1) + "/" + path.replace('/', '_');
        server.bytes(serverPath, data);
        JsonObject f = new JsonObject();
        f.addProperty("path", path);
        JsonObject h = new JsonObject();
        h.addProperty("sha1", Hashing.hash(data, HashAlgo.SHA1));
        h.addProperty("sha512", Hashing.hash(data, HashAlgo.SHA512));
        f.add("hashes", h);
        JsonArray dl = new JsonArray();
        dl.add(server.uri(serverPath).toString());
        f.add("downloads", dl);
        f.addProperty("fileSize", data.length);
        return f;
    }

    Path pack(String file, String name, String version, String minecraft, Map<String, byte[]> mods, Map<String, byte[]> overrides)
        throws IOException {
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", version);
        index.addProperty("name", name);
        JsonArray arr = new JsonArray();
        mods.forEach((p, b) -> arr.add(entry(p, b)));
        index.add("files", arr);
        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", minecraft);
        deps.addProperty("fabric-loader", "0.19.5");
        index.add("dependencies", deps);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ModpackService.MRPACK_INDEX, utf8(index.toString()));
        overrides.forEach((p, b) -> entries.put("overrides/" + p, b));
        Path f = dir.resolve("in").resolve(file);
        Files.createDirectories(f.getParent());
        return Files.write(f, zip(entries));
    }

    static byte[] text(String s) {
        return utf8(s);
    }

    /** version 1: a, b, c mods; configs a, b, c, d */
    Path v1() throws IOException {
        Map<String, byte[]> mods = new LinkedHashMap<>();
        mods.put("mods/a.jar", mod("a", "*"));
        mods.put("mods/b.jar", mod("b", "*"));
        mods.put("mods/c.jar", mod("c", "*"));
        mods.put("mods/e.jar", mod("e", "*"));
        Map<String, byte[]> cfg = new LinkedHashMap<>();
        cfg.put("config/a.toml", text("a=1"));
        cfg.put("config/b.toml", text("b=1"));
        cfg.put("config/c.toml", text("c=1"));
        cfg.put("config/d.toml", text("d=1"));
        cfg.put("config/same.toml", text("same=1"));
        return pack("v1.mrpack", "Speedy Pack", "1.0.0", "26.3", mods, cfg);
    }

    /** version 2: b changed, c dropped, e changed, f added; c.toml changed, b.toml changed, d.toml changed, g.toml added */
    Path v2() throws IOException {
        Map<String, byte[]> mods = new LinkedHashMap<>();
        mods.put("mods/a.jar", mod("a", "*"));
        mods.put("mods/b.jar", mod("b-new", "*"));
        mods.put("mods/e.jar", mod("e-new", "*"));
        mods.put("mods/f.jar", mod("f", "*"));
        Map<String, byte[]> cfg = new LinkedHashMap<>();
        cfg.put("config/a.toml", text("a=1"));
        cfg.put("config/b.toml", text("b=2"));
        cfg.put("config/c.toml", text("c=2"));
        cfg.put("config/d.toml", text("d=2"));
        cfg.put("config/g.toml", text("g=1"));
        cfg.put("config/same.toml", text("same=1"));
        return pack("v2.mrpack", "Speedy Pack", "2.0.0", "26.3.1", mods, cfg);
    }

    Project install() throws Exception {
        return service.importMrpackDetailed(v1(), null, null, CancellationToken.NONE).project();
    }

    Path game(Project p) {
        return projects.gameDir(p);
    }

    static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    static void snapshotInto(Map<String, String> out, Path root) {
        out.clear();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith(".modpack-update-")) {
                    out.put(rel, Hashing.hash(p, HashAlgo.SHA1));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    UpdateAction actionOf(UpdatePlan plan, String path) {
        return plan.items().stream().filter(i -> i.path().equals(path)).findFirst().map(UpdateItem::action).orElse(null);
    }

    // ---------------------------------------------------------------- the baseline

    @Test
    void importingRecordsTheOriginalFilesAndTheirHashes() throws Exception {
        Project p = install();
        PackBaseline b = PackBaseline.read(projects.projectDir(p.id)).orElseThrow();
        assertEquals("Speedy Pack", b.packName);
        assertEquals("1.0.0", b.packVersion);
        assertEquals("mrpack", b.format);
        assertEquals(9, b.files.size());
        assertEquals(Hashing.hash(game(p).resolve("mods/a.jar"), HashAlgo.SHA1), b.files.get("mods/a.jar"));
        assertEquals(Hashing.hash(text("b=1"), HashAlgo.SHA1), b.files.get("config/b.toml"));
        assertTrue(updater.canUpdate(p));
    }

    // ---------------------------------------------------------------- the four cases, in the preview

    @Test
    void thePreviewTellsEveryCaseApart() throws Exception {
        Project p = install();
        Path g = game(p);
        Files.writeString(g.resolve("config/a.toml"), "a=mine");          // edited by the player, pack unchanged
        Files.writeString(g.resolve("config/c.toml"), "c=mine");          // edited, and the pack changed it too
        Files.delete(g.resolve("config/d.toml"));                          // removed by the player, pack changed it
        Files.write(g.resolve("mods/mine.jar"), mod("mine", "*"));         // added by the player
        Files.writeString(g.resolve("config/mine.toml"), "x=1");           // added by the player
        Files.move(g.resolve("mods/e.jar"), g.resolve("mods/e.jar.disabled")); // turned off by the player

        UpdatePlan plan = updater.plan(p, v2(), CancellationToken.NONE);

        assertTrue(plan.samePack());
        assertEquals("1.0.0", plan.fromVersion());
        assertEquals("2.0.0", plan.toVersion());
        assertEquals(UpdateAction.UPDATE, actionOf(plan, "mods/b.jar"), "changed by the pack only");
        assertEquals(UpdateAction.REMOVE, actionOf(plan, "mods/c.jar"), "dropped by the pack, untouched");
        assertEquals(UpdateAction.ADD, actionOf(plan, "mods/f.jar"), "new in the pack");
        assertEquals(UpdateAction.ADD, actionOf(plan, "config/g.toml"));
        assertEquals(UpdateAction.UPDATE, actionOf(plan, "config/b.toml"));
        assertEquals(UpdateAction.KEEP_CONFLICT, actionOf(plan, "config/c.toml"), "edited by both");
        assertEquals(UpdateAction.KEEP_REMOVED, actionOf(plan, "config/d.toml"), "the player deleted it");
        assertEquals(UpdateAction.KEEP_ADDED, actionOf(plan, "mods/mine.jar"));
        assertEquals(UpdateAction.KEEP_ADDED, actionOf(plan, "config/mine.toml"));
        assertEquals(UpdateAction.UPDATE, actionOf(plan, "mods/e.jar"));
        assertTrue(plan.items().stream().filter(i -> i.path().equals("mods/e.jar")).findFirst().orElseThrow().disabled());
        assertEquals(null, actionOf(plan, "mods/a.jar"), "nothing to say about an unchanged file");
        assertEquals(null, actionOf(plan, "config/same.toml"));
        assertTrue(plan.changesGame(), "26.3 to 26.3.1");
        // the preview changed nothing
        assertEquals("a=mine", read(g.resolve("config/a.toml")));
        assertTrue(Files.exists(g.resolve("mods/c.jar")));
        assertEquals("1.0.0", PackBaseline.read(projects.projectDir(p.id)).orElseThrow().packVersion);
        assertTrue(events.isEmpty());
    }

    @Test
    void anEditedFileThatThePackLeavesAloneOrDropsIsKept() throws Exception {
        Project p = install();
        Files.writeString(game(p).resolve("config/a.toml"), "a=mine");
        Files.writeString(game(p).resolve("config/b.toml"), "b=mine");
        UpdatePlan plan = updater.plan(p, v2(), CancellationToken.NONE);
        assertEquals(UpdateAction.KEEP_EDITED, actionOf(plan, "config/a.toml"), "pack unchanged, player edited");
        assertEquals(UpdateAction.KEEP_CONFLICT, actionOf(plan, "config/b.toml"));
        // a dropped file the player edited stays
        Files.write(game(p).resolve("mods/c.jar"), mod("c-mine", "*"));
        assertEquals(UpdateAction.KEEP_EDITED, actionOf(updater.plan(p, v2(), CancellationToken.NONE), "mods/c.jar"));
    }

    @Test
    void theMergeTableCoversEveryCombination() {
        // path -> (baseline, current, next)
        Map<String, String> b = new LinkedHashMap<>();
        Map<String, String> c = new LinkedHashMap<>();
        Map<String, String> n = new LinkedHashMap<>();
        row(b, c, n, "added-absent", null, null, "N");
        row(b, c, n, "added-same", null, "N", "N");
        row(b, c, n, "added-clash", null, "X", "N");
        row(b, c, n, "dropped-gone", "B", null, null);
        row(b, c, n, "dropped-untouched", "B", "B", null);
        row(b, c, n, "dropped-edited", "B", "X", null);
        row(b, c, n, "removed-same-pack", "B", null, "B");
        row(b, c, n, "removed-changed-pack", "B", null, "N");
        row(b, c, n, "untouched-unchanged", "B", "B", "B");
        row(b, c, n, "untouched-changed", "B", "B", "N");
        row(b, c, n, "edited-unchanged", "B", "X", "B");
        row(b, c, n, "edited-became-same", "B", "N", "N");
        row(b, c, n, "edited-both", "B", "X", "N");
        Map<String, UpdateAction> got = new LinkedHashMap<>();
        ModpackUpdater.diff(b, c, n, Set.of()).forEach(i -> got.put(i.path(), i.action()));
        assertEquals(UpdateAction.ADD, got.get("added-absent"));
        assertEquals(null, got.get("added-same"));
        assertEquals(UpdateAction.KEEP_CONFLICT, got.get("added-clash"));
        assertEquals(null, got.get("dropped-gone"));
        assertEquals(UpdateAction.REMOVE, got.get("dropped-untouched"));
        assertEquals(UpdateAction.KEEP_EDITED, got.get("dropped-edited"));
        assertEquals(UpdateAction.KEEP_REMOVED, got.get("removed-same-pack"));
        assertEquals(UpdateAction.KEEP_REMOVED, got.get("removed-changed-pack"));
        assertEquals(null, got.get("untouched-unchanged"));
        assertEquals(UpdateAction.UPDATE, got.get("untouched-changed"));
        assertEquals(UpdateAction.KEEP_EDITED, got.get("edited-unchanged"));
        assertEquals(null, got.get("edited-became-same"));
        assertEquals(UpdateAction.KEEP_CONFLICT, got.get("edited-both"));
    }

    private static void row(Map<String, String> b, Map<String, String> c, Map<String, String> n, String path, String bv, String cv, String nv) {
        if (bv != null) {
            b.put(path, bv);
        }
        if (cv != null) {
            c.put(path, cv);
        }
        if (nv != null) {
            n.put(path, nv);
        }
    }

    // ---------------------------------------------------------------- applying

    @Test
    void applyingUpdatesThePackAndKeepsThePlayersChanges() throws Exception {
        Project p = install();
        Path g = game(p);
        Files.writeString(g.resolve("config/a.toml"), "a=mine");
        Files.writeString(g.resolve("config/c.toml"), "c=mine");
        Files.delete(g.resolve("config/d.toml"));
        Files.write(g.resolve("mods/mine.jar"), mod("mine", "*"));
        Files.move(g.resolve("mods/e.jar"), g.resolve("mods/e.jar.disabled"));
        Path v2 = v2();

        ModpackUpdater.Result r = updater.apply(p, v2, null, CancellationToken.NONE);

        // changed by the pack
        assertEquals(Hashing.hash(mod("b-new", "*"), HashAlgo.SHA1), Hashing.hash(g.resolve("mods/b.jar"), HashAlgo.SHA1));
        assertEquals("b=2", read(g.resolve("config/b.toml")));
        assertTrue(Files.exists(g.resolve("mods/f.jar")), "added");
        assertEquals("g=1", read(g.resolve("config/g.toml")));
        assertFalse(Files.exists(g.resolve("mods/c.jar")), "dropped by the pack");
        // the player's changes
        assertEquals("a=mine", read(g.resolve("config/a.toml")));
        assertEquals("c=mine", read(g.resolve("config/c.toml")), "edited by both: the player's file stays");
        assertFalse(Files.exists(g.resolve("config/d.toml")), "stays deleted");
        assertTrue(Files.exists(g.resolve("mods/mine.jar")));
        // a mod the player switched off is updated and stays off
        assertFalse(Files.exists(g.resolve("mods/e.jar")));
        assertEquals(Hashing.hash(mod("e-new", "*"), HashAlgo.SHA1), Hashing.hash(g.resolve("mods/e.jar.disabled"), HashAlgo.SHA1));
        // bookkeeping
        assertEquals(List.of("backup"), events);
        assertFalse(filesAtBackup.isEmpty());
        PackBaseline nb = PackBaseline.read(projects.projectDir(p.id)).orElseThrow();
        assertEquals("2.0.0", nb.packVersion);
        assertTrue(nb.files.containsKey("mods/f.jar"));
        assertFalse(nb.files.containsKey("mods/c.jar"));
        Project after = projects.get(p.id).orElseThrow();
        assertEquals("26.3.1", after.minecraftVersion);
        assertEquals(LoaderType.FABRIC, after.loader.type);
        assertTrue(after.content.stream().anyMatch(e -> "f.jar".equals(e.fileName)), "new mods are recorded");
        assertTrue(after.content.stream().noneMatch(e -> "c.jar".equals(e.fileName) && !e.missing), "removed mods are not listed");
        assertTrue(r.plan().changesFiles());
        try (var walk = Files.walk(g)) {
            assertTrue(walk.noneMatch(x -> x.getFileName().toString().startsWith(".modpack-update-")), "no staging left behind");
        }
        // a second update to the same pack has nothing left to do
        UpdatePlan again = updater.plan(after, v2, CancellationToken.NONE);
        assertFalse(again.changesFiles());
        assertFalse(again.changesGame());
    }

    @Test
    void theBackupIsTakenBeforeAnyFileChanges() throws Exception {
        Project p = install();
        Map<String, String> before = new LinkedHashMap<>();
        snapshotInto(before, game(p));
        updater.apply(p, v2(), null, CancellationToken.NONE);
        assertEquals(before, filesAtBackup, "at backup time the project still had its old files");
    }

    @Test
    void aFailedBackupStopsTheUpdateBeforeAnythingChanges() throws Exception {
        Project p = install();
        ModpackUpdater strict = new ModpackUpdater(service, projects, new ContentService(paths, projects, downloads), downloads,
            (pr, pl, ct) -> {
                throw new LauncherException(ErrorCode.IO, "error.io", "backup", "disk full");
            });
        Map<String, String> before = new LinkedHashMap<>();
        snapshotInto(before, game(p));
        assertThrows(LauncherException.class, () -> strict.apply(p, v2(), null, CancellationToken.NONE));
        Map<String, String> after = new LinkedHashMap<>();
        snapshotInto(after, game(p));
        assertEquals(before, after);
        assertEquals("1.0.0", PackBaseline.read(projects.projectDir(p.id)).orElseThrow().packVersion);
    }

    @Test
    void aFailedDownloadChangesNothing() throws Exception {
        Project p = install();
        Path pack = v2();
        server.bytes("/files/" + Hashing.hash(mod("f", "*"), HashAlgo.SHA1) + "/mods_f.jar", utf8("not the file")); // wrong bytes
        Map<String, String> before = new LinkedHashMap<>();
        snapshotInto(before, game(p));
        LauncherException e = assertThrows(LauncherException.class, () -> updater.apply(p, pack, null, CancellationToken.NONE));
        assertNotNull(e);
        Map<String, String> after = new LinkedHashMap<>();
        snapshotInto(after, game(p));
        assertEquals(before, after);
        assertTrue(events.isEmpty(), "no backup was needed, nothing had started");
    }

    @Test
    void aFailureWhileSavingPutsEveryFileBack() throws Exception {
        Project p = install();
        Files.writeString(game(p).resolve("config/a.toml"), "a=mine");
        Map<String, String> before = new LinkedHashMap<>();
        snapshotInto(before, game(p));
        Path pack = v2();
        projects.failSave = true;
        assertThrows(LauncherException.class, () -> updater.apply(p, pack, null, CancellationToken.NONE));
        projects.failSave = false;
        Map<String, String> after = new LinkedHashMap<>();
        snapshotInto(after, game(p));
        assertEquals(before, after, "files restored, including the removed c.jar");
        PackBaseline b = PackBaseline.read(projects.projectDir(p.id)).orElseThrow();
        assertEquals("1.0.0", b.packVersion, "the record still describes version 1");
        assertEquals("26.3", projects.get(p.id).orElseThrow().minecraftVersion);
    }

    @Test
    void cancellingMidwayPutsEveryFileBack() throws Exception {
        Project p = install();
        Map<String, String> before = new LinkedHashMap<>();
        snapshotInto(before, game(p));
        Path pack = v2();
        AtomicInteger seen = new AtomicInteger();
        CancellationToken token = new CancellationToken();
        LauncherException e = assertThrows(LauncherException.class, () -> updater.apply(p, pack, (stage, detail, done, total) -> {
            if (stage.equals("modpack.update.stage.applying") && done >= 2 && seen.incrementAndGet() == 1) {
                token.cancel();
            }
        }, token));
        assertTrue(e.isCancelled());
        Map<String, String> after = new LinkedHashMap<>();
        snapshotInto(after, game(p));
        assertEquals(before, after);
    }

    @Test
    void aProjectWithoutARecordedOriginalOrAnotherFormatIsRefusedClearly() throws Exception {
        Project p = install();
        Files.delete(projects.projectDir(p.id).resolve(PackBaseline.FILE));
        assertFalse(updater.canUpdate(p));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class, () -> updater.plan(p, v2(), null)).code());
        Project q = install2();
        Path notAPack = Files.write(dir.resolve("in/plain.zip"), zip(Map.of("readme.txt", utf8("hi"))));
        assertEquals(ErrorCode.INVALID_ARCHIVE, assertThrows(LauncherException.class, () -> updater.plan(q, notAPack, null)).code());
    }

    Project install2() throws Exception {
        // the same project folder is reused by the test factory, so only the record is restored
        Project p = projects.get("pack").orElseThrow();
        Path packFile = v1();
        service.importMrpackDetailed(packFile, null, null, CancellationToken.NONE);
        return projects.get(p.id).orElseThrow();
    }

    @Test
    void anotherPackIsFlaggedSoTheUserCanConfirm() throws Exception {
        Project p = install();
        Path other = pack("other.mrpack", "Other Pack", "1.0.0", "26.3", Map.of("mods/z.jar", mod("z", "*")), Map.of());
        UpdatePlan plan = updater.plan(p, other, null);
        assertFalse(plan.samePack());
        assertEquals("Other Pack", plan.packName());
    }
}
