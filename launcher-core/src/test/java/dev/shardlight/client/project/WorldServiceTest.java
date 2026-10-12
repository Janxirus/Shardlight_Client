package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.nbt.NbtCompound;
import dev.shardlight.client.nbt.NbtIo;
import dev.shardlight.client.storage.AppPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldServiceTest {
    @TempDir
    Path dir;
    ProjectService projects;
    WorldService worlds;
    Project p;
    Path saves;

    @BeforeEach
    void setUp() throws Exception {
        projects = new ProjectService(new AppPaths(dir));
        worlds = new WorldService(projects);
        p = projects.create(NewProjectRequest.vanilla("Worlds", "1.21.4"));
        saves = projects.gameDir(p).resolve("saves");
    }

    @Test
    void listsWorldsFromLevelDat() throws Exception {
        Path w = Files.createDirectories(saves.resolve("Creative One"));
        Files.write(w.resolve("level.dat"), NbtIo.toBytes(TestFiles.levelDat("My Creative", 2_000L, 1, false, true, "1.21.4", 4189), true));
        Files.write(w.resolve("icon.png"), TestFiles.PNG);
        Path h = Files.createDirectories(saves.resolve("hc"));
        Files.write(h.resolve("level.dat"), NbtIo.toBytes(TestFiles.levelDat("Hard", 5_000L, 0, true, false, "26.1", 4500), true));
        Path broken = Files.createDirectories(saves.resolve("broken"));
        Files.writeString(broken.resolve("level.dat"), "garbage, not NBT");
        Path old = Files.createDirectories(saves.resolve("only-old"));
        Files.write(old.resolve("level.dat_old"), NbtIo.toBytes(TestFiles.levelDat("Backup Copy", 1_000L, 2, false, false, "1.20.1", 3465), true));
        Path uncompressed = Files.createDirectories(saves.resolve("plain"));
        Files.write(uncompressed.resolve("level.dat"), NbtIo.toBytes(TestFiles.levelDat("Plain", 3_000L, 3, false, false, "1.21", 3953), false));
        Files.createDirectories(saves.resolve(".hidden"));
        Files.writeString(saves.resolve("stray.txt"), "x");

        // an unreadable world sorts by its folder time
        Files.setLastModifiedTime(broken, FileTime.fromMillis(500L));

        List<WorldInfo> list = worlds.list(p);
        assertEquals(List.of("hc", "plain", "Creative One", "only-old", "broken"),
            list.stream().map(WorldInfo::folder).toList());
        WorldInfo c = list.stream().filter(x -> x.folder().equals("Creative One")).findFirst().orElseThrow();
        assertEquals("My Creative", c.name());
        assertEquals(2_000L, c.lastPlayed());
        assertEquals("creative", c.gameMode());
        assertFalse(c.hardcore());
        assertTrue(c.cheats());
        assertEquals("1.21.4", c.versionName());
        assertEquals(4189, c.dataVersion());
        assertEquals(w.resolve("icon.png"), c.icon());
        assertTrue(c.readable());
        assertTrue(c.sizeBytes() > 0);
        WorldInfo hc = list.get(0);
        assertTrue(hc.hardcore());
        assertEquals("survival", hc.gameMode());
        assertNull(hc.icon());
        WorldInfo b = list.stream().filter(x -> x.folder().equals("broken")).findFirst().orElseThrow();
        assertFalse(b.readable());
        assertEquals("broken", b.name());
        assertEquals("unknown", b.gameMode());
        WorldInfo o = list.stream().filter(x -> x.folder().equals("only-old")).findFirst().orElseThrow();
        assertTrue(o.readable(), "falls back to level.dat_old like the game");
        assertEquals("adventure", o.gameMode());
        assertEquals("spectator", list.stream().filter(x -> x.folder().equals("plain")).findFirst().orElseThrow().gameMode());
        assertEquals(5, list.size());
    }

    @Test
    void duplicateCopiesAndRenamesTheCopyOnly() throws Exception {
        Path src = TestFiles.world(saves, "Base", "Base World", 1_000L, "1.21.4");
        byte[] originalLevel = Files.readAllBytes(src.resolve("level.dat"));
        WorldInfo copy = worlds.duplicate(p, "Base", "Base: Copy?");
        assertEquals("Base_ Copy_", copy.folder());
        assertEquals("Base: Copy?", copy.name());
        assertArrayEquals(originalLevel, Files.readAllBytes(src.resolve("level.dat")));
        assertFalse(Files.exists(saves.resolve(copy.folder()).resolve("session.lock")));
        Map<String, String> a = TestFiles.tree(src);
        Map<String, String> b = TestFiles.tree(saves.resolve(copy.folder()));
        a.remove("session.lock");
        a.remove("level.dat");
        b.remove("level.dat");
        assertEquals(a, b);
        WorldInfo again = worlds.duplicate(p, "Base", "Base: Copy?");
        assertEquals("Base_ Copy_ (2)", again.folder());
        assertFalse(TestFiles.anyStaging(projects.projectDir(p.id)));
        assertThrows(LauncherException.class, () -> worlds.duplicate(p, "Base", "  "));
    }

    @Test
    void renameChangesOnlyLevelName() throws Exception {
        Path w = TestFiles.world(saves, "Keep Folder", "Old Name", 1_000L, "1.21.4");
        NbtCompound before = NbtIo.readFile(w.resolve("level.dat"));
        WorldInfo r = worlds.rename(p, "Keep Folder", "  New Name ");
        assertEquals("Keep Folder", r.folder());
        assertEquals("New Name", r.name());
        NbtCompound after = NbtIo.readFile(w.resolve("level.dat"));
        assertEquals("New Name", after.getCompound("Data").getString("LevelName"));
        after.getCompound("Data").putString("LevelName", "Old Name");
        assertEquals(before, after, "every other tag kept");
        Files.writeString(w.resolve("level.dat"), "broken");
        LauncherException e = assertThrows(LauncherException.class, () -> worlds.rename(p, "Keep Folder", "x"));
        assertEquals("project.world.unreadable", e.messageKey());
    }

    @Test
    void trashKeepsWorldsRestorable() throws Exception {
        TestFiles.world(saves, "Doomed", "Doomed World", 1_000L, "1.21.4");
        Map<String, String> before = TestFiles.tree(saves.resolve("Doomed"));
        worlds.moveToTrash(p, "Doomed");
        assertFalse(Files.exists(saves.resolve("Doomed")));
        assertTrue(worlds.list(p).isEmpty());
        List<TrashedWorld> trashed = worlds.trashed(p);
        assertEquals(1, trashed.size());
        TrashedWorld t = trashed.get(0);
        assertEquals("Doomed", t.folder());
        assertEquals("Doomed World", t.name());
        assertTrue(t.trashName().matches("Doomed-\\d{8}-\\d{6}"), t.trashName());
        assertTrue(t.trashedAt() > 0);
        WorldInfo back = worlds.restore(p, t.trashName());
        assertEquals("Doomed", back.folder());
        assertEquals(before, TestFiles.tree(saves.resolve("Doomed")));
        assertTrue(worlds.trashed(p).isEmpty());

        // restoring when the folder name is taken again picks a new one
        worlds.moveToTrash(p, "Doomed");
        TestFiles.world(saves, "Doomed", "Replacement", 2_000L, "1.21.4");
        WorldInfo second = worlds.restore(p, worlds.trashed(p).get(0).trashName());
        assertEquals("Doomed (2)", second.folder());
        assertEquals("Doomed World", second.name());

        worlds.moveToTrash(p, "Doomed (2)");
        String name = worlds.trashed(p).get(0).trashName();
        worlds.deleteFromTrash(p, name);
        assertTrue(worlds.trashed(p).isEmpty());
        assertThrows(LauncherException.class, () -> worlds.restore(p, name));
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> worlds.deleteFromTrash(p, "../../../minecraft")).code());
    }

    @Test
    void worldsOpenInTheGameAreNotTouched() throws Exception {
        Path w = TestFiles.world(saves, "Open", "Open World", 1_000L, "1.21.4");
        try (FileChannel ch = FileChannel.open(w.resolve("session.lock"), StandardOpenOption.WRITE);
             FileLock lock = ch.lock()) {
            assertTrue(lock.isValid());
            assertTrue(worlds.isInUse(p, "Open"));
            for (LauncherException e : List.of(
                assertThrows(LauncherException.class, () -> worlds.rename(p, "Open", "x")),
                assertThrows(LauncherException.class, () -> worlds.moveToTrash(p, "Open")),
                assertThrows(LauncherException.class, () -> worlds.duplicate(p, "Open", "x")))) {
                assertEquals("project.world.inUse", e.messageKey());
                assertTrue(e.userMessage().contains("Close the world"), e.userMessage());
            }
        }
        assertFalse(worlds.isInUse(p, "Open"));
        assertTrue(Files.exists(w.resolve("level.dat")));
    }

    @Test
    void folderNamesCantEscapeSaves() {
        for (String bad : new String[] {"..", "../..", "a/b", ".trash", "", "C:\\Windows", "nul"}) {
            LauncherException e = assertThrows(LauncherException.class, () -> worlds.info(p, bad), bad);
            assertEquals(ErrorCode.UNSAFE_PATH, e.code(), bad);
        }
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class, () -> worlds.info(p, "missing")).code());
    }

    @Test
    void downgradeWarnings() {
        assertTrue(newer("1.21.4", "1.21.1"));
        assertFalse(newer("1.21.4", "1.21.4"));
        assertFalse(newer("1.21.4", "1.21.10"), "numeric, not text order");
        assertFalse(newer("1.21.11", "26.1"));
        assertTrue(newer("26.1", "1.21.11"));
        assertTrue(newer("26.2", "26.1.2"));
        assertFalse(newer("26.1-snapshot-2", "26.1"));
        assertTrue(newer("26.1", "26.1-snapshot-2"));
        assertTrue(newer("26.1-snapshot-3", "26.1-snapshot-2"));
        assertFalse(newer("1.21-pre1", "1.21"));
        assertFalse(newer("1.14 Pre-Release 2", "1.14"));
        assertTrue(newer("1.21.4-rc1", "1.21.3"));
        assertFalse(newer("1.21.4-rc1", "1.21.4"));
        assertTrue(newer("24w14a", "1.20.4"), "a 2024 snapshot is newer than a 2023 release");
        assertFalse(newer("23w51a", "1.21"));
        assertTrue(newer("24w14a", "1.21"), "same year: can't tell, so warn");
        assertTrue(newer("24w20a", "24w14a"));
        assertFalse(newer("24w14a", "24w14b"));
        assertTrue(newer("24w14potato", "1.21"), "unknown names warn");
        assertFalse(newer(null, "1.21"), "worlds older than the Version tag");
        WorldInfo unreadable = new WorldInfo("x", "x", 0, "unknown", false, false, "99.0", 0, 0, null, false);
        assertFalse(worlds.isNewerThan(unreadable, "1.21"));
        WorldInfo dv = new WorldInfo("x", "x", 0, "survival", false, false, "1.21.4", 4189, 0, null, true);
        assertTrue(worlds.isNewerThan(dv, 3953));
        assertFalse(worlds.isNewerThan(dv, 4189));
        assertEquals(0, GameVersionOrder.compare("1.21", "1.21.0").orElseThrow());
    }

    private boolean newer(String world, String game) {
        return worlds.isNewerThan(new WorldInfo("w", "w", 0, "survival", false, false, world, 0, 0, null, true), game);
    }
}
