package dev.shardlight.client.project;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.SafeZip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectServiceTest {
    @TempDir
    Path dir;
    AppPaths paths;
    ProjectService svc;

    @BeforeEach
    void setUp() {
        paths = new AppPaths(dir.resolve("data"));
        svc = new ProjectService(paths);
    }

    private Project make(String name) throws LauncherException {
        return svc.create(NewProjectRequest.vanilla(name, "1.21.4"));
    }

    private static LauncherException fails(ErrorCode code, Executable e) {
        LauncherException ex = assertThrows(LauncherException.class, e::run);
        assertEquals(code, ex.code(), ex.toString());
        return ex;
    }

    @FunctionalInterface
    interface Executable {
        void run() throws Exception;
    }

    // ---- create / list / get / update ---------------------------------------------------------------------

    @Test
    void createWritesProjectJsonAndAnEmptyGameDirectory() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        svc.addListener(changes::incrementAndGet);
        JavaSettings java = new JavaSettings();
        java.maxMemoryMb = 6144;
        Project p = svc.create(new NewProjectRequest("  My World  ", "creeper", "shardlight-default", "1.21.4",
            new LoaderSpec(LoaderType.FABRIC, "0.16.10"), Set.of("ui", "map"), "ember", Preset.ULTRA_PERFORMANCE, java));
        assertEquals("my-world", p.id);
        assertEquals("My World", p.name);
        assertEquals("builtin:creeper", p.icon);
        assertEquals("shardlight-default", p.template);
        assertEquals(LoaderType.FABRIC, p.loader.type);
        assertEquals("0.16.10", p.loader.version);
        assertEquals(Set.of("ui", "map"), p.shardlightModules);
        assertEquals("ember", p.shardlightTheme);
        assertEquals(Preset.ULTRA_PERFORMANCE, p.preset.active);
        assertEquals(6144, p.java.maxMemoryMb);
        assertTrue(p.createdAt > 0);
        assertEquals(0, p.lastPlayedAt);
        Path game = svc.gameDir(p);
        for (String d : GameDirs.CREATED) {
            assertTrue(Files.isDirectory(game.resolve(d)), d);
        }
        JsonObject o = JsonParser.parseString(Files.readString(svc.projectDir("my-world").resolve("project.json"))).getAsJsonObject();
        assertEquals(1, o.get("schemaVersion").getAsInt());
        assertEquals("my-world", o.get("id").getAsString());
        assertEquals(1, changes.get());
        assertFalse(TestFiles.anyStaging(paths.projects()));
        // the request's Java settings were copied, not shared
        java.maxMemoryMb = 1;
        assertEquals(6144, svc.get("my-world").orElseThrow().java.maxMemoryMb);
    }

    @Test
    void createDefaultsAndValidation() throws Exception {
        Project p = svc.create(new NewProjectRequest("Defaults", null, null, "26.1", null, null, null, null, null));
        assertEquals(ProjectService.DEFAULT_ICON, p.icon);
        assertEquals("custom", p.template);
        assertTrue(p.loader.isVanilla());
        assertTrue(p.shardlightModules.isEmpty());
        assertNull(p.shardlightTheme);
        assertEquals(Preset.BALANCED, p.preset.active);
        assertTrue(p.content.isEmpty());

        assertEquals("project.error.nameEmpty", fails(ErrorCode.INVALID_DATA, () -> make("   ")).messageKey());
        assertEquals("project.error.nameEmpty", fails(ErrorCode.INVALID_DATA, () -> make(null)).messageKey());
        assertEquals("project.error.nameTooLong", fails(ErrorCode.INVALID_DATA, () -> make("x".repeat(65))).messageKey());
        assertEquals("project.error.nameOnlySymbols", fails(ErrorCode.INVALID_DATA, () -> make("!!! ??? ***")).messageKey());
        assertEquals("project.error.nameOnlySymbols", fails(ErrorCode.INVALID_DATA, () -> make("\uD83C\uDFAE\uD83C\uDFAE")).messageKey());
        assertEquals("project.error.nameInvalid", fails(ErrorCode.INVALID_DATA, () -> make("two\nlines")).messageKey());
        assertEquals(64, make("y".repeat(64)).name.length());
        // 64 characters even when they take two chars each
        String wide = make("\uD83D\uDE00a".repeat(32)).name;
        assertEquals(64, wide.codePointCount(0, wide.length()));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(NewProjectRequest.vanilla("Bad version", "../../x")));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(NewProjectRequest.vanilla("No version", null)));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(new NewProjectRequest("T", null, "no-such-template", "1.21", null, null,
            null, null, null)));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(new NewProjectRequest("T", "../evil", null, "1.21", null, null, null,
            null, null)));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(new NewProjectRequest("T", null, null, "1.21", null, Set.of("../x"),
            null, null, null)));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(new NewProjectRequest("T", null, null, "1.21",
            new LoaderSpec(LoaderType.FABRIC, "a/b"), null, null, null, null)));
        assertFalse(TestFiles.anyStaging(paths.projects()));
    }

    @Test
    void idGenerationEdgeCases() throws Exception {
        assertEquals("cafe-ete", make("Caf\u00e9 \u00c9t\u00e9!").id);
        assertEquals("project", make("\u65e5\u672c\u8a9e").id);
        assertEquals("project-2", make("\u4e16\u754c").id);
        assertEquals("con-2", make("CON").id, "Windows reserved names are never used");
        assertEquals("nul-2", make("nul").id);
        assertEquals("com1-2", make("Com1").id);
        assertEquals("lpt9-2", make("LPT9").id);
        assertEquals("a".repeat(40), make("a".repeat(64)).id);
        assertEquals("a".repeat(38) + "-2", make("a".repeat(50)).id, "suffix fits in 40 characters");
        assertEquals("my-pack", make("My Pack").id);
        assertEquals("my-pack-2", make("my pack").id);
        assertEquals("my-pack-3", make("MY   PACK").id);
        assertEquals("x-y", make("--x__y--").id);
        // a folder created by hand with capitals still blocks the id (Windows is case-insensitive)
        Files.createDirectories(paths.projects().resolve("Other-Pack"));
        assertEquals("other-pack-2", make("other pack").id);
        assertEquals("other-pack-3", svc.idFor("Other Pack"));
        for (Project p : svc.list()) {
            assertTrue(p.id.matches("[a-z0-9-]{1,40}"), p.id);
        }
    }

    @Test
    void namesHelpers() {
        assertEquals("hello-world-2", Names.slug("  Hello, World #2 "));
        assertEquals("", Names.fileLabel("!!!", 10));
        assertEquals("before-upd", Names.fileLabel("Before update", 10));
        assertEquals("aux-2", Names.uniqueId("aux", s -> false));
        assertEquals("abc-3", Names.uniqueId("abc", s -> s.equals("abc") || s.equals("abc-2")));
        assertTrue(Names.isWindowsReserved("PRN"));
        assertFalse(Names.isWindowsReserved("prn-1"));
    }

    @Test
    void listIsSortedByLastPlayedThenName() throws Exception {
        make("Bravo");
        make("alpha");
        make("Charlie");
        svc.update("charlie", p -> p.lastPlayedAt = 5000);
        svc.update("bravo", p -> p.lastPlayedAt = 9000);
        List<String> ids = svc.list().stream().map(p -> p.id).toList();
        assertEquals(List.of("bravo", "charlie", "alpha"), ids);
    }

    @Test
    void updateRenameAndSave() throws Exception {
        Project p = make("Original");
        Project renamed = svc.rename(p.id, "  Shiny New Name ");
        assertEquals("Shiny New Name", renamed.name);
        assertEquals("original", renamed.id, "the id and folder stay");
        assertTrue(Files.exists(paths.project("original")));
        assertEquals("Shiny New Name", svc.get("original").orElseThrow().name);
        // update can't change the id
        Project u = svc.update("original", x -> {
            x.id = "hijack";
            x.notes = "hello";
        });
        assertEquals("original", u.id);
        assertFalse(Files.exists(paths.project("hijack")));
        // save of a copy
        Project copy = svc.get("original").orElseThrow();
        copy.totalPlayMillis = 1234;
        svc.save(copy);
        assertEquals(1234, svc.get("original").orElseThrow().totalPlayMillis);
        assertTrue(Files.exists(paths.project("original").resolve("project.json.bak")), "previous version kept");
        fails(ErrorCode.INVALID_DATA, () -> svc.rename("original", ""));
        fails(ErrorCode.NOT_FOUND, () -> svc.rename("missing", "x"));
        Project ghost = Json.copy(copy, Project.class);
        ghost.id = "ghost";
        fails(ErrorCode.NOT_FOUND, () -> svc.save(ghost));
        assertFalse(Files.exists(paths.project("ghost")), "save never creates a project folder");
    }

    @Test
    void idsCantEscapeTheProjectsFolder() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> svc.projectDir("../evil"));
        assertThrows(IllegalArgumentException.class, () -> svc.projectDir("a/b"));
        assertThrows(IllegalArgumentException.class, () -> svc.projectDir(".staging-x"));
        assertTrue(svc.get("../../etc").isEmpty());
        assertTrue(svc.get(null).isEmpty());
        Project p = make("Victim");
        p.id = "../outside";
        fails(ErrorCode.NOT_FOUND, () -> svc.save(p));
        assertFalse(Files.exists(dir.resolve("outside")));
    }

    // ---- duplicate ------------------------------------------------------------------------------------------

    private Project populated(String name) throws Exception {
        Project p = make(name);
        Path game = svc.gameDir(p);
        Files.writeString(game.resolve("mods/sodium.jar"), "jar bytes");
        Files.writeString(game.resolve("mods/old.jar.disabled"), "disabled jar");
        Files.writeString(game.resolve("config/sodium-options.json"), "{\"quality\":1}");
        Files.createDirectories(game.resolve("config/sub"));
        Files.writeString(game.resolve("config/sub/deep.toml"), "a = 1");
        Files.writeString(game.resolve("options.txt"), "fov:0.5\n");
        Files.writeString(game.resolve("resourcepacks/pack.zip"), "pack");
        Files.writeString(game.resolve("shaderpacks/shader.zip"), "shader");
        TestFiles.world(game.resolve("saves"), "World", "My World", 1000, "1.21.4");
        Files.write(game.resolve("screenshots/2024-05-01_12.00.00.png"), TestFiles.PNG);
        Files.writeString(game.resolve("logs/latest.log"), "log");
        Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(game.resolve("crash-reports/crash.txt"), "crash");
        ContentEntry e = new ContentEntry();
        e.id = "e1";
        e.kind = ContentKind.MOD;
        e.source = ContentSource.MODRINTH;
        e.fileName = "sodium.jar";
        e.title = "Sodium";
        return svc.update(p.id, x -> {
            x.content.add(e);
            x.lastPlayedAt = 777;
            x.totalPlayMillis = 999;
            x.favorite = true;
            x.java.javaPath = "C:\\Java\\bin\\javaw.exe";
        });
    }

    @Test
    void duplicateCopiesEverythingAndSharesNoFile() throws Exception {
        Project src = populated("Source");
        Project copy = svc.duplicate(src.id, "Source Copy", CloneOptions.EVERYTHING);
        assertEquals("source-copy", copy.id);
        assertEquals("Source Copy", copy.name);
        assertEquals(0, copy.lastPlayedAt);
        assertEquals(0, copy.totalPlayMillis);
        assertFalse(copy.favorite);
        assertTrue(copy.createdAt >= src.createdAt);
        assertEquals(1, copy.content.size());
        Map<String, String> a = TestFiles.tree(svc.gameDir(src));
        Map<String, String> b = TestFiles.tree(svc.gameDir(copy));
        a.remove("saves/World/session.lock");
        assertEquals(a, b, "same content except the world lock");
        assertFalse(Files.exists(svc.gameDir(copy).resolve("saves/World/session.lock")));

        // isolation: editing the copy never changes the original
        Files.writeString(svc.gameDir(copy).resolve("config/sodium-options.json"), "{\"quality\":0}");
        Files.delete(svc.gameDir(copy).resolve("mods/sodium.jar"));
        Files.writeString(svc.gameDir(copy).resolve("options.txt"), "fov:1.0\n");
        assertEquals("{\"quality\":1}", Files.readString(svc.gameDir(src).resolve("config/sodium-options.json")));
        assertTrue(Files.exists(svc.gameDir(src).resolve("mods/sodium.jar")));
        assertEquals("fov:0.5\n", Files.readString(svc.gameDir(src).resolve("options.txt")));
        svc.update(copy.id, x -> x.content.clear());
        assertEquals(1, svc.get(src.id).orElseThrow().content.size());

        // two projects never share a file (no hard links): compare file keys
        Set<Object> keysA = fileKeys(svc.projectDir(src.id));
        Set<Object> keysB = fileKeys(svc.projectDir(copy.id));
        keysA.retainAll(keysB);
        assertTrue(keysA.isEmpty(), "shared files: " + keysA);
    }

    private static Set<Object> fileKeys(Path root) throws IOException {
        Set<Object> keys = new HashSet<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                Object k = Files.readAttributes(p, BasicFileAttributes.class).fileKey();
                if (k != null) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    @Test
    void duplicateHonoursCloneOptions() throws Exception {
        Project src = populated("Setup");
        Project copy = svc.duplicate(src.id, "Setup Only", CloneOptions.SETUP_ONLY);
        Path game = svc.gameDir(copy);
        assertTrue(Files.exists(game.resolve("mods/sodium.jar")));
        assertTrue(Files.exists(game.resolve("config/sub/deep.toml")));
        assertTrue(Files.exists(game.resolve("options.txt")));
        for (String d : List.of("saves", "screenshots", "logs")) {
            assertTrue(Files.isDirectory(game.resolve(d)), d + " exists");
            try (Stream<Path> s = Files.list(game.resolve(d))) {
                assertEquals(0, s.count(), d + " is empty");
            }
        }
        assertFalse(Files.exists(game.resolve("crash-reports")));
        Project worldsOnly = svc.duplicate(src.id, "With Worlds", new CloneOptions(true, false, false));
        assertTrue(Files.exists(svc.gameDir(worldsOnly).resolve("saves/World/level.dat")));
        fails(ErrorCode.NOT_FOUND, () -> svc.duplicate("missing", "x", CloneOptions.EVERYTHING));
        fails(ErrorCode.INVALID_DATA, () -> svc.duplicate(src.id, "", CloneOptions.EVERYTHING));
    }

    @Test
    void cancelledDuplicateLeavesNothingBehind() throws Exception {
        Project src = populated("Big");
        dev.shardlight.client.task.CancellationToken token = new dev.shardlight.client.task.CancellationToken();
        LauncherException e = assertThrows(LauncherException.class, () -> svc.duplicate(src.id, "Big Copy",
            CloneOptions.EVERYTHING, (stage, detail, done, total) -> token.cancel(), token));
        assertTrue(e.isCancelled());
        assertEquals(1, svc.list().size());
        assertFalse(TestFiles.anyStaging(paths.projects()));
    }

    // ---- trash ----------------------------------------------------------------------------------------------

    @Test
    void archiveRestoreAndDeletePermanently() throws Exception {
        Project p = populated("Keeper");
        RemovalPlan plan = svc.planRemoval(p.id);
        assertEquals(1, plan.worlds());
        assertEquals(1, plan.screenshots());
        assertEquals(2, plan.mods());
        assertEquals(1, plan.resourcePacks());
        assertTrue(plan.hasConfig());
        assertTrue(plan.totalBytes() > 40_000);
        assertEquals("Keeper", plan.name());

        ArchivedProject a = svc.archive(p.id);
        assertTrue(a.trashId().matches("keeper-\\d{8}-\\d{6}"), a.trashId());
        assertEquals("keeper", a.projectId());
        assertEquals("Keeper", a.name());
        assertEquals(plan.totalBytes(), a.sizeBytes());
        assertTrue(svc.list().isEmpty());
        assertTrue(svc.get("keeper").isEmpty());
        assertTrue(Files.isDirectory(paths.trash().resolve(a.trashId()).resolve("minecraft/saves/World")));
        assertTrue(Files.readString(paths.trash().resolve("index.json")).contains(a.trashId()));
        assertEquals(List.of(a), svc.archived());

        Project back = svc.restore(a.trashId());
        assertEquals("keeper", back.id);
        assertEquals("Keeper", back.name);
        assertTrue(svc.archived().isEmpty());
        assertTrue(Files.exists(svc.gameDir(back).resolve("mods/sodium.jar")));

        // the old id is taken by a new project: the restored one gets a new id
        ArchivedProject again = svc.archive("keeper");
        make("Keeper");
        Project second = svc.restore(again.trashId());
        assertEquals("keeper-2", second.id);
        assertEquals("keeper-2", JsonParser.parseString(Files.readString(svc.projectDir("keeper-2").resolve("project.json")))
            .getAsJsonObject().get("id").getAsString());
        assertEquals(2, svc.list().size());

        ArchivedProject gone = svc.archive("keeper-2");
        svc.deletePermanently(gone.trashId());
        assertFalse(Files.exists(paths.trash().resolve(gone.trashId())));
        assertTrue(svc.archived().isEmpty());
        fails(ErrorCode.NOT_FOUND, () -> svc.restore(gone.trashId()));
        fails(ErrorCode.NOT_FOUND, () -> svc.deletePermanently(gone.trashId()));
        fails(ErrorCode.NOT_FOUND, () -> svc.archive("keeper-2"));
    }

    @Test
    void trashIndexIsRebuiltFromFolders() throws Exception {
        make("Lost Index");
        ArchivedProject a = svc.archive("lost-index");
        Files.writeString(paths.trash().resolve("index.json"), "{ broken");
        List<ArchivedProject> listed = svc.archived();
        assertEquals(1, listed.size());
        assertEquals(a.trashId(), listed.get(0).trashId());
        assertEquals("lost-index", listed.get(0).projectId());
        assertEquals("Lost Index", listed.get(0).name());
        assertEquals("lost-index", svc.restore(a.trashId()).id);
    }

    @Test
    void deletePermanentlyStaysInsideTrashAndNeverFollowsLinks() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("precious"));
        Files.writeString(outside.resolve("keep.txt"), "do not delete");
        Project p = make("Linked");
        ArchivedProject a = svc.archive(p.id);
        Path archived = paths.trash().resolve(a.trashId());
        try {
            Files.createSymbolicLink(archived.resolve("minecraft/link-to-outside"), outside);
            Files.createSymbolicLink(paths.trash().resolve("evil-link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symlinks on this file system
        }
        svc.deletePermanently(a.trashId());
        assertFalse(Files.exists(archived));
        svc.deletePermanently("evil-link");
        assertFalse(Files.exists(paths.trash().resolve("evil-link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("do not delete", Files.readString(outside.resolve("keep.txt")));

        for (String bad : new String[] {"..", "../projects", "a/b", "a\\b", "", "index.json", ".hidden", "C:\\x"}) {
            LauncherException e = assertThrows(LauncherException.class, () -> svc.deletePermanently(bad), bad);
            assertTrue(e.code() == ErrorCode.UNSAFE_PATH || e.code() == ErrorCode.NOT_FOUND, bad + " " + e);
        }
        assertTrue(Files.isDirectory(paths.projects()));
        assertTrue(Files.exists(paths.trash()));
    }

    @Test
    void archivingAProjectInUseChangesNothing() throws Exception {
        Project p = populated("Busy");
        Map<String, String> before = TestFiles.tree(svc.projectDir(p.id));
        svc.mover = (from, to) -> {
            throw new FileSystemException(from.toString(), null,
                "The process cannot access the file because it is being used by another process");
        };
        LauncherException e = fails(ErrorCode.IO, () -> svc.archive(p.id));
        assertEquals("project.error.inUse", e.messageKey());
        assertTrue(e.userMessage().contains("Close Minecraft"), e.userMessage());
        svc.mover = FileTrees::move;
        assertEquals(1, svc.list().size());
        assertEquals(before, TestFiles.tree(svc.projectDir(p.id)));
        assertTrue(svc.archived().isEmpty());
        try (Stream<Path> s = Files.list(paths.trash())) {
            assertTrue(s.allMatch(x -> x.getFileName().toString().equals("index.json")));
        }
    }

    // ---- schema, migration, recovery ------------------------------------------------------------------------

    @Test
    void migratesSchemaZeroToOne() throws Exception {
        Path pdir = Files.createDirectories(paths.project("old-one").resolve("minecraft"));
        String old = "{\"id\":\"old-one\",\"name\":\"Old One\",\"minecraftVersion\":\"1.20.1\",\"java\":null}";
        Files.writeString(pdir.getParent().resolve("project.json"), old);
        Project p = svc.get("old-one").orElseThrow();
        assertEquals("Old One", p.name);
        assertEquals(1, p.schemaVersion);
        assertEquals(4096, p.java.maxMemoryMb);
        assertEquals(1280, p.window.width);
        assertEquals(Preset.BALANCED, p.preset.active);
        assertTrue(p.content.isEmpty());
        assertTrue(p.shardlightModules.isEmpty());
        assertTrue(p.loader.isVanilla());
        JsonObject onDisk = JsonParser.parseString(Files.readString(paths.project("old-one").resolve("project.json")))
            .getAsJsonObject();
        assertEquals(1, onDisk.get("schemaVersion").getAsInt());
        assertTrue(onDisk.get("java").isJsonObject());
        assertEquals(old, Files.readString(paths.project("old-one").resolve("project.json.pre-migration")));

        JsonObject o = JsonParser.parseString("{\"name\":\"x\",\"content\":5}").getAsJsonObject();
        assertTrue(ProjectService.MIGRATOR.migrate(o));
        for (String k : List.of("java", "window", "preset", "loader")) {
            assertTrue(o.get(k).isJsonObject(), k);
        }
        assertTrue(o.get("content").isJsonArray());
        assertTrue(o.get("shardlightModules").isJsonArray());
        assertEquals(1, o.get("schemaVersion").getAsInt());
    }

    @Test
    void projectFromNewerLauncherIsShownButNeverOverwritten() throws Exception {
        Path pdir = Files.createDirectories(paths.project("future").resolve("minecraft"));
        String text = "{\"schemaVersion\":99,\"id\":\"future\",\"name\":\"Future\",\"minecraftVersion\":\"30.1\","
            + "\"brandNewField\":{\"x\":1}}";
        Path file = pdir.getParent().resolve("project.json");
        Files.writeString(file, text);
        List<Project> all = svc.list();
        assertEquals(1, all.size());
        assertEquals("Future", all.get(0).name);
        assertTrue(svc.isReadOnly("future"));
        assertTrue(svc.problems().stream().anyMatch(s -> s.contains("Future") && s.contains("newer")), svc.problems().toString());
        LauncherException e = fails(ErrorCode.UNSUPPORTED_VERSION, () -> svc.save(all.get(0)));
        assertEquals("project.error.newerSchema", e.messageKey());
        fails(ErrorCode.UNSUPPORTED_VERSION, () -> svc.update("future", p -> p.name = "Changed"));
        fails(ErrorCode.UNSUPPORTED_VERSION, () -> svc.rename("future", "Changed"));
        fails(ErrorCode.UNSUPPORTED_VERSION, () -> svc.setBuiltinIcon("future", "stone"));
        fails(ErrorCode.UNSUPPORTED_VERSION, () -> svc.duplicate("future", "Copy", CloneOptions.EVERYTHING));
        assertEquals(text, Files.readString(file), "untouched");
        assertFalse(Files.exists(pdir.getParent().resolve("project.json.bak")));
        svc.clearProblems();
        assertTrue(svc.problems().isEmpty());
    }

    @Test
    void corruptProjectJsonIsSetAsideAndRestoredFromTheBackupCopy() throws Exception {
        make("Precious");
        svc.rename("precious", "Precious Renamed"); // writes project.json.bak with the previous version
        Path file = paths.project("precious").resolve("project.json");
        Files.writeString(file, "{ \"name\": \"Precious\", oops");
        List<Project> all = svc.list();
        assertEquals(1, all.size());
        assertEquals("precious", all.get(0).id);
        assertEquals("Precious", all.get(0).name, "the previous good copy");
        assertEquals("1.21.4", all.get(0).minecraftVersion);
        try (Stream<Path> s = Files.list(paths.project("precious"))) {
            List<Path> aside = s.filter(x -> x.getFileName().toString().startsWith("project.json.corrupt-")).toList();
            assertEquals(1, aside.size());
            assertEquals("{ \"name\": \"Precious\", oops", Files.readString(aside.get(0)));
        }
        assertEquals(1, svc.problems().size());
        assertTrue(svc.problems().get(0).contains("damaged"), svc.problems().get(0));
        // the repaired file is valid again
        assertNotEquals(null, ProjectService.parseObject(Files.readString(file)));
    }

    @Test
    void corruptProjectJsonWithoutBackupIsRebuiltFromTheFolder() throws Exception {
        Path saves = Files.createDirectories(paths.project("orphan").resolve("minecraft/saves"));
        TestFiles.world(saves, "Old", "Old", 1000, "1.20.4");
        TestFiles.world(saves, "Newer", "Newer", 5000, "1.20.6");
        Files.writeString(paths.project("orphan").resolve("project.json"), "");
        Project p = svc.get("orphan").orElseThrow();
        assertEquals("orphan", p.name);
        assertEquals("1.20.6", p.minecraftVersion, "guessed from the most recently played world");
        assertTrue(svc.problems().get(0).contains("rebuilt"), svc.problems().toString());
        assertTrue(Files.exists(paths.project("orphan").resolve("minecraft/saves/Old/level.dat")), "worlds untouched");
        assertEquals(1, svc.list().size());
        assertEquals(1, svc.problems().size(), "reported once");
    }

    @Test
    void listSkipsStrangersAndCleansStaleStaging() throws Exception {
        make("Real");
        Files.createDirectories(paths.projects().resolve("no-project-json"));
        Files.writeString(paths.projects().resolve("stray.txt"), "x");
        Path stale = Files.createDirectories(paths.projects().resolve(".staging-old"));
        Files.writeString(stale.resolve("junk"), "x");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 3L * 24 * 3600 * 1000));
        Path fresh = Files.createDirectories(paths.projects().resolve(".staging-fresh"));
        assertEquals(List.of("real"), svc.list().stream().map(p -> p.id).toList());
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "may belong to a running operation");
    }

    // ---- icons ----------------------------------------------------------------------------------------------

    @Test
    void iconsFromFilesAreCheckedByTheirBytes() throws Exception {
        Project p = make("Iconic");
        Path png = Files.write(dir.resolve("icon.PNG"), TestFiles.PNG);
        Project withIcon = svc.setIconFromFile(p.id, png);
        assertEquals(ProjectService.FILE_ICON, withIcon.icon);
        Path stored = svc.iconFile(withIcon).orElseThrow();
        assertArrayEquals(TestFiles.PNG, Files.readAllBytes(stored));
        assertEquals("icon.png", stored.getFileName().toString());

        byte[] jpeg = new byte[100];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        jpeg[3] = (byte) 0xE0;
        svc.setIconFromFile(p.id, Files.write(dir.resolve("photo.jpg"), jpeg));
        assertArrayEquals(jpeg, Files.readAllBytes(stored));

        Path gif = Files.write(dir.resolve("anim.gif"), "GIF89a....".getBytes(StandardCharsets.US_ASCII));
        assertEquals("project.icon.notImage", fails(ErrorCode.INVALID_DATA, () -> svc.setIconFromFile(p.id, gif)).messageKey());
        Path fake = Files.writeString(dir.resolve("fake.png"), "not really a png at all, just text");
        fails(ErrorCode.INVALID_DATA, () -> svc.setIconFromFile(p.id, fake));
        byte[] huge = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(TestFiles.PNG, 0, huge, 0, TestFiles.PNG.length);
        Path big = Files.write(dir.resolve("big.png"), huge);
        assertEquals("project.icon.tooLarge", fails(ErrorCode.INVALID_DATA, () -> svc.setIconFromFile(p.id, big)).messageKey());
        fails(ErrorCode.NOT_FOUND, () -> svc.setIconFromFile(p.id, dir.resolve("missing.png")));
        assertArrayEquals(jpeg, Files.readAllBytes(stored), "failed attempts left the icon alone");

        Project builtin = svc.setBuiltinIcon(p.id, "builtin:diamond");
        assertEquals("builtin:diamond", builtin.icon);
        assertTrue(svc.iconFile(builtin).isEmpty());
        assertFalse(Files.exists(stored));
        assertEquals("builtin:stone", svc.setBuiltinIcon(p.id, "stone").icon);
        fails(ErrorCode.INVALID_DATA, () -> svc.setBuiltinIcon(p.id, "../../x"));

        Project tricky = svc.get(p.id).orElseThrow();
        tricky.icon = "file:../../outside.png";
        assertTrue(svc.iconFile(tricky).isEmpty());
    }

    // ---- export / import ------------------------------------------------------------------------------------

    @Test
    void exportImportRoundTrip() throws Exception {
        Project src = populated("Traveller");
        svc.setIconFromFile(src.id, Files.write(dir.resolve("i.png"), TestFiles.PNG));
        Path zip = svc.exportProject(src.id, dir.resolve("out/Traveller" + ProjectService.EXPORT_EXTENSION),
            ExportOptions.EVERYTHING);
        assertTrue(Files.isRegularFile(zip));
        assertFalse(Files.exists(dir.resolve("out/Traveller.shardlight.part")));
        Set<String> names = SafeZip.list(zip, SafeZip.Limits.DEFAULT).stream().map(SafeZip.Entry::name)
            .collect(Collectors.toSet());
        assertTrue(names.containsAll(List.of("shardlight-project.json", "project.json", "icon.png", "minecraft/options.txt",
            "minecraft/mods/sodium.jar", "minecraft/saves/World/level.dat", "minecraft/screenshots/2024-05-01_12.00.00.png",
            "minecraft/config/sub/deep.toml", "minecraft/resourcepacks/pack.zip", "minecraft/shaderpacks/shader.zip")), names.toString());
        assertFalse(names.contains("minecraft/saves/World/session.lock"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("minecraft/logs")));
        JsonObject manifest = JsonParser.parseString(new String(SafeZip.read(zip, "shardlight-project.json", 10_000),
            StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, manifest.get("format").getAsInt());
        assertTrue(manifest.has("exportedAt"));
        assertTrue(manifest.has("launcherVersion"));
        JsonObject pj = JsonParser.parseString(new String(SafeZip.read(zip, "project.json", 100_000), StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertFalse(pj.getAsJsonObject("java").has("javaPath"), "Java path cleared");
        assertEquals(1, pj.getAsJsonArray("content").size(), "content list kept");

        Project imported = svc.importProject(zip, null);
        assertEquals("traveller-2", imported.id);
        assertEquals("Traveller", imported.name);
        assertNull(imported.java.javaPath);
        assertEquals(1, imported.content.size());
        assertEquals("sodium.jar", imported.content.get(0).fileName);
        assertEquals(0, imported.lastPlayedAt);
        assertEquals(0, imported.totalPlayMillis);
        assertEquals(ProjectService.FILE_ICON, imported.icon);
        assertArrayEquals(TestFiles.PNG, Files.readAllBytes(svc.iconFile(imported).orElseThrow()));
        Map<String, String> original = TestFiles.tree(svc.gameDir(src));
        original.remove("saves/World/session.lock");
        original.remove("logs/latest.log");
        original.remove("crash-reports/crash.txt");
        assertEquals(original, TestFiles.tree(svc.gameDir(imported)));
        for (String d : GameDirs.CREATED) {
            assertTrue(Files.isDirectory(svc.gameDir(imported).resolve(d)), d);
        }

        Project named = svc.importProject(zip, "  Holiday Copy ");
        assertEquals("holiday-copy", named.id);
        assertEquals("Holiday Copy", named.name);
        assertFalse(TestFiles.anyStaging(paths.projects()));
    }

    @Test
    void shareableExportLeavesWorldsAndScreenshotsOut() throws Exception {
        Project src = populated("Share");
        Path zip = svc.exportProject(src.id, dir.resolve("share.shardlight"), ExportOptions.SHAREABLE);
        Set<String> names = SafeZip.list(zip, SafeZip.Limits.DEFAULT).stream().map(SafeZip.Entry::name)
            .collect(Collectors.toSet());
        assertTrue(names.contains("minecraft/mods/sodium.jar"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("minecraft/saves")));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("minecraft/screenshots")));
        assertTrue(names.contains("minecraft/options.txt"));
        fails(ErrorCode.INVALID_DATA, () -> svc.exportProject(src.id, svc.gameDir(src).resolve("inside.zip"),
            ExportOptions.EVERYTHING));
    }

    private static final String MANIFEST = "{\"format\":1,\"exportedAt\":\"2026-01-01T00:00:00Z\",\"launcherVersion\":\"0.1.0\"}";
    private static final String PROJECT = "{\"schemaVersion\":1,\"id\":\"x\",\"name\":\"Evil\",\"minecraftVersion\":\"1.21.4\"}";

    private void assertImportRejected(ErrorCode code, String... entries) throws Exception {
        Path zip = TestFiles.zip(dir.resolve("bad" + System.nanoTime() + ".shardlight"), entries);
        Set<String> before = TestFiles.tree(dir).keySet();
        LauncherException e = assertThrows(LauncherException.class, () -> svc.importProject(zip, null));
        assertEquals(code, e.code(), e.toString());
        assertTrue(svc.list().isEmpty(), "no project was created");
        assertFalse(TestFiles.anyStaging(paths.projects()), "no staging left behind");
        Set<String> after = new HashSet<>(TestFiles.tree(dir).keySet());
        after.removeIf(n -> n.startsWith("bad"));
        before.removeIf(n -> n.startsWith("bad"));
        assertEquals(before, after, "nothing was written anywhere");
    }

    @Test
    void importRejectsPathTraversalAndStaysClean() throws Exception {
        assertImportRejected(ErrorCode.UNSAFE_PATH, "shardlight-project.json", MANIFEST, "project.json", PROJECT,
            "minecraft/mods/ok.jar", "fine", "minecraft/../../evil.txt", "pwned");
        assertImportRejected(ErrorCode.UNSAFE_PATH, "shardlight-project.json", MANIFEST, "project.json", PROJECT,
            "minecraft/config/..\\..\\..\\evil.txt", "pwned");
        assertImportRejected(ErrorCode.UNSAFE_PATH, "shardlight-project.json", MANIFEST, "project.json", PROJECT,
            "/etc/evil.txt", "pwned");
        assertImportRejected(ErrorCode.UNSAFE_PATH, "shardlight-project.json", MANIFEST, "project.json", PROJECT,
            "minecraft/mods/CON.jar", "reserved");
        assertFalse(Files.exists(dir.resolve("evil.txt")));
        assertFalse(Files.exists(dir.getParent().resolve("evil.txt")));
    }

    @Test
    void importRejectsAnythingOutsideTheLayout() throws Exception {
        assertImportRejected(ErrorCode.INVALID_ARCHIVE, "shardlight-project.json", MANIFEST, "project.json", PROJECT,
            "minecraft/versions/1.21.4/1.21.4.jar", "not allowed");
        assertImportRejected(ErrorCode.INVALID_ARCHIVE, "shardlight-project.json", MANIFEST, "project.json", PROJECT,
            "readme.txt", "extra");
        assertImportRejected(ErrorCode.INVALID_ARCHIVE, "shardlight-project.json", MANIFEST, "project.json", PROJECT,
            "minecraft/mods", "a file where a folder belongs");
        assertImportRejected(ErrorCode.INVALID_ARCHIVE, "project.json", PROJECT, "minecraft/options.txt", "x");
        assertImportRejected(ErrorCode.UNSUPPORTED_VERSION, "shardlight-project.json", "{\"format\":2}", "project.json", PROJECT);
        assertImportRejected(ErrorCode.INVALID_ARCHIVE, "shardlight-project.json", MANIFEST, "project.json", "{ nope");
        assertImportRejected(ErrorCode.INVALID_ARCHIVE, "shardlight-project.json", MANIFEST, "project.json",
            "{\"schemaVersion\":1,\"name\":\"No version\"}");
        assertImportRejected(ErrorCode.UNSUPPORTED_VERSION, "shardlight-project.json", MANIFEST, "project.json",
            "{\"schemaVersion\":7,\"name\":\"Future\",\"minecraftVersion\":\"30.1\"}");
        Path notZip = Files.writeString(dir.resolve("plain.shardlight"), "this is not a zip");
        assertThrows(LauncherException.class, () -> svc.importProject(notZip, null));
        fails(ErrorCode.NOT_FOUND, () -> svc.importProject(dir.resolve("missing.shardlight"), null));
    }

    @Test
    void importSanitizesTheProjectSettings() throws Exception {
        String project = "{\"schemaVersion\":1,\"id\":\"../../escape\",\"name\":\"\",\"minecraftVersion\":\"1.21.4\","
            + "\"icon\":\"file:../../secret.png\",\"template\":\"nonsense\",\"installedVersionId\":\"1.21.4\","
            + "\"java\":{\"javaPath\":\"C:\\\\evil\\\\java.exe\"},\"lastPlayedAt\":99,\"totalPlayMillis\":99,"
            + "\"shardlightModules\":[\"ui\",\"BAD/../x\"],"
            + "\"content\":[{\"id\":\"a\",\"kind\":\"MOD\",\"fileName\":\"../../evil.jar\"},"
            + "{\"id\":\"b\",\"kind\":\"MOD\",\"fileName\":\"good.jar\"}]}";
        Path zip = TestFiles.zip(dir.resolve("odd.shardlight"), "shardlight-project.json", MANIFEST, "project.json", project,
            "minecraft/", null, "minecraft/mods/", null, "minecraft/mods/good.jar", "jar");
        Project p = svc.importProject(zip, null);
        assertEquals("Imported project", p.name);
        assertEquals("imported-project", p.id);
        assertEquals(ProjectService.DEFAULT_ICON, p.icon);
        assertEquals("custom", p.template);
        assertNull(p.installedVersionId);
        assertNull(p.java.javaPath);
        assertEquals(0, p.lastPlayedAt);
        assertEquals(Set.of("ui"), p.shardlightModules);
        assertEquals(List.of("good.jar"), p.content.stream().map(e -> e.fileName).toList());
        assertEquals("jar", Files.readString(svc.gameDir(p).resolve("mods/good.jar")));
    }

    // ---- listeners and concurrency ----------------------------------------------------------------------

    @Test
    void listenersHearEveryChange() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Runnable l = () -> events.add("x");
        svc.addListener(l);
        svc.addListener(() -> {
            throw new IllegalStateException("a broken listener must not break the service");
        });
        Project p = make("Loud");
        svc.rename(p.id, "Louder");
        svc.duplicate(p.id, "Echo", CloneOptions.SETUP_ONLY);
        ArchivedProject a = svc.archive("echo");
        svc.restore(a.trashId());
        assertEquals(5, events.size());
        svc.removeListener(l);
        svc.rename(p.id, "Quiet");
        assertEquals(5, events.size());
    }

    @Test
    void concurrentCreatesGetDistinctIds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Project>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> make("Same Name")));
            }
            Set<String> ids = new HashSet<>();
            for (Future<Project> f : futures) {
                ids.add(f.get().id);
            }
            assertEquals(16, ids.size(), ids.toString());
            assertTrue(ids.contains("same-name"));
            assertTrue(ids.contains("same-name-16"));
            assertEquals(16, svc.list().size());
            // concurrent updates of one project never lose a write
            List<Future<Project>> updates = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                updates.add(pool.submit(() -> svc.update("same-name", p -> p.totalPlayMillis += 10)));
            }
            for (Future<Project> f : updates) {
                f.get();
            }
            assertEquals(200, svc.get("same-name").orElseThrow().totalPlayMillis);
        } finally {
            pool.shutdownNow();
        }
    }
}
