package dev.shardlight.client.modpack;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentService;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.content.ExistingFile;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.PathSafety;
import dev.shardlight.client.util.SafeZip;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Updates a project that was made from a modpack to a newer version of the same pack without losing what the
 * player did. The pack's files at install time are recorded ({@link PackBaseline}); an update compares three
 * states of every file, the original, the file on disk and the new pack:
 * <ul>
 *   <li>only the pack changed it: replaced (or removed, or added)</li>
 *   <li>the player edited it, or added it, or deleted it: the player's version stays</li>
 *   <li>both changed it: the player's version stays</li>
 * </ul>
 * Before anything is written a backup is taken, and every change can be undone: files are replaced by moving
 * the old ones aside first, and any failure moves them back.
 */
public final class ModpackUpdater {
    private static final Logger LOG = Logger.getLogger(ModpackUpdater.class.getName());
    private static final String STAGING = ".modpack-update-";
    private static final int MAX_PLAYER_FILES = 5000;
    /** folders where the player's own files are listed */
    private static final List<String> WATCHED = List.of(GameDirs.MODS, GameDirs.RESOURCE_PACKS, GameDirs.SHADER_PACKS, GameDirs.CONFIG);
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /** takes the backup that comes before an update; a failure stops the update */
    @FunctionalInterface
    public interface BackupHook {
        void backup(Project project, ProgressListener progress, CancellationToken cancel) throws LauncherException;
    }

    /** the outcome of an applied update */
    public record Result(UpdatePlan plan, List<String> warnings) {
    }

    /** one file of the new pack: what to fetch it from */
    private record Source(String sha1, Mrpack.FileRef download, boolean override) {
    }

    private final ModpackService service;
    private final ProjectAccess projects;
    private final ContentService content;
    private final DownloadManager downloads;
    private final BackupHook backupHook;

    public ModpackUpdater(ModpackService service, ProjectAccess projects, ContentService content, DownloadManager downloads,
                          BackupHook backupHook) {
        this.service = Objects.requireNonNull(service);
        this.projects = Objects.requireNonNull(projects);
        this.content = Objects.requireNonNull(content);
        this.downloads = Objects.requireNonNull(downloads);
        this.backupHook = Objects.requireNonNull(backupHook);
    }

    // ================================================================== planning

    /** true when the project has a recorded pack to update from */
    public boolean canUpdate(Project project) {
        try {
            return PackBaseline.read(projects.projectDir(project.id)).isPresent();
        } catch (LauncherException e) {
            return false;
        }
    }

    /**
     * Compare {@code pack} (a .mrpack) with the installed pack and the project's files. Changes nothing.
     * Fails with {@code NOT_FOUND} when the project has no recorded original and {@code INVALID_ARCHIVE}
     * when the file isn't a .mrpack.
     */
    public UpdatePlan plan(Project project, Path pack, CancellationToken cancel) throws LauncherException {
        return load(project, pack, cancel).plan();
    }

    private record Loaded(UpdatePlan plan, Mrpack index, Map<String, Source> next) {
    }

    private Loaded load(Project project, Path pack, CancellationToken cancel) throws LauncherException {
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        PackBaseline baseline = PackBaseline.read(projects.projectDir(project.id))
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "modpack.update.noBaseline", project.name));
        if (ModpackService.detect(pack) != ModpackService.Format.MRPACK) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "modpack.update.onlyMrpack", pack.getFileName().toString());
        }
        Mrpack m = Mrpack.parse(ModpackService.readEntry(pack, ModpackService.MRPACK_INDEX));
        m.requireValid();
        Map<String, Source> next = snapshot(m, pack);
        Path gameDir = projects.gameDir(project);
        Set<String> paths = new TreeSet<>(baseline.files.keySet());
        paths.addAll(next.keySet());
        Map<String, String> current = new TreeMap<>();
        Set<String> disabled = new TreeSet<>();
        for (String path : paths) {
            ct.check();
            Path file = PathSafety.resolveInside(gameDir, path);
            String h = hashOf(file);
            if (h == null && path.endsWith(".jar")) {
                Path off = PathSafety.resolveInside(gameDir, path + GameDirs.DISABLED_SUFFIX);
                h = hashOf(off);
                if (h != null) {
                    disabled.add(path);
                }
            }
            if (h != null) {
                current.put(path, h);
            }
        }
        Map<String, String> nextHashes = new TreeMap<>();
        next.forEach((p, s) -> nextHashes.put(p, s.sha1()));
        List<UpdateItem> items = new ArrayList<>(diff(baseline.files, current, nextHashes, disabled));
        for (String extra : playerFiles(gameDir, paths, ct)) {
            items.add(new UpdateItem(extra, UpdateAction.KEEP_ADDED, false));
        }
        items.sort(Comparator.comparing(UpdateItem::path));
        UpdatePlan plan = new UpdatePlan(m.name(), baseline.packName, Objects.equals(m.name(), baseline.packName), baseline.packVersion, m.versionId(),
            project.minecraftVersion, m.minecraft(), project.loader, m.loader(), items);
        return new Loaded(plan, m, next);
    }

    /**
     * The three-way comparison. {@code baseline}, {@code current} and {@code next} map paths to SHA-1; a path
     * missing from a map is absent there. {@code disabled} holds mods the player turned off (their hash is in
     * {@code current}). Files the player added are not in any of the maps, so they are not listed here.
     */
    static List<UpdateItem> diff(Map<String, String> baseline, Map<String, String> current, Map<String, String> next,
                                 Set<String> disabled) {
        Set<String> all = new TreeSet<>(baseline.keySet());
        all.addAll(next.keySet());
        List<UpdateItem> out = new ArrayList<>();
        for (String path : all) {
            String b = baseline.get(path);
            String c = current.get(path);
            String n = next.get(path);
            boolean off = disabled.contains(path);
            UpdateAction a;
            if (b == null) {
                // new in the pack
                a = c == null ? UpdateAction.ADD : c.equals(n) ? null : UpdateAction.KEEP_CONFLICT;
            } else if (n == null) {
                // dropped from the pack
                a = c == null ? null : c.equals(b) ? UpdateAction.REMOVE : UpdateAction.KEEP_EDITED;
            } else if (c == null) {
                a = UpdateAction.KEEP_REMOVED;
            } else if (c.equals(b)) {
                a = n.equals(b) ? null : UpdateAction.UPDATE;
            } else if (c.equals(n)) {
                a = null;
            } else {
                a = n.equals(b) ? UpdateAction.KEEP_EDITED : UpdateAction.KEEP_CONFLICT;
            }
            if (a != null) {
                out.add(new UpdateItem(path, a, off));
            }
        }
        return out;
    }

    /** the files of the pack: downloads first, then the overrides on top of them (as an import does) */
    private static Map<String, Source> snapshot(Mrpack m, Path pack) throws LauncherException {
        Map<String, Source> out = new LinkedHashMap<>();
        for (Mrpack.FileRef r : m.files()) {
            if ("unsupported".equals(r.envClient())) {
                continue;
            }
            String path = r.path().replace('\\', '/');
            PathSafety.resolveInside(Path.of("probe").toAbsolutePath(), path);
            out.put(path, new Source(r.sha1().toLowerCase(Locale.ROOT), r, false));
        }
        overrideHashes(pack, "overrides/", "client-overrides/").forEach((p, h) -> out.put(p, new Source(h, null, true)));
        return out;
    }

    /** SHA-1 of every file under the prefixes of a pack zip, later prefixes winning, keyed by the stripped path */
    static Map<String, String> overrideHashes(Path zip, String... prefixes) throws LauncherException {
        Map<String, String> out = new TreeMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (String prefix : prefixes) {
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String name = e.getName().replace('\\', '/');
                    if (e.isDirectory() || !name.startsWith(prefix) || name.length() == prefix.length()) {
                        continue;
                    }
                    String rel = name.substring(prefix.length());
                    try {
                        PathSafety.resolveInside(Path.of("probe").toAbsolutePath(), rel);
                    } catch (LauncherException bad) {
                        continue; // extraction would refuse it as well
                    }
                    MessageDigest md = HashAlgo.SHA1.newDigest();
                    try (InputStream in = zf.getInputStream(e)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            md.update(buf, 0, n);
                        }
                    }
                    out.put(rel, Hashing.hex(md.digest()));
                }
            }
        } catch (IOException e) {
            throw LauncherException.io(e, zip.toString());
        }
        return out;
    }

    private static String hashOf(Path file) throws LauncherException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return Hashing.hash(file, HashAlgo.SHA1);
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
    }

    /** files in the watched folders that neither the old nor the new pack know: the player's own */
    private static List<String> playerFiles(Path gameDir, Set<String> known, CancellationToken ct) throws LauncherException {
        List<String> out = new ArrayList<>();
        for (String folder : WATCHED) {
            Path root = gameDir.resolve(folder);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            collect(root, folder, known, out, ct);
        }
        return out;
    }

    private static void collect(Path dir, String rel, Set<String> known, List<String> out, CancellationToken ct) throws LauncherException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                ct.check();
                if (out.size() >= MAX_PLAYER_FILES) {
                    return;
                }
                String name = rel + "/" + p.getFileName();
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (rel.equals(GameDirs.CONFIG) || rel.startsWith(GameDirs.CONFIG + "/")) {
                        collect(p, name, known, out, ct);
                    }
                } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    String base = name.endsWith(GameDirs.DISABLED_SUFFIX) ? name.substring(0, name.length() - GameDirs.DISABLED_SUFFIX.length()) : name;
                    if (!known.contains(name) && !known.contains(base)) {
                        out.add(name);
                    }
                }
            }
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
    }

    // ================================================================== applying

    /**
     * Update the project to {@code pack}. The plan is made again at this moment (the player may have changed
     * files since the preview), the new files are fetched and checked first, a backup is taken, and only then
     * are files replaced. If anything fails, or the update is cancelled, every file is put back and the project
     * stays as it was. The project must not be running.
     */
    public Result apply(Project project, Path pack, ProgressListener progress, CancellationToken cancel) throws LauncherException {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        ReentrantLock lock = LOCKS.computeIfAbsent(project.id.toLowerCase(Locale.ROOT), k -> new ReentrantLock());
        lock.lock();
        try {
            pl.stage("modpack.update.stage.comparing");
            Loaded l = load(project, pack, ct);
            UpdatePlan plan = l.plan();
            List<UpdateItem> writes = plan.items().stream().filter(i -> i.action().writes()).toList();
            if (writes.isEmpty() && !plan.changesGame()) {
                return new Result(plan, List.of());
            }
            Path gameDir = projects.gameDir(project);
            Path projectDir = projects.projectDir(project.id);
            Path work = gameDir.resolve(STAGING + UUID.randomUUID());
            Path fresh = work.resolve("new");
            Path undo = work.resolve("old");
            List<String> warnings = new ArrayList<>();
            try {
                Files.createDirectories(fresh);
                Files.createDirectories(undo);
                stage(l, writes, pack, fresh, pl, ct);
                pl.stage("modpack.update.stage.backup");
                backupHook.backup(project, pl, ct);
                ct.check();
                pl.stage("modpack.update.stage.applying");
                Journal journal = new Journal(gameDir, fresh, undo);
                PackBaseline previous = PackBaseline.read(projectDir).orElse(null);
                try {
                    int done = 0;
                    for (UpdateItem item : writes) {
                        ct.check();
                        pl.progress("modpack.update.stage.applying", item.path(), done++, writes.size());
                        journal.apply(item);
                    }
                    PackBaseline nb = new PackBaseline();
                    nb.format = "mrpack";
                    nb.packName = l.index().name();
                    nb.packVersion = l.index().versionId();
                    nb.minecraft = l.index().minecraft();
                    nb.loader = l.index().loader();
                    l.next().forEach((p, s) -> nb.files.put(p, s.sha1()));
                    nb.write(projectDir);
                    try {
                        saveProject(project.id, plan);
                    } catch (LauncherException | RuntimeException e) {
                        restoreBaseline(projectDir, previous);
                        throw e;
                    }
                } catch (LauncherException | RuntimeException | IOException e) {
                    journal.rollback();
                    if (e instanceof IOException io) {
                        throw LauncherException.io(io, gameDir.toString());
                    }
                    throw e instanceof LauncherException le ? le : new LauncherException(ErrorCode.IO, e, "error.io", gameDir.toString(), e.getMessage());
                }
            } catch (IOException e) {
                throw LauncherException.io(e, work.toString());
            } finally {
                deleteQuietly(work);
            }
            try {
                recordContent(project.id, l, writes, pack, ct);
            } catch (LauncherException e) {
                if (e.isCancelled()) {
                    throw e;
                }
                LOG.log(Level.WARNING, "Updated " + project.id + " but couldn't refresh its content list", e);
                warnings.add(e.userMessage());
            }
            return new Result(plan, warnings);
        } finally {
            lock.unlock();
        }
    }

    /** download the new files and unpack the pack's own files into {@code fresh}, checking every hash */
    private void stage(Loaded l, List<UpdateItem> writes, Path pack, Path fresh, ProgressListener pl, CancellationToken ct)
        throws LauncherException, IOException {
        pl.stage("modpack.update.stage.downloading");
        Set<String> overridePaths = new TreeSet<>();
        List<DownloadRequest> requests = new ArrayList<>();
        for (UpdateItem item : writes) {
            if (item.action() == UpdateAction.REMOVE) {
                continue;
            }
            Source s = l.next().get(item.path());
            if (s.override()) {
                overridePaths.add(item.path());
                continue;
            }
            Mrpack.FileRef r = s.download();
            List<URI> urls = service.allowedUrls(r.downloads());
            if (urls.isEmpty()) {
                throw new LauncherException(ErrorCode.UNSAFE_PATH, "modpack.error.noAllowedUrl", r.path());
            }
            Path target = PathSafety.resolveInside(fresh, item.path());
            requests.add(new DownloadRequest(urls.get(0), urls.subList(1, urls.size()), target, HashAlgo.SHA1, s.sha1(),
                r.fileSize() > 0 ? r.fileSize() : -1, target.getFileName().toString(), true, false));
        }
        downloads.downloadAll(I18n.tr("modpack.update.download.group", l.plan().packName()), requests, "modpack.update.stage.downloading", pl, ct);
        for (UpdateItem item : writes) {
            Source s = l.next().get(item.path());
            if (s != null && !s.override() && s.download().sha512() != null
                && !Hashing.matches(PathSafety.resolveInside(fresh, item.path()), HashAlgo.SHA512, s.download().sha512())) {
                throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", item.path());
            }
        }
        if (!overridePaths.isEmpty()) {
            pl.stage("modpack.stage.overrides");
            SafeZip.extract(pack, "overrides/", fresh, SafeZip.Limits.DEFAULT, overridePaths::contains, ct);
            SafeZip.extract(pack, "client-overrides/", fresh, SafeZip.Limits.DEFAULT, overridePaths::contains, ct);
            for (String p : overridePaths) {
                String want = l.next().get(p).sha1();
                if (!Hashing.matches(PathSafety.resolveInside(fresh, p), HashAlgo.SHA1, want)) {
                    throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", p);
                }
            }
        }
    }

    /** puts files in place while remembering how to put each one back */
    private static final class Journal {
        private final Path gameDir;
        private final Path fresh;
        private final Path undo;
        /** (target in the game dir, where the old file was moved or null if there was none), in the order done */
        private final List<Path[]> steps = new ArrayList<>();

        Journal(Path gameDir, Path fresh, Path undo) {
            this.gameDir = gameDir;
            this.fresh = fresh;
            this.undo = undo;
        }

        void apply(UpdateItem item) throws IOException, LauncherException {
            String rel = item.disabled() ? item.path() + GameDirs.DISABLED_SUFFIX : item.path();
            Path target = PathSafety.resolveInside(gameDir, rel);
            PathSafety.requireNoLinkEscape(gameDir, target.getParent());
            Path aside = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                aside = PathSafety.resolveInside(undo, rel);
                Files.createDirectories(aside.getParent());
                moveFile(target, aside);
            }
            steps.add(new Path[] {target, aside});
            if (item.action() != UpdateAction.REMOVE) {
                Path source = PathSafety.resolveInside(fresh, item.path());
                Files.createDirectories(target.getParent());
                moveFile(source, target);
            }
        }

        /** undo every step, newest first; a failure to undo one file doesn't stop the others */
        void rollback() {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Path target = steps.get(i)[0];
                Path aside = steps.get(i)[1];
                try {
                    Files.deleteIfExists(target);
                    if (aside != null) {
                        Files.createDirectories(target.getParent());
                        moveFile(aside, target);
                    }
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Couldn't put back " + target + "; the old file is at " + aside, e);
                }
            }
            steps.clear();
        }
    }

    /** move a file, replacing the target; renames atomically when the file system allows it */
    private static void moveFile(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void saveProject(String id, UpdatePlan plan) throws LauncherException {
        Project p = projects.get(id).orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "project.error.notFound", id));
        if (plan.changesGame()) {
            p.minecraftVersion = plan.toMinecraft();
            p.loader = plan.toLoader();
            p.installedVersionId = null;
        }
        projects.save(p);
    }

    private void restoreBaseline(Path projectDir, PackBaseline previous) {
        try {
            if (previous != null) {
                previous.write(projectDir);
            } else {
                Files.deleteIfExists(projectDir.resolve(PackBaseline.FILE));
            }
        } catch (LauncherException | IOException e) {
            LOG.log(Level.SEVERE, "Couldn't restore " + PackBaseline.FILE, e);
        }
    }

    /** keep the content list in step with the files: drop removed ones, record new ones with their catalog details */
    private void recordContent(String id, Loaded l, List<UpdateItem> writes, Path pack, CancellationToken ct) throws LauncherException {
        Project p = projects.get(id).orElse(null);
        if (p == null) {
            return;
        }
        Set<String> removed = new TreeSet<>();
        List<ExistingFile> records = new ArrayList<>();
        List<Mrpack.FileRef> refs = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (UpdateItem item : writes) {
            ContentKind kind = ModpackService.contentKindOf(item.path());
            if (kind == null) {
                continue;
            }
            String fileName = item.path().substring(item.path().indexOf('/') + 1);
            if (item.action() == UpdateAction.REMOVE) {
                removed.add(kind.folder() + "/" + fileName);
                continue;
            }
            Source s = l.next().get(item.path());
            if (s.override()) {
                records.add(new ExistingFile(kind, fileName, ContentSource.MODPACK, null, null, null, null, null, null));
            } else {
                URI url = service.allowedUrls(s.download().downloads()).get(0);
                Matcher cdn = ModpackService.MODRINTH_CDN.matcher(url.getPath() == null ? "" : url.getPath());
                boolean modrinth = cdn.matches();
                records.add(new ExistingFile(kind, fileName, modrinth ? ContentSource.MODRINTH : ContentSource.MODPACK,
                    modrinth ? cdn.group(1) : null, modrinth ? cdn.group(2) : null, null, null, null, url.toString()));
            }
            refs.add(s.override() ? new Mrpack.FileRef(item.path(), s.sha1(), null, null, null, List.of(), -1) : s.download());
            indexes.add(refs.size() - 1);
        }
        if (!removed.isEmpty()) {
            projects.save(dropEntries(p, removed));
            p = projects.get(id).orElse(p);
        }
        if (!records.isEmpty()) {
            records = service.enrichFromModrinth(records, refs, indexes, l.index(), ct);
            content.recordExisting(p, records);
        }
        content.scan(projects.get(id).orElse(p));
    }

    private static Project dropEntries(Project p, Set<String> removed) {
        p.content.removeIf((ContentEntry e) -> e.kind != null && e.kind.folder() != null && removed.contains(e.kind.folder() + "/" + e.fileName));
        return p;
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Couldn't remove " + dir, e);
        }
    }

    /** a pack's recorded original for a fresh import: downloads by index, overrides from the zip */
    static PackBaseline baselineOf(Mrpack m, Path pack) throws LauncherException {
        PackBaseline b = new PackBaseline();
        b.format = "mrpack";
        b.packName = m.name();
        b.packVersion = m.versionId();
        b.minecraft = m.minecraft();
        b.loader = m.loader();
        snapshot(m, pack).forEach((p, s) -> b.files.put(p, s.sha1()));
        return b;
    }
}
