package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where a project's game directory lives, and moving it. A move copies everything to the new place, checks every
 * file, switches the project to the new place with one atomic write of project.json, and only then deletes the old
 * folder. Any failure, or a cancel, before the switch deletes the partial copy and leaves the project exactly as it
 * was; the old folder is never touched until the new one is complete.
 */
final class ProjectMover {
    private static final Logger LOG = Logger.getLogger(ProjectMover.class.getName());
    /** space kept free on the target drive on top of the project's own size */
    private static final long MARGIN = 64L << 20;

    /** a point in a move where a test can make things fail */
    @FunctionalInterface
    interface Fault {
        void at(String point) throws IOException;
    }

    private ProjectMover() {
    }

    /**
     * Move the game directory of {@code id} into a new folder inside {@code newParent}, or back to the data
     * folder when {@code newParent} is null.
     */
    static MoveResult move(ProjectService svc, AppPaths paths, String id, Path newParent, ProgressListener progress,
                           CancellationToken cancel, Fault fault) throws LauncherException {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        Project p = svc.require(id);
        if (svc.isReadOnly(id)) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "project.error.newerSchema", p.name);
        }
        Path from = svc.gameDir(p).toAbsolutePath().normalize();
        Path def = paths.project(id).resolve("minecraft").toAbsolutePath().normalize();
        boolean toDefault = newParent == null;
        Path to;
        if (toDefault) {
            to = def;
        } else {
            Path parent = requireLocationParent(paths, newParent);
            to = parent.resolve(folderName(parent, id));
            requireNoOverlap(svc, id, to);
        }
        if (from.equals(to)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.move.same", to.toString());
        }
        if (from.startsWith(to) || to.startsWith(from)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.move.nested", to.toString());
        }
        requireNoLinks(from);
        boolean created = !Files.exists(to, LinkOption.NOFOLLOW_LINKS);
        if (!created && (!FileTrees.isPlainDirectory(to) || !FileTrees.children(to).isEmpty())) {
            throw new LauncherException(ErrorCode.CONFLICT, "project.move.targetNotEmpty", to.toString());
        }
        long size = FileTrees.sizeOf(from);
        ProjectService.requireFreeSpace(to, size + MARGIN);

        ReentrantLock lock = svc.lockFor(id);
        lock.lock();
        try {
            try {
                pl.stage("project.move.stage.copying");
                FileTrees.copyTree(from, to, rel -> !FileTrees.isSessionLock(rel),
                    new FileTrees.Copy(pl, "project.move.stage.copying", size, ct));
                GameDirs.create(to);
                if (fault != null) {
                    fault.at("afterCopy");
                }
                pl.stage("project.move.stage.checking");
                verify(from, to, ct);
                ct.check();
                if (fault != null) {
                    fault.at("beforeSwitch");
                }
                Path target = to;
                svc.update(id, x -> x.gameDirLocation = toDefault ? null : target.toString());
            } catch (IOException e) {
                discard(to, created);
                throw translate(e, to);
            } catch (LauncherException | RuntimeException e) {
                discard(to, created);
                throw e;
            }
            // the project now runs from the new place; the old folder is only cleanup
            pl.stage("project.move.stage.cleaning");
            Path leftover = null;
            try {
                FileTrees.deleteTree(from.getParent(), from);
            } catch (LauncherException e) {
                LOG.log(Level.WARNING, "Moved " + id + " but couldn't remove " + from, e);
                leftover = from;
            }
            return new MoveResult(svc.require(id), leftover);
        } finally {
            lock.unlock();
        }
    }

    /** remove a partial copy: the whole folder when this move made it, only its contents when it was an empty folder before */
    private static void discard(Path to, boolean created) {
        try {
            if (created) {
                FileTrees.deleteTree(to.getParent(), to);
            } else {
                for (Path child : FileTrees.children(to)) {
                    FileTrees.deleteTree(to, child);
                }
            }
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't remove the partial copy at " + to, e);
        }
    }

    /** every file of the old folder is in the new one with the same size */
    private static void verify(Path from, Path to, CancellationToken ct) throws IOException, LauncherException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    ct.check();
                } catch (LauncherException e) {
                    throw new CancelledIo(e);
                }
                String rel = from.relativize(file).toString().replace('\\', '/');
                if (FileTrees.isSessionLock(rel) || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path copy = to.resolve(rel);
                if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS) || Files.size(copy) != attrs.size()) {
                    throw new MismatchIo(rel);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class CancelledIo extends IOException {
        final LauncherException cause;

        CancelledIo(LauncherException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    private static final class MismatchIo extends IOException {
        MismatchIo(String rel) {
            super(rel);
        }
    }

    /** a move can't carry links; refuse instead of silently dropping them */
    private static void requireNoLinks(Path from) throws LauncherException {
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (FileTrees.isLinkLike(attrs)) {
                        throw new MismatchIo(dir.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (FileTrees.isLinkLike(attrs)) {
                        throw new MismatchIo(file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (MismatchIo e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.move.hasLinks", e.getMessage());
        } catch (IOException e) {
            throw ProjectService.fileError(e, from);
        }
    }

    /** translate the private IO errors of {@link #verify} */
    static LauncherException translate(IOException e, Path to) {
        if (e instanceof CancelledIo c) {
            return c.cause;
        }
        if (e instanceof MismatchIo m) {
            return new LauncherException(ErrorCode.IO, "project.move.verifyFailed", m.getMessage());
        }
        return ProjectService.fileError(e, to);
    }

    // ---- locations ------------------------------------------------------------------------------------

    /**
     * The folder a project's game directory may be put in: an existing, writable folder, not a link, and not inside
     * the launcher's data folder.
     */
    static Path requireLocationParent(AppPaths paths, Path location) throws LauncherException {
        Path parent = location.toAbsolutePath().normalize();
        if (parent.startsWith(paths.root())) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.move.insideData", parent.toString());
        }
        if (!FileTrees.isPlainDirectory(parent)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.move.noFolder", parent.toString());
        }
        if (parent.getParent() == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.move.rootFolder", parent.toString());
        }
        if (!Files.isWritable(parent)) {
            throw new LauncherException(ErrorCode.IO, "project.move.notWritable", parent.toString());
        }
        return parent;
    }

    /** a folder name under {@code parent} that isn't taken (compared without case): {@code base}, {@code base-2}... */
    static String folderName(Path parent, String base) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Path c : FileTrees.children(parent)) {
            taken.add(c.getFileName().toString().toLowerCase(Locale.ROOT));
        }
        String name = base;
        int n = 2;
        while (taken.contains(name.toLowerCase(Locale.ROOT))) {
            name = base + "-" + n++;
        }
        return name;
    }

    /** the new game directory may not contain or sit inside another project's game directory */
    private static void requireNoOverlap(ProjectService svc, String ignoreId, Path target) throws LauncherException {
        for (Project other : svc.list()) {
            if (other.id.equalsIgnoreCase(ignoreId)) {
                continue;
            }
            Path game = svc.gameDir(other).toAbsolutePath().normalize();
            if (game.startsWith(target) || target.startsWith(game)) {
                throw new LauncherException(ErrorCode.CONFLICT, "project.move.overlap", other.name);
            }
        }
    }

    /** delete a game directory that belonged to a project removed for good (never a filesystem root or a link) */
    static void deleteExternal(AppPaths paths, Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        if (d.getParent() == null || d.getNameCount() < 2 || d.startsWith(paths.root())) {
            LOG.warning("Not deleting the unusual project folder " + d);
            return;
        }
        try {
            FileTrees.deleteTree(d.getParent(), d);
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't delete " + d, e);
        }
    }
}
