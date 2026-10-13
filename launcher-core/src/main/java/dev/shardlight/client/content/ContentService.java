package dev.shardlight.client.content;

import dev.shardlight.client.catalog.CatalogDependency;
import dev.shardlight.client.catalog.CatalogFile;
import dev.shardlight.client.catalog.CatalogRegistry;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.DependencyType;
import dev.shardlight.client.catalog.InstallPlan;
import dev.shardlight.client.catalog.PlannedFile;
import dev.shardlight.client.catalog.Severity;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static dev.shardlight.client.content.ContentKind.MOD;
import static dev.shardlight.client.content.ContentKind.RESOURCE_PACK;
import static dev.shardlight.client.content.ContentKind.SHADER_PACK;

/**
 * A project's mods, resource packs and shader packs: what is installed, where it came from, enabling and
 * disabling for the next launch, installs and updates from the catalogs, removal with history and
 * rollback, and the resource pack order in {@code options.txt}.
 *
 * <p>Disabling a mod renames {@code <file>} to {@code <file>.disabled}, so no loader can load it on the next
 * launch. Resource packs are enabled through {@code options.txt}; shader packs through the entry flag (and
 * Iris's {@code config/iris.properties} when the pack is the selected one).
 *
 * <p>Every method blocks (call it off the UI thread) and is safe to call from several threads: operations
 * on one project are serialized, re-read the stored project, change only its content list, save it, and
 * then update the {@code content} list of the {@link Project} object passed in. Files never leave the
 * project folder: removed and replaced files go to {@code projects/<id>/.history/<event>/} (at most
 * {@value #MAX_HISTORY_EVENTS} events and 1 GB of stored files per project, oldest pruned first).
 */
public final class ContentService {
    private static final Logger LOG = Logger.getLogger(ContentService.class.getName());
    /** most history events kept per project */
    public static final int MAX_HISTORY_EVENTS = 100;
    /** most bytes of stored files kept per project (the newest event is always kept) */
    public static final long MAX_HISTORY_BYTES = 1L << 30;
    static final String STAGING = ".staging";
    private static final String DISABLED = GameDirs.DISABLED_SUFFIX;
    private static final long STALE_STAGING_MILLIS = 6 * 3_600_000L;
    private static final List<ContentKind> FOLDER_KINDS = List.of(MOD, RESOURCE_PACK, SHADER_PACK);
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final AppPaths paths;
    private final ProjectAccess projects;
    private final DownloadManager downloads;
    private final ModMetadataReader metadata = new ModMetadataReader();
    private volatile int maxHistoryEvents = MAX_HISTORY_EVENTS;
    private volatile long maxHistoryBytes = MAX_HISTORY_BYTES;

    /**
     * @param paths     launcher folders
     * @param projects  project storage
     * @param downloads the download queue (shared verified-file cache)
     */
    public ContentService(AppPaths paths, ProjectAccess projects, DownloadManager downloads) {
        this.paths = Objects.requireNonNull(paths);
        this.projects = Objects.requireNonNull(projects);
        this.downloads = Objects.requireNonNull(downloads);
    }

    /** the metadata reader this service uses */
    public ModMetadataReader metadataReader() {
        return metadata;
    }

    /** the launcher folders */
    public AppPaths paths() {
        return paths;
    }

    /** tests: smaller history limits */
    void setHistoryLimits(int events, long bytes) {
        maxHistoryEvents = events;
        maxHistoryBytes = bytes;
    }

    // ================================================================== scan and warnings

    /**
     * Reconcile the project's content list with the files in {@code mods/}, {@code resourcepacks/} and
     * {@code shaderpacks/}: unknown files become LOCAL entries (title and mod id from their metadata, SHA-1
     * and SHA-512 computed), entries whose file is gone are marked {@link ContentEntry#missing}, a mod's
     * enabled flag follows its {@code .disabled} suffix and a resource pack's follows {@code options.txt}.
     * Saves the project and returns the entries.
     */
    public List<ContentEntry> scan(Project project) throws LauncherException {
        return locked(project, s -> {
            cleanStaleStaging(s);
            OptionsPacks packs = OptionsPacks.open(s.gameDir);
            boolean dirty = false;
            for (ContentKind kind : FOLDER_KINDS) {
                Path dir = s.gameDir.resolve(kind.folder());
                Map<String, Path> onDisk = listFolder(dir);
                Set<String> claimed = new HashSet<>();
                for (ContentEntry e : s.project.content) {
                    if (e.kind != kind) {
                        continue;
                    }
                    if (!isSafeEntryName(e)) {
                        LOG.warning("Ignoring content entry with an unsafe file name in project " + s.project.id);
                        dirty |= setMissing(e, true);
                        continue;
                    }
                    Path plain = unclaimed(onDisk, claimed, e.fileName);
                    Path disabled = kind == MOD ? unclaimed(onDisk, claimed, e.fileName + DISABLED) : null;
                    Path found;
                    boolean enabledOnDisk = true;
                    if (plain != null && (e.enabled || disabled == null || kind != MOD)) {
                        found = plain;
                    } else if (disabled != null) {
                        found = disabled;
                        enabledOnDisk = false;
                    } else {
                        found = null;
                    }
                    if (found == null) {
                        dirty |= setMissing(e, true);
                        continue;
                    }
                    claimed.add(ContentFiles.lower(found.getFileName().toString()));
                    dirty |= setMissing(e, false);
                    String diskName = found.getFileName().toString();
                    if (!enabledOnDisk) {
                        diskName = diskName.substring(0, diskName.length() - DISABLED.length());
                    }
                    if (!diskName.equals(e.fileName)) {
                        e.fileName = diskName; // same name in another case (Windows)
                        dirty = true;
                    }
                    if (kind == MOD && e.enabled != enabledOnDisk) {
                        e.enabled = enabledOnDisk;
                        dirty = true;
                    }
                    if (kind == RESOURCE_PACK) {
                        boolean selected = packs.isSelected(e.fileName);
                        if (e.enabled != selected) {
                            e.enabled = selected;
                            dirty = true;
                        }
                    }
                    dirty |= refreshFileFacts(e, found);
                }
                for (Map.Entry<String, Path> f : onDisk.entrySet()) {
                    if (claimed.contains(f.getKey()) || !isCandidate(kind, f.getValue())) {
                        continue;
                    }
                    s.project.content.add(newLocalEntry(kind, f.getValue(), packs));
                    dirty = true;
                }
            }
            s.projectDirty |= dirty;
            s.commit();
            return copyList(s.project.content);
        });
    }

    /**
     * Problems found from the enabled files' own metadata: duplicate mod ids, mods for another loader (or
     * mods in a project without a loader), a declared Minecraft range that excludes the project's version,
     * server-only mods, mods declaring they break with another installed mod, and missing files. Call
     * {@link #scan} first to pick up files changed outside the launcher.
     */
    public List<ContentWarning> warnings(Project project) throws LauncherException {
        return locked(project, s -> {
            List<ContentWarning> out = new ArrayList<>();
            Map<String, ModMetadata> meta = new HashMap<>();
            Map<String, List<ContentEntry>> byModId = new LinkedHashMap<>();
            List<ContentEntry> mods = new ArrayList<>();
            for (ContentEntry e : s.project.content) {
                Path p = isSafeEntryName(e) && e.kind != null && e.kind.folder() != null && FOLDER_KINDS.contains(e.kind)
                    ? currentPath(s, e) : null;
                if (e.missing || (p == null && FOLDER_KINDS.contains(e.kind))) {
                    out.add(new ContentWarning(ContentWarning.Type.MISSING_FILE, Severity.WARNING, List.of(e.id),
                        "content.warning.missingFile", List.of(title(e), String.valueOf(e.fileName))));
                    continue;
                }
                if (e.kind != MOD || !e.enabled || p == null) {
                    continue;
                }
                mods.add(e);
                ModMetadata md = metadata.read(p).orElse(null);
                String modId = e.modId;
                if (md != null) {
                    meta.put(e.id, md);
                    if (md.id() != null) {
                        modId = md.id();
                    }
                }
                if (modId != null && !modId.isBlank()) {
                    byModId.computeIfAbsent(ContentFiles.lower(modId), k -> new ArrayList<>()).add(e);
                }
            }
            for (Map.Entry<String, List<ContentEntry>> d : byModId.entrySet()) {
                if (d.getValue().size() > 1) {
                    out.add(new ContentWarning(ContentWarning.Type.DUPLICATE_MOD_ID, Severity.ERROR,
                        d.getValue().stream().map(x -> x.id).toList(), "content.warning.duplicateModId",
                        List.of(d.getKey(), d.getValue().stream().map(ContentService::title).collect(Collectors.joining(", ")))));
                }
            }
            LoaderType projectLoader = s.project.loader == null || s.project.loader.type == null ? LoaderType.VANILLA
                : s.project.loader.type;
            String mc = s.project.minecraftVersion;
            if (projectLoader == LoaderType.VANILLA && !mods.isEmpty()) {
                out.add(new ContentWarning(ContentWarning.Type.MODS_WITHOUT_LOADER, Severity.WARNING,
                    mods.stream().map(x -> x.id).toList(), "content.warning.modsWithoutLoader",
                    List.of(String.valueOf(mods.size()))));
            }
            for (ContentEntry e : mods) {
                ModMetadata md = meta.get(e.id);
                if (md == null) {
                    continue;
                }
                if (projectLoader != LoaderType.VANILLA && !md.loaders().isEmpty()
                    && md.loaders().stream().noneMatch(l -> loads(projectLoader, l, mc))) {
                    out.add(new ContentWarning(ContentWarning.Type.WRONG_LOADER, Severity.ERROR, List.of(e.id),
                        "content.warning.wrongLoader", List.of(title(e),
                            md.loaders().stream().map(LoaderType::displayName).collect(Collectors.joining(" / ")),
                            projectLoader.displayName())));
                }
                String pred = md.minecraftPredicate();
                if (pred != null && mc != null && VersionPredicates.test(pred, mc).filter(ok -> !ok).isPresent()) {
                    out.add(new ContentWarning(ContentWarning.Type.MINECRAFT_VERSION, Severity.WARNING, List.of(e.id),
                        "content.warning.minecraftVersion", List.of(title(e), pred, mc)));
                }
                if (md.isServerOnly()) {
                    out.add(new ContentWarning(ContentWarning.Type.SERVER_ONLY, Severity.WARNING, List.of(e.id),
                        "content.warning.serverOnly", List.of(title(e))));
                }
                for (Map.Entry<String, String> b : md.breaks().entrySet()) {
                    List<ContentEntry> others = byModId.getOrDefault(ContentFiles.lower(b.getKey()), List.of());
                    for (ContentEntry other : others) {
                        if (other == e) {
                            continue;
                        }
                        ModMetadata om = meta.get(other.id);
                        String pred2 = b.getValue();
                        boolean hit = pred2 == null || pred2.isBlank() || pred2.trim().equals("*")
                            || (om != null && VersionPredicates.test(pred2, om.version()).orElse(false));
                        if (hit) {
                            out.add(new ContentWarning(ContentWarning.Type.BREAKS, Severity.ERROR, List.of(e.id, other.id),
                                "content.warning.breaks", List.of(title(e), title(other))));
                        }
                    }
                }
            }
            return out;
        });
    }

    private static boolean loads(LoaderType project, LoaderType mod, String mc) {
        if (project.acceptsModsFor(mod)) {
            return true;
        }
        // NeoForge for 1.20.1 is still compatible with Forge mods
        return project == LoaderType.NEOFORGE && mod == LoaderType.FORGE && "1.20.1".equals(mc);
    }

    // ================================================================== installing

    /**
     * Copy a file the user picked into the project. The name is sanitized; a name already used by a
     * different file is refused ({@code CONFLICT}) unless the file replaces the entry of the same
     * {@code source} + {@code projectId} (the old file then goes to history and the entry keeps its id). An
     * identical file already present is adopted instead of copied. Records the entry and a history event.
     *
     * @param kind      MOD (a .jar), RESOURCE_PACK or SHADER_PACK (a .zip)
     * @param file      the file to copy
     * @param source    where it came from (LOCAL for drag and drop, SHARDLIGHT for the content library...)
     * @param projectId catalog/module id, may be null
     * @param versionId catalog version id, may be null
     */
    public ContentEntry installLocalFile(Project project, ContentKind kind, Path file, ContentSource source, String projectId,
                                         String versionId) throws LauncherException {
        requireFolderKind(kind);
        if (file == null || !Files.isRegularFile(file)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.sourceMissing", String.valueOf(file));
        }
        String original = file.getFileName().toString();
        if (ContentFiles.lower(original).endsWith(DISABLED)) {
            original = original.substring(0, original.length() - DISABLED.length());
        }
        String name = PathSafety.sanitizeFileName(original);
        String expected = kind == MOD ? ".jar" : ".zip";
        if (!ContentFiles.lower(name).endsWith(expected)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "content.error.wrongFileType", name, expected);
        }
        try (ZipFile ignored = new ZipFile(file.toFile())) {
            // a readable archive
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "content.error.notAnArchive", name);
        }
        ContentSource src = source == null ? ContentSource.LOCAL : source;
        Optional<ModMetadata> md = metadata.read(file);
        String[] hashes = ContentFiles.sha1AndSha512(file);
        return locked(project, s -> {
            Path folder = folder(s, kind);
            ContentEntry same = projectId == null ? null : findSameProject(s, src, projectId, kind);
            Path clash = existingVariant(folder, name, kind);
            ContentEntry owner = clash == null ? null : ownerOf(s, kind, clash);
            if (clash != null && !(same != null && owner == same)) {
                if (Files.isRegularFile(clash) && hashes[0].equalsIgnoreCase(ContentFiles.sha1AndSha512(clash)[0])) {
                    // the very same file is already there: record it instead of copying
                    ContentEntry before = owner == null ? null : copy(owner);
                    ContentEntry e = owner != null ? owner : newEntry(kind, clash);
                    applyLocalFacts(e, src, projectId, versionId, md, hashes, clash);
                    if (owner == null) {
                        s.project.content.add(e);
                        if (kind == RESOURCE_PACK) {
                            OptionsPacks op = OptionsPacks.open(s.gameDir);
                            e.enabled = op.isSelected(e.fileName);
                        }
                    }
                    s.record(owner == null ? HistoryEvent.INSTALL : HistoryEvent.UPDATE, before, e, newId(), null);
                    s.projectDirty = true;
                    s.commit();
                    return copy(e);
                }
                throw nameClash(name, owner);
            }
            String eventId = newId();
            Path tmp = folder.resolve("." + UUID.randomUUID() + ".tmp");
            try {
                Files.copy(file, tmp);
            } catch (IOException e) {
                ContentFiles.deleteTree(tmp);
                throw LauncherException.io(e, file.toString());
            }
            String stored = null;
            Path oldPath = null;
            try {
                if (same != null) {
                    oldPath = currentPath(s, same);
                    if (oldPath != null) {
                        stored = moveToHistory(s, eventId, oldPath);
                    }
                }
                boolean enabled = same == null || same.enabled || kind != MOD;
                Path target = entryPath(s, kind, enabled || kind != MOD ? name : name + DISABLED);
                try {
                    ContentFiles.move(tmp, target);
                } catch (LauncherException e) {
                    if (stored != null) {
                        restoreQuietly(s, stored, oldPath);
                    }
                    throw e;
                }
            } finally {
                ContentFiles.deleteTree(tmp);
            }
            ContentEntry before = same == null ? null : copy(same);
            ContentEntry e = same != null ? same : new ContentEntry();
            if (same == null) {
                e.id = newId();
                e.kind = kind;
                e.enabled = true;
                e.installedAt = Instant.now().toString();
                s.project.content.add(e);
            }
            String oldName = same == null ? null : same.fileName;
            e.fileName = name;
            applyLocalFacts(e, src, projectId, versionId, md, hashes, entryPath(s, kind, name));
            e.missing = false;
            if (kind == RESOURCE_PACK) {
                OptionsPacks op = OptionsPacks.open(s.gameDir);
                if (oldName != null) {
                    op.replace(oldName, name, e.enabled);
                } else {
                    op.select(name);
                }
                op.save();
            } else if (kind == SHADER_PACK && oldName != null) {
                ShaderSettings.renameIfActive(s.gameDir, oldName, name);
            }
            s.record(same == null ? HistoryEvent.INSTALL : HistoryEvent.UPDATE, before, e, eventId, stored);
            s.projectDirty = true;
            s.commit();
            return copy(e);
        });
    }

    private void applyLocalFacts(ContentEntry e, ContentSource src, String projectId, String versionId,
                                 Optional<ModMetadata> md, String[] hashes, Path file) {
        e.source = src;
        if (projectId != null) {
            e.projectId = projectId;
        }
        if (versionId != null) {
            e.versionId = versionId;
        }
        e.sha1 = hashes[0];
        e.sha512 = hashes[1];
        e.size = ContentFiles.sizeOf(file);
        e.modifiedAt = ContentFiles.modifiedMillis(file);
        e.title = md.map(ModMetadata::name).filter(n -> !n.isBlank()).orElse(stripExtension(e.fileName));
        if (e.kind == MOD) {
            e.modId = md.map(ModMetadata::id).orElse(e.modId);
        }
        e.versionNumber = md.map(ModMetadata::version).orElse(e.versionNumber);
    }

    /**
     * Download and install every file of a plan. Files are downloaded (size and hash verified, through the
     * shared cache) into a staging folder first, so nothing half-downloaded ever appears in the project;
     * then they are moved into place. If any file fails, the files already placed for this plan are taken
     * out again and replaced files restored. Records the entries (source, ids, version, title, hashes,
     * dependency flag, requiredBy) and one history event per file.
     *
     * @return the installed entries, plan order
     */
    public List<ContentEntry> install(Project project, InstallPlan plan, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        if (plan == null || plan.files().isEmpty()) {
            return List.of();
        }
        if (!plan.canInstall()) {
            throw new LauncherException(ErrorCode.CONFLICT, "content.error.planBlocked");
        }
        Set<String> names = new HashSet<>();
        for (PlannedFile pf : plan.files()) {
            requireFolderKind(pf.kind());
            requireDownloadable(pf.title(), pf.file(), pf.pageUrl() == null ? null : pf.pageUrl().toString());
            if (!names.add(pf.kind() + "/" + ContentFiles.lower(PathSafety.sanitizeFileName(pf.file().fileName())))) {
                throw new LauncherException(ErrorCode.CONFLICT, "content.error.duplicateInPlan", pf.file().fileName());
            }
        }
        requireSafeProject(project);
        // fail fast on name clashes we can already see
        locked(project, s -> {
            for (PlannedFile pf : plan.files()) {
                checkClash(s, pf.kind(), PathSafety.sanitizeFileName(pf.file().fileName()), pf.file().sha1(),
                    CatalogRegistry.sourceOf(pf.version().source()), pf.version().projectId());
            }
            return null;
        });
        Path staging = projects.projectDir(project.id).resolve(STAGING).resolve(UUID.randomUUID().toString());
        try {
            List<Staged> staged = download(plan.files(), staging,
                plan.files().get(0).title(), pl, ct);
            ct.check();
            pl.stage("content.stage.installing");
            return locked(project, s -> place(s, staged));
        } finally {
            ContentFiles.deleteTree(staging);
            ContentFiles.deleteIfEmpty(staging.getParent());
        }
    }

    /** one downloaded, verified file waiting in the staging folder */
    private record Staged(PlannedFile pf, String name, Path path, String sha1, String sha512, long size, ModMetadata md) {
    }

    private List<Staged> download(List<PlannedFile> files, Path staging, String groupTitle, ProgressListener pl,
                                  CancellationToken ct) throws LauncherException {
        List<DownloadRequest> requests = new ArrayList<>();
        List<Path> targets = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            PlannedFile pf = files.get(i);
            CatalogFile f = pf.file();
            Path target = staging.resolve(String.valueOf(i)).resolve(PathSafety.sanitizeFileName(f.fileName()));
            targets.add(target);
            requests.add(request(f, target, pf.title()));
        }
        pl.stage("content.stage.downloading");
        downloads.downloadAll(I18n.tr("content.download.group", groupTitle), requests, "content.stage.downloading", pl, ct);
        List<Staged> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            ct.check();
            PlannedFile pf = files.get(i);
            Path p = targets.get(i);
            String[] h = ContentFiles.sha1AndSha512(p);
            if ((pf.file().sha1() != null && !pf.file().sha1().isBlank() && !h[0].equalsIgnoreCase(pf.file().sha1().trim()))
                || (pf.file().sha512() != null && !pf.file().sha512().isBlank() && !h[1].equalsIgnoreCase(pf.file().sha512().trim()))) {
                throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", pf.title());
            }
            out.add(new Staged(pf, p.getFileName().toString(), p, h[0], h[1], ContentFiles.sizeOf(p),
                metadata.read(p).orElse(null)));
        }
        return out;
    }

    private static DownloadRequest request(CatalogFile f, Path target, String label) {
        HashAlgo algo = null;
        String hash = null;
        if (HashAlgo.SHA1.isValidHex(trim(f.sha1()))) {
            algo = HashAlgo.SHA1;
            hash = trim(f.sha1());
        } else if (HashAlgo.SHA512.isValidHex(trim(f.sha512()))) {
            algo = HashAlgo.SHA512;
            hash = trim(f.sha512());
        }
        if (algo == null) {
            LOG.warning("The catalog publishes no hash for " + f.fileName() + "; only its size is checked");
        }
        return new DownloadRequest(f.url(), List.of(), target, algo, hash, f.size() > 0 ? f.size() : -1, label,
            algo == HashAlgo.SHA1, false);
    }

    private List<ContentEntry> place(Session s, List<Staged> staged) throws LauncherException {
        List<Runnable> undo = new ArrayList<>();
        List<ContentEntry> result = new ArrayList<>();
        List<Object[]> events = new ArrayList<>(); // action, before, after, id, stored
        List<String[]> renamedPacks = new ArrayList<>(); // old name, new name, "true" when selected
        List<String> newPacks = new ArrayList<>();
        Map<String, String> renamedShaders = new LinkedHashMap<>();
        try {
            for (Staged st : staged) {
                PlannedFile pf = st.pf();
                ContentKind kind = pf.kind();
                ContentSource src = CatalogRegistry.sourceOf(pf.version().source());
                Path folder = folder(s, kind);
                ContentEntry same = findSameProject(s, src, pf.version().projectId(), kind);
                Path clash = existingVariant(folder, st.name(), kind);
                ContentEntry owner = clash == null ? null : ownerOf(s, kind, clash);
                String eventId = newId();
                if (clash != null && !(same != null && owner == same)) {
                    if (Files.isRegularFile(clash) && st.sha1().equalsIgnoreCase(ContentFiles.sha1AndSha512(clash)[0])) {
                        // identical file already present (for example dropped in by hand): adopt it
                        ContentEntry before = owner == null ? null : copy(owner);
                        ContentEntry e = owner != null ? owner : newEntry(kind, clash);
                        fillCatalogFacts(e, st, owner == null);
                        if (owner == null) {
                            s.project.content.add(e);
                            if (kind == RESOURCE_PACK) {
                                newPacks.add(e.fileName);
                            }
                        }
                        result.add(e);
                        events.add(new Object[] {owner == null ? HistoryEvent.INSTALL : HistoryEvent.UPDATE, before, e, eventId, null});
                        continue;
                    }
                    throw nameClash(st.name(), owner);
                }
                String stored = null;
                Path oldPath = null;
                if (same != null) {
                    oldPath = currentPath(s, same);
                    if (oldPath != null) {
                        stored = moveToHistory(s, eventId, oldPath);
                        String storedFinal = stored;
                        Path oldFinal = oldPath;
                        undo.add(() -> restoreQuietly(s, storedFinal, oldFinal));
                    }
                }
                boolean enabled = same == null || same.enabled || kind != MOD;
                Path target = entryPath(s, kind, enabled || kind != MOD ? st.name() : st.name() + DISABLED);
                ContentFiles.move(st.path(), target);
                undo.add(() -> ContentFiles.deleteTree(target));
                ContentEntry before = same == null ? null : copy(same);
                ContentEntry e;
                if (same != null) {
                    e = same;
                    String oldName = same.fileName;
                    e.fileName = st.name();
                    if (kind == RESOURCE_PACK) {
                        renamedPacks.add(new String[] {oldName, st.name(), String.valueOf(same.enabled)});
                    } else if (kind == SHADER_PACK) {
                        renamedShaders.put(oldName, st.name());
                    }
                } else {
                    e = new ContentEntry();
                    e.id = newId();
                    e.kind = kind;
                    e.enabled = true;
                    e.fileName = st.name();
                    e.installedAt = Instant.now().toString();
                    s.project.content.add(e);
                    if (kind == RESOURCE_PACK) {
                        newPacks.add(st.name());
                    }
                }
                fillCatalogFacts(e, st, same == null);
                result.add(e);
                events.add(new Object[] {same == null ? HistoryEvent.INSTALL : HistoryEvent.UPDATE, before, e, eventId, stored});
            }
            linkRequiredBy(s, staged, result);
            if (!newPacks.isEmpty() || !renamedPacks.isEmpty()) {
                OptionsPacks op = OptionsPacks.open(s.gameDir);
                for (String[] r : renamedPacks) {
                    op.replace(r[0], r[1], Boolean.parseBoolean(r[2]));
                }
                newPacks.forEach(op::select);
                op.save();
            }
            for (Map.Entry<String, String> r : renamedShaders.entrySet()) {
                ShaderSettings.renameIfActive(s.gameDir, r.getKey(), r.getValue());
            }
        } catch (LauncherException | RuntimeException e) {
            for (int i = undo.size() - 1; i >= 0; i--) {
                try {
                    undo.get(i).run();
                } catch (RuntimeException ex) {
                    LOG.warning("Couldn't undo part of a failed install: " + ex);
                }
            }
            throw e;
        }
        for (Object[] ev : events) {
            s.record((String) ev[0], (ContentEntry) ev[1], (ContentEntry) ev[2], (String) ev[3], (String) ev[4]);
        }
        s.projectDirty = true;
        s.commit();
        return copyList(result);
    }

    private void fillCatalogFacts(ContentEntry e, Staged st, boolean fresh) {
        PlannedFile pf = st.pf();
        CatalogVersion v = pf.version();
        e.source = CatalogRegistry.sourceOf(v.source());
        e.projectId = v.projectId();
        e.versionId = v.id();
        e.versionNumber = v.versionNumber();
        e.title = pf.title() == null || pf.title().isBlank() ? (st.md() != null ? st.md().name() : stripExtension(st.name())) : pf.title();
        e.sha1 = st.sha1();
        e.sha512 = st.sha512();
        e.size = st.size();
        e.modifiedAt = 0; // recorded from the placed file when the session commits
        e.dependency = fresh ? pf.dependency() : e.dependency && pf.dependency();
        if (pf.pageUrl() != null) {
            e.pageUrl = pf.pageUrl().toString();
        }
        e.downloadUrl = pf.file().url() == null ? null : pf.file().url().toString();
        if (e.kind == MOD && st.md() != null && st.md().id() != null) {
            e.modId = st.md().id();
        }
        e.missing = false;
        if (e.requiredBy == null) {
            e.requiredBy = new ArrayList<>();
        }
    }

    /** fill requiredBy from the plan, and note the roots on dependencies that were already installed */
    private static void linkRequiredBy(Session s, List<Staged> staged, List<ContentEntry> placed) {
        Map<String, ContentEntry> byProject = new HashMap<>();
        for (ContentEntry e : s.project.content) {
            if (e.projectId != null) {
                byProject.putIfAbsent(e.source + "/" + e.projectId, e);
            }
        }
        for (ContentEntry e : placed) {
            byProject.put(e.source + "/" + e.projectId, e);
        }
        for (int i = 0; i < staged.size(); i++) {
            PlannedFile pf = staged.get(i).pf();
            ContentEntry e = placed.get(i);
            ContentSource src = CatalogRegistry.sourceOf(pf.version().source());
            if (pf.requiredBy() != null) {
                ContentEntry parent = byProject.get(src + "/" + pf.requiredBy());
                if (parent != null && !parent.id.equals(e.id) && !e.requiredBy.contains(parent.id)) {
                    e.requiredBy.add(parent.id);
                }
            }
            for (CatalogDependency d : pf.version().dependencies()) {
                if (d.type() != DependencyType.REQUIRED || d.projectId() == null) {
                    continue;
                }
                ContentEntry dep = byProject.get(src + "/" + d.projectId());
                if (dep != null && !dep.id.equals(e.id)) {
                    if (dep.requiredBy == null) {
                        dep.requiredBy = new ArrayList<>();
                    }
                    if (!dep.requiredBy.contains(e.id)) {
                        dep.requiredBy.add(e.id);
                    }
                }
            }
        }
    }

    /**
     * Replace an entry's file with another catalog version: the new file is downloaded and verified first,
     * then the old file moves to history and the new one takes its place (restored if anything fails). The
     * entry keeps its id, enabled state and pin.
     */
    public ContentEntry update(Project project, String entryId, CatalogVersion newVersion, CatalogFile file,
                               ProgressListener progress, CancellationToken cancel) throws LauncherException {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        Objects.requireNonNull(newVersion, "newVersion");
        CatalogFile f = file != null ? file : newVersion.primaryFile()
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "content.error.noFile", newVersion.name()));
        ContentEntry current = locked(project, s -> copy(find(s, entryId)));
        requireFolderKind(current.kind);
        requireDownloadable(title(current), f, current.pageUrl);
        String newName = PathSafety.sanitizeFileName(f.fileName());
        PlannedFile pf = new PlannedFile(title(current), newVersion, f, current.kind, current.dependency, null,
            null);
        Path staging = projects.projectDir(project.id).resolve(STAGING).resolve(UUID.randomUUID().toString());
        try {
            List<Staged> staged = download(List.of(pf), staging, title(current), pl, ct);
            ct.check();
            pl.stage("content.stage.installing");
            Staged st = staged.get(0);
            return locked(project, s -> {
                ContentEntry e = find(s, entryId);
                Path folder = folder(s, e.kind);
                Path oldPath = currentPath(s, e);
                Path clash = existingVariant(folder, newName, e.kind);
                if (clash != null && (oldPath == null || !clash.getFileName().toString().equalsIgnoreCase(oldPath.getFileName().toString())
                    && !clash.getFileName().toString().equalsIgnoreCase(e.fileName))) {
                    throw nameClash(newName, ownerOf(s, e.kind, clash));
                }
                String eventId = newId();
                String stored = oldPath == null ? null : moveToHistory(s, eventId, oldPath);
                boolean disabledMod = e.kind == MOD && !e.enabled;
                Path target = entryPath(s, e.kind, disabledMod ? newName + DISABLED : newName);
                try {
                    ContentFiles.move(st.path(), target);
                } catch (LauncherException ex) {
                    if (stored != null) {
                        restoreQuietly(s, stored, oldPath);
                    }
                    throw ex;
                }
                ContentEntry before = copy(e);
                String oldName = e.fileName;
                e.fileName = newName;
                String keepTitle = e.title;
                fillCatalogFacts(e, st, false);
                e.title = keepTitle == null ? e.title : keepTitle;
                e.dependency = before.dependency;
                if (e.kind == RESOURCE_PACK) {
                    OptionsPacks op = OptionsPacks.open(s.gameDir);
                    op.replace(oldName, newName, e.enabled);
                    if (!e.enabled) {
                        op.deselect(newName);
                    }
                    op.save();
                } else if (e.kind == SHADER_PACK) {
                    ShaderSettings.renameIfActive(s.gameDir, oldName, newName);
                }
                s.record(HistoryEvent.UPDATE, before, e, eventId, stored);
                s.projectDirty = true;
                s.commit();
                return copy(e);
            });
        } finally {
            ContentFiles.deleteTree(staging);
            ContentFiles.deleteIfEmpty(staging.getParent());
        }
    }

    /**
     * Record files that are already in place (a modpack import downloaded them) with the given catalog
     * details. Existing entries for the same file are updated; nothing is moved and no history is written.
     */
    public List<ContentEntry> recordExisting(Project project, List<ExistingFile> files) throws LauncherException {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return locked(project, s -> {
            List<ContentEntry> out = new ArrayList<>();
            OptionsPacks op = OptionsPacks.open(s.gameDir);
            for (ExistingFile f : files) {
                requireFolderKind(f.kind());
                Path folder = folder(s, f.kind());
                Path p = existingVariant(folder, f.fileName(), f.kind());
                if (p == null) {
                    throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.fileMissing", f.fileName());
                }
                ContentEntry e = ownerOf(s, f.kind(), p);
                if (e == null) {
                    e = newEntry(f.kind(), p);
                    s.project.content.add(e);
                }
                Optional<ModMetadata> md = metadata.read(p);
                e.source = f.source() == null ? ContentSource.LOCAL : f.source();
                e.projectId = f.projectId();
                e.versionId = f.versionId();
                e.versionNumber = f.versionNumber() != null ? f.versionNumber() : md.map(ModMetadata::version).orElse(null);
                e.title = f.title() != null && !f.title().isBlank() ? f.title()
                    : md.map(ModMetadata::name).filter(n -> !n.isBlank()).orElse(stripExtension(e.fileName));
                e.pageUrl = f.pageUrl();
                e.downloadUrl = f.downloadUrl();
                if (f.kind() == MOD) {
                    e.modId = md.map(ModMetadata::id).orElse(e.modId);
                } else if (f.kind() == RESOURCE_PACK) {
                    e.enabled = op.isSelected(e.fileName);
                }
                if (Files.isRegularFile(p)) {
                    String[] h = ContentFiles.sha1AndSha512(p);
                    e.sha1 = h[0];
                    e.sha512 = h[1];
                }
                e.size = ContentFiles.sizeOf(p);
                e.modifiedAt = ContentFiles.modifiedMillis(p);
                e.missing = false;
                out.add(e);
            }
            s.projectDirty = true;
            s.commit();
            return copyList(out);
        });
    }

    // ================================================================== enable, remove, pin

    /**
     * Enable or disable an entry for the next launch. Mods: {@code <file>} is renamed to
     * {@code <file>.disabled} (or back), so the loader can't load a disabled mod. Resource packs: added to
     * (highest priority) or removed from {@code options.txt}. Shader packs: the entry flag, plus
     * {@code enableShaders} in Iris's config when this pack is the selected one.
     */
    public void setEnabled(Project project, String entryId, boolean enabled) throws LauncherException {
        locked(project, s -> {
            ContentEntry e = find(s, entryId);
            ContentEntry before = copy(e);
            boolean changed = applyEnabled(s, e, enabled);
            if (changed) {
                s.record(enabled ? HistoryEvent.ENABLE : HistoryEvent.DISABLE, before, e, newId(), null);
                s.projectDirty = true;
            }
            s.commit();
            return null;
        });
    }

    /** apply the enabled state on disk and in the entry; returns true when anything changed */
    private boolean applyEnabled(Session s, ContentEntry e, boolean enabled) throws LauncherException {
        boolean changed = e.enabled != enabled;
        switch (e.kind) {
            case MOD -> {
                Path plain = entryPath(s, MOD, e.fileName);
                Path disabled = entryPath(s, MOD, e.fileName + DISABLED);
                boolean hasPlain = ContentFiles.exists(plain);
                boolean hasDisabled = ContentFiles.exists(disabled);
                if (enabled) {
                    if (!hasPlain) {
                        if (!hasDisabled) {
                            throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.fileMissing", e.fileName);
                        }
                        ContentFiles.move(disabled, plain);
                        changed = true;
                    }
                } else if (hasPlain) {
                    if (hasDisabled) {
                        throw new LauncherException(ErrorCode.CONFLICT, "content.error.bothVariants", e.fileName);
                    }
                    ContentFiles.move(plain, disabled);
                    changed = true;
                }
                e.missing = !hasPlain && !hasDisabled;
            }
            case RESOURCE_PACK -> {
                Path p = entryPath(s, RESOURCE_PACK, e.fileName);
                OptionsPacks op = OptionsPacks.open(s.gameDir);
                if (enabled) {
                    if (!ContentFiles.exists(p)) {
                        throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.fileMissing", e.fileName);
                    }
                    changed |= !op.isSelected(e.fileName);
                    op.select(e.fileName);
                } else {
                    changed |= op.isSelected(e.fileName);
                    op.deselect(e.fileName);
                }
                op.save();
            }
            case SHADER_PACK -> ShaderSettings.setEnabledIfActive(s.gameDir, e.fileName, enabled);
            default -> {
                // data packs and other kinds: the flag only
            }
        }
        e.enabled = enabled;
        return changed;
    }

    /**
     * Remove an entry: its file moves to {@code .history/<event>/} (so {@link #rollback} can restore it) and
     * resource packs leave {@code options.txt}. Nothing outside the project is touched.
     */
    public void remove(Project project, String entryId) throws LauncherException {
        locked(project, s -> {
            ContentEntry e = find(s, entryId);
            String eventId = newId();
            String stored = null;
            if (FOLDER_KINDS.contains(e.kind)) {
                Path p = currentPath(s, e);
                if (p != null) {
                    stored = moveToHistory(s, eventId, p);
                }
                if (e.kind == RESOURCE_PACK) {
                    OptionsPacks op = OptionsPacks.open(s.gameDir);
                    op.deselect(e.fileName);
                    op.save();
                } else if (e.kind == SHADER_PACK) {
                    ShaderSettings.setEnabledIfActive(s.gameDir, e.fileName, false);
                }
            }
            s.project.content.remove(e);
            for (ContentEntry other : s.project.content) {
                if (other.requiredBy != null) {
                    other.requiredBy.remove(e.id);
                }
            }
            s.record(HistoryEvent.REMOVE, copy(e), null, eventId, stored);
            s.projectDirty = true;
            s.commit();
            return null;
        });
    }

    /** pin or unpin an entry (pinned entries are skipped by "update all") */
    public void setPinned(Project project, String entryId, boolean pinned) throws LauncherException {
        locked(project, s -> {
            ContentEntry e = find(s, entryId);
            if (e.pinned != pinned) {
                e.pinned = pinned;
                s.projectDirty = true;
            }
            s.commit();
            return null;
        });
    }

    /** where an entry's file is right now (enabled or {@code .disabled} name); empty when missing */
    public Optional<Path> fileOf(Project project, String entryId) throws LauncherException {
        return locked(project, s -> {
            ContentEntry e = find(s, entryId);
            return FOLDER_KINDS.contains(e.kind) ? Optional.ofNullable(currentPath(s, e)) : Optional.empty();
        });
    }

    // ================================================================== history

    /** the project's content history, newest first */
    public List<HistoryEvent> history(Project project) throws LauncherException {
        return locked(project, s -> {
            List<HistoryEvent> out = new ArrayList<>(s.events());
            java.util.Collections.reverse(out);
            return out;
        });
    }

    /**
     * Undo one history event: the entry and its file go back to the state before the event. An install is
     * undone by removing what it added; an update or removal by restoring the stored file; enable/disable by
     * switching back. The undo is itself recorded as a "rollback" event (which can be rolled back too). An
     * event whose stored file was already restored or pruned can't be rolled back again ({@code NOT_FOUND}).
     */
    public void rollback(Project project, String eventId) throws LauncherException {
        locked(project, s -> {
            HistoryEvent ev = s.events().stream().filter(x -> x.id().equals(eventId)).findFirst()
                .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "content.error.historyNotFound", String.valueOf(eventId)));
            ContentEntry current = s.project.content.stream().filter(x -> x.id.equals(ev.entryId())).findFirst().orElse(null);
            ContentEntry before = ev.before();
            if (current == null && before == null) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.entryGone", ev.title());
            }
            boolean fileChange = before == null || ev.after() == null || ev.storedFile() != null;
            String newId = newId();
            if (!fileChange) {
                if (current == null) {
                    throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.entryGone", ev.title());
                }
                ContentEntry snapshot = copy(current);
                if (before.fileName != null && before.fileName.equalsIgnoreCase(current.fileName)) {
                    // same file: restore every recorded detail, then the enabled state on disk
                    boolean wanted = before.enabled;
                    ContentEntry restored = copy(before);
                    restored.id = current.id;
                    restored.fileName = current.fileName;
                    restored.enabled = current.enabled;
                    restored.missing = current.missing;
                    replaceEntry(s, current, restored);
                    applyEnabled(s, restored, wanted);
                    s.record(HistoryEvent.ROLLBACK, snapshot, restored, newId, null);
                } else {
                    applyEnabled(s, current, before.enabled);
                    s.record(HistoryEvent.ROLLBACK, snapshot, current, newId, null);
                }
                s.projectDirty = true;
                s.commit();
                return null;
            }
            Path storedPath = null;
            if (before != null && ev.storedFile() != null) {
                storedPath = resolveStored(s, ev.storedFile());
                if (!ContentFiles.exists(storedPath)) {
                    throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.historyFileGone", ev.title());
                }
            }
            if (before != null) {
                requireFolderKind(before.kind);
                if (!isSafeEntryName(before)) {
                    throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(before.fileName));
                }
            }
            ContentEntry snapshot = current == null ? null : copy(current);
            String newStored = null;
            Path currentPath = null;
            if (current != null && FOLDER_KINDS.contains(current.kind) && isSafeEntryName(current)) {
                currentPath = currentPath(s, current);
                if (currentPath != null) {
                    newStored = moveToHistory(s, newId, currentPath);
                }
            }
            ContentEntry restored = null;
            if (before != null) {
                restored = copy(before);
                restored.id = ev.entryId();
                if (storedPath != null) {
                    boolean disabledMod = before.kind == MOD && !before.enabled;
                    Path target = entryPath(s, before.kind, disabledMod ? before.fileName + DISABLED : before.fileName);
                    Path clash = existingVariant(folder(s, before.kind), before.fileName, before.kind);
                    if (clash != null) {
                        if (newStored != null) {
                            restoreQuietly(s, newStored, currentPath);
                        }
                        throw nameClash(before.fileName, ownerOf(s, before.kind, clash));
                    }
                    try {
                        ContentFiles.move(storedPath, target);
                    } catch (LauncherException ex) {
                        if (newStored != null) {
                            restoreQuietly(s, newStored, currentPath);
                        }
                        throw ex;
                    }
                    ContentFiles.deleteIfEmpty(storedPath.getParent());
                    restored.missing = false;
                } else {
                    restored.missing = true;
                }
            }
            int index = current == null ? -1 : s.project.content.indexOf(current);
            if (current != null) {
                s.project.content.remove(current);
            }
            if (restored != null) {
                if (restored.requiredBy == null) {
                    restored.requiredBy = new ArrayList<>();
                }
                s.project.content.add(index < 0 ? s.project.content.size() : index, restored);
            } else {
                for (ContentEntry other : s.project.content) {
                    if (other.requiredBy != null) {
                        other.requiredBy.remove(ev.entryId());
                    }
                }
            }
            ContentKind kind = restored != null ? restored.kind : current != null ? current.kind : null;
            if (kind == RESOURCE_PACK) {
                OptionsPacks op = OptionsPacks.open(s.gameDir);
                if (current != null && restored != null) {
                    op.replace(current.fileName, restored.fileName, restored.enabled && !restored.missing);
                    if (!restored.enabled || restored.missing) {
                        op.deselect(restored.fileName);
                    }
                } else if (current != null) {
                    op.deselect(current.fileName);
                } else if (restored.enabled && !restored.missing) {
                    op.select(restored.fileName);
                }
                op.save();
            } else if (kind == SHADER_PACK) {
                if (current != null && restored != null) {
                    ShaderSettings.renameIfActive(s.gameDir, current.fileName, restored.fileName);
                } else if (current != null) {
                    ShaderSettings.setEnabledIfActive(s.gameDir, current.fileName, false);
                }
            }
            s.record(HistoryEvent.ROLLBACK, snapshot, restored, newId, newStored);
            s.projectDirty = true;
            s.commit();
            return null;
        });
    }

    private static void replaceEntry(Session s, ContentEntry old, ContentEntry replacement) {
        int i = s.project.content.indexOf(old);
        if (i >= 0) {
            s.project.content.set(i, replacement);
        } else {
            s.project.content.add(replacement);
        }
    }

    private Path resolveStored(Session s, String stored) throws LauncherException {
        Path p = PathSafety.resolveInside(s.projectDir, stored);
        if (!p.startsWith(HistoryStore.dir(s.projectDir))) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", stored);
        }
        return p;
    }

    private String moveToHistory(Session s, String eventId, Path file) throws LauncherException {
        String name = file.getFileName().toString();
        Path target = HistoryStore.dir(s.projectDir).resolve(eventId).resolve(name);
        ContentFiles.move(file, target);
        return HistoryStore.DIR + "/" + eventId + "/" + name;
    }

    private void restoreQuietly(Session s, String stored, Path original) {
        try {
            Path p = resolveStored(s, stored);
            ContentFiles.move(p, original);
            ContentFiles.deleteIfEmpty(p.getParent());
        } catch (LauncherException e) {
            LOG.warning("Couldn't put " + original.getFileName() + " back; it is kept in " + stored + ": " + e.getMessage());
        }
    }

    // ================================================================== resource pack order

    /** enabled user resource packs (file names), highest priority first; built-in packs are not listed */
    public List<String> resourcePackOrder(Project project) throws LauncherException {
        return locked(project, s -> OptionsPacks.open(s.gameDir).order());
    }

    /**
     * Put the user resource packs in this order, highest priority first. Built-in and mod-provided packs
     * ({@code vanilla}, {@code fabric}...) stay at the bottom; enabled packs not listed stay enabled below
     * the listed ones; listed packs become enabled. Creates {@code options.txt} with only the
     * {@code resourcePacks} line when it doesn't exist yet.
     */
    public void setResourcePackOrder(Project project, List<String> highestFirst) throws LauncherException {
        List<String> names = new ArrayList<>(new LinkedHashSet<>(highestFirst == null ? List.of() : highestFirst));
        locked(project, s -> {
            Path folder = folder(s, RESOURCE_PACK);
            for (String n : names) {
                if (!PathSafety.isSafeFileName(n)) {
                    throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(n));
                }
                if (!ContentFiles.exists(folder.resolve(n))) {
                    throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.packNotFound", n);
                }
            }
            OptionsPacks op = OptionsPacks.open(s.gameDir);
            op.setOrder(names);
            op.save();
            for (ContentEntry e : s.project.content) {
                if (e.kind == RESOURCE_PACK && names.contains(e.fileName) && !e.enabled) {
                    e.enabled = true;
                    s.projectDirty = true;
                }
            }
            s.commit();
            return null;
        });
    }

    // ================================================================== helpers

    private ContentEntry newLocalEntry(ContentKind kind, Path p, OptionsPacks packs) throws LauncherException {
        ContentEntry e = newEntry(kind, p);
        if (kind == RESOURCE_PACK) {
            e.enabled = packs.isSelected(e.fileName);
        }
        Optional<ModMetadata> md = metadata.read(p);
        e.title = md.map(ModMetadata::name).filter(n -> !n.isBlank()).orElse(stripExtension(e.fileName));
        if (kind == MOD) {
            e.modId = md.map(ModMetadata::id).orElse(null);
        }
        e.versionNumber = md.map(ModMetadata::version).orElse(null);
        if (Files.isRegularFile(p)) {
            String[] h = ContentFiles.sha1AndSha512(p);
            e.sha1 = h[0];
            e.sha512 = h[1];
        }
        e.size = ContentFiles.sizeOf(p);
        e.modifiedAt = ContentFiles.modifiedMillis(p);
        return e;
    }

    /** a LOCAL entry for an existing file or folder ({@code .disabled} mods start disabled) */
    private static ContentEntry newEntry(ContentKind kind, Path p) {
        ContentEntry e = new ContentEntry();
        e.id = newId();
        e.kind = kind;
        e.source = ContentSource.LOCAL;
        String name = p.getFileName().toString();
        boolean disabled = kind == MOD && ContentFiles.lower(name).endsWith(DISABLED);
        e.fileName = disabled ? name.substring(0, name.length() - DISABLED.length()) : name;
        e.enabled = !disabled;
        e.title = stripExtension(e.fileName);
        e.installedAt = Instant.now().toString();
        return e;
    }

    /** refresh size/hashes (and metadata when the file changed); true when the entry changed */
    private boolean refreshFileFacts(ContentEntry e, Path p) throws LauncherException {
        if (!Files.isRegularFile(p)) {
            long size = ContentFiles.sizeOf(p);
            if (e.size != size) {
                e.size = size;
                return true;
            }
            return false;
        }
        long size = ContentFiles.sizeOf(p);
        long modified = ContentFiles.modifiedMillis(p);
        boolean changed = false;
        if (e.sha1 == null || e.sha512 == null || e.size != size || e.modifiedAt != modified) {
            String[] h = ContentFiles.sha1AndSha512(p);
            if (e.sha1 != null && !e.sha1.equalsIgnoreCase(h[0])) {
                // replaced outside the launcher: the catalog version no longer describes it
                LOG.info("Content file " + e.fileName + " changed outside the launcher");
                Optional<ModMetadata> md = metadata.read(p);
                e.versionId = null;
                e.versionNumber = md.map(ModMetadata::version).orElse(null);
                if (e.kind == MOD) {
                    e.modId = md.map(ModMetadata::id).orElse(e.modId);
                }
                e.downloadUrl = null;
            }
            changed |= e.sha1 == null || !e.sha1.equalsIgnoreCase(h[0]) || e.size != size || e.modifiedAt != modified;
            e.sha1 = h[0];
            e.sha512 = h[1];
            e.size = size;
            e.modifiedAt = modified;
        }
        if (e.kind == MOD && e.modId == null) {
            String id = metadata.read(p).map(ModMetadata::id).orElse(null);
            if (id != null) {
                e.modId = id;
                changed = true;
            }
        }
        return changed;
    }

    private static boolean setMissing(ContentEntry e, boolean missing) {
        if (e.missing == missing) {
            return false;
        }
        e.missing = missing;
        return true;
    }

    private static Path unclaimed(Map<String, Path> onDisk, Set<String> claimed, String name) {
        String key = ContentFiles.lower(name);
        return claimed.contains(key) ? null : onDisk.get(key);
    }

    /** folder entries by lowercase name, without hidden and temporary files */
    private static Map<String, Path> listFolder(Path dir) throws LauncherException {
        Map<String, Path> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> all = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(all::add);
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        all.sort(null);
        for (Path p : all) {
            String n = p.getFileName().toString();
            String l = ContentFiles.lower(n);
            if (n.startsWith(".") || l.endsWith(".part") || l.endsWith(".tmp") || l.endsWith(".unzip-tmp")) {
                continue;
            }
            out.putIfAbsent(l, p);
        }
        return out;
    }

    private static boolean isCandidate(ContentKind kind, Path p) {
        String l = ContentFiles.lower(p.getFileName().toString());
        boolean file = Files.isRegularFile(p);
        boolean dir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
        return switch (kind) {
            case MOD -> file && (l.endsWith(".jar") || l.endsWith(".jar" + DISABLED));
            case RESOURCE_PACK -> (file && l.endsWith(".zip")) || (dir && Files.isRegularFile(p.resolve("pack.mcmeta")));
            case SHADER_PACK -> (file && l.endsWith(".zip")) || (dir && Files.isDirectory(p.resolve("shaders")));
            default -> false;
        };
    }

    private void cleanStaleStaging(Session s) {
        Path root = s.projectDir.resolve(STAGING);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (now - Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis() > STALE_STAGING_MILLIS) {
                    ContentFiles.deleteTree(p);
                }
            }
        } catch (IOException e) {
            LOG.fine(() -> "Couldn't tidy staging folder: " + e);
        }
        ContentFiles.deleteIfEmpty(root);
    }

    private static ContentEntry find(Session s, String entryId) throws LauncherException {
        for (ContentEntry e : s.project.content) {
            if (e.id != null && e.id.equals(entryId)) {
                return e;
            }
        }
        throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.entryNotFound", String.valueOf(entryId));
    }

    private static ContentEntry findSameProject(Session s, ContentSource src, String projectId, ContentKind kind) {
        if (projectId == null) {
            return null;
        }
        for (ContentEntry e : s.project.content) {
            if (e.kind == kind && e.source == src && projectId.equals(e.projectId)) {
                return e;
            }
        }
        return null;
    }

    /** the entry whose file (enabled or disabled name) is {@code file}, or null */
    private static ContentEntry ownerOf(Session s, ContentKind kind, Path file) {
        String n = ContentFiles.lower(file.getFileName().toString());
        for (ContentEntry e : s.project.content) {
            if (e.kind != kind || e.fileName == null) {
                continue;
            }
            String l = ContentFiles.lower(e.fileName);
            if (n.equals(l) || (kind == MOD && n.equals(l + DISABLED))) {
                return e;
            }
        }
        return null;
    }

    /** the existing file using {@code name} (mods: also {@code name.disabled}), case-insensitive; or null */
    private static Path existingVariant(Path folder, String name, ContentKind kind) throws LauncherException {
        Path p = ContentFiles.findIgnoreCase(folder, name);
        if (p == null && kind == MOD) {
            p = ContentFiles.findIgnoreCase(folder, name + DISABLED);
        }
        return p;
    }

    private void checkClash(Session s, ContentKind kind, String name, String sha1, ContentSource src, String projectId)
        throws LauncherException {
        Path folder = s.gameDir.resolve(kind.folder());
        Path clash = existingVariant(folder, name, kind);
        if (clash == null) {
            return;
        }
        ContentEntry owner = ownerOf(s, kind, clash);
        ContentEntry same = findSameProject(s, src, projectId, kind);
        if (same != null && owner == same) {
            return;
        }
        if (sha1 != null && Files.isRegularFile(clash) && sha1.equalsIgnoreCase(ContentFiles.sha1AndSha512(clash)[0])) {
            return;
        }
        if (sha1 == null) {
            return; // decided after the download, when the hash is known
        }
        throw nameClash(name, owner);
    }

    private static LauncherException nameClash(String name, ContentEntry owner) {
        return new LauncherException(ErrorCode.CONFLICT, "content.error.nameClash", name,
            owner == null ? I18n.tr("content.unknownFile") : title(owner));
    }

    private Path folder(Session s, ContentKind kind) throws LauncherException {
        requireFolderKind(kind);
        Path f = s.gameDir.resolve(kind.folder());
        try {
            Files.createDirectories(f);
        } catch (IOException e) {
            throw LauncherException.io(e, f.toString());
        }
        PathSafety.requireNoLinkEscape(s.projectDir, f);
        return f;
    }

    private Path entryPath(Session s, ContentKind kind, String fileName) throws LauncherException {
        if (!PathSafety.isSafeFileName(fileName)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(fileName));
        }
        return PathSafety.resolveInside(folder(s, kind), fileName);
    }

    /** the entry's file as it is on disk now (preferring its recorded state), or null */
    private Path currentPath(Session s, ContentEntry e) throws LauncherException {
        Path plain = entryPath(s, e.kind, e.fileName);
        if (e.kind != MOD) {
            return ContentFiles.exists(plain) ? plain : null;
        }
        Path disabled = entryPath(s, MOD, e.fileName + DISABLED);
        Path first = e.enabled ? plain : disabled;
        Path second = e.enabled ? disabled : plain;
        return ContentFiles.exists(first) ? first : ContentFiles.exists(second) ? second : null;
    }

    /** record the file time of entries whose hashes the launcher just set (see {@link ContentEntry#modifiedAt}) */
    private void stampModifiedTimes(Session s) {
        for (ContentEntry e : s.project.content) {
            if (e.modifiedAt != 0 || !FOLDER_KINDS.contains(e.kind) || !isSafeEntryName(e)) {
                continue;
            }
            try {
                Path p = currentPath(s, e);
                if (p != null && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    e.modifiedAt = ContentFiles.modifiedMillis(p);
                }
            } catch (LauncherException ex) {
                LOG.fine("No file time for " + e.fileName + ": " + ex.getMessage());
            }
        }
    }

    private static boolean isSafeEntryName(ContentEntry e) {
        return e.fileName != null && PathSafety.isSafeFileName(e.fileName)
            && (e.kind != MOD || PathSafety.isSafeFileName(e.fileName + DISABLED));
    }

    private static void requireFolderKind(ContentKind kind) throws LauncherException {
        if (kind == null || !FOLDER_KINDS.contains(kind)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "content.error.kindUnsupported", String.valueOf(kind));
        }
    }

    private static void requireDownloadable(String title, CatalogFile f, String page) throws LauncherException {
        if (f == null) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "content.error.noFile", String.valueOf(title));
        }
        if (!f.downloadable()) {
            throw new LauncherException(ErrorCode.DISTRIBUTION_RESTRICTED, "content.error.restricted", String.valueOf(title),
                page == null ? "" : page);
        }
    }

    private void requireSafeProject(Project project) throws LauncherException {
        if (project == null || project.id == null || !PathSafety.isSafeSegment(project.id)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", project == null ? "null" : String.valueOf(project.id));
        }
    }

    static String title(ContentEntry e) {
        return e.title != null && !e.title.isBlank() ? e.title : String.valueOf(e.fileName);
    }

    static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    static ContentEntry copy(ContentEntry e) {
        return e == null ? null : Json.copy(e, ContentEntry.class);
    }

    private static List<ContentEntry> copyList(List<ContentEntry> l) {
        List<ContentEntry> out = new ArrayList<>(l.size());
        for (ContentEntry e : l) {
            out.add(copy(e));
        }
        return out;
    }

    // ================================================================== locking and sessions

    @FunctionalInterface
    private interface Op<T> {
        T run(Session s) throws LauncherException;
    }

    private <T> T locked(Project project, Op<T> op) throws LauncherException {
        requireSafeProject(project);
        String key = projects.projectDir(project.id).toAbsolutePath().normalize().toString();
        ReentrantLock lock = LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return op.run(new Session(project));
        } finally {
            lock.unlock();
        }
    }

    /** one locked operation on a fresh copy of the stored project */
    private final class Session {
        final Project caller;
        final Project project;
        final Path projectDir;
        final Path gameDir;
        private List<HistoryEvent> events;
        boolean projectDirty;
        boolean historyDirty;

        Session(Project caller) throws LauncherException {
            this.caller = caller;
            Project stored = projects.get(caller.id).orElse(caller);
            this.project = Json.copy(stored, Project.class);
            if (project.content == null) {
                project.content = new ArrayList<>();
                projectDirty = true;
            }
            if (project.content.removeIf(Objects::isNull)) {
                projectDirty = true;
            }
            for (ContentEntry e : project.content) {
                if (e.id == null || e.kind == null || e.source == null || e.requiredBy == null) {
                    e.id = e.id == null ? newId() : e.id;
                    e.kind = e.kind == null ? MOD : e.kind;
                    e.source = e.source == null ? ContentSource.LOCAL : e.source;
                    e.requiredBy = e.requiredBy == null ? new ArrayList<>() : e.requiredBy;
                    projectDirty = true;
                }
            }
            this.projectDir = projects.projectDir(caller.id).toAbsolutePath().normalize();
            this.gameDir = projects.gameDir(project).toAbsolutePath().normalize();
            try {
                Files.createDirectories(gameDir);
            } catch (IOException e) {
                throw LauncherException.io(e, gameDir.toString());
            }
        }

        List<HistoryEvent> events() throws LauncherException {
            if (events == null) {
                events = HistoryStore.load(projectDir);
            }
            return events;
        }

        void record(String action, ContentEntry before, ContentEntry after, String eventId, String stored)
            throws LauncherException {
            ContentEntry ref = after != null ? after : before;
            events().add(new HistoryEvent(eventId, System.currentTimeMillis(), action, ref == null ? null : ref.id,
                ref == null ? "" : title(ref), copy(before), copy(after), stored));
            historyDirty = true;
        }

        void commit() throws LauncherException {
            if (projectDirty) {
                stampModifiedTimes(this);
                Project saved = projects.save(project);
                List<ContentEntry> c = saved != null && saved.content != null ? saved.content : project.content;
                caller.content = copyList(c);
                projectDirty = false;
            } else {
                caller.content = copyList(project.content);
            }
            if (historyDirty) {
                List<HistoryEvent> dropped = HistoryStore.prune(projectDir, events, maxHistoryEvents, maxHistoryBytes);
                HistoryStore.save(projectDir, events);
                HistoryStore.deleteStored(projectDir, dropped);
                historyDirty = false;
            }
        }
    }
}
