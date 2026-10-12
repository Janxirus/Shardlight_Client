package dev.shardlight.client.util;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeZipTest {
    @TempDir
    Path dir;

    private Path zip(String... namesAndContents) throws Exception {
        Path z = dir.resolve("t" + System.nanoTime() + ".zip");
        try (OutputStream os = Files.newOutputStream(z); ZipOutputStream zo = new ZipOutputStream(os)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zo.putNextEntry(new ZipEntry(namesAndContents[i]));
                zo.write(namesAndContents[i + 1].getBytes());
                zo.closeEntry();
            }
        }
        return z;
    }

    @Test
    void extractsUnderPrefix() throws Exception {
        Path z = zip("modrinth.index.json", "{}", "overrides/config/a.json", "A", "overrides/options.txt", "B");
        List<Path> out = SafeZip.extract(z, "overrides/", dir.resolve("game"), SafeZip.Limits.DEFAULT, null, CancellationToken.NONE);
        assertEquals(2, out.size());
        assertEquals("A", Files.readString(dir.resolve("game/config/a.json")));
        assertFalse(Files.exists(dir.resolve("game/modrinth.index.json")));
    }

    @Test
    void rejectsTraversalBeforeWritingAnything() throws Exception {
        Path z = zip("ok.txt", "fine", "../evil.txt", "bad");
        LauncherException e = assertThrows(LauncherException.class,
            () -> SafeZip.extract(z, "", dir.resolve("out"), SafeZip.Limits.DEFAULT, null, CancellationToken.NONE));
        assertEquals(ErrorCode.UNSAFE_PATH, e.code());
        assertFalse(Files.exists(dir.resolve("out/ok.txt")));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    @Test
    void rejectsTooManyEntries() throws Exception {
        Path z = zip("a", "1", "b", "2", "c", "3");
        LauncherException e = assertThrows(LauncherException.class,
            () -> SafeZip.list(z, new SafeZip.Limits(2, 1000, 1000, 100)));
        assertEquals(ErrorCode.INVALID_ARCHIVE, e.code());
    }

    @Test
    void neverWritesThroughALink() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path dest = Files.createDirectories(dir.resolve("dest"));
        try {
            Files.createSymbolicLink(dest.resolve("config"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlink support on this file system
        }
        Path z = zip("config/x.txt", "pwned");
        assertThrows(LauncherException.class,
            () -> SafeZip.extract(z, "", dest, SafeZip.Limits.DEFAULT, null, CancellationToken.NONE));
        assertTrue(Files.notExists(outside.resolve("x.txt")));
    }
}
