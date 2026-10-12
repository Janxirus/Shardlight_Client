package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.nbt.NbtCompound;
import dev.shardlight.client.nbt.NbtIo;
import dev.shardlight.client.nbt.NbtTag;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * The worlds of a project ({@code <game dir>/saves/<folder>/}): listing from {@code level.dat}, duplicate,
 * rename, a per-project trash ({@code projects/<id>/.trash/worlds/}) and downgrade warnings. A world the
 * game has open (its {@code session.lock} is locked) is never changed; those calls fail with the message
 * key {@code project.world.inUse}.
 */
public final class WorldService {
    private static final Logger LOG = Logger.getLogger(WorldService.class.getName());
    static final String LEVEL_DAT = "level.dat";
    static final String LEVEL_DAT_OLD = "level.dat_old";
    private static final long MAX_LEVEL_DAT = 16L * 1024 * 1024;
    private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};

    private final ProjectAccess projects;
    private final ReentrantLock mutation = new ReentrantLock();
    /** moves folders; replaceable in tests */
    volatile ProjectService.DirMover mover = FileTrees::move;

    public WorldService(ProjectAccess projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /** every world, most recently played first; damaged worlds are included with {@code readable == false} */
    public List<WorldInfo> list(Project p) {
        List<WorldInfo> out = new ArrayList<>();
        for (Path dir : FileTrees.children(savesDir(p))) {
            String name = dir.getFileName().toString();
            if (name.startsWith(".") || !FileTrees.isPlainDirectory(dir)) {
                continue;
            }
            out.add(readWorld(dir));
        }
        out.sort(Comparator.comparingLong(WorldInfo::lastPlayed).reversed().thenComparing(WorldInfo::folder));
        return out;
    }

    /** one world by folder name */
    public WorldInfo info(Project p, String folder) throws LauncherException {
        return readWorld(worldDir(p, folder));
    }

    /** true when the game currently has the world open */
    public boolean isInUse(Project p, String folder) throws LauncherException {
        return isInUse(worldDir(p, folder));
    }

    /**
     * Copy a world to a new folder (named after {@code newName}, made unique) and set the copy's
     * {@code LevelName}. The original is not touched.
     */
    public WorldInfo duplicate(Project p, String folder, String newName) throws LauncherException {
        String name = Names.requireWorldName(newName);
        Path src = worldDir(p, folder);
        Path projectDir = projects.projectDir(p.id);
        mutation.lock();
        try {
            requireNotInUse(src, folder);
            NbtIo.NamedTag level = readLevel(src, folder);
            Path staging = projectDir.resolve(ProjectService.STAGING_PREFIX + "world-" + UUID.randomUUID());
            try {
                FileTrees.copyTree(src, staging, rel -> !FileTrees.isSessionLock(rel),
                    new FileTrees.Copy(ProgressListener.NONE, "project.stage.copying", 0, CancellationToken.NONE));
                writeLevelName(staging, level, name);
                Path saves = savesDir(p);
                String target = uniqueWorldFolder(saves, name);
                mover.move(staging, saves.resolve(target));
                LOG.info("Duplicated world " + folder + " as " + target + " in " + p.id);
                return readWorld(saves.resolve(target));
            } catch (IOException e) {
                throw ProjectService.fileError(e, src);
            } finally {
                discard(projectDir, staging);
            }
        } finally {
            mutation.unlock();
        }
    }

    /** change the in-game name ({@code LevelName}); the folder keeps its name */
    public WorldInfo rename(Project p, String folder, String newName) throws LauncherException {
        String name = Names.requireWorldName(newName);
        Path dir = worldDir(p, folder);
        mutation.lock();
        try {
            requireNotInUse(dir, folder);
            NbtIo.NamedTag level = readLevel(dir, folder);
            writeLevelName(dir, level, name);
            return readWorld(dir);
        } finally {
            mutation.unlock();
        }
    }

    /** move the world to {@code projects/<id>/.trash/worlds/<folder>-<yyyyMMdd-HHmmss>} (restorable) */
    public void moveToTrash(Project p, String folder) throws LauncherException {
        Path dir = worldDir(p, folder);
        mutation.lock();
        try {
            requireNotInUse(dir, folder);
            Path trash = trashDir(p);
            try {
                Files.createDirectories(trash);
                String name = ProjectService.uniqueChild(trash, folder + "-" + ProjectService.stamp(System.currentTimeMillis()));
                mover.move(dir, trash.resolve(name));
                LOG.info("Moved world " + folder + " of " + p.id + " to its trash as " + name);
            } catch (IOException e) {
                throw FileTrees.isLockError(e)
                    ? new LauncherException(ErrorCode.IO, e, "project.world.inUse", folder)
                    : LauncherException.io(e, FileTrees.failedFile(e, dir));
            }
        } finally {
            mutation.unlock();
        }
    }

    /** worlds in the project's trash, most recently trashed first */
    public List<TrashedWorld> trashed(Project p) {
        List<TrashedWorld> out = new ArrayList<>();
        for (Path d : FileTrees.children(trashDir(p))) {
            String trashName = d.getFileName().toString();
            if (!FileTrees.isPlainDirectory(d)) {
                continue;
            }
            long at = ProjectService.parseStampSuffix(trashName);
            String folder = at > 0 ? trashName.replaceFirst("-\\d{8}-\\d{6}(?:-\\d+)?$", "") : trashName;
            if (at <= 0) {
                try {
                    at = FileTrees.attrs(d).lastModifiedTime().toMillis();
                } catch (IOException e) {
                    at = 0;
                }
            }
            WorldInfo w = readWorld(d);
            out.add(new TrashedWorld(trashName, folder, w.readable() ? w.name() : folder, at, w.sizeBytes()));
        }
        out.sort(Comparator.comparingLong(TrashedWorld::trashedAt).reversed());
        return out;
    }

    /** move a trashed world back to {@code saves/} (under a new folder name if its old one is taken) */
    public WorldInfo restore(Project p, String trashName) throws LauncherException {
        Path trash = trashDir(p);
        Path src = trashEntry(trash, trashName);
        mutation.lock();
        try {
            TrashedWorld t = trashed(p).stream().filter(x -> x.trashName().equals(trashName)).findFirst()
                .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "project.world.notFound", trashName));
            Path saves = savesDir(p);
            String target = PathSafety.isSafeSegment(t.folder()) ? t.folder() : PathSafety.sanitizeFileName(t.folder());
            target = uniqueFolder(saves, target);
            try {
                Files.createDirectories(saves);
                mover.move(src, saves.resolve(target));
            } catch (IOException e) {
                throw ProjectService.fileError(e, src);
            }
            return readWorld(saves.resolve(target));
        } finally {
            mutation.unlock();
        }
    }

    /** delete a trashed world for good (only inside the project's world trash; links are not followed) */
    public void deleteFromTrash(Project p, String trashName) throws LauncherException {
        Path trash = trashDir(p);
        Path src = trashEntry(trash, trashName);
        mutation.lock();
        try {
            FileTrees.deleteTree(trash, src);
        } finally {
            mutation.unlock();
        }
    }

    /**
     * True when the world was last saved by a version that may be newer than {@code minecraftVersion}, so
     * the UI should warn before opening it. Version names are compared numerically (1.x and 26.x schemes);
     * snapshots and names that can't be ordered count as newer (better a needless warning than a damaged
     * world). Unreadable worlds and worlds without a version return false.
     */
    public boolean isNewerThan(WorldInfo world, String minecraftVersion) {
        if (world == null || !world.readable()) {
            return false;
        }
        return GameVersionOrder.mayBeNewer(world.versionName(), minecraftVersion);
    }

    /** the exact check when the game version's data version is known (from its version JSON) */
    public boolean isNewerThan(WorldInfo world, int gameDataVersion) {
        return world != null && world.readable() && world.dataVersion() > 0 && gameDataVersion > 0
            && world.dataVersion() > gameDataVersion;
    }

    // ---- reading level.dat ----------------------------------------------------------------------------

    /** read a world folder; never throws (damaged worlds come back with {@code readable == false}) */
    static WorldInfo readWorld(Path dir) {
        String folder = dir.getFileName().toString();
        long size = FileTrees.sizeOf(dir);
        Path icon = FileTrees.isPlainFile(dir.resolve("icon.png")) ? dir.resolve("icon.png") : null;
        for (String f : new String[] {LEVEL_DAT, LEVEL_DAT_OLD}) {
            Optional<NbtCompound> data = readData(dir.resolve(f));
            if (data.isPresent()) {
                return fromData(folder, data.get(), size, icon);
            }
        }
        long mtime;
        try {
            mtime = FileTrees.attrs(dir).lastModifiedTime().toMillis();
        } catch (IOException e) {
            mtime = 0;
        }
        return new WorldInfo(folder, folder, mtime, "unknown", false, false, null, 0, size, icon, false);
    }

    private static Optional<NbtCompound> readData(Path file) {
        try {
            if (!FileTrees.isPlainFile(file) || Files.size(file) > MAX_LEVEL_DAT) {
                return Optional.empty();
            }
            return NbtIo.readFile(file).findCompound("Data");
        } catch (IOException e) {
            LOG.fine("Unreadable " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static WorldInfo fromData(String folder, NbtCompound d, long size, Path icon) {
        String name = d.getString("LevelName", folder);
        if (name.isBlank()) {
            name = folder;
        }
        int gameType = d.getInt("GameType", 0);
        String mode = gameType >= 0 && gameType < GAME_MODES.length ? GAME_MODES[gameType] : "unknown";
        NbtCompound version = d.getCompound("Version");
        String versionName = version.contains("Name", NbtTag.TAG_STRING) ? version.getString("Name") : null;
        int dataVersion = d.getInt("DataVersion", version.getInt("Id", 0));
        return new WorldInfo(folder, name, d.getLong("LastPlayed", 0), mode, d.getBoolean("hardcore"),
            d.getBoolean("allowCommands"), versionName, dataVersion, size, icon, true);
    }

    private static NbtIo.NamedTag readLevel(Path worldDir, String folder) throws LauncherException {
        Path file = worldDir.resolve(LEVEL_DAT);
        try {
            if (FileTrees.isPlainFile(file) && Files.size(file) <= MAX_LEVEL_DAT) {
                NbtIo.NamedTag t = NbtIo.readNamedFile(file);
                if (t.tag() instanceof NbtCompound c && c.contains("Data", NbtTag.TAG_COMPOUND)) {
                    return t;
                }
            }
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "project.world.unreadable", folder);
        }
        throw new LauncherException(ErrorCode.INVALID_DATA, "project.world.unreadable", folder);
    }

    private static void writeLevelName(Path worldDir, NbtIo.NamedTag level, String name) throws LauncherException {
        NbtCompound root = ((NbtCompound) level.tag()).copy();
        root.getCompound("Data").putString("LevelName", name);
        byte[] bytes;
        try {
            bytes = NbtIo.toBytes(new NbtIo.NamedTag(level.name(), root), NbtIo.Compression.GZIP);
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "project.world.unreadable", worldDir.getFileName().toString());
        }
        AtomicFiles.writeBytes(worldDir.resolve(LEVEL_DAT), bytes);
    }

    // ---- locks and paths ------------------------------------------------------------------------------

    /**
     * True when another program (the game) holds the world's {@code session.lock}. Minecraft keeps that
     * file locked while the world is open; a sharing violation on Windows counts as held too.
     */
    static boolean isInUse(Path worldDir) {
        Path lock = worldDir.resolve(FileTrees.SESSION_LOCK);
        if (!FileTrees.isPlainFile(lock)) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) {
                return true;
            }
            l.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return FileTrees.isLockError(e);
        }
    }

    static void requireNotInUse(Path worldDir, String folder) throws LauncherException {
        if (isInUse(worldDir)) {
            throw new LauncherException(ErrorCode.IO, "project.world.inUse", folder);
        }
    }

    Path savesDir(Project p) {
        return projects.gameDir(p).resolve(GameDirs.SAVES);
    }

    private Path trashDir(Project p) {
        return projects.projectDir(p.id).resolve(ProjectService.PROJECT_TRASH).resolve("worlds");
    }

    /** {@code saves/<folder>}: one safe folder name that exists as a real folder */
    Path worldDir(Project p, String folder) throws LauncherException {
        Objects.requireNonNull(p, "project");
        Path saves = savesDir(p).toAbsolutePath().normalize();
        if (folder == null || folder.startsWith(".") || !PathSafety.isSafeSegment(folder)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(folder));
        }
        Path dir = PathSafety.resolveInside(saves, folder);
        if (!saves.equals(dir.getParent())) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", folder);
        }
        if (!FileTrees.isPlainDirectory(dir)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.world.notFound", folder);
        }
        return dir;
    }

    private static void discard(Path base, Path staging) {
        try {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                FileTrees.deleteTree(base, staging);
            }
        } catch (LauncherException e) {
            LOG.warning("Couldn't clean up " + staging + ": " + e.getMessage());
        }
    }

    private static Path trashEntry(Path trash, String trashName) throws LauncherException {
        if (trashName == null || trashName.startsWith(".") || !PathSafety.isSafeSegment(trashName)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(trashName));
        }
        Path t = trash.toAbsolutePath().normalize();
        Path src = PathSafety.resolveInside(t, trashName);
        if (!t.equals(src.getParent()) || !Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.world.notFound", trashName);
        }
        return src;
    }

    /** a folder name for a world called {@code name}: sanitized, then " (2)", " (3)"... when taken */
    static String uniqueWorldFolder(Path saves, String name) {
        return uniqueFolder(saves, PathSafety.sanitizeFileName(name));
    }

    private static String uniqueFolder(Path saves, String base) {
        Set<String> taken = new HashSet<>();
        for (Path c : FileTrees.children(saves)) {
            taken.add(ProjectService.key(c.getFileName().toString()));
        }
        String b = (base.length() > 60 ? base.substring(0, 60) : base).replaceAll("[. ]+$", "");
        if (b.isEmpty() || !PathSafety.isSafeSegment(b)) {
            b = PathSafety.sanitizeFileName(b);
        }
        if (!taken.contains(ProjectService.key(b))) {
            return b;
        }
        for (int i = 2; ; i++) {
            String candidate = b + " (" + i + ")";
            if (!taken.contains(ProjectService.key(candidate))) {
                return candidate;
            }
        }
    }
}
