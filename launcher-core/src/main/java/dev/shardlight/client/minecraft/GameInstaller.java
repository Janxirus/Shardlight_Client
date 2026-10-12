package dev.shardlight.client.minecraft;

import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.minecraft.model.AssetIndex;
import dev.shardlight.client.minecraft.model.VersionJson;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.OsInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Installs, verifies and repairs Minecraft versions in the shared game folder ({@code game/versions},
 * {@code game/libraries}, {@code game/assets}). Every file comes from Mojang (or the loader's Maven
 * repository) and is checked against its SHA-1 and size before it is used.
 *
 * <p>For a loader version (Fabric, Quilt, Forge, NeoForge) the loader step writes
 * {@code versions/<id>/<id>.json} first; this installer then fetches the inherited vanilla version and
 * everything the merged version needs. Like the official launcher, a version that inherits its client jar
 * gets {@code versions/<id>/<id>.jar} as a hard link (or copy) of the parent's verified jar, so loader
 * arguments that name {@code ${version_name}.jar} (Forge's module ignore list) find it.
 *
 * <p>Blocking and thread-safe; installs into the same game folder run one at a time.
 */
public final class GameInstaller {
    public static final String STAGE_METADATA = "install.stage.metadata";
    public static final String STAGE_CLIENT = "install.stage.client";
    public static final String STAGE_LIBRARIES = "install.stage.libraries";
    public static final String STAGE_ASSETS = "install.stage.assets";
    public static final String STAGE_VERIFY = "install.stage.verify";

    private static final Logger LOG = Logger.getLogger(GameInstaller.class.getName());
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Endpoints endpoints;
    private final AppPaths paths;
    private final DownloadManager downloads;
    private final VersionManifestService manifest;
    private final VersionResolver resolver;
    private final InstallPlanner planner;
    private final ReentrantLock lock;

    public GameInstaller(HttpService http, Endpoints endpoints, AppPaths paths, DownloadManager downloads,
                         VersionManifestService manifest) {
        this(http, endpoints, paths, downloads, manifest, OsInfo.current());
    }

    /** with an explicit OS, so tests can install as Windows, macOS or Linux */
    public GameInstaller(HttpService http, Endpoints endpoints, AppPaths paths, DownloadManager downloads,
                         VersionManifestService manifest, OsInfo os) {
        this.endpoints = endpoints;
        this.paths = paths;
        this.downloads = downloads;
        this.manifest = manifest;
        this.resolver = new VersionResolver(paths.versions());
        this.planner = new InstallPlanner(paths, endpoints, os);
        this.lock = LOCKS.computeIfAbsent(paths.game().toAbsolutePath().normalize(), p -> new ReentrantLock());
    }

    /**
     * Install {@code versionId} and everything it needs. Files already present and valid are kept, so this
     * is quick for an installed version and can run before every launch.
     *
     * @throws LauncherException {@link ErrorCode#NOT_FOUND} for an unknown version,
     *                           {@link ErrorCode#UNSUPPORTED_VERSION} for one that can't run here, download and
     *                           checksum errors, {@link ErrorCode#CANCELLED}
     */
    public InstalledVersion install(String versionId, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        lock(c);
        try {
            return doInstall(versionId, p, c);
        } catch (LauncherException e) {
            throw cancelledWins(e, c);
        } finally {
            lock.unlock();
        }
    }

    /** re-hash every file {@code versionId} needs and list the missing and corrupt ones */
    public VerifyReport verify(String versionId, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        lock(c);
        try {
            return doVerify(versionId, p, c);
        } catch (LauncherException e) {
            throw cancelledWins(e, c);
        } finally {
            lock.unlock();
        }
    }

    /** verify, delete what is corrupt, then install again (downloading everything missing) */
    public InstalledVersion repair(String versionId, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        lock(c);
        try {
            try {
                VerifyReport report = doVerify(versionId, p, c);
                for (Path bad : report.corrupt()) {
                    c.check();
                    LOG.info("Repair: deleting corrupt " + bad);
                    FileOps.deleteTolerant(bad);
                }
            } catch (LauncherException e) {
                // a missing or unreadable version file: the install below fetches it again when it can
                if (e.isCancelled() || (e.code() != ErrorCode.NOT_FOUND && e.code() != ErrorCode.INVALID_DATA)) {
                    throw e;
                }
                LOG.info("Repair: " + e.getMessage());
            }
            return doInstall(versionId, p, c);
        } catch (LauncherException e) {
            throw cancelledWins(e, c);
        } finally {
            lock.unlock();
        }
    }

    /** the merged version of an installed version (no network) */
    public ResolvedVersion resolve(String versionId) throws LauncherException {
        return resolver.resolve(versionId);
    }

    /**
     * Describe an installed version from the files on disk, without downloading or verifying anything (for
     * launching when {@link #install} already ran).
     */
    public InstalledVersion installed(String versionId) throws LauncherException {
        ResolvedVersion v = resolver.resolve(versionId);
        InstallPlan plan = planner.plan(v);
        boolean virtual = false;
        boolean map = false;
        if (plan.assetIndex() != null && Files.isRegularFile(plan.assetIndex().path())) {
            AssetIndex index = Assets.read(plan.assetIndex().path());
            virtual = index.isVirtual();
            map = index.isMapToResources();
        }
        return toInstalled(plan, virtual, map);
    }

    /** the version's own JSON from {@code versions/<id>/<id>.json}, without its parents (no network) */
    public VersionJson readVersionJson(String versionId) throws LauncherException {
        return resolver.read(versionId);
    }

    /** true when {@code versions/<id>/<id>.json} exists (its content isn't checked) */
    public boolean hasVersionJson(String versionId) {
        return resolver.exists(versionId);
    }

    /** true when the version's JSON (and every parent's) is present */
    public boolean isInstalled(String versionId) {
        try {
            resolver.resolve(versionId);
            return true;
        } catch (LauncherException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- install

    private InstalledVersion doInstall(String versionId, ProgressListener p, CancellationToken c) throws LauncherException {
        String group = I18n.tr("install.group", versionId);
        p.progress(STAGE_METADATA, versionId, 0, -1);
        ensureMetadata(versionId, versionId, group, p, c, new LinkedHashSet<>());
        ResolvedVersion v = resolver.resolve(versionId);
        InstallPlan plan = planner.plan(v);
        c.check();

        installClient(plan, group, p, c);

        List<DownloadRequest> libs = new ArrayList<>();
        for (PlannedFile f : plan.libraries()) {
            if (f.downloadable()) {
                libs.add(f.request());
            } else if (!Files.isRegularFile(f.path())) {
                throw new LauncherException(ErrorCode.DEPENDENCY_MISSING, "install.error.libraryNotDownloadable", f.label());
            }
        }
        if (plan.loggingFile() != null && plan.loggingFile().downloadable()) {
            libs.add(plan.loggingFile().request());
        }
        p.progress(STAGE_LIBRARIES, versionId, 0, libs.size());
        downloads.downloadAll(group, libs, STAGE_LIBRARIES, p, c);

        boolean[] flags = installAssets(plan, group, p, c);
        InstalledVersion out = toInstalled(plan, flags[0], flags[1]);
        LOG.info("Installed " + versionId + " (" + out.classpath().size() + " classpath entries, "
            + out.nativeJars().size() + " native jars)");
        return out;
    }

    /** make sure {@code versions/<id>/<id>.json} (and every parent's) is present and current */
    private void ensureMetadata(String requested, String id, String group, ProgressListener p, CancellationToken c,
                                Set<String> visited) throws LauncherException {
        c.check();
        if (!visited.add(id) || visited.size() > VersionResolver.MAX_DEPTH) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "install.error.inheritanceLoop", requested);
        }
        Path json = resolver.jsonPath(id);
        VersionJson parsed = null;
        LauncherException unreadable = null;
        if (Files.isRegularFile(json)) {
            try {
                parsed = resolver.read(id);
            } catch (LauncherException e) {
                if (e.code() != ErrorCode.INVALID_DATA) {
                    throw e;
                }
                unreadable = e;
            }
            if (parsed != null) {
                Optional<VersionSummary> s = quietFind(id, c);
                if (s.isPresent() && s.get().sha1() != null && !Hashing.matches(json, HashAlgo.SHA1, s.get().sha1())) {
                    LOG.info("Mojang updated the version file of " + id + "; fetching the new one");
                    parsed = null;
                }
            }
        }
        if (parsed == null) {
            Optional<VersionSummary> entry;
            try {
                entry = manifest.find(id, c);
            } catch (LauncherException e) {
                if (unreadable != null && !e.isCancelled()) {
                    throw unreadable;
                }
                throw e;
            }
            if (entry.isEmpty()) {
                if (unreadable != null) {
                    throw unreadable;
                }
                if (!id.equals(requested)) {
                    throw new LauncherException(ErrorCode.NOT_FOUND, "install.error.parentMissing", requested, id);
                }
                throw new LauncherException(ErrorCode.NOT_FOUND, "install.error.versionNotFound", id);
            }
            VersionSummary s = entry.get();
            DownloadRequest r = DownloadRequest.sha1(s.url(), json, s.sha1(), -1).withLabel(id + ".json");
            if (s.sha1() != null && Files.exists(json) && !Hashing.matches(json, HashAlgo.SHA1, s.sha1())) {
                FileOps.deleteTolerant(json);
            }
            downloads.downloadAll(group, List.of(r), STAGE_METADATA, p, c);
            parsed = resolver.read(id);
        }
        if (parsed.inheritsFrom() != null && !parsed.inheritsFrom().isBlank()) {
            ensureMetadata(requested, parsed.inheritsFrom().trim(), group, p, c, visited);
        }
    }

    private Optional<VersionSummary> quietFind(String id, CancellationToken c) throws LauncherException {
        try {
            return manifest.find(id, c);
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                throw e;
            }
            LOG.fine(() -> "Version list unavailable, keeping the local file of " + id + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private void installClient(InstallPlan plan, String group, ProgressListener p, CancellationToken c)
        throws LauncherException {
        String id = plan.version().id();
        p.progress(STAGE_CLIENT, id, 0, 1);
        PlannedFile source = plan.clientSource() != null ? plan.clientSource() : plan.client();
        if (source.downloadable()) {
            downloads.downloadAll(group, List.of(source.request()), STAGE_CLIENT, p, c);
        } else if (!Files.isRegularFile(source.path())) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "install.error.noClientJar", id);
        }
        if (plan.clientSource() != null) {
            PlannedFile target = plan.client();
            if (target.check() != PlannedFile.Check.OK) {
                FileOps.linkOrCopy(source.path(), target.path());
                if (target.check() != PlannedFile.Check.OK) {
                    throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", target.label());
                }
            }
        }
        p.progress(STAGE_CLIENT, id, 1, 1);
    }

    /** returns {virtual, mapToResources} */
    private boolean[] installAssets(InstallPlan plan, String group, ProgressListener p, CancellationToken c)
        throws LauncherException {
        PlannedFile indexFile = plan.assetIndex();
        if (indexFile == null) {
            return new boolean[] {false, false};
        }
        p.progress(STAGE_ASSETS, plan.assetIndexId(), 0, -1);
        if (indexFile.downloadable()) {
            downloads.downloadAll(group, List.of(indexFile.request()), STAGE_ASSETS, p, c);
        }
        AssetIndex index = Assets.read(indexFile.path());
        List<DownloadRequest> missing = new ArrayList<>();
        for (PlannedFile f : Assets.objects(index, paths.assets(), endpoints.resources())) {
            if (!f.presentWithSize()) {
                missing.add(f.request());
            }
        }
        p.progress(STAGE_ASSETS, plan.assetIndexId(), 0, missing.size());
        downloads.downloadAll(group, missing, STAGE_ASSETS, p, c);
        if (index.isVirtual()) {
            Path dir = paths.assets().resolve("virtual").resolve(plan.assetIndexId());
            int n = Assets.copyObjects(index, paths.assets(), dir, c);
            if (n > 0) {
                LOG.info("Copied " + n + " assets to " + dir);
            }
        }
        return new boolean[] {index.isVirtual(), index.isMapToResources()};
    }

    private InstalledVersion toInstalled(InstallPlan plan, boolean virtual, boolean mapToResources) {
        ResolvedVersion v = plan.version();
        String logging = plan.loggingFile() != null && Files.isRegularFile(plan.loggingFile().path())
            ? plan.loggingArgument() : null;
        return new InstalledVersion(v.id(), v, plan.client().path(), plan.classpath(), plan.nativeJars(),
            plan.nativeExcludes(), plan.assetIndexId(), paths.assets(), virtual, mapToResources, v.javaMajorVersion(),
            v.javaComponent(), logging);
    }

    // ---------------------------------------------------------------- verify

    private VerifyReport doVerify(String versionId, ProgressListener p, CancellationToken c) throws LauncherException {
        p.progress(STAGE_VERIFY, versionId, 0, -1);
        ResolvedVersion v = resolver.resolve(versionId);
        InstallPlan plan = planner.plan(v);
        List<PlannedFile> expected = new ArrayList<>();
        if (plan.clientSource() != null) {
            expected.add(plan.clientSource());
        }
        expected.add(plan.client());
        expected.addAll(plan.libraries());
        if (plan.loggingFile() != null) {
            expected.add(plan.loggingFile());
        }
        ConcurrentLinkedQueue<Path> missing = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Path> corrupt = new ConcurrentLinkedQueue<>();
        PlannedFile indexFile = plan.assetIndex();
        if (indexFile != null) {
            PlannedFile.Check ic = indexFile.check();
            if (ic == PlannedFile.Check.OK) {
                try {
                    AssetIndex index = Assets.read(indexFile.path());
                    expected.addAll(Assets.objects(index, paths.assets(), endpoints.resources()));
                    if (index.isVirtual()) {
                        expected.addAll(Assets.copies(index, paths.assets().resolve("virtual").resolve(plan.assetIndexId())));
                    }
                } catch (LauncherException e) {
                    if (e.code() != ErrorCode.INVALID_DATA) {
                        throw e;
                    }
                    corrupt.add(indexFile.path());
                }
            } else {
                (ic == PlannedFile.Check.MISSING ? missing : corrupt).add(indexFile.path());
            }
        }
        checkAll(expected, missing, corrupt, versionId, p, c);
        Comparator<Path> order = Comparator.comparing(Path::toString);
        List<Path> m = missing.stream().distinct().sorted(order).toList();
        List<Path> k = corrupt.stream().distinct().sorted(order).toList();
        int checked = expected.size() + (indexFile == null ? 0 : 1);
        if (!m.isEmpty() || !k.isEmpty()) {
            LOG.info("Verify " + versionId + ": " + m.size() + " missing, " + k.size() + " corrupt of " + checked);
        }
        return new VerifyReport(versionId, checked, m, k);
    }

    private static void checkAll(List<PlannedFile> files, ConcurrentLinkedQueue<Path> missing,
                                 ConcurrentLinkedQueue<Path> corrupt, String detail, ProgressListener p,
                                 CancellationToken c) throws LauncherException {
        AtomicLong done = new AtomicLong();
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        List<Future<?>> futures = new ArrayList<>(files.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "verify");
            t.setDaemon(true);
            return t;
        });
        try {
            for (PlannedFile f : files) {
                futures.add(pool.submit(() -> {
                    if (c.isCancelled()) {
                        return;
                    }
                    switch (f.check()) {
                        case MISSING -> missing.add(f.path());
                        case CORRUPT -> corrupt.add(f.path());
                        default -> {
                        }
                    }
                    done.incrementAndGet();
                }));
            }
            for (Future<?> f : futures) {
                while (true) {
                    c.check();
                    try {
                        f.get(250, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException e) {
                        p.progress(STAGE_VERIFY, detail, done.get(), files.size());
                    } catch (ExecutionException e) {
                        throw LauncherException.internal(String.valueOf(e.getCause()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw LauncherException.cancelled();
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        p.progress(STAGE_VERIFY, detail, done.get(), files.size());
    }

    // ---------------------------------------------------------------- helpers

    /** once the user cancelled, a failure caused by the cancellation (an aborted request) is reported as such */
    private static LauncherException cancelledWins(LauncherException e, CancellationToken c) {
        return c.isCancelled() && !e.isCancelled() ? LauncherException.cancelled() : e;
    }

    private void lock(CancellationToken c) throws LauncherException {
        try {
            while (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {
                c.check();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LauncherException.cancelled();
        }
    }
}
