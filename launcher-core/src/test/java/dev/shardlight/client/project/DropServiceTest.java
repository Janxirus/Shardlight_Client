package dev.shardlight.client.project;

import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentService;
import dev.shardlight.client.content.DropItem;
import dev.shardlight.client.content.DropKind;
import dev.shardlight.client.content.DropRouter;
import dev.shardlight.client.content.DropService;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delivering routed files into a real project: the right folder, recorded, and nothing half-done. */
class DropServiceTest {
    @TempDir
    Path dir;
    ProjectService projects;
    DownloadManager downloads;
    ContentService content;
    WorldService worlds;
    ScreenshotService screenshots;
    DropService drops;
    Project p;

    @BeforeEach
    void setUp() throws Exception {
        AppPaths paths = new AppPaths(dir.resolve("data"));
        projects = new ProjectService(paths);
        downloads = new DownloadManager(HttpService.forTests(), paths.files(), 2, 1);
        content = new ContentService(paths, projects, downloads);
        worlds = new WorldService(projects);
        screenshots = new ScreenshotService(projects);
        drops = new DropService(content, worlds, screenshots);
        p = projects.create(NewProjectRequest.vanilla("Drops", "1.21.4"));
    }

    @AfterEach
    void tearDown() {
        downloads.close();
    }

    Path game() {
        return projects.gameDir(p);
    }

    void drop(Path file) throws Exception {
        DropItem item = DropRouter.classify(file);
        assertTrue(item.kind().accepted(), file + " was refused: " + item.reason());
        drops.deliver(projects.get(p.id).orElseThrow(), item, null, null);
    }

    @Test
    void aModLandsInModsAndIsRecorded() throws Exception {
        drop(TestFiles.zip(dir.resolve("cool.jar"), "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"cool\",\"version\":\"1.0\"}"));
        assertTrue(Files.isRegularFile(game().resolve("mods/cool.jar")));
        assertTrue(projects.get(p.id).orElseThrow().content.stream().anyMatch(e -> e.kind == ContentKind.MOD && "cool.jar".equals(e.fileName)));
    }

    @Test
    void resourceAndShaderPacksLandInTheirOwnFolders() throws Exception {
        drop(TestFiles.zip(dir.resolve("res.zip"), "pack.mcmeta", "{}", "assets/a.txt", "x"));
        drop(TestFiles.zip(dir.resolve("shade.zip"), "shaders/a.fsh", "x"));
        assertTrue(Files.isRegularFile(game().resolve("resourcepacks/res.zip")));
        assertTrue(Files.isRegularFile(game().resolve("shaderpacks/shade.zip")));
        assertFalse(Files.exists(game().resolve("mods/res.zip")));
    }

    @Test
    void aWorldZipBecomesAWorldNamedAfterItsInGameName() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path w = TestFiles.world(src, "folder-on-disk", "Cool Island", 1000, "1.21.4");
        Path zip = dir.resolve("island.zip");
        zipTree(w, "Wrapped/", zip);
        drop(zip);
        Path saved = game().resolve("saves/Cool Island");
        assertTrue(Files.isRegularFile(saved.resolve("level.dat")));
        assertTrue(Files.isRegularFile(saved.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(saved.resolve("session.lock")), "the old session lock isn't carried over");
        assertEquals(1, worlds.list(projects.get(p.id).orElseThrow()).size());
        // dropping it again makes a second world instead of overwriting
        drop(zip);
        assertTrue(Files.isDirectory(game().resolve("saves/Cool Island (2)")));
        assertEquals(2, worlds.list(projects.get(p.id).orElseThrow()).size());
    }

    @Test
    void aWorldFolderIsCopiedAndTheOriginalStays() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path w = TestFiles.world(src, "Folder World", "Folder World", 1000, "1.21.4");
        drop(w);
        assertEquals(TestFiles.tree(w).size() - 1, TestFiles.tree(game().resolve("saves/Folder World")).size(), "same files, minus the lock");
        assertTrue(Files.isRegularFile(w.resolve("level.dat")), "the dropped folder is untouched");
    }

    @Test
    void aBrokenWorldLeavesNothingBehind() throws Exception {
        Path zip = TestFiles.zip(dir.resolve("broken.zip"), "level.dat", "this is not nbt", "region/r.0.0.mca", "x");
        LauncherException e = assertThrows(LauncherException.class, () -> drops.deliver(p, DropRouter.classify(zip), null, null));
        assertEquals(ErrorCode.INVALID_DATA, e.code());
        assertTrue(worlds.list(p).isEmpty());
        assertFalse(TestFiles.anyStaging(projects.projectDir(p.id)), "no staging folder is left");
    }

    @Test
    void aScreenshotLandsInScreenshotsAndNeverOverwrites() throws Exception {
        Path shot = Files.write(dir.resolve("2026-01-01_10.00.00.png"), TestFiles.PNG);
        drop(shot);
        drop(shot);
        assertTrue(Files.isRegularFile(game().resolve("screenshots/2026-01-01_10.00.00.png")));
        assertTrue(Files.isRegularFile(game().resolve("screenshots/2026-01-01_10.00.00-2.png")));
        assertEquals(2, screenshots.list(projects.get(p.id).orElseThrow()).size());
        assertFalse(TestFiles.anyStaging(game().resolve("screenshots")), "no .part file stays");
    }

    @Test
    void thingsThatMakeProjectsOrAreRefusedAreNotDeliveredIntoOne() throws Exception {
        Path pack = TestFiles.zip(dir.resolve("p.mrpack"), "modrinth.index.json", "{}");
        DropItem item = DropRouter.classify(pack);
        assertEquals(DropKind.MODPACK, item.kind());
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> drops.deliver(p, item, null, null)).code());
        Path txt = Files.writeString(dir.resolve("a.txt"), "x");
        assertThrows(LauncherException.class, () -> drops.deliver(p, DropRouter.classify(txt), null, null));
        try (var walk = Files.list(game().resolve("mods"))) {
            assertEquals(0, walk.count());
        }
    }

    /** zip a folder with every entry under {@code prefix} */
    static void zipTree(Path root, String prefix, Path out) throws IOException {
        try (var os = Files.newOutputStream(out); var zo = new java.util.zip.ZipOutputStream(os); var walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                zo.putNextEntry(new java.util.zip.ZipEntry(prefix + root.relativize(f).toString().replace('\\', '/')));
                zo.write(Files.readAllBytes(f));
                zo.closeEntry();
            }
        }
    }
}
