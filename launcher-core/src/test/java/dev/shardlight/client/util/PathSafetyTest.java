package dev.shardlight.client.util;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSafetyTest {
    @TempDir
    Path base;

    @Test
    void resolvesNormalRelativePaths() throws Exception {
        assertEquals(base.resolve("config/a.json").normalize(), PathSafety.resolveInside(base, "config/a.json"));
        assertEquals(base.resolve("mods/x.jar").normalize(), PathSafety.resolveInside(base, "mods\\x.jar"));
    }

    @Test
    void rejectsEscapes() {
        for (String bad : new String[] {"../x", "a/../../x", "/etc/passwd", "C:/Windows/x", "c:\\x", "", "a/CON", "a/nul.txt",
            "a/b./c", "a/<b>"}) {
            LauncherException e = assertThrows(LauncherException.class, () -> PathSafety.resolveInside(base, bad), bad);
            assertEquals(ErrorCode.UNSAFE_PATH, e.code());
        }
    }

    @Test
    void sanitizesNames() {
        assertEquals("My World_ 1", PathSafety.sanitizeFileName("My World: 1"));
        assertEquals("unnamed", PathSafety.sanitizeFileName("..."));
        assertEquals("_con", PathSafety.sanitizeFileName("con"));
        assertTrue(PathSafety.isSafeFileName("Shardlight UI - Ember.zip"));
        assertFalse(PathSafety.isSafeFileName("a/b"));
    }
}
