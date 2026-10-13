package dev.shardlight.client.skin;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.auth.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinLibraryTest {
    @TempDir
    Path dir;
    FakeClock clock;
    SkinLibrary lib;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-10-13T10:00:00Z"));
        lib = new SkinLibrary(dir.resolve("skins"), clock);
    }

    private static byte[] skin(int shade) {
        BufferedImage img = SkinFixtures.skin();
        img.setRGB(0, 0, 0xFF000000 | shade);
        return SkinFixtures.png(img);
    }

    @Test
    void startsEmpty() throws Exception {
        assertTrue(lib.list().isEmpty());
    }

    @Test
    void savesASkinAndReadsItBack() throws Exception {
        byte[] png = skin(1);
        SkinLibrary.Entry e = lib.add("  Knight  ", SkinVariant.SLIM, png);
        assertEquals("Knight", e.name);
        assertEquals(64, e.id.length());
        assertEquals(SkinVariant.SLIM, lib.list().get(0).variant());
        assertArrayEquals(png, lib.bytes(e.id));
        // a new library object on the same folder sees it
        assertEquals(1, new SkinLibrary(dir.resolve("skins"), clock).list().size());
    }

    @Test
    void theSamePictureIsStoredOnceAndMarkedUsedAgain() throws Exception {
        byte[] png = skin(2);
        SkinLibrary.Entry first = lib.add("One", SkinVariant.CLASSIC, png);
        clock.advance(Duration.ofMinutes(5));
        SkinLibrary.Entry again = lib.add("Renamed", SkinVariant.SLIM, png);
        assertEquals(first.id, again.id);
        List<SkinLibrary.Entry> all = lib.list();
        assertEquals(1, all.size());
        assertEquals("Renamed", all.get(0).name);
        assertEquals(first.addedAt, all.get(0).addedAt);
        assertTrue(all.get(0).lastUsedAt > first.addedAt);
    }

    @Test
    void listsTheMostRecentlyUsedFirst() throws Exception {
        SkinLibrary.Entry a = lib.add("A", SkinVariant.CLASSIC, skin(3));
        clock.advance(Duration.ofMinutes(1));
        SkinLibrary.Entry b = lib.add("B", SkinVariant.CLASSIC, skin(4));
        assertEquals(List.of(b.id, a.id), lib.list().stream().map(x -> x.id).toList());
        clock.advance(Duration.ofMinutes(1));
        lib.markUsed(a.id);
        assertEquals(List.of(a.id, b.id), lib.list().stream().map(x -> x.id).toList());
    }

    @Test
    void renamesAndRemoves() throws Exception {
        SkinLibrary.Entry a = lib.add("A", SkinVariant.CLASSIC, skin(5));
        lib.rename(a.id, "Better name");
        assertEquals("Better name", lib.list().get(0).name);
        lib.remove(a.id);
        assertTrue(lib.list().isEmpty());
        try (var files = Files.list(dir.resolve("skins"))) {
            assertFalse(files.anyMatch(f -> f.toString().endsWith(".png")), "the picture is deleted too");
        }
    }

    @Test
    void refusesAFileThatIsNotASkin() {
        assertThrows(LauncherException.class, () -> lib.add("x", SkinVariant.CLASSIC, "nope".getBytes()));
    }

    @Test
    void anIdThatIsNotAHashNeverTouchesAnotherFile() throws Exception {
        lib.add("A", SkinVariant.CLASSIC, skin(6));
        assertThrows(LauncherException.class, () -> lib.bytes("../library"));
        lib.remove("../library");
        assertEquals(1, lib.list().size());
    }

    @Test
    void forgetsAnEntryWhoseFileWasDeleted() throws Exception {
        SkinLibrary.Entry a = lib.add("A", SkinVariant.CLASSIC, skin(7));
        Files.delete(dir.resolve("skins").resolve(a.id + ".png"));
        assertTrue(lib.list().isEmpty());
    }

    @Test
    void keepsAtMostTheLimit() throws Exception {
        for (int i = 0; i < SkinLibrary.MAX_SKINS + 3; i++) {
            clock.advance(Duration.ofSeconds(1));
            BufferedImage img = SkinFixtures.skin();
            img.setRGB(1, 1, 0xFF000000 | i);
            lib.add("S" + i, SkinVariant.CLASSIC, SkinFixtures.png(img));
        }
        assertEquals(SkinLibrary.MAX_SKINS, lib.list().size());
        assertEquals("S" + (SkinLibrary.MAX_SKINS + 2), lib.list().get(0).name);
    }

    @Test
    void cleansNames() {
        assertEquals("Skin", SkinLibrary.cleanName("   "));
        assertEquals("Skin", SkinLibrary.cleanName(null));
        assertEquals("a b", SkinLibrary.cleanName("a\nb"));
        assertEquals(40, SkinLibrary.cleanName("x".repeat(100)).length());
    }
}
