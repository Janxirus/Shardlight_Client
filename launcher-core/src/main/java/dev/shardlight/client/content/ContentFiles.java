package dev.shardlight.client.content;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * File operations for content folders. Moves are retried for a few seconds when another program holds the
 * file (on Windows a running game, an antivirus scanner or the search indexer can briefly lock a jar);
 * names are compared case-insensitively, as on Windows.
 */
final class ContentFiles {
    private static final Logger LOG = Logger.getLogger(ContentFiles.class.getName());
    private static final int MOVE_ATTEMPTS = 10;

    private ContentFiles() {
    }

    /** move a file or folder; never replaces an existing target; retries while the file is locked */
    static void move(Path from, Path to) throws LauncherException {
        long delay = 50;
        for (int attempt = 1; ; attempt++) {
            try {
                Path parent = to.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.move(from, to);
                return;
            } catch (FileAlreadyExistsException e) {
                throw new LauncherException(ErrorCode.CONFLICT, e, "content.error.targetExists", to.getFileName().toString());
            } catch (NoSuchFileException e) {
                throw new LauncherException(ErrorCode.NOT_FOUND, e, "content.error.fileMissing", from.getFileName().toString());
            } catch (DirectoryNotEmptyException e) {
                throw LauncherException.io(e, from.toString());
            } catch (FileSystemException e) {
                // AccessDeniedException or "used by another process": usually a short-lived lock on Windows
                if (attempt >= MOVE_ATTEMPTS) {
                    throw new LauncherException(ErrorCode.IO, e, "content.error.fileLocked", from.getFileName().toString());
                }
                LOG.fine(() -> "Retrying move of " + from.getFileName() + " (" + e.getClass().getSimpleName() + ")");
                if (!sleep(delay)) {
                    throw LauncherException.cancelled();
                }
                delay = Math.min(500, delay * 2);
            } catch (IOException e) {
                throw LauncherException.io(e, from.toString());
            }
        }
    }

    /** the entry of {@code dir} named {@code name} (exact match preferred, else ignoring case), or null */
    static Path findIgnoreCase(Path dir, String name) throws LauncherException {
        if (name == null || !Files.isDirectory(dir)) {
            return null;
        }
        Path loose = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.equals(name)) {
                    return p;
                }
                if (loose == null && n.equalsIgnoreCase(name)) {
                    loose = p;
                }
            }
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        return loose;
    }

    static boolean exists(Path p) {
        return p != null && Files.exists(p, LinkOption.NOFOLLOW_LINKS);
    }

    /** delete a file or a folder tree without following links; best effort, logs failures */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    deleteWithRetry(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    deleteWithRetry(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    deleteWithRetry(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warning("Couldn't delete " + root + ": " + e);
        }
    }

    private static void deleteWithRetry(Path p) throws IOException {
        long delay = 50;
        for (int attempt = 1; ; attempt++) {
            try {
                Files.deleteIfExists(p);
                return;
            } catch (AccessDeniedException e) {
                // a read-only file on Windows: clear the flag once, then retry
                if (attempt == 1 && !p.toFile().canWrite() && p.toFile().setWritable(true)) {
                    continue;
                }
                if (attempt >= MOVE_ATTEMPTS) {
                    throw e;
                }
            } catch (DirectoryNotEmptyException e) {
                throw e;
            } catch (FileSystemException e) {
                if (attempt >= MOVE_ATTEMPTS) {
                    throw e;
                }
            }
            if (!sleep(delay)) {
                throw new IOException("interrupted while deleting " + p);
            }
            delay = Math.min(500, delay * 2);
        }
    }

    /** delete {@code dir} when it exists and is empty */
    static void deleteIfEmpty(Path dir) {
        try {
            if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    if (ds.iterator().hasNext()) {
                        return;
                    }
                }
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            LOG.fine(() -> "Couldn't remove empty folder " + dir + ": " + e);
        }
    }

    /** size of a file, or of every file in a folder tree (links not followed); 0 when unreadable */
    static long sizeOf(Path p) {
        if (p == null) {
            return 0;
        }
        try {
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return Files.size(p);
            }
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                return 0;
            }
            long[] total = {0};
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        total[0] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return total[0];
        } catch (IOException e) {
            return 0;
        }
    }

    /** SHA-1 and SHA-512 in one pass */
    static String[] sha1AndSha512(Path file) throws LauncherException {
        MessageDigest sha1 = HashAlgo.SHA1.newDigest();
        MessageDigest sha512 = HashAlgo.SHA512.newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha1.update(buf, 0, n);
                sha512.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
        return new String[] {Hashing.hex(sha1.digest()), Hashing.hex(sha512.digest())};
    }

    /** lowercase file name */
    static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** false when interrupted */
    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
