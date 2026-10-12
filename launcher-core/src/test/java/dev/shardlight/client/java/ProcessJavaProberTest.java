package dev.shardlight.client.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the JVM that runs the tests, so the probe is checked against a real Java. */
class ProcessJavaProberTest {
    @TempDir
    Path dir;

    static Path ownJava() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path exe = bin.resolve("java.exe");
        return Files.isRegularFile(exe) ? exe : bin.resolve("java");
    }

    @Test
    void probesTheRunningJava() {
        JavaProperties p = new ProcessJavaProber().probe(ownJava()).orElseThrow();
        assertEquals(Runtime.version().feature(), p.majorVersion());
        assertEquals(System.getProperty("java.version"), p.version());
        assertEquals(System.getProperty("java.vendor"), p.vendor());
        assertEquals(System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT), p.arch());
    }

    @Test
    void nonJavaFilesGiveNothing() throws Exception {
        ProcessJavaProber prober = new ProcessJavaProber(Duration.ofSeconds(5));
        assertTrue(prober.probe(null).isEmpty());
        assertTrue(prober.probe(dir.resolve("missing")).isEmpty());
        assertTrue(prober.probe(dir).isEmpty(), "a folder");
        Path text = Files.writeString(dir.resolve("java"), "not a program");
        assertTrue(prober.probe(text).isEmpty());
    }

    @Test
    void hangingProgramsTimeOut() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return; // a shell script can't stand in for java.exe on Windows
        }
        Path script = Files.writeString(dir.resolve("slow-java"), "#!/bin/sh\nsleep 30\n");
        assertTrue(script.toFile().setExecutable(true));
        long start = System.nanoTime();
        assertTrue(new ProcessJavaProber(Duration.ofMillis(500)).probe(script).isEmpty());
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "the probe gave up after its timeout");
    }
}
