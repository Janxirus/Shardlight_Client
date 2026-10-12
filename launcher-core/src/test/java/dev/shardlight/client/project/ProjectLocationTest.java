package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projects that live outside the data folder: creating one there, moving one, and every way a move can fail. */
class ProjectLocationTest {
    @TempDir
    Path dir;
    AppPaths paths;
    ProjectService svc;
    Path drive;

    @BeforeEach
    void setUp() throws IOException {
        paths = new AppPaths(dir.resolve("data"));
        svc = new ProjectService(paths);
        drive = Files.createDirectories(dir.resolve("big-drive"));
    }

    Project make(String name) throws LauncherException {
        return svc.create(NewProjectRequest.vanilla(name, "1.21.4"));
    }

    /** a project with a world, a mod, a config and a screenshot */
    Project filled(String name) throws Exception {
        Project p = make(name);
        Path g = svc.gameDir(p);
        Files.createDirectories(g.resolve("saves/World One/region"));
        Files.writeString(g.resolve("saves/World One/level.dat"), "level");
        Files.write(g.resolve("saves/World One/region/r.0.0.mca"), new byte[5000]);
        Files.writeString(g.resolve("saves/World One/session.lock"), "lock");
        Files.writeString(g.resolve("mods/a.jar"), "jar");
        Files.writeString(g.resolve("config/a.toml"), "a=1");
        Files.write(g.resolve("screenshots/s.png"), TestFiles.PNG);
        Files.writeString(g.resolve("options.txt"), "fov:70");
        return svc.get(p.id).orElseThrow();
    }

    static Map<String, String> tree(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith("session.lock")) {
                    out.put(rel, Hashing.hash(p, HashAlgo.SHA1));
                }
            }
        }
        return out;
    }

    static LauncherException fails(ErrorCode code, ThrowingRunnable r) {
        LauncherException e = assertThrows(LauncherException.class, r::run);
        assertEquals(code, e.code(), e.toString());
        return e;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---- creating there --------------------------------------------------------------------------------

    @Test
    void aNewProjectCanKeepItsGameFilesInAChosenFolder() throws Exception {
        Project p = svc.create(NewProjectRequest.vanilla("Big Pack", "1.21.4"), drive);
        Path game = svc.gameDir(p);
        assertEquals(drive.resolve("big-pack"), game);
        assertEquals(game.toString(), p.gameDirLocation);
        for (String d : GameDirs.CREATED) {
            assertTrue(Files.isDirectory(game.resolve(d)), d);
        }
        assertTrue(Files.isRegularFile(paths.project(p.id).resolve("project.json")), "project.json stays in the data folder");
        assertFalse(Files.exists(paths.project(p.id).resolve("minecraft")));
        assertEquals(game.toString(), svc.get(p.id).orElseThrow().gameDirLocation, "survives a reload");
        Project second = svc.create(NewProjectRequest.vanilla("Big Pack", "1.21.4"), drive);
        assertEquals(drive.resolve("big-pack-2"), svc.gameDir(second), "a taken folder name is never reused");
    }

    @Test
    void aBadLocationIsRefusedBeforeAnythingIsCreated() throws Exception {
        fails(ErrorCode.NOT_FOUND, () -> svc.create(NewProjectRequest.vanilla("X", "1.21.4"), dir.resolve("missing")));
        Path file = Files.writeString(dir.resolve("a-file"), "x");
        fails(ErrorCode.NOT_FOUND, () -> svc.create(NewProjectRequest.vanilla("X", "1.21.4"), file));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(NewProjectRequest.vanilla("X", "1.21.4"), paths.backups()));
        fails(ErrorCode.INVALID_DATA, () -> svc.create(NewProjectRequest.vanilla("X", "1.21.4"), paths.root()));
        assertTrue(svc.list().isEmpty());
    }

    @Test
    void duplicatingAndExportingTheProjectDoNotCarryTheLocationAlong() throws Exception {
        Project p = svc.create(NewProjectRequest.vanilla("Big Pack", "1.21.4"), drive);
        Files.writeString(svc.gameDir(p).resolve("config/a.toml"), "a=1");
        Project copy = svc.duplicate(p.id, "Copy", null);
        assertNull(copy.gameDirLocation);
        assertEquals(paths.project(copy.id).resolve("minecraft"), svc.gameDir(copy));
        assertEquals("a=1", Files.readString(svc.gameDir(copy).resolve("config/a.toml")));
        Path zip = dir.resolve("out.shardlight");
        svc.exportProject(p.id, zip, null);
        Project imported = svc.importProject(zip, "Imported");
        assertNull(imported.gameDirLocation);
        assertEquals("a=1", Files.readString(svc.gameDir(imported).resolve("config/a.toml")));
    }

    // ---- moving ----------------------------------------------------------------------------------------

    @Test
    void movingCopiesEverythingSwitchesAndRemovesTheOldFolder() throws Exception {
        Project p = filled("Move Me");
        Path old = svc.gameDir(p);
        Map<String, String> before = tree(old);
        List<String> stages = new ArrayList<>();

        MoveResult r = svc.moveGameDir(p.id, drive, (stage, detail, done, total) -> stages.add(stage), CancellationToken.NONE);

        Path now = drive.resolve("move-me");
        assertEquals(now, svc.gameDir(r.project()));
        assertEquals(before, tree(now), "every file arrived, byte for byte");
        assertFalse(Files.exists(old), "the old folder is gone");
        assertNull(r.leftover());
        assertEquals(now.toString(), svc.get(p.id).orElseThrow().gameDirLocation);
        assertFalse(Files.exists(now.resolve("saves/World One/session.lock")), "session locks aren't carried over");
        assertTrue(stages.contains("project.move.stage.copying"));
        assertTrue(stages.contains("project.move.stage.checking"));
        for (String d : GameDirs.CREATED) {
            assertTrue(Files.isDirectory(now.resolve(d)), d);
        }
    }

    @Test
    void movingBackPutsTheFilesInTheDataFolderAgain() throws Exception {
        Project p = filled("Round Trip");
        Map<String, String> before = tree(svc.gameDir(p));
        svc.moveGameDir(p.id, drive, null, null);
        MoveResult back = svc.moveGameDir(p.id, null, null, null);
        assertNull(back.project().gameDirLocation);
        assertEquals(paths.project(p.id).resolve("minecraft"), svc.gameDir(back.project()));
        assertEquals(before, tree(svc.gameDir(back.project())));
        assertFalse(Files.exists(drive.resolve("round-trip")));
    }

    @Test
    void movingFromOnePlaceToAnotherWorks() throws Exception {
        Project p = filled("Hop");
        Path second = Files.createDirectories(dir.resolve("second-drive"));
        svc.moveGameDir(p.id, drive, null, null);
        Map<String, String> before = tree(drive.resolve("hop"));
        svc.moveGameDir(p.id, second, null, null);
        assertEquals(second.resolve("hop"), svc.gameDir(svc.get(p.id).orElseThrow()));
        assertEquals(before, tree(second.resolve("hop")));
        assertFalse(Files.exists(drive.resolve("hop")));
    }

    @Test
    void aFailureAfterCopyingLeavesTheProjectAndTheOldFolderUntouched() throws Exception {
        Project p = filled("Safe");
        Path old = svc.gameDir(p);
        Map<String, String> before = tree(old);
        svc.moveFault = point -> {
            if (point.equals("afterCopy")) {
                throw new IOException("disk went away");
            }
        };
        assertThrows(LauncherException.class, () -> svc.moveGameDir(p.id, drive, null, null));
        svc.moveFault = null;
        assertEquals(before, tree(old), "the original is intact");
        assertFalse(Files.exists(drive.resolve("safe")), "the partial copy is removed");
        assertNull(svc.get(p.id).orElseThrow().gameDirLocation);
        assertEquals(old, svc.gameDir(svc.get(p.id).orElseThrow()));
    }

    @Test
    void aFailureJustBeforeTheSwitchRollsBackToo() throws Exception {
        Project p = filled("Late");
        Map<String, String> before = tree(svc.gameDir(p));
        svc.moveFault = point -> {
            if (point.equals("beforeSwitch")) {
                throw new IOException("boom");
            }
        };
        assertThrows(LauncherException.class, () -> svc.moveGameDir(p.id, drive, null, null));
        svc.moveFault = null;
        assertEquals(before, tree(svc.gameDir(p)));
        assertFalse(Files.exists(drive.resolve("late")));
        assertNull(svc.get(p.id).orElseThrow().gameDirLocation);
    }

    @Test
    void cancellingRollsBack() throws Exception {
        Project p = filled("Cancel");
        Map<String, String> before = tree(svc.gameDir(p));
        CancellationToken token = new CancellationToken();
        ProgressListener cancelOnFirstFile = (stage, detail, done, total) -> {
            if (done > 0) {
                token.cancel();
            }
        };
        LauncherException e = assertThrows(LauncherException.class, () -> svc.moveGameDir(p.id, drive, cancelOnFirstFile, token));
        assertTrue(e.isCancelled());
        assertEquals(before, tree(svc.gameDir(p)));
        assertFalse(Files.exists(drive.resolve("cancel")));
        assertNull(svc.get(p.id).orElseThrow().gameDirLocation);
    }

    @Test
    void aFolderThatWasEmptyBeforeIsEmptiedNotDeletedOnFailure() throws Exception {
        // the move picks a fresh folder name, so this checks the helper path used when the folder already exists:
        // moving back to the data folder where an empty "minecraft" folder is waiting
        Project p = filled("Back");
        svc.moveGameDir(p.id, drive, null, null);
        Files.createDirectories(paths.project(p.id).resolve("minecraft"));
        svc.moveFault = point -> {
            if (point.equals("afterCopy")) {
                throw new IOException("boom");
            }
        };
        assertThrows(LauncherException.class, () -> svc.moveGameDir(p.id, null, null, null));
        svc.moveFault = null;
        assertTrue(Files.isDirectory(paths.project(p.id).resolve("minecraft")), "the folder stays");
        assertTrue(tree(paths.project(p.id).resolve("minecraft")).isEmpty(), "but the partial copy is gone");
        assertTrue(Files.exists(drive.resolve("back/saves/World One/level.dat")), "the project still works from the drive");
    }

    @Test
    void impossibleMovesAreRefusedWithoutChangingAnything() throws Exception {
        Project p = filled("Nope");
        Map<String, String> before = tree(svc.gameDir(p));
        fails(ErrorCode.INVALID_DATA, () -> svc.moveGameDir(p.id, paths.backups(), null, null)); // inside the data folder
        fails(ErrorCode.NOT_FOUND, () -> svc.moveGameDir(p.id, dir.resolve("nowhere"), null, null));
        fails(ErrorCode.INVALID_DATA, () -> svc.moveGameDir(p.id, null, null, null)); // already in the data folder
        fails(ErrorCode.INVALID_DATA, () -> svc.moveGameDir(p.id, svc.gameDir(p).resolve("saves"), null, null)); // into itself
        fails(ErrorCode.NOT_FOUND, () -> svc.moveGameDir("no-such-project", drive, null, null));
        assertEquals(before, tree(svc.gameDir(p)));
        assertNull(svc.get(p.id).orElseThrow().gameDirLocation);
    }

    @Test
    void twoProjectsCannotShareOrNestGameFolders() throws Exception {
        Project a = filled("Alpha");
        Project b = filled("Beta");
        svc.moveGameDir(a.id, drive, null, null);
        // beta may not go inside alpha's game folder, nor onto it
        fails(ErrorCode.CONFLICT, () -> svc.moveGameDir(b.id, drive.resolve("alpha"), null, null));
        // a second move to the same drive picks a different folder name
        svc.moveGameDir(b.id, drive, null, null);
        assertEquals(drive.resolve("beta"), svc.gameDir(svc.get(b.id).orElseThrow()));
    }

    @Test
    void aProjectWithALinkInsideIsNotMovedBecauseTheLinkWouldBeLost() throws Exception {
        Project p = filled("Linked");
        Path target = Files.createDirectories(dir.resolve("elsewhere"));
        Path link = svc.gameDir(p).resolve("saves/linked-world");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            return; // this system can't make links without extra rights
        }
        fails(ErrorCode.INVALID_DATA, () -> svc.moveGameDir(p.id, drive, null, null));
        assertTrue(Files.isSymbolicLink(link), "the link is still there");
        assertFalse(Files.exists(drive.resolve("linked")));
    }

    // ---- the rest of the project follows the location ------------------------------------------------------

    @Test
    void servicesThatReadTheGameFolderFollowTheProject() throws Exception {
        Project p = filled("Follow");
        svc.moveGameDir(p.id, drive, null, null);
        Project moved = svc.get(p.id).orElseThrow();
        assertEquals(1, new ScreenshotService(svc).list(moved).size());
        assertEquals(1, new WorldService(svc).list(moved).size());
        RemovalPlan plan = svc.planRemoval(p.id);
        assertEquals(1, plan.worlds());
        assertEquals(1, plan.mods());
        assertTrue(plan.totalBytes() >= 5000, "the size counts the files that live outside the data folder");
    }

    @Test
    void removingForGoodAlsoDeletesTheFolderOutsideTheDataFolder() throws Exception {
        Project p = filled("Gone");
        svc.moveGameDir(p.id, drive, null, null);
        ArchivedProject a = svc.archive(p.id);
        assertTrue(Files.exists(drive.resolve("gone/saves")), "archiving moves nothing out there");
        Project restored = svc.restore(a.trashId());
        assertEquals(drive.resolve("gone"), svc.gameDir(restored), "restoring keeps the location");
        ArchivedProject again = svc.archive(restored.id);
        svc.deletePermanently(again.trashId());
        assertFalse(Files.exists(drive.resolve("gone")));
        assertTrue(Files.isDirectory(drive), "only the project's own folder goes");
    }

    @Test
    void defaultGameDirIsInsideTheProjectFolder() {
        assertNotNull(svc.defaultGameDir("x"));
    }
}
