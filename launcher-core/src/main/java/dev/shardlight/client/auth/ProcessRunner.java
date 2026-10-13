package dev.shardlight.client.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Runs a short OS command (the macOS {@code security} tool, Linux {@code secret-tool}) and collects its
 * output. Secrets go to the process through {@code stdin}, never on the command line, where other users
 * could read them in the process list. Tests replace this with a fake to check command construction.
 */
@FunctionalInterface
public interface ProcessRunner {
    /** a finished command: exit code, standard output and standard error (UTF-8) */
    record Result(int exitCode, String stdout, String stderr) {
        /** true when the command exited with 0 */
        public boolean ok() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            // output may hold a secret (a lookup result), so it is never printed
            return "Result[exit=" + exitCode + ", stdout=" + stdout.length() + " chars, stderr=" + stderr.length() + " chars]";
        }
    }

    /**
     * Run {@code command} (no shell), write {@code stdin} (may be null) and close it, then wait at most
     * {@code timeout}. Throws {@link IOException} when the program can't be started or doesn't finish in
     * time (it is then killed).
     */
    Result run(List<String> command, String stdin, Duration timeout) throws IOException;

    /** the real implementation based on {@link ProcessBuilder} */
    static ProcessRunner system() {
        return new SystemProcessRunner();
    }
}
