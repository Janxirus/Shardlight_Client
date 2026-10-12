package dev.shardlight.client.auth;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.logging.Logger;

/**
 * Retries small file operations that fail because another program briefly holds the file, which happens
 * on Windows when an antivirus scanner or the search indexer opens a freshly written file.
 */
final class FileRetry {
    private static final Logger LOG = Logger.getLogger(FileRetry.class.getName());
    static final int ATTEMPTS = 5;

    /** a file operation that may fail with an I/O error */
    @FunctionalInterface
    interface IoAction<T> {
        T run() throws IOException, LauncherException;
    }

    private FileRetry() {
    }

    /** run {@code action}, retrying up to {@value #ATTEMPTS} times on transient I/O failures */
    static <T> T run(String what, IoAction<T> action) throws IOException, LauncherException {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.run();
            } catch (NoSuchFileException e) {
                throw e;
            } catch (IOException | LauncherException e) {
                if (attempt >= ATTEMPTS || !isTransient(e)) {
                    throw e;
                }
                LOG.fine(() -> "Retrying " + what + " after " + e.getClass().getSimpleName());
                try {
                    Thread.sleep(25L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** sharing violations and "access denied" (a scanner holding the file); not a missing file */
    private static boolean isTransient(Exception e) {
        Throwable t = e instanceof LauncherException le && le.code() == ErrorCode.IO ? le.getCause() : e;
        return (t instanceof AccessDeniedException || t instanceof FileSystemException) && !(t instanceof NoSuchFileException);
    }
}
