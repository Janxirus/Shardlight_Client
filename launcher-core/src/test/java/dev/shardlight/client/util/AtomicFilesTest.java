package dev.shardlight.client.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {
    @TempDir
    Path dir;

    static final class Doc {
        int a = 1;
        String b = "x";
    }

    @Test
    void writesAndReadsJson() throws Exception {
        Path f = dir.resolve("sub/doc.json");
        Doc d = new Doc();
        d.a = 7;
        AtomicFiles.writeJson(f, d);
        AtomicFiles.ReadResult<Doc> r = AtomicFiles.readJson(f, Doc.class, Doc::new);
        assertEquals(7, r.value().a);
        try (var s = Files.list(f.getParent())) {
            assertEquals(1, s.count(), "no temp files left behind");
        }
    }

    @Test
    void corruptFileIsSetAsideNotLost() throws Exception {
        Path f = dir.resolve("doc.json");
        Files.writeString(f, "{ this is not json");
        AtomicFiles.ReadResult<Doc> r = AtomicFiles.readJson(f, Doc.class, Doc::new);
        assertTrue(r.wasRecovered());
        assertEquals(1, r.value().a);
        assertEquals("{ this is not json", Files.readString(r.recoveredFrom()));
    }
}
