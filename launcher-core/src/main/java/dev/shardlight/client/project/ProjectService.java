package dev.shardlight.client.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.storage.JsonMigrator;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.PathSafety;
import dev.shardlight.client.util.SafeZip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Projects (instances) on disk: {@code projects/<id>/project.json}, the game directory
 * {@code projects/<id>/minecraft/}, an optional {@code icon.png} and {@code servers-meta.json}.
 *
 * <ul>
 *   <li>Every write is atomic; the previous good {@code project.json} is kept as {@code project.json.bak}.</li>
 *   <li>Older files are migrated ({@link #MIGRATOR}); a file written by a newer launcher is shown but never
 *       overwritten ({@link #isReadOnly}).</li>
 *   <li>An unreadable {@code project.json} is set aside as {@code project.json.corrupt-<time>} and the project
 *       is rebuilt from the backup copy (or from the folder), reported through {@link #problems()}.</li>
 *   <li>New, duplicated, imported and restored projects are assembled in a hidden staging folder and appear
 *       in one rename, so a failure or crash never leaves half a project behind.</li>
 *   <li>Removing a project moves it to {@code trash/} first ({@link #archive}); only
 *       {@link #deletePermanently} deletes, and only inside {@code trash/}, never through links.</li>
 * </ul>
 * All methods block and are safe to call from several threads; listeners run after each change on the
 * calling thread.
 */
public final class ProjectService implements ProjectAccess {
    private static final Logger LOG = Logger.getLogger(ProjectService.class.getName());

    /** the icon a project gets when none is chosen */
    public static final String DEFAULT_ICON = "builtin:grass";
    /** the file icon reference set by {@link #setIconFromFile} */
    public static final String FILE_ICON = "file:icon.png";
    /** extension of exported projects */
    public static final String EXPORT_EXTENSION = ".shardlight";

    static final String PROJECT_FILE = "project.json";
    static final String PROJECT_BACKUP = "project.json.bak";
    static final String PRE_MIGRATION = "project.json.pre-migration";
    static final String GAME_DIR = "minecraft";
    static final String ICON_FILE = "icon.png";
    static final String SERVERS_META = "servers-meta.json";
    /** per-project trash for worlds and screenshots */
    static final String PROJECT_TRASH = ".trash";
    static final String STAGING_PREFIX = ".staging-";
    static final String TRASH_INDEX = "index.json";
    static final String EXPORT_MANIFEST = "shardlight-project.json";
    static final int EXPORT_FORMAT = 1;
    static final long MAX_ICON_BYTES = 2L * 1024 * 1024;
    static final Set<String> EXPORT_FOLDERS = Set.of(GameDirs.SAVES, GameDirs.SCREENSHOTS, GameDirs.MODS, GameDirs.CONFIG,
        GameDirs.RESOURCE_PACKS, GameDirs.SHADER_PACKS);
    /** imports come from other people: generous for big worlds, strict about expansion */
    static final SafeZip.Limits IMPORT_LIMITS = new SafeZip.Limits(500_000, 64L << 30, 16L << 30, 1000);
    private static final long STALE_STAGING_MILLIS = 24L * 60 * 60 * 1000;
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** project.json schema migrations */
    public static final JsonMigrator MIGRATOR = new JsonMigrator(PROJECT_FILE, Project.SCHEMA_VERSION)
        .step(0, ProjectService::migrate0to1);

    static final Comparator<Project> ORDER = Comparator.comparingLong((Project p) -> p.lastPlayedAt).reversed()
        .thenComparing(p -> p.name == null ? "" : p.name, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(p -> p.id);

    private final AppPaths paths;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> problems = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> readOnlyIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Set<Path> activeStaging = ConcurrentHashMap.newKeySet();
    private final Object idLock = new Object();
    private final Object trashLock = new Object();

    /** moves folders; replaced in tests to simulate files held open by another program */
    volatile DirMover mover = FileTrees::move;
    /** the clock (epoch milliseconds); replaceable in tests */
    /** lets tests make a move fail at chosen points */
    volatile ProjectMover.Fault moveFault;
    volatile LongSupplier clock = System::currentTimeMillis;

    /** moves a folder to a path that doesn't exist yet */
    @FunctionalInterface
    interface DirMover {
        void move(Path from, Path to) throws IOException;
    }

    public ProjectService(AppPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    // ---- listing and reading --------------------------------------------------------------------------

    /**
     * Every project, most recently played first, then by name. Unreadable files are recovered (see the
     * class description) and reported through {@link #problems()}; this method never fails as a whole.
     */
    public List<Project> list() {
        List<Project> out = new ArrayList<>();
        Path root = paths.projects();
        if (!Files.isDirectory(root)) {
            return out;
        }
        for (Path dir : FileTrees.children(root)) {
            String id = dir.getFileName().toString();
            if (id.startsWith(STAGING_PREFIX)) {
                cleanupStaleStaging(dir);
                continue;
            }
            if (!isUsableId(id) || !FileTrees.isPlainDirectory(dir)) {
                continue;
            }
            try {
                load(id).ifPresent(out::add);
            } catch (LauncherException e) {
                LOG.log(Level.WARNING, "Can't read project " + id, e);
                addProblem(e.userMessage());
            }
        }
        out.sort(ORDER);
        return out;
    }

    /**
     * Localized messages about project files that were unreadable, recovered or written by a newer
     * launcher, collected since startup or the last {@link #clearProblems()}.
     */
    public List<String> problems() {
        synchronized (problems) {
            return List.copyOf(problems);
        }
    }

    /** forget the reported problems (after the UI showed them) */
    public void clearProblems() {
        problems.clear();
    }

    /** true when the project's file was written by a newer launcher; it can be read but not saved */
    public boolean isReadOnly(String id) {
        return id != null && readOnlyIds.contains(key(id));
    }

    @Override
    public Optional<Project> get(String id) {
        if (!isUsableId(id)) {
            return Optional.empty();
        }
        try {
            return load(id);
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Can't read project " + id, e);
            addProblem(e.userMessage());
            return Optional.empty();
        }
    }

    /** the project or {@link ErrorCode#NOT_FOUND} */
    Project require(String id) throws LauncherException {
        if (!isUsableId(id)) {
            throw notFound(id);
        }
        return load(id).orElseThrow(() -> notFound(id));
    }

    @Override
    public Path projectDir(String id) {
        if (!isUsableId(id)) {
            throw new IllegalArgumentException("Not a valid project id: " + id);
        }
        return paths.project(id);
    }

    @Override
    public Path gameDir(Project p) {
        if (p.gameDirLocation != null && !p.gameDirLocation.isBlank()) {
            return Path.of(p.gameDirLocation);
        }
        return projectDir(p.id).resolve(GAME_DIR);
    }

    // ---- saving ---------------------------------------------------------------------------------------

    /**
     * Save atomically (keeping the previous file as {@code project.json.bak}) and notify listeners.
     * Fails with {@link ErrorCode#NOT_FOUND} when the project folder doesn't exist and with
     * {@link ErrorCode#UNSUPPORTED_VERSION} when the file on disk was written by a newer launcher.
     */
    @Override
    public Project save(Project project) throws LauncherException {
        Objects.requireNonNull(project, "project");
        String id = project.id;
        if (!isUsableId(id)) {
            throw notFound(id);
        }
        Project saved;
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            saved = writeProject(id, project);
        } finally {
            lock.unlock();
        }
        fireChanged();
        return saved;
    }

    /** load, apply {@code change}, save; all under the project's lock. The id can't be changed. */
    public Project update(String id, Consumer<Project> change) throws LauncherException {
        Objects.requireNonNull(change, "change");
        if (!isUsableId(id)) {
            throw notFound(id);
        }
        Project saved;
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            Project p = load(id).orElseThrow(() -> notFound(id));
            change.accept(p);
            saved = writeProject(id, p);
        } finally {
            lock.unlock();
        }
        fireChanged();
        return saved;
    }

    /** change the display name only; the id and folder stay */
    public Project rename(String id, String newName) throws LauncherException {
        String name = Names.requireProjectName(newName);
        return update(id, p -> p.name = name);
    }

    /** use a builtin icon ("grass" or "builtin:grass"); a custom icon.png is removed */
    public Project setBuiltinIcon(String id, String iconId) throws LauncherException {
        String icon = Names.builtinIcon(iconId);
        Project saved = update(id, p -> p.icon = icon);
        try {
            FileTrees.deleteOne(paths.project(id).resolve(ICON_FILE));
        } catch (IOException e) {
            LOG.fine("Couldn't remove the old icon of " + id + ": " + e.getMessage());
        }
        return saved;
    }

    /**
     * Use a PNG or JPEG image (at most 2 MB, checked by its signature, not its name) as the project icon. It
     * is stored as {@code icon.png} in the project folder and the icon becomes {@link #FILE_ICON}.
     */
    public Project setIconFromFile(String id, Path image) throws LauncherException {
        Objects.requireNonNull(image, "image");
        byte[] data = readIcon(image);
        if (!isUsableId(id)) {
            throw notFound(id);
        }
        Project saved;
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            Project p = load(id).orElseThrow(() -> notFound(id));
            Path dir = paths.project(id);
            requireWritable(id, dir.resolve(PROJECT_FILE), p.name);
            AtomicFiles.writeBytes(dir.resolve(ICON_FILE), data);
            p.icon = FILE_ICON;
            saved = writeProject(id, p);
        } finally {
            lock.unlock();
        }
        fireChanged();
        return saved;
    }

    /** the project's custom icon file, when it uses one and the file exists */
    public Optional<Path> iconFile(Project p) {
        if (p == null || p.icon == null || !p.icon.startsWith("file:") || !isUsableId(p.id)) {
            return Optional.empty();
        }
        String name = p.icon.substring("file:".length());
        if (!PathSafety.isSafeFileName(name)) {
            return Optional.empty();
        }
        Path f = paths.project(p.id).resolve(name);
        return FileTrees.isPlainFile(f) ? Optional.of(f) : Optional.empty();
    }

    // ---- creating -------------------------------------------------------------------------------------

    /**
     * Create a project folder with an empty game directory and its {@code project.json}. Nothing is
     * installed. The id is derived from the name (see {@link #idFor}).
     */
    public Project create(NewProjectRequest req) throws LauncherException {
        return create(req, null);
    }

    /**
     * Like {@link #create(NewProjectRequest)}, with the game directory in a new folder inside {@code location}
     * (a folder outside the launcher's data folder that already exists), named after the project. project.json
     * and the icon stay in the data folder. Nothing is left behind when it fails.
     */
    public Project create(NewProjectRequest req, Path location) throws LauncherException {
        Objects.requireNonNull(req, "req");
        Project p = new Project();
        p.name = Names.requireProjectName(req.name());
        p.icon = Names.builtinIcon(req.icon());
        p.template = templateId(req.templateId());
        p.minecraftVersion = Names.requireVersion(req.minecraftVersion(), "project.error.version");
        p.loader = loader(req.loader());
        p.shardlightModules = modules(req.shardlightModules());
        p.shardlightTheme = Names.token(req.shardlightTheme(), "project.error.theme");
        p.preset = new PresetState();
        p.preset.active = req.preset() == null ? Preset.DEFAULT : req.preset();
        p.java = req.java() == null ? new JavaSettings() : Json.copy(req.java(), JavaSettings.class);
        p.createdAt = now();
        String id;
        Path external = null;
        if (location != null) {
            Path parent = ProjectMover.requireLocationParent(paths, location);
            external = parent.resolve(ProjectMover.folderName(parent, Names.slug(p.name)));
            p.gameDirLocation = external.toString();
        }
        Path staging = newStaging();
        try {
            if (external == null) {
                GameDirs.create(staging.resolve(GAME_DIR));
            } else {
                GameDirs.create(external);
            }
            id = commit(staging, Names.slug(p.name), p);
        } catch (IOException e) {
            removeCreated(external);
            throw fileError(e, external == null ? paths.projects() : external);
        } catch (LauncherException | RuntimeException e) {
            removeCreated(external);
            throw e;
        } finally {
            discardStaging(staging);
        }
        LOG.info("Created project " + id + " (" + p.minecraftVersion + ", " + p.loader + ")");
        fireChanged();
        return require(id);
    }

    /**
     * The id a new project with this name would get now: lowercase a-z, 0-9 and dashes, at most 40
     * characters, never a Windows reserved name, with {@code -2}, {@code -3}... when the id is taken
     * (compared case-insensitively).
     */
    public String idFor(String name) {
        synchronized (idLock) {
            Set<String> taken = takenIds();
            return Names.uniqueId(Names.slug(name), taken::contains);
        }
    }

    /** {@link #duplicate(String, String, CloneOptions, ProgressListener, CancellationToken)} without progress */
    public Project duplicate(String id, String newName, CloneOptions opts) throws LauncherException {
        return duplicate(id, newName, opts, ProgressListener.NONE, CancellationToken.NONE);
    }

    /**
     * Copy a project: the whole game directory (mods, config, packs, options...) minus what {@code opts}
     * excludes, as real independent files. The copy gets a new id, creation time and zero play time.
     * World {@code session.lock} files are never copied (the game recreates them).
     */
    public Project duplicate(String id, String newName, CloneOptions opts, ProgressListener progress,
                             CancellationToken cancel) throws LauncherException {
        String name = Names.requireProjectName(newName);
        CloneOptions o = opts == null ? CloneOptions.EVERYTHING : opts;
        Project src = require(id);
        if (isReadOnly(id)) {
            throw newerSchema(src.name);
        }
        Path srcDir = paths.project(id);
        Path srcGame = gameDir(src);
        String newId;
        Path staging = newStaging();
        try {
            FileTrees.Copy ctx = new FileTrees.Copy(progress, "project.stage.copying", FileTrees.sizeOf(srcGame), cancel);
            FileTrees.copyTree(srcGame, staging.resolve(GAME_DIR), o::includes, ctx);
            GameDirs.create(staging.resolve(GAME_DIR));
            copyPlainFile(srcDir.resolve(ICON_FILE), staging.resolve(ICON_FILE));
            copyPlainFile(srcDir.resolve(SERVERS_META), staging.resolve(SERVERS_META));
            Project copy = Json.copy(src, Project.class);
            copy.gameDirLocation = null; // a copy always starts in the data folder
            copy.name = name;
            copy.createdAt = now();
            copy.lastPlayedAt = 0;
            copy.totalPlayMillis = 0;
            copy.favorite = false;
            newId = commit(staging, Names.slug(name), copy);
        } catch (IOException e) {
            throw fileError(e, srcDir);
        } finally {
            discardStaging(staging);
        }
        LOG.info("Duplicated project " + id + " as " + newId);
        fireChanged();
        return require(newId);
    }

    // ---- location ----------------------------------------------------------------------------------------

    /**
     * Move the project's game directory into a new folder named after the project inside {@code newParent}, or
     * back into the data folder when {@code newParent} is null. Copies first, checks every file, switches with one
     * atomic write and deletes the old folder last; any failure or cancel leaves the project untouched. The game
     * must not be running.
     */
    public MoveResult moveGameDir(String id, Path newParent, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        if (!isUsableId(id)) {
            throw notFound(id);
        }
        return ProjectMover.move(this, paths, id, newParent, progress, cancel, moveFault);
    }

    /** where a new project called {@code projectName} would keep its game files inside {@code parent} */
    public Path suggestNewGameDir(Path parent, String projectName) {
        Path p = parent.toAbsolutePath().normalize();
        return p.resolve(ProjectMover.folderName(p, Names.slug(projectName == null || projectName.isBlank() ? "project" : projectName)));
    }

    /** a folder name for a new game directory inside {@code parent}, so the dialogs can show the exact path */
    public Path suggestGameDir(Path parent, String id) {
        return parent.toAbsolutePath().normalize().resolve(ProjectMover.folderName(parent, id));
    }

    /** the data folder location a project's game directory has when it isn't moved */
    public Path defaultGameDir(String id) {
        return projectDir(id).resolve(GAME_DIR);
    }

    // ---- removal --------------------------------------------------------------------------------------

    /** what removing the project would affect */
    public RemovalPlan planRemoval(String id) throws LauncherException {
        Project p = require(id);
        Path dir = paths.project(id);
        Path game = gameDir(p);
        int worlds = 0;
        for (Path w : FileTrees.children(game.resolve(GameDirs.SAVES))) {
            if (FileTrees.isPlainDirectory(w)) {
                worlds++;
            }
        }
        int screenshots = 0;
        for (Path s : FileTrees.children(game.resolve(GameDirs.SCREENSHOTS))) {
            if (FileTrees.isPlainFile(s)) {
                screenshots++;
            }
        }
        int mods = 0;
        for (Path m : FileTrees.children(game.resolve(GameDirs.MODS))) {
            String n = m.getFileName().toString().toLowerCase(Locale.ROOT);
            if (FileTrees.isPlainFile(m) && (n.endsWith(".jar") || n.endsWith(".jar" + GameDirs.DISABLED_SUFFIX))) {
                mods++;
            }
        }
        int packs = 0;
        for (Path r : FileTrees.children(game.resolve(GameDirs.RESOURCE_PACKS))) {
            if (FileTrees.isPlainFile(r) || FileTrees.isPlainDirectory(r)) {
                packs++;
            }
        }
        boolean hasConfig = !FileTrees.children(game.resolve(GameDirs.CONFIG)).isEmpty();
        long total = FileTrees.sizeOf(dir) + (p.gameDirLocation == null ? 0 : FileTrees.sizeOf(game));
        return new RemovalPlan(id, p.name, worlds, screenshots, mods, packs, total, hasConfig);
    }

    /**
     * Move the project to {@code trash/<id>-<yyyyMMdd-HHmmss>/} (one rename; reversible with
     * {@link #restore}). When a program still holds files of the project (Windows), this fails with
     * {@link ErrorCode#IO} and the project stays exactly where it was.
     */
    public ArchivedProject archive(String id) throws LauncherException {
        if (!isUsableId(id)) {
            throw notFound(id);
        }
        ArchivedProject result;
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            Project p = require(id);
            Path src = paths.project(id);
            long size = FileTrees.sizeOf(src);
            synchronized (trashLock) {
                Path trash = paths.trash();
                try {
                    Files.createDirectories(trash);
                } catch (IOException e) {
                    throw LauncherException.io(e, trash.toString());
                }
                String trashId = uniqueChild(trash, id + "-" + stamp(now()));
                try {
                    mover.move(src, trash.resolve(trashId));
                } catch (IOException e) {
                    throw fileError(e, src);
                }
                result = new ArchivedProject(trashId, id, p.name, now(), size);
                TrashIndex idx = readIndex();
                idx.items.removeIf(a -> a.trashId().equalsIgnoreCase(trashId));
                idx.items.add(result);
                writeIndexQuietly(idx);
            }
            readOnlyIds.remove(key(id));
        } finally {
            lock.unlock();
        }
        LOG.info("Moved project " + id + " to the trash as " + result.trashId());
        fireChanged();
        return result;
    }

    /** projects in the trash, most recently archived first */
    public List<ArchivedProject> archived() throws LauncherException {
        synchronized (trashLock) {
            List<ArchivedProject> out = new ArrayList<>(reconcileTrash().items);
            out.sort(Comparator.comparingLong(ArchivedProject::archivedAt).reversed());
            return out;
        }
    }

    /**
     * Move an archived project back into {@code projects/}. It keeps its old id unless that is taken now, in
     * which case it gets a new one.
     */
    public Project restore(String trashId) throws LauncherException {
        if (!isUsableId(trashId) || trashId.equalsIgnoreCase(TRASH_INDEX)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.error.trashNotFound", String.valueOf(trashId));
        }
        String newId;
        ArchivedProject entry;
        synchronized (trashLock) {
            TrashIndex idx = reconcileTrash();
            entry = idx.items.stream().filter(a -> a.trashId().equalsIgnoreCase(trashId)).findFirst()
                .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "project.error.trashNotFound", trashId));
            Path src = paths.trash().resolve(entry.trashId());
            if (!FileTrees.isPlainDirectory(src)) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "project.error.trashNotFound", trashId);
            }
            synchronized (idLock) {
                Set<String> taken = takenIds();
                String base = isUsableId(entry.projectId()) ? entry.projectId() : Names.slug(entry.name());
                newId = !taken.contains(key(base)) && !Names.isWindowsReserved(base)
                    ? base : Names.uniqueId(Names.slug(base), taken::contains);
                try {
                    Files.createDirectories(paths.projects());
                    mover.move(src, paths.project(newId));
                } catch (IOException e) {
                    throw fileError(e, src);
                }
            }
            idx.items.removeIf(a -> a.trashId().equalsIgnoreCase(entry.trashId()));
            writeIndexQuietly(idx);
        }
        if (!newId.equals(entry.projectId())) {
            ReentrantLock lock = lockFor(newId);
            lock.lock();
            try {
                Optional<Project> p = load(newId);
                if (p.isPresent() && !isReadOnly(newId)) {
                    writeProject(newId, p.get());
                }
            } finally {
                lock.unlock();
            }
        }
        LOG.info("Restored project " + entry.trashId() + " as " + newId);
        fireChanged();
        return require(newId);
    }

    /**
     * Delete an archived project for good. Only ever deletes inside {@code trash/}; links inside are
     * removed as links and never followed.
     */
    public void deletePermanently(String trashId) throws LauncherException {
        if (!isUsableId(trashId) || trashId.equalsIgnoreCase(TRASH_INDEX)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(trashId));
        }
        synchronized (trashLock) {
            Path trash = paths.trash().toAbsolutePath().normalize();
            Path target = PathSafety.resolveInside(trash, trashId);
            if (!trash.equals(target.getParent())) {
                throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", trashId);
            }
            TrashIndex idx = readIndex();
            boolean listed = idx.items.removeIf(a -> a.trashId().equalsIgnoreCase(trashId));
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (!exists && !listed) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "project.error.trashNotFound", trashId);
            }
            if (exists) {
                if (FileTrees.isPlainFile(target)) {
                    throw new LauncherException(ErrorCode.NOT_FOUND, "project.error.trashNotFound", trashId);
                }
                Path external = externalGameDir(target);
                FileTrees.deleteTree(trash, target);
                if (external != null) {
                    ProjectMover.deleteExternal(paths, external);
                }
            }
            writeIndexQuietly(idx);
        }
        LOG.info("Deleted archived project " + trashId);
        fireChanged();
    }

    /** the folder outside the data folder that holds an archived project's game files, which deleting it for good also deletes */
    public Optional<Path> archivedGameDir(String trashId) {
        if (!isUsableId(trashId) || trashId.equalsIgnoreCase(TRASH_INDEX)) {
            return Optional.empty();
        }
        return Optional.ofNullable(externalGameDir(paths.trash().resolve(trashId)));
    }

    /** the game directory an archived project keeps outside the data folder, or null */
    private static Path externalGameDir(Path archivedDir) {
        try {
            Path file = archivedDir.resolve(PROJECT_FILE);
            if (!FileTrees.isPlainFile(file)) {
                return null;
            }
            JsonObject o = parseObject(Files.readString(file, StandardCharsets.UTF_8));
            Project p = o == null ? null : fromJson(o);
            return p == null || p.gameDirLocation == null || p.gameDirLocation.isBlank() ? null : Path.of(p.gameDirLocation);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- export and import ----------------------------------------------------------------------------

    /** {@link #exportProject(String, Path, ExportOptions, ProgressListener, CancellationToken)} without progress */
    public Path exportProject(String id, Path zipFile, ExportOptions opts) throws LauncherException {
        return exportProject(id, zipFile, opts, ProgressListener.NONE, CancellationToken.NONE);
    }

    /**
     * Write a {@code .shardlight} zip: {@code shardlight-project.json} ({@code format}, {@code exportedAt},
     * {@code launcherVersion}), {@code project.json} (Java path and install marker cleared, content list
     * kept), {@code icon.png}, {@code minecraft/options.txt}, {@code minecraft/servers.dat} and the selected
     * {@code minecraft/<folder>/} trees. The file appears only when complete. Returns the written file.
     */
    public Path exportProject(String id, Path zipFile, ExportOptions opts, ProgressListener progress,
                              CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(zipFile, "zipFile");
        ExportOptions o = opts == null ? ExportOptions.EVERYTHING : opts;
        Project p = require(id);
        Path out = zipFile.toAbsolutePath().normalize();
        Path projectDir = paths.project(id).toAbsolutePath().normalize();
        if (out.getParent() == null || out.startsWith(projectDir) || out.getFileName().toString().isBlank()) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.export.badTarget", out.toString());
        }
        Path part = out.resolveSibling(out.getFileName() + ".part");
        Project copy = Json.copy(p, Project.class);
        copy.java.javaPath = null;
        copy.installedVersionId = null;
        copy.gameDirLocation = null;
        copy.schemaVersion = Project.SCHEMA_VERSION;
        Path game = gameDir(p);
        List<String> folders = o.folders();
        long total = 0;
        for (String f : folders) {
            total += FileTrees.sizeOf(game.resolve(f));
        }
        try {
            Files.createDirectories(out.getParent());
            Files.deleteIfExists(part);
            try (ZipBuilder zb = new ZipBuilder(part, progress, "project.stage.exporting", total, cancel)) {
                JsonObject manifest = new JsonObject();
                manifest.addProperty("format", EXPORT_FORMAT);
                manifest.addProperty("exportedAt", Instant.ofEpochMilli(now()).toString());
                manifest.addProperty("launcherVersion", BuildInfo.VERSION);
                zb.addBytes(EXPORT_MANIFEST, (Json.write(manifest) + "\n").getBytes(StandardCharsets.UTF_8));
                zb.addBytes(PROJECT_FILE, (Json.write(copy) + "\n").getBytes(StandardCharsets.UTF_8));
                Optional<Path> icon = iconFile(p);
                if (icon.isPresent()) {
                    zb.addFile(ICON_FILE, icon.get());
                }
                zb.addDirectory(GAME_DIR + "/");
                for (String f : List.of(GameDirs.OPTIONS, GameDirs.SERVERS)) {
                    if (FileTrees.isPlainFile(game.resolve(f))) {
                        zb.addFile(GAME_DIR + "/" + f, game.resolve(f));
                    }
                }
                for (String folder : folders) {
                    zb.addTree(GAME_DIR + "/" + folder, game.resolve(folder), rel -> !FileTrees.isSessionLock(rel));
                }
                zb.finish();
            }
            AtomicFiles.moveReplacing(part, out);
        } catch (IOException e) {
            throw fileError(e, out);
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException e) {
                LOG.fine("Couldn't remove " + part + ": " + e.getMessage());
            }
        }
        LOG.info("Exported project " + id + " to " + out.getFileName());
        return out;
    }

    /** {@link #importProject(Path, String, ProgressListener, CancellationToken)} without progress */
    public Project importProject(Path zipFile, String nameOverride) throws LauncherException {
        return importProject(zipFile, nameOverride, ProgressListener.NONE, CancellationToken.NONE);
    }

    /**
     * Import a {@code .shardlight} file as a new project with a fresh id. The archive is validated with
     * {@link SafeZip} and must contain only the export layout; anything else is refused before a single
     * file is written. {@code nameOverride} (optional) replaces the exported name.
     */
    public Project importProject(Path zipFile, String nameOverride, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        Objects.requireNonNull(zipFile, "zipFile");
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        if (!Files.isRegularFile(zipFile)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.import.notFound", zipFile.toString());
        }
        String fileName = zipFile.getFileName().toString();
        pl.stage("project.stage.checking");
        List<SafeZip.Entry> entries = SafeZip.list(zipFile, IMPORT_LIMITS);
        boolean hasIcon = false;
        long declared = 0;
        for (SafeZip.Entry e : entries) {
            String n = e.name().replace('\\', '/');
            if (!isExportLayout(n, e.directory())) {
                throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "project.import.unexpectedEntry", fileName, e.name());
            }
            hasIcon |= n.equals(ICON_FILE);
            declared += Math.max(0, e.size());
        }
        JsonObject manifest = zipJson(zipFile, EXPORT_MANIFEST, 64 * 1024);
        if (manifest == null) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "project.import.notExport", fileName);
        }
        int format;
        try {
            format = manifest.get("format").getAsInt();
        } catch (RuntimeException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "project.import.notExport", fileName);
        }
        if (format > EXPORT_FORMAT) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "project.import.newerFormat", fileName);
        }
        if (format != EXPORT_FORMAT) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "project.import.notExport", fileName);
        }
        JsonObject pj = zipJson(zipFile, PROJECT_FILE, 8L * 1024 * 1024);
        if (pj == null) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "project.import.badProject", fileName);
        }
        if (isNewer(pj)) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "project.import.newerFormat", fileName);
        }
        Project p;
        try {
            MIGRATOR.migrate(pj);
            p = Json.GSON.fromJson(pj, Project.class);
        } catch (RuntimeException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "project.import.badProject", fileName);
        }
        if (p == null) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "project.import.badProject", fileName);
        }
        sanitizeImported(p, nameOverride, fileName);
        requireFreeSpace(paths.projects(), declared);
        String id;
        Path staging = newStaging();
        try {
            pl.stage("project.stage.extracting");
            SafeZip.extract(zipFile, GAME_DIR + "/", staging.resolve(GAME_DIR), IMPORT_LIMITS, null, ct);
            GameDirs.create(staging.resolve(GAME_DIR));
            p.icon = importIcon(zipFile, hasIcon, staging, p.icon);
            id = commit(staging, Names.slug(p.name), p);
        } catch (IOException e) {
            throw fileError(e, paths.projects());
        } finally {
            discardStaging(staging);
        }
        LOG.info("Imported project " + id + " from " + fileName);
        fireChanged();
        return require(id);
    }

    // ---- listeners ------------------------------------------------------------------------------------

    /** called (on the changing thread) after any project is created, saved, moved or deleted */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Project listener failed", e);
            }
        }
    }

    // ---- internals: loading and writing ---------------------------------------------------------------

    /** the per-project lock (ids compared case-insensitively) */
    ReentrantLock lockFor(String id) {
        return locks.computeIfAbsent(key(id), k -> new ReentrantLock());
    }

    long now() {
        return clock.getAsLong();
    }

    private Optional<Project> load(String id) throws LauncherException {
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            Path dir = paths.project(id);
            Path file = dir.resolve(PROJECT_FILE);
            if (!FileTrees.isPlainFile(file)) {
                if (FileTrees.isPlainDirectory(dir) && FileTrees.isPlainFile(dir.resolve(PROJECT_BACKUP))) {
                    return Optional.of(recover(id, dir, null));
                }
                return Optional.empty();
            }
            String text = readText(file);
            JsonObject o = parseObject(text);
            if (o == null) {
                return Optional.of(recover(id, dir, file));
            }
            if (isNewer(o)) {
                readOnlyIds.add(key(id));
                Project p = fromJson(o);
                if (p == null) {
                    addProblem(I18n.tr("project.problem.newerUnreadable", id));
                    return Optional.empty();
                }
                p.id = id;
                fillDefaults(p);
                addProblem(I18n.tr("project.problem.newerSchema", p.name));
                return Optional.of(p);
            }
            readOnlyIds.remove(key(id));
            boolean migrated;
            try {
                migrated = MIGRATOR.migrate(o);
            } catch (RuntimeException e) {
                return Optional.of(recover(id, dir, file));
            }
            Project p = fromJson(o);
            if (p == null) {
                return Optional.of(recover(id, dir, file));
            }
            p.id = id;
            fillDefaults(p);
            if (migrated) {
                Path pre = dir.resolve(PRE_MIGRATION);
                if (!Files.exists(pre)) {
                    AtomicFiles.writeString(pre, text);
                }
                p.schemaVersion = Project.SCHEMA_VERSION;
                AtomicFiles.writeJson(file, p);
            }
            return Optional.of(p);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Set an unreadable project.json aside and rebuild the project from project.json.bak, or from the
     * folder itself when there is no usable backup.
     */
    private Project recover(String id, Path dir, Path corrupt) throws LauncherException {
        String asideName = PROJECT_FILE;
        if (corrupt != null) {
            String name = uniqueChild(dir, PROJECT_FILE + ".corrupt-" + stamp(now()));
            try {
                FileTrees.move(corrupt, dir.resolve(name));
            } catch (IOException e) {
                throw LauncherException.io(e, corrupt.toString());
            }
            asideName = name;
        }
        Project p = readBackupCopy(dir);
        String problemKey = "project.problem.restoredFromBackup";
        if (p == null) {
            p = rebuild(id, dir);
            problemKey = "project.problem.rebuilt";
        }
        p.id = id;
        p.schemaVersion = Project.SCHEMA_VERSION;
        fillDefaults(p);
        AtomicFiles.writeJson(dir.resolve(PROJECT_FILE), p);
        LOG.warning("project.json of " + id + " was unreadable (set aside as " + asideName + "); recovered with " + problemKey);
        addProblem(I18n.tr(problemKey, p.name, asideName));
        return p;
    }

    private Project readBackupCopy(Path dir) {
        Path bak = dir.resolve(PROJECT_BACKUP);
        if (!FileTrees.isPlainFile(bak)) {
            return null;
        }
        try {
            JsonObject o = parseObject(readText(bak));
            if (o == null || isNewer(o)) {
                return null;
            }
            MIGRATOR.migrate(o);
            return fromJson(o);
        } catch (LauncherException | RuntimeException e) {
            return null;
        }
    }

    /** a minimal project for a folder whose settings are lost; the version is guessed from its worlds */
    private Project rebuild(String id, Path dir) {
        Project p = new Project();
        p.id = id;
        p.name = id;
        p.template = ProjectTemplates.CUSTOM;
        try {
            p.createdAt = FileTrees.attrs(dir).creationTime().toMillis();
        } catch (IOException e) {
            p.createdAt = now();
        }
        long newest = Long.MIN_VALUE;
        for (Path w : FileTrees.children(dir.resolve(GAME_DIR).resolve(GameDirs.SAVES))) {
            WorldInfo info = WorldService.readWorld(w);
            if (info.readable() && info.versionName() != null && !info.versionName().isBlank() && info.lastPlayed() > newest) {
                newest = info.lastPlayed();
                p.minecraftVersion = info.versionName();
            }
        }
        return p;
    }

    /** write project.json for an existing folder; caller holds the project lock */
    private Project writeProject(String id, Project project) throws LauncherException {
        Path dir = paths.project(id);
        if (!FileTrees.isPlainDirectory(dir)) {
            throw notFound(id);
        }
        Path file = dir.resolve(PROJECT_FILE);
        String previous = requireWritable(id, file, project.name);
        if (previous != null) {
            AtomicFiles.writeString(dir.resolve(PROJECT_BACKUP), previous);
        }
        Project copy = Json.copy(project, Project.class);
        copy.id = id;
        copy.schemaVersion = Project.SCHEMA_VERSION;
        fillDefaults(copy);
        AtomicFiles.writeJson(file, copy);
        return Json.copy(copy, Project.class);
    }

    /**
     * Fail when the file on disk was written by a newer launcher. Returns its text when it is a readable
     * document worth keeping as the backup copy, otherwise null.
     */
    private String requireWritable(String id, Path file, String name) throws LauncherException {
        if (!FileTrees.isPlainFile(file)) {
            return null;
        }
        String text = readText(file);
        JsonObject o = parseObject(text);
        if (o == null) {
            return null;
        }
        if (isNewer(o)) {
            readOnlyIds.add(key(id));
            throw newerSchema(name == null ? id : name);
        }
        return text;
    }

    private static String readText(Path file) throws LauncherException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
    }

    /** a JSON object, or null when the text isn't one */
    static JsonObject parseObject(String text) {
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) {
                return null;
            }
            JsonObject o = e.getAsJsonObject();
            JsonMigrator.versionOf(o);
            return o;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isNewer(JsonObject o) {
        try {
            return MIGRATOR.isNewer(o);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Project fromJson(JsonObject o) {
        try {
            return Json.GSON.fromJson(o, Project.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** schema 0 -> 1: the first versioned schema; fill in objects older files may lack */
    static void migrate0to1(JsonObject o) {
        addObjectIfMissing(o, "java", Json.GSON.toJsonTree(new JavaSettings()));
        addObjectIfMissing(o, "window", Json.GSON.toJsonTree(new WindowSettings()));
        addObjectIfMissing(o, "preset", Json.GSON.toJsonTree(new PresetState()));
        addObjectIfMissing(o, "loader", Json.GSON.toJsonTree(LoaderSpec.vanilla()));
        if (!o.has("content") || !o.get("content").isJsonArray()) {
            o.add("content", new JsonArray());
        }
        if (!o.has("shardlightModules") || !o.get("shardlightModules").isJsonArray()) {
            o.add("shardlightModules", new JsonArray());
        }
    }

    private static void addObjectIfMissing(JsonObject o, String key, JsonElement value) {
        if (!o.has(key) || !o.get(key).isJsonObject()) {
            o.add(key, value);
        }
    }

    /** replace nulls (from hand edits or older files) with defaults */
    static void fillDefaults(Project p) {
        if (p.name == null || p.name.isBlank()) {
            p.name = p.id == null ? "Project" : p.id;
        }
        if (p.icon == null || p.icon.isBlank()) {
            p.icon = DEFAULT_ICON;
        }
        if (p.template == null || p.template.isBlank()) {
            p.template = ProjectTemplates.CUSTOM;
        }
        if (p.loader == null) {
            p.loader = LoaderSpec.vanilla();
        } else if (p.loader.type == null) {
            p.loader.type = LoaderType.VANILLA;
            p.loader.version = null;
        }
        if (p.java == null) {
            p.java = new JavaSettings();
        }
        if (p.java.extraJvmArgs == null) {
            p.java.extraJvmArgs = "";
        }
        if (p.java.extraGameArgs == null) {
            p.java.extraGameArgs = "";
        }
        if (p.window == null) {
            p.window = new WindowSettings();
        }
        if (p.preset == null) {
            p.preset = new PresetState();
        }
        if (p.preset.active == null) {
            p.preset.active = Preset.DEFAULT;
        }
        if (p.preset.customOverrides == null) {
            p.preset.customOverrides = new java.util.LinkedHashMap<>();
        }
        if (p.shardlightModules == null) {
            p.shardlightModules = new LinkedHashSet<>();
        } else {
            p.shardlightModules.removeIf(Objects::isNull);
        }
        if (p.content == null) {
            p.content = new ArrayList<>();
        } else {
            p.content.removeIf(Objects::isNull);
            for (ContentEntry e : p.content) {
                if (e.requiredBy == null) {
                    e.requiredBy = new ArrayList<>();
                }
            }
        }
        if (p.notes == null) {
            p.notes = "";
        }
    }

    // ---- internals: ids, staging, trash ---------------------------------------------------------------

    /** a folder name the service accepts as a project id: one safe path segment, not hidden */
    static boolean isUsableId(String id) {
        return id != null && !id.isEmpty() && !id.startsWith(".") && PathSafety.isSafeSegment(id) && !Names.hasControl(id);
    }

    static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    /** lowercase names of everything in projects/ */
    private Set<String> takenIds() {
        Set<String> taken = new HashSet<>();
        for (Path p : FileTrees.children(paths.projects())) {
            taken.add(key(p.getFileName().toString()));
        }
        return taken;
    }

    /** remove a game directory this class just made for a project that then failed to be created */
    private static void removeCreated(Path external) {
        if (external == null) {
            return;
        }
        try {
            FileTrees.deleteTree(external.getParent(), external);
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't remove " + external, e);
        }
    }

    private Path newStaging() throws LauncherException {
        Path dir = paths.projects().resolve(STAGING_PREFIX + UUID.randomUUID());
        try {
            Files.createDirectories(paths.projects());
            Files.createDirectory(dir);
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        activeStaging.add(dir);
        return dir;
    }

    private void discardStaging(Path dir) {
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                FileTrees.deleteTree(paths.projects(), dir);
            }
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't clean up " + dir, e);
        } finally {
            activeStaging.remove(dir);
        }
    }

    private void cleanupStaleStaging(Path dir) {
        if (activeStaging.contains(dir)) {
            return;
        }
        try {
            BasicFileAttributes a = FileTrees.attrs(dir);
            if (now() - a.lastModifiedTime().toMillis() > STALE_STAGING_MILLIS) {
                FileTrees.deleteTree(paths.projects(), dir);
            }
        } catch (IOException | LauncherException e) {
            LOG.fine("Couldn't clean up " + dir + ": " + e.getMessage());
        }
    }

    /** give the staged folder an id, write its project.json and rename it into projects/ */
    private String commit(Path staging, String base, Project p) throws LauncherException, IOException {
        synchronized (idLock) {
            Set<String> taken = takenIds();
            String id = Names.uniqueId(base, taken::contains);
            p.id = id;
            p.schemaVersion = Project.SCHEMA_VERSION;
            fillDefaults(p);
            AtomicFiles.writeJson(staging.resolve(PROJECT_FILE), p);
            mover.move(staging, paths.project(id));
            return id;
        }
    }

    private static void copyPlainFile(Path from, Path to) throws IOException {
        if (FileTrees.isPlainFile(from)) {
            Files.copy(from, to);
        }
    }

    static String stamp(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(STAMP);
    }

    /** the stamp at the end of {@code name-yyyyMMdd-HHmmss[-n]}, as epoch millis, or -1 */
    static long parseStampSuffix(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-(\\d{8}-\\d{6})(?:-\\d+)?$").matcher(name);
        if (!m.find()) {
            return -1;
        }
        try {
            return LocalDateTime.parse(m.group(1), STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    /** {@code base}, or {@code base-2}, {@code base-3}... whichever doesn't exist in {@code dir} (case-insensitive) */
    static String uniqueChild(Path dir, String base) {
        Set<String> names = new HashSet<>();
        for (Path p : FileTrees.children(dir)) {
            names.add(key(p.getFileName().toString()));
        }
        if (!names.contains(key(base))) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "-" + i;
            if (!names.contains(key(candidate))) {
                return candidate;
            }
        }
    }

    /** trash/index.json */
    private static final class TrashIndex {
        int schemaVersion = 1;
        List<ArchivedProject> items = new ArrayList<>();
    }

    private TrashIndex readIndex() throws LauncherException {
        AtomicFiles.ReadResult<TrashIndex> r = AtomicFiles.readJson(paths.trash().resolve(TRASH_INDEX), TrashIndex.class,
            TrashIndex::new);
        TrashIndex idx = r.value();
        if (idx.items == null) {
            idx.items = new ArrayList<>();
        }
        idx.items.removeIf(a -> a == null || a.trashId() == null);
        return idx;
    }

    private void writeIndexQuietly(TrashIndex idx) {
        try {
            AtomicFiles.writeJson(paths.trash().resolve(TRASH_INDEX), idx);
        } catch (LauncherException e) {
            // the folders are the truth; archived() rebuilds missing entries
            LOG.log(Level.WARNING, "Couldn't update the trash index", e);
        }
    }

    /** the index, minus entries whose folder is gone, plus folders it doesn't list */
    private TrashIndex reconcileTrash() throws LauncherException {
        Path trash = paths.trash();
        TrashIndex idx = readIndex();
        boolean changed = idx.items.removeIf(a -> !isUsableId(a.trashId())
            || !FileTrees.isPlainDirectory(trash.resolve(a.trashId())));
        Set<String> known = new HashSet<>();
        for (ArchivedProject a : idx.items) {
            known.add(key(a.trashId()));
        }
        for (Path d : FileTrees.children(trash)) {
            String name = d.getFileName().toString();
            if (!isUsableId(name) || known.contains(key(name)) || !FileTrees.isPlainDirectory(d)
                || !FileTrees.isPlainFile(d.resolve(PROJECT_FILE))) {
                continue;
            }
            idx.items.add(describeTrashFolder(d));
            changed = true;
        }
        if (changed) {
            writeIndexQuietly(idx);
        }
        return idx;
    }

    private ArchivedProject describeTrashFolder(Path d) {
        String name = d.getFileName().toString();
        long at = parseStampSuffix(name);
        String projectId = at > 0 ? name.replaceFirst("-\\d{8}-\\d{6}(?:-\\d+)?$", "") : name;
        String display = projectId;
        try {
            JsonObject o = parseObject(readText(d.resolve(PROJECT_FILE)));
            if (o != null && o.has("name") && o.get("name").isJsonPrimitive()) {
                display = o.get("name").getAsString();
            }
        } catch (LauncherException e) {
            // keep the folder name
        }
        if (at <= 0) {
            try {
                at = FileTrees.attrs(d).lastModifiedTime().toMillis();
            } catch (IOException e) {
                at = now();
            }
        }
        return new ArchivedProject(name, projectId, display, at, FileTrees.sizeOf(d));
    }

    // ---- internals: validation ------------------------------------------------------------------------

    private static String templateId(String id) throws LauncherException {
        if (id == null || id.isBlank()) {
            return ProjectTemplates.CUSTOM;
        }
        return ProjectTemplates.byId(id).map(ProjectTemplate::id)
            .orElseThrow(() -> Names.invalid("project.error.template", id));
    }

    private static LoaderSpec loader(LoaderSpec l) throws LauncherException {
        if (l == null || l.isVanilla()) {
            return LoaderSpec.vanilla();
        }
        String version = l.version == null || l.version.isBlank() ? null
            : Names.requireVersion(l.version, "project.error.loaderVersion");
        return new LoaderSpec(l.type, version);
    }

    private static Set<String> modules(Set<String> requested) throws LauncherException {
        Set<String> out = new LinkedHashSet<>();
        if (requested != null) {
            for (String m : requested) {
                if (m != null) {
                    out.add(Names.token(m, "project.error.module"));
                }
            }
        }
        return out;
    }

    private void sanitizeImported(Project p, String nameOverride, String fileName) throws LauncherException {
        String exportedName = p.name;
        p.id = null; // a fresh id is chosen on commit; the exported one is never used
        p.gameDirLocation = null; // a file from someone else never decides where files are written
        fillDefaults(p);
        if (nameOverride != null && !nameOverride.isBlank()) {
            p.name = Names.requireProjectName(nameOverride);
        } else {
            try {
                p.name = Names.requireProjectName(exportedName);
            } catch (LauncherException e) {
                p.name = I18n.tr("project.import.defaultName");
            }
        }
        try {
            p.minecraftVersion = Names.requireVersion(p.minecraftVersion, "project.error.version");
            p.loader = loader(p.loader);
        } catch (LauncherException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "project.import.badProject", fileName);
        }
        if (ProjectTemplates.byId(p.template).isEmpty()) {
            p.template = ProjectTemplates.CUSTOM;
        }
        Set<String> modules = new LinkedHashSet<>();
        for (String m : p.shardlightModules) {
            try {
                modules.add(Names.token(m, "project.error.module"));
            } catch (LauncherException e) {
                LOG.fine("Dropping unknown module id from import: " + m);
            }
        }
        p.shardlightModules = modules;
        try {
            p.shardlightTheme = Names.token(p.shardlightTheme, "project.error.theme");
        } catch (LauncherException e) {
            p.shardlightTheme = null;
        }
        p.content.removeIf(e -> e.fileName == null || !PathSafety.isSafeFileName(e.fileName));
        p.java.javaPath = null;
        p.installedVersionId = null;
        p.createdAt = now();
        p.lastPlayedAt = 0;
        p.totalPlayMillis = 0;
        p.favorite = false;
    }

    private static boolean isExportLayout(String n, boolean directory) {
        switch (n) {
            case EXPORT_MANIFEST, PROJECT_FILE, ICON_FILE, GAME_DIR + "/" + GameDirs.OPTIONS, GAME_DIR + "/" + GameDirs.SERVERS -> {
                return !directory;
            }
            case GAME_DIR + "/" -> {
                return directory;
            }
            default -> {
                if (!n.startsWith(GAME_DIR + "/")) {
                    return false;
                }
                String rest = n.substring(GAME_DIR.length() + 1);
                int slash = rest.indexOf('/');
                return slash > 0 && EXPORT_FOLDERS.contains(rest.substring(0, slash));
            }
        }
    }

    private static JsonObject zipJson(Path zip, String entry, long maxBytes) throws LauncherException {
        byte[] data = SafeZip.read(zip, entry, maxBytes);
        return data == null ? null : parseObject(new String(data, StandardCharsets.UTF_8));
    }

    private String importIcon(Path zip, boolean hasIcon, Path staging, String icon) throws LauncherException {
        if (hasIcon) {
            byte[] data;
            try {
                data = SafeZip.read(zip, ICON_FILE, MAX_ICON_BYTES);
            } catch (LauncherException e) {
                data = null;
            }
            if (data != null && ImageMagic.isPngOrJpeg(data)) {
                AtomicFiles.writeBytes(staging.resolve(ICON_FILE), data);
                return FILE_ICON;
            }
        }
        if (icon == null || !icon.startsWith("builtin:")) {
            return DEFAULT_ICON;
        }
        try {
            return Names.builtinIcon(icon);
        } catch (LauncherException e) {
            return DEFAULT_ICON;
        }
    }

    private static byte[] readIcon(Path image) throws LauncherException {
        if (!Files.isRegularFile(image)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.icon.notFound", image.toString());
        }
        byte[] data;
        try (InputStream in = Files.newInputStream(image)) {
            data = in.readNBytes((int) MAX_ICON_BYTES + 1);
        } catch (IOException e) {
            throw LauncherException.io(e, image.toString());
        }
        if (data.length > MAX_ICON_BYTES) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.icon.tooLarge", MAX_ICON_BYTES / (1024 * 1024));
        }
        if (!ImageMagic.isPngOrJpeg(data)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.icon.notImage", image.getFileName().toString());
        }
        return data;
    }

    static void requireFreeSpace(Path dir, long needed) throws LauncherException {
        Path probe = dir.toAbsolutePath();
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return;
        }
        try {
            long usable = Files.getFileStore(probe).getUsableSpace();
            if (needed > usable) {
                throw new LauncherException(ErrorCode.IO, "project.error.diskFull", needed / (1024 * 1024) + 1,
                    usable / (1024 * 1024));
            }
        } catch (IOException e) {
            LOG.fine("Couldn't check free space: " + e.getMessage());
        }
    }

    private void addProblem(String message) {
        problems.add(message);
    }

    static LauncherException notFound(String id) {
        return new LauncherException(ErrorCode.NOT_FOUND, "project.error.notFound", String.valueOf(id));
    }

    static LauncherException newerSchema(String name) {
        return new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "project.error.newerSchema", name);
    }

    /** an IO failure, worded as "in use" when another program holds the file */
    static LauncherException fileError(IOException e, Path where) {
        String file = FileTrees.failedFile(e, where);
        return FileTrees.isLockError(e)
            ? new LauncherException(ErrorCode.IO, e, "project.error.inUse", file)
            : LauncherException.io(e, file);
    }
}
