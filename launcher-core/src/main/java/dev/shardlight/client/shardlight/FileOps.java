package dev.shardlight.client.shardlight;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * File operations shared by the Shardlight, preset and update services, tolerant of Windows file locks:
 * antivirus scanners, Explorer previews and indexers briefly hold files open, so deletes and moves that
 * fail with a sharing or access error are retried a few times with a growing pause before giving up with
 * a clear "file is in use" message.
 */
public final class FileOps {
    private static final Logger LOG = Logger.getLogger(FileOps.class.getName());
    private static final long[] PAUSES_MS = {50, 100, 200, 400, 800};

    private FileOps() {
    }

    /** an IO action that may be retried */
    @FunctionalInterface
    public interface IoAction {
        void run() throws IOException;
    }

    /** run {@code action}, retrying while it fails with a (Windows) lock-style error */
    public static void retry(IoAction action) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                action.run();
                return;
            } catch (FileSystemException e) {
                if (!isLockLike(e) || attempt >= PAUSES_MS.length) {
                    throw e;
                }
                try {
                    Thread.sleep(PAUSES_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Windows reports a sharing violation ("being used by another process") as a plain
     * FileSystemException and a pending delete as AccessDeniedException; the other subclasses (no such
     * file, already exists, not empty...) won't change by waiting.
     */
    static boolean isLockLike(Throwable e) {
        return e instanceof AccessDeniedException || (e != null && e.getClass() == FileSystemException.class);
    }

    /** delete a file if it exists, retrying over short locks; {@code shardlight.file.locked} when it stays locked */
    public static void delete(Path file) throws LauncherException {
        try {
            retry(() -> Files.deleteIfExists(file));
        } catch (IOException e) {
            throw wrap(e, file);
        }
    }

    /**
     * Copy {@code from} to {@code to} safely: into a temporary file next to the target first, then moved
     * over it in one step, so a half-copied file never appears under the final name.
     */
    public static void copyAtomic(Path from, Path to) throws LauncherException {
        Path dir = to.toAbsolutePath().getParent();
        Path tmp = dir.resolve("." + to.getFileName() + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
            retry(() -> AtomicFiles.moveReplacing(tmp, to));
        } catch (IOException e) {
            throw wrap(e, to);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** move a file over another, retrying over short locks */
    public static void move(Path from, Path to) throws LauncherException {
        try {
            Files.createDirectories(to.toAbsolutePath().getParent());
            retry(() -> AtomicFiles.moveReplacing(from, to));
        } catch (IOException e) {
            throw wrap(e, to);
        }
    }

    /** write bytes atomically ({@link AtomicFiles}), retrying when the final move hits a lock */
    public static void write(Path file, byte[] data) throws LauncherException {
        for (int attempt = 0; ; attempt++) {
            try {
                AtomicFiles.writeBytes(file, data);
                return;
            } catch (LauncherException e) {
                boolean lockLike = e.code() == ErrorCode.IO && isLockLike(e.getCause());
                if (!lockLike) {
                    throw e;
                }
                if (attempt >= PAUSES_MS.length) {
                    throw new LauncherException(ErrorCode.IO, e.getCause(), "shardlight.file.locked", file.toString());
                }
                try {
                    Thread.sleep(PAUSES_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** delete a folder and everything in it without following symbolic links; best effort, never throws */
    public static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    retry(() -> Files.deleteIfExists(file));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    retry(() -> Files.deleteIfExists(d));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.fine(() -> "Couldn't fully delete " + dir + ": " + e);
        }
    }

    /** a lock-style failure becomes {@code shardlight.file.locked}, anything else the usual IO error */
    public static LauncherException wrap(IOException e, Path file) {
        if (isLockLike(e)) {
            return new LauncherException(ErrorCode.IO, e, "shardlight.file.locked", file.toString());
        }
        return LauncherException.io(e, file.toString());
    }
}
