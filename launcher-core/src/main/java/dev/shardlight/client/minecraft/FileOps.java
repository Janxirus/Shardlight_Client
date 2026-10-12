package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * File operations that tolerate Windows: a file another process (antivirus, a running game) has open can't
 * be deleted or replaced for a moment, so these retry and, for deletes, fall back to renaming the file out
 * of the way.
 */
final class FileOps {
    private static final Logger LOG = Logger.getLogger(FileOps.class.getName());
    private static final int ATTEMPTS = 5;

    private FileOps() {
    }

    /** make {@code dst} a hard link to {@code src}, or a copy where links aren't possible */
    static void linkOrCopy(Path src, Path dst) throws LauncherException {
        try {
            Files.createDirectories(dst.toAbsolutePath().getParent());
            Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp-" + System.nanoTime());
            try {
                try {
                    Files.createLink(tmp, src);
                } catch (IOException | UnsupportedOperationException e) {
                    Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                moveWithRetry(tmp, dst);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw LauncherException.io(e, dst.toString());
        }
    }

    /** copy {@code src} over {@code dst} through a temporary file */
    static void copy(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.toAbsolutePath().getParent());
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            moveWithRetry(tmp, dst);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** {@link AtomicFiles#moveReplacing}, retried while the target is locked */
    static void moveWithRetry(Path from, Path to) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                AtomicFiles.moveReplacing(from, to);
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                pause(attempt);
            }
        }
    }

    /**
     * Delete a file. A locked file is retried a few times, then renamed aside ({@code <name>.delete-<n>})
     * so a fresh copy can take its place; it is deleted later when possible.
     */
    static void deleteTolerant(Path file) throws LauncherException {
        FileSystemException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (FileSystemException e) {
                last = e;
                pause(attempt);
            } catch (IOException e) {
                throw LauncherException.io(e, file.toString());
            }
        }
        Path aside = file.resolveSibling(file.getFileName() + ".delete-" + System.nanoTime());
        try {
            Files.move(file, aside);
            try {
                Files.deleteIfExists(aside);
            } catch (IOException e) {
                aside.toFile().deleteOnExit();
            }
            LOG.info("Moved locked file aside: " + file);
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.IO, last != null ? last : e, "install.error.fileLocked", file.toString());
        }
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(100L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
