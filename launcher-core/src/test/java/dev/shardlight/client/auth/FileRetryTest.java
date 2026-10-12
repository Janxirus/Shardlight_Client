package dev.shardlight.client.auth;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tolerance for files briefly held by another program (Windows virus scanners, indexers). */
class FileRetryTest {
    @Test
    void retriesWhileTheFileIsHeld() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String v = FileRetry.run("test", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new FileSystemException("a.bin", null, "The process cannot access the file because it is being used by another process");
            }
            return "ok";
        });
        assertEquals("ok", v);
        assertEquals(3, calls.get());
    }

    @Test
    void retriesWrappedAccessDenied() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FileRetry.run("test", () -> {
            if (calls.incrementAndGet() == 1) {
                throw LauncherException.io(new AccessDeniedException("a.bin"), "a.bin");
            }
            return null;
        });
        assertEquals(2, calls.get());
    }

    @Test
    void givesUpAfterAFewAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(AccessDeniedException.class, () -> FileRetry.run("test", () -> {
            calls.incrementAndGet();
            throw new AccessDeniedException("a.bin");
        }));
        assertEquals(FileRetry.ATTEMPTS, calls.get());
    }

    @Test
    void doesNotRetryRealErrors() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(NoSuchFileException.class, () -> FileRetry.run("test", () -> {
            calls.incrementAndGet();
            throw new NoSuchFileException("a.bin");
        }));
        assertThrows(IOException.class, () -> FileRetry.run("test", () -> {
            calls.incrementAndGet();
            throw new IOException("disk full");
        }));
        LauncherException e = assertThrows(LauncherException.class, () -> FileRetry.run("test", () -> {
            calls.incrementAndGet();
            throw LauncherException.internal("bug");
        }));
        assertEquals(ErrorCode.INTERNAL, e.code());
        assertEquals(3, calls.get());
    }
}
