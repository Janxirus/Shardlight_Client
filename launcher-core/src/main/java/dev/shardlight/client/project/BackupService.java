package dev.shardlight.client.project;

import com.google.gson.JsonObject;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.PathSafety;
import dev.shardlight.client.util.SafeZip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Project and world backups in {@code backups/<projectId>/}. A backup is written to a {@code .part} file
 * and renamed into place when complete, with a JSON sidecar holding its {@link BackupInfo} (including the
 * zip's SHA-256). Restoring verifies the hash, takes an automatic {@link #PRE_RESTORE} backup of exactly
 * what will be replaced, extracts into a staging folder with {@link SafeZip} and then swaps folders in by
 * renaming; when anything fails, the current data stays as it was.
 *
 * <p>World {@code session.lock} files are never copied. A world the game has open can't be backed up or
 * restored (message key {@code project.world.inUse}). All methods block; calls for the same project are
 * serialized.
 */
public final class BackupService {
    private static final Logger LOG = Logger.getLogger(BackupService.class.getName());
    /** label of the automatic backup taken before every restore */
    public static final String PRE_RESTORE = "pre-restore";
    /** pre-restore backups younger than this are never pruned */
    public static final long PRE_RESTORE_KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000;
    static final String MANIFEST = "shardlight-backup.json";
    static final String WORLD_PREFIX = "world";
    static final String GAME_PREFIX = "minecraft";
    /** our own archives, verified by SHA-256 before use: large worlds are fine, names are still checked */
    static final SafeZip.Limits LIMITS = new SafeZip.Limits(2_000_000, 1L << 40, 64L << 30, 100_000);

    private final AppPaths paths;
    private final ProjectService projects;
    private final WorldService worlds;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Set<Path> activeParts = ConcurrentHashMap.newKeySet();
    /** the clock (epoch milliseconds); replaceable in tests */
    volatile LongSupplier clock = System::currentTimeMillis;
    /** moves folders during a restore; replaceable in tests */
    volatile ProjectService.DirMover mover = FileTrees::move;

    public BackupService(AppPaths paths, ProjectService projects) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.worlds = new WorldService(projects);
    }

    @FunctionalInterface
    private interface Content {
        void write(ZipBuilder zb) throws IOException, LauncherException;
    }

    // ---- creating backups -----------------------------------------------------------------------------

    /**
     * Back up the parts of a project's game directory selected by {@code scope} (null = everything), plus
     * a snapshot of its project.json. Fails when a world included in the scope is open in the game.
     */
    public BackupInfo backupProject(String projectId, String label, BackupScope scope, ProgressListener progress,
                                    CancellationToken cancel) throws LauncherException {
        Project p = projects.require(projectId);
        BackupScope s = scope == null ? BackupScope.EVERYTHING : scope;
        Path game = projects.gameDir(p);
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            if (s.worlds()) {
                for (Path w : FileTrees.children(game.resolve(GameDirs.SAVES))) {
                    if (FileTrees.isPlainDirectory(w)) {
                        WorldService.requireNotInUse(w, w.getFileName().toString());
                    }
                }
            }
            List<String> items = s.items();
            long total = 0;
            for (String item : items) {
                total += FileTrees.sizeOf(game.resolve(item));
            }
            String snapshot = Json.write(p) + "\n";
            return create(projectId, BackupInfo.PROJECT, null, label, s, zb -> {
                zb.addBytes(ProjectService.PROJECT_FILE, snapshot.getBytes(StandardCharsets.UTF_8));
                zb.addDirectory(GAME_PREFIX + "/");
                for (String item : items) {
                    Path f = game.resolve(item);
                    if (BackupScope.isFileItem(item)) {
                        if (FileTrees.isPlainFile(f)) {
                            zb.addFile(GAME_PREFIX + "/" + item, f);
                        }
                    } else {
                        zb.addTree(GAME_PREFIX + "/" + item, f, rel -> !FileTrees.isSessionLock(rel));
                    }
                }
            }, total, progress, cancel);
        } catch (LauncherException e) {
            if ("project.error.fileLocked".equals(e.messageKey())) {
                throw new LauncherException(ErrorCode.IO, e, "project.backup.fileInUse", e.args()[0]);
            }
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Back up one world ({@code saves/<worldFolder>}), without its {@code session.lock}. A world the game
     * holds open fails with a message asking to close it first.
     */
    public BackupInfo backupWorld(String projectId, String worldFolder, String label, ProgressListener progress,
                                  CancellationToken cancel) throws LauncherException {
        Project p = projects.require(projectId);
        Path dir = worlds.worldDir(p, worldFolder);
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            WorldService.requireNotInUse(dir, worldFolder);
            return create(projectId, BackupInfo.WORLD, worldFolder, label, BackupScope.WORLDS_ONLY,
                zb -> zb.addTree(WORLD_PREFIX, dir, rel -> !FileTrees.isSessionLock(rel)), FileTrees.sizeOf(dir), progress, cancel);
        } catch (LauncherException e) {
            if ("project.error.fileLocked".equals(e.messageKey())) {
                throw new LauncherException(ErrorCode.IO, e, "project.world.inUse", worldFolder);
            }
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private BackupInfo create(String projectId, String kind, String worldFolder, String label, BackupScope scope,
                              Content content, long total, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        String cleanLabel = cleanLabel(label);
        Path dir = backupsDir(projectId);
        long createdAt = clock.getAsLong();
        String fileLabel = Names.fileLabel(cleanLabel, 32);
        String base = ProjectService.stamp(createdAt) + "-" + kind + (fileLabel.isEmpty() ? "" : "-" + fileLabel);
        Path part = null;
        try {
            Files.createDirectories(dir);
            String id = base;
            for (int i = 2; Files.exists(dir.resolve(id + ".zip")) || Files.exists(dir.resolve(id + ".json"))
                || Files.exists(dir.resolve(id + ".zip.part")); i++) {
                id = base + "-" + i;
            }
            Path zip = dir.resolve(id + ".zip");
            part = dir.resolve(id + ".zip.part");
            activeParts.add(part);
            BackupInfo pending = new BackupInfo(id, projectId, kind, worldFolder, cleanLabel, createdAt, 0, null, scope);
            String sha;
            try (ZipBuilder zb = new ZipBuilder(part, progress, "project.stage.backup", total, cancel)) {
                zb.addBytes(MANIFEST, (Json.write(pending) + "\n").getBytes(StandardCharsets.UTF_8));
                content.write(zb);
                sha = zb.finish();
            }
            FileTrees.move(part, zip);
            BackupInfo info = new BackupInfo(id, projectId, kind, worldFolder, cleanLabel, createdAt, Files.size(zip), sha, scope);
            AtomicFiles.writeJson(dir.resolve(id + ".json"), info);
            LOG.info("Backup " + id + " of " + projectId + " written (" + info.sizeBytes() + " bytes)");
            return info;
        } catch (IOException e) {
            throw ProjectService.fileError(e, dir);
        } finally {
            if (part != null) {
                activeParts.remove(part);
                try {
                    Files.deleteIfExists(part);
                } catch (IOException e) {
                    LOG.fine("Couldn't remove " + part + ": " + e.getMessage());
                }
            }
        }
    }

    // ---- listing, deleting, pruning -------------------------------------------------------------------

    /** the project's backups, newest first (a lost sidecar is rebuilt from the zip) */
    public List<BackupInfo> list(String projectId) throws LauncherException {
        Path dir = backupsDir(projectId);
        List<BackupInfo> out = new ArrayList<>();
        for (Path f : FileTrees.children(dir)) {
            String name = f.getFileName().toString();
            if (name.endsWith(".zip.part") && !activeParts.contains(f)) {
                cleanupStalePart(f);
                continue;
            }
            if (!name.endsWith(".zip") || !FileTrees.isPlainFile(f)) {
                continue;
            }
            String id = name.substring(0, name.length() - ".zip".length());
            BackupInfo info = readSidecar(dir, projectId, id);
            if (info == null) {
                info = rebuildSidecar(dir, projectId, id);
            }
            if (info != null) {
                out.add(info);
            }
        }
        out.sort(Comparator.comparingLong(BackupInfo::createdAt).reversed().thenComparing(BackupInfo::id, Comparator.reverseOrder()));
        return out;
    }

    /** the zip file of a backup */
    public Path backupFile(BackupInfo b) throws LauncherException {
        Objects.requireNonNull(b, "backup");
        requireBackupId(b.id());
        return backupsDir(b.projectId()).resolve(b.id() + ".zip");
    }

    /** delete a backup (zip and sidecar) */
    public void delete(BackupInfo b) throws LauncherException {
        Path zip = backupFile(b);
        Path json = zip.resolveSibling(b.id() + ".json");
        ReentrantLock lock = lockFor(b.projectId());
        lock.lock();
        try {
            if (!Files.exists(zip, LinkOption.NOFOLLOW_LINKS) && !Files.exists(json, LinkOption.NOFOLLOW_LINKS)) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "project.backup.notFound", b.id());
            }
            FileTrees.deleteOne(zip);
            FileTrees.deleteOne(json);
            LOG.info("Deleted backup " + b.id() + " of " + b.projectId());
        } catch (IOException e) {
            throw ProjectService.fileError(e, zip);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Keep the newest {@code keepLast} backups and delete older ones, except automatic pre-restore backups
     * younger than seven days.
     */
    public void prune(String projectId, int keepLast) throws LauncherException {
        if (keepLast < 0) {
            throw new IllegalArgumentException("keepLast must be >= 0");
        }
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            List<BackupInfo> all = list(projectId);
            long now = clock.getAsLong();
            for (int i = keepLast; i < all.size(); i++) {
                BackupInfo b = all.get(i);
                if (b.isPreRestore() && now - b.createdAt() < PRE_RESTORE_KEEP_MILLIS) {
                    continue;
                }
                delete(b);
            }
        } finally {
            lock.unlock();
        }
    }

    // ---- restoring ------------------------------------------------------------------------------------

    /**
     * Restore a backup. The zip's SHA-256 must match its sidecar; then an automatic pre-restore backup of
     * exactly what will be replaced is taken, the archive is extracted into a staging folder and the
     * folders are swapped in by renaming (for a world: {@code saves/<folder>}). On any failure,
     * cancellation included, the current data is left as it was.
     */
    public void restore(BackupInfo b, ProgressListener progress, CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(b, "backup");
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        Path zip = backupFile(b);
        ReentrantLock lock = lockFor(b.projectId());
        lock.lock();
        try {
            Project p = projects.require(b.projectId());
            BackupInfo stored = readSidecar(zip.getParent(), b.projectId(), b.id());
            if (stored == null || !FileTrees.isPlainFile(zip)) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "project.backup.notFound", b.id());
            }
            if (b.sha256() != null && !b.sha256().equalsIgnoreCase(stored.sha256())) {
                throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "project.backup.damaged", b.id());
            }
            pl.stage("project.stage.verifying");
            if (stored.sha256() == null || !stored.sha256().equalsIgnoreCase(sha256(zip, ct))) {
                throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "project.backup.damaged", b.id());
            }
            List<SafeZip.Entry> entries = SafeZip.list(zip, LIMITS);
            if (BackupInfo.WORLD.equals(stored.kind())) {
                requireLayout(zip, entries, n -> n.equals(WORLD_PREFIX + "/") || n.startsWith(WORLD_PREFIX + "/"));
                restoreWorld(p, stored, zip, pl, ct);
            } else {
                BackupScope scope = stored.scope() == null ? BackupScope.EVERYTHING : stored.scope();
                List<String> items = scope.items();
                requireLayout(zip, entries, n -> isProjectEntry(n, items));
                restoreProject(p, stored, scope, zip, pl, ct);
            }
            LOG.info("Restored backup " + b.id() + " of " + b.projectId());
        } finally {
            lock.unlock();
        }
        projects.fireChanged();
    }

    private void restoreWorld(Project p, BackupInfo b, Path zip, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        String folder = b.worldFolder();
        if (folder == null || folder.startsWith(".") || !PathSafety.isSafeSegment(folder)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(folder));
        }
        Path saves = projects.gameDir(p).resolve(GameDirs.SAVES);
        Path target = saves.resolve(folder);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists) {
            if (!FileTrees.isPlainDirectory(target)) {
                throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", target.toString());
            }
            WorldService.requireNotInUse(target, folder);
            progress.stage("project.stage.preRestore");
            backupWorld(p.id, folder, PRE_RESTORE, progress, cancel);
        }
        Path staging = newStaging(p);
        Swap swap = new Swap();
        try {
            progress.stage("project.stage.extracting");
            Path fresh = staging.resolve("new");
            SafeZip.extract(zip, WORLD_PREFIX + "/", fresh, LIMITS, null, cancel);
            Files.createDirectories(fresh);
            Files.createDirectories(saves);
            Files.createDirectories(staging.resolve("old"));
            cancel.check();
            progress.stage("project.stage.swapping");
            if (exists) {
                swap.move(target, staging.resolve("old").resolve(folder));
            }
            swap.move(fresh, target);
        } catch (IOException e) {
            swap.undo();
            throw FileTrees.isLockError(e)
                ? new LauncherException(ErrorCode.IO, e, "project.world.inUse", folder)
                : LauncherException.io(e, FileTrees.failedFile(e, target));
        } catch (LauncherException | RuntimeException e) {
            swap.undo();
            throw e;
        } finally {
            discard(p, staging);
        }
    }

    private void restoreProject(Project p, BackupInfo b, BackupScope scope, Path zip, ProgressListener progress,
                                CancellationToken cancel) throws LauncherException {
        Path game = projects.gameDir(p);
        List<String> items = scope.items();
        if (scope.worlds()) {
            for (Path w : FileTrees.children(game.resolve(GameDirs.SAVES))) {
                if (FileTrees.isPlainDirectory(w)) {
                    WorldService.requireNotInUse(w, w.getFileName().toString());
                }
            }
        }
        progress.stage("project.stage.preRestore");
        backupProject(p.id, PRE_RESTORE, scope, progress, cancel);
        Project snapshot = readSnapshot(zip);
        Path staging = newStaging(p);
        Swap swap = new Swap();
        try {
            progress.stage("project.stage.extracting");
            Path fresh = staging.resolve("new");
            Path old = staging.resolve("old");
            SafeZip.extract(zip, GAME_PREFIX + "/", fresh, LIMITS, rel -> items.contains(topLevel(rel)), cancel);
            Files.createDirectories(fresh);
            Files.createDirectories(old);
            Files.createDirectories(game);
            cancel.check();
            progress.stage("project.stage.swapping");
            for (String item : items) {
                Path current = game.resolve(item);
                Path staged = fresh.resolve(item);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    swap.move(current, old.resolve(item));
                }
                if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                    swap.move(staged, current);
                } else if (!BackupScope.isFileItem(item)) {
                    swap.createDirectory(current);
                }
            }
        } catch (IOException e) {
            swap.undo();
            throw ProjectService.fileError(e, game);
        } catch (LauncherException | RuntimeException e) {
            swap.undo();
            throw e;
        } finally {
            discard(p, staging);
        }
        if (snapshot != null && (scope.mods() || scope.resourcePacks())) {
            Set<ContentKind> kinds = EnumSet.noneOf(ContentKind.class);
            if (scope.mods()) {
                kinds.add(ContentKind.MOD);
            }
            if (scope.resourcePacks()) {
                kinds.add(ContentKind.RESOURCE_PACK);
                kinds.add(ContentKind.SHADER_PACK);
            }
            try {
                projects.update(p.id, proj -> {
                    proj.content.removeIf(e -> kinds.contains(e.kind));
                    for (ContentEntry e : snapshot.content) {
                        if (kinds.contains(e.kind)) {
                            proj.content.add(e);
                        }
                    }
                });
            } catch (LauncherException e) {
                LOG.log(Level.WARNING, "Files were restored but the content list of " + p.id + " couldn't be updated", e);
            }
        }
    }

    /** the project.json snapshot inside a project backup, or null */
    private static Project readSnapshot(Path zip) {
        try {
            byte[] data = SafeZip.read(zip, ProjectService.PROJECT_FILE, 8L * 1024 * 1024);
            if (data == null) {
                return null;
            }
            JsonObject o = ProjectService.parseObject(new String(data, StandardCharsets.UTF_8));
            if (o == null || ProjectService.MIGRATOR.isNewer(o)) {
                return null;
            }
            ProjectService.MIGRATOR.migrate(o);
            Project p = Json.GSON.fromJson(o, Project.class);
            if (p != null) {
                ProjectService.fillDefaults(p);
            }
            return p;
        } catch (LauncherException | RuntimeException e) {
            LOG.fine("No usable project snapshot in " + zip + ": " + e.getMessage());
            return null;
        }
    }

    /** renames done during a restore, undone in reverse order when a later step fails */
    private final class Swap {
        private record Op(Path from, Path to) {
        }

        private final Deque<Op> done = new ArrayDeque<>();

        void move(Path from, Path to) throws IOException {
            mover.move(from, to);
            done.push(new Op(from, to));
        }

        void createDirectory(Path dir) throws IOException {
            Files.createDirectories(dir);
            done.push(new Op(null, dir));
        }

        void undo() {
            while (!done.isEmpty()) {
                Op op = done.pop();
                try {
                    if (op.from() == null) {
                        Files.deleteIfExists(op.to());
                    } else {
                        mover.move(op.to(), op.from());
                    }
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Couldn't undo a restore step: " + op.to() + " -> " + op.from(), e);
                }
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private ReentrantLock lockFor(String projectId) {
        return locks.computeIfAbsent(ProjectService.key(String.valueOf(projectId)), k -> new ReentrantLock());
    }

    private Path backupsDir(String projectId) throws LauncherException {
        if (!ProjectService.isUsableId(projectId)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(projectId));
        }
        return paths.backups().resolve(projectId);
    }

    private static void requireBackupId(String id) throws LauncherException {
        if (id == null || id.startsWith(".") || !PathSafety.isSafeSegment(id) || Names.hasControl(id)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(id));
        }
    }

    private Path newStaging(Project p) throws LauncherException {
        Path dir = projects.projectDir(p.id).resolve(".restore-" + UUID.randomUUID());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        return dir;
    }

    private void discard(Project p, Path staging) {
        try {
            FileTrees.deleteTree(projects.projectDir(p.id), staging);
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't remove the restore staging folder " + staging, e);
        }
    }

    private void cleanupStalePart(Path part) {
        try {
            if (clock.getAsLong() - FileTrees.attrs(part).lastModifiedTime().toMillis() > 60L * 60 * 1000) {
                FileTrees.deleteOne(part);
            }
        } catch (IOException e) {
            LOG.fine("Couldn't remove stale " + part + ": " + e.getMessage());
        }
    }

    private BackupInfo readSidecar(Path dir, String projectId, String id) throws LauncherException {
        requireBackupId(id);
        Path json = dir.resolve(id + ".json");
        if (!FileTrees.isPlainFile(json)) {
            return null;
        }
        BackupInfo info;
        try {
            info = AtomicFiles.readJson(json, BackupInfo.class, () -> null).value();
        } catch (LauncherException e) {
            LOG.fine("Unreadable backup sidecar " + json + ": " + e.getMessage());
            return null;
        }
        if (info == null || !id.equals(info.id()) || !projectId.equalsIgnoreCase(String.valueOf(info.projectId()))) {
            return null;
        }
        return info;
    }

    /** rebuild a lost or damaged sidecar from the manifest inside the zip */
    private BackupInfo rebuildSidecar(Path dir, String projectId, String id) {
        Path zip = dir.resolve(id + ".zip");
        try {
            byte[] data = SafeZip.read(zip, MANIFEST, 64 * 1024);
            if (data == null) {
                return null;
            }
            BackupInfo m = Json.GSON.fromJson(new String(data, StandardCharsets.UTF_8), BackupInfo.class);
            if (m == null || m.kind() == null) {
                return null;
            }
            BackupInfo info = new BackupInfo(id, projectId, m.kind(), m.worldFolder(), m.label(), m.createdAt(),
                Files.size(zip), Hashing.hash(zip, HashAlgo.SHA256), m.scope());
            AtomicFiles.writeJson(dir.resolve(id + ".json"), info);
            LOG.info("Rebuilt the description of backup " + id);
            return info;
        } catch (LauncherException | IOException | RuntimeException e) {
            LOG.warning("Ignoring unreadable backup " + zip + ": " + e.getMessage());
            return null;
        }
    }

    private static String sha256(Path file, CancellationToken cancel) throws LauncherException {
        MessageDigest md = HashAlgo.SHA256.newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                cancel.check();
            }
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
        return Hashing.hex(md.digest());
    }

    private static void requireLayout(Path zip, List<SafeZip.Entry> entries, java.util.function.Predicate<String> ok)
        throws LauncherException {
        for (SafeZip.Entry e : entries) {
            String n = e.name().replace('\\', '/');
            if (!n.equals(MANIFEST) && !ok.test(n)) {
                throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "error.archive.invalid", zip.getFileName().toString(),
                    "unexpected entry " + e.name());
            }
        }
    }

    private static boolean isProjectEntry(String n, List<String> items) {
        if (n.equals(ProjectService.PROJECT_FILE) || n.equals(GAME_PREFIX + "/")) {
            return true;
        }
        if (!n.startsWith(GAME_PREFIX + "/")) {
            return false;
        }
        String rel = n.substring(GAME_PREFIX.length() + 1);
        return items.contains(topLevel(rel));
    }

    /** the first segment of a '/'-separated path, without a trailing slash */
    private static String topLevel(String rel) {
        int slash = rel.indexOf('/');
        return slash < 0 ? rel : rel.substring(0, slash);
    }

    private static String cleanLabel(String label) {
        if (label == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        label.strip().codePoints().limit(100).forEach(cp -> b.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
        return b.toString().strip();
    }
}
