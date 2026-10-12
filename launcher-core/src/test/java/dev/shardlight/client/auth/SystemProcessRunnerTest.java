package dev.shardlight.client.auth;

import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The real process runner, exercised with POSIX sh (skipped on Windows). */
class SystemProcessRunnerTest {
    final ProcessRunner runner = ProcessRunner.system();

    @BeforeEach
    void needsSh() {
        assumeTrue(!OsInfo.current().isWindows() && Files.isExecutable(Path.of("/bin/sh")));
    }

    @Test
    void passesStdinAndCollectsOutput() throws Exception {
        ProcessRunner.Result r = runner.run(List.of("/bin/sh", "-c", "cat; echo oops >&2; exit 3"), "secret\nvalue", Duration.ofSeconds(10));
        assertEquals(3, r.exitCode());
        assertFalse(r.ok());
        assertEquals("secret\nvalue", r.stdout());
        assertEquals("oops\n", r.stderr());
        assertFalse(r.toString().contains("secret"), "results never print their output");
    }

    @Test
    void worksWithoutStdinAndWithProgramsThatIgnoreIt() throws Exception {
        assertTrue(runner.run(List.of("/bin/sh", "-c", "exit 0"), null, Duration.ofSeconds(10)).ok());
        assertTrue(runner.run(List.of("/bin/sh", "-c", "exit 0"), "x".repeat(200_000), Duration.ofSeconds(10)).ok());
    }

    @Test
    void argumentsAreNotInterpretedByAShell() throws Exception {
        ProcessRunner.Result r = runner.run(List.of("/bin/sh", "-c", "printf '%s' \"$1\"", "sh", "$(id) `id` ; rm -rf /"), null,
            Duration.ofSeconds(10));
        assertEquals("$(id) `id` ; rm -rf /", r.stdout());
    }

    @Test
    void killsProgramsThatTakeTooLong() {
        long t0 = System.nanoTime();
        assertThrows(IOException.class, () -> runner.run(List.of("/bin/sh", "-c", "sleep 20"), null, Duration.ofMillis(300)));
        assertTrue(System.nanoTime() - t0 < Duration.ofSeconds(10).toNanos());
    }

    @Test
    void missingProgram() {
        assertThrows(IOException.class, () -> runner.run(List.of("/definitely/not/a/program"), null, Duration.ofSeconds(5)));
        assertThrows(IOException.class, () -> runner.run(List.of(), null, Duration.ofSeconds(5)));
    }

    @Test
    void capsHugeOutput() throws Exception {
        ProcessRunner.Result r = runner.run(List.of("/bin/sh", "-c", "head -c 3000000 /dev/zero"), null, Duration.ofSeconds(20));
        assertTrue(r.ok());
        assertEquals(SystemProcessRunner.MAX_OUTPUT, r.stdout().length());
    }
}
