package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Folder operations for user data that never follow links: symbolic links and Windows junctions
 * (directories that are reparse points, reported as "other") are skipped when copying or measuring and
 * removed as links (never through them) when deleting. Deletes and moves retry briefly, because on Windows
 * an antivirus scanner or the search indexer often holds a file for a moment; read-only files are made
 * writable before deleting.
 */
final class FileTrees {
    private static final Logger LOG = Logger.getLogger(FileTrees.class.getName());
    static final String SESSION_LOCK = "session.lock";
    private static final int ATTEMPTS = 5;

    private FileTrees() {
    }

    /** attributes of {@code p} itself (links not followed) */
    static BasicFileAttributes attrs(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /** a symbolic link, a Windows junction or another special file */
    static boolean isLinkLike(BasicFileAttributes a) {
        return a.isSymbolicLink() || a.isOther();
    }

    /** a real directory (not a link or junction) */
    static boolean isPlainDirectory(Path p) {
        try {
            BasicFileAttributes a = attrs(p);
            return a.isDirectory() && !isLinkLike(a);
        } catch (IOException e) {
            return false;
        }
    }

    /** a real regular file (not a link) */
    static boolean isPlainFile(Path p) {
        try {
            BasicFileAttributes a = attrs(p);
            return a.isRegularFile() && !isLinkLike(a);
        } catch (IOException e) {
            return false;
        }
    }

    /** true when the last segment of a '/'-separated relative path is session.lock */
    static boolean isSessionLock(String rel) {
        int slash = rel.lastIndexOf('/');
        return rel.substring(slash + 1).equalsIgnoreCase(SESSION_LOCK);
    }

    /**
     * The entries of a directory, sorted by name (empty when it is missing or unreadable). The directory
     * itself may be reached through a link (a data folder moved with a junction); its entries are returned
     * as they are, links included, for the caller to check.
     */
    static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException e) {
            LOG.fine("Can't list " + dir + ": " + e.getMessage());
        }
        out.sort(null);
        return out;
    }

    /** total size of the regular files under {@code root}; links are not followed, unreadable parts are skipped */
    static long sizeOf(Path root) {
        try {
            BasicFileAttributes a = attrs(root);
            if (isLinkLike(a)) {
                return 0;
            }
            if (!a.isDirectory()) {
                return a.size();
            }
        } catch (IOException e) {
            return 0;
        }
        long total = 0;
        for (Path child : children(root)) {
            total += sizeOf(child);
        }
        return total;
    }

    /** progress and cancellation for a copy */
    static final class Copy {
        final ProgressListener progress;
        final String stageKey;
        final long total;
        final CancellationToken cancel;
        long done;

        Copy(ProgressListener progress, String stageKey, long total, CancellationToken cancel) {
            this.progress = progress == null ? ProgressListener.NONE : progress;
            this.stageKey = stageKey;
            this.total = total;
            this.cancel = cancel == null ? CancellationToken.NONE : cancel;
        }
    }

    /**
     * Copy the folder {@code src} to {@code dst} (created as needed). {@code include} receives each path
     * relative to {@code src} with '/' separators, for folders and files; a rejected folder is skipped
     * whole. Links and junctions are skipped. Every file is a real, independent copy.
     */
    static void copyTree(Path src, Path dst, Predicate<String> include, Copy ctx) throws IOException, LauncherException {
        if (!isPlainDirectory(src)) {
            return;
        }
        copyDir(src, dst, "", include, ctx);
    }

    private static void copyDir(Path src, Path dst, String relPrefix, Predicate<String> include, Copy ctx)
        throws IOException, LauncherException {
        Files.createDirectories(dst);
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            for (Path p : ds) {
                entries.add(p);
            }
        }
        for (Path child : entries) {
            ctx.cancel.check();
            String name = child.getFileName().toString();
            String rel = relPrefix.isEmpty() ? name : relPrefix + "/" + name;
            BasicFileAttributes a = attrs(child);
            if (isLinkLike(a)) {
                LOG.fine("Not copying link " + child);
                continue;
            }
            if (include != null && !include.test(rel)) {
                continue;
            }
            if (a.isDirectory()) {
                copyDir(child, dst.resolve(name), rel, include, ctx);
            } else if (a.isRegularFile()) {
                Path target = dst.resolve(name);
                Files.copy(child, target, StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(target, a.lastModifiedTime());
                ctx.done += a.size();
                ctx.progress.progress(ctx.stageKey, rel, ctx.done, ctx.total);
            }
        }
    }

    /**
     * Delete {@code target} (a file, a link or a whole folder) that must lie inside {@code base}. A link or
     * junction is removed itself and never followed. Missing targets are ignored.
     */
    static void deleteTree(Path base, Path target) throws LauncherException {
        Path b = base.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        if (!t.startsWith(b) || t.equals(b)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", target.toString());
        }
        BasicFileAttributes a;
        try {
            a = attrs(t);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            throw LauncherException.io(e, t.toString());
        }
        PathSafety.requireNoLinkEscape(b, t.getParent());
        try {
            if (a.isDirectory() && !isLinkLike(a)) {
                deleteRecursive(t);
            } else {
                deleteOne(t);
            }
        } catch (IOException e) {
            throw isLockError(e)
                ? new LauncherException(ErrorCode.IO, e, "project.error.inUse", failedFile(e, t))
                : LauncherException.io(e, failedFile(e, t));
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                entries.add(p);
            }
        }
        for (Path child : entries) {
            BasicFileAttributes a;
            try {
                a = attrs(child);
            } catch (NoSuchFileException e) {
                continue;
            }
            if (a.isDirectory() && !isLinkLike(a)) {
                deleteRecursive(child);
            } else {
                deleteOne(child); // files, and links/junctions themselves
            }
        }
        deleteOne(dir);
    }

    /** delete one file, empty folder or link, retrying briefly and clearing the read-only flag */
    static void deleteOne(Path p) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                Files.delete(p);
                return;
            } catch (NoSuchFileException e) {
                return;
            } catch (AccessDeniedException e) {
                last = e;
                makeWritable(p);
            } catch (DirectoryNotEmptyException e) {
                // Windows finishes deleting children asynchronously; anything else is a real error
                last = e;
            } catch (FileSystemException e) {
                last = e;
            }
            pause(attempt);
        }
        throw last;
    }

    /** clear the DOS read-only flag (Windows); no-op elsewhere */
    static void makeWritable(Path p) {
        DosFileAttributeView dos = Files.getFileAttributeView(p, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos == null) {
            return;
        }
        try {
            if (dos.readAttributes().isReadOnly()) {
                dos.setReadOnly(false);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // best effort
        }
    }

    /**
     * Move (rename) a file or folder to a path that must not exist yet, atomically when the file system
     * allows it. Transient Windows sharing violations are retried for about a second and a half; when the
     * move still fails nothing has changed.
     */
    static void move(Path from, Path to) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(to.toString());
            }
            try {
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to);
                }
                return;
            } catch (FileAlreadyExistsException | NoSuchFileException | DirectoryNotEmptyException e) {
                throw e;
            } catch (FileSystemException e) {
                last = e;
            }
            pause(attempt);
        }
        throw last;
    }

    /** true when the error looks like another process holding the file (Windows sharing or lock violation) */
    static boolean isLockError(IOException e) {
        if (e instanceof AccessDeniedException) {
            return true;
        }
        String m = e instanceof FileSystemException fse && fse.getReason() != null ? fse.getReason() : e.getMessage();
        if (m == null) {
            return false;
        }
        String s = m.toLowerCase(Locale.ROOT);
        return s.contains("another process") || s.contains("being used") || s.contains("lock") || s.contains("sharing violation");
    }

    /** the file an IO error names, or {@code fallback} */
    static String failedFile(IOException e, Path fallback) {
        return e instanceof FileSystemException fse && fse.getFile() != null ? fse.getFile() : String.valueOf(fallback);
    }

    private static void pause(int attempt) {
        if (attempt + 1 >= ATTEMPTS) {
            return;
        }
        try {
            Thread.sleep(50L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
