package dev.shardlight.client.java;

import com.google.gson.JsonParseException;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.OsInfo;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Finds Java runtimes on this computer and downloads Mojang's runtimes (the ones the official launcher
 * uses) into {@code game/runtimes/<component>/<platform>/}. Picks the right Java for a version:
 * the project's own choice, Mojang's runtime, or a matching installed Java. Thread-safe and blocking.
 */
public final class JavaRuntimeService {
    public static final String STAGE_JAVA = "install.stage.java";
    static final String MARKER = ".version";
    static final String PROBE_CACHE = "java_probes.json";

    /** Mojang runtime components by Java feature release, for versions whose JSON names none */
    static final Map<Integer, String> COMPONENTS = Map.of(
        8, "jre-legacy",
        16, "java-runtime-alpha",
        17, "java-runtime-gamma",
        21, "java-runtime-delta",
        25, "java-runtime-epsilon");

    private static final Logger LOG = Logger.getLogger(JavaRuntimeService.class.getName());
    private static final ConcurrentHashMap<String, ReentrantLock> INSTALL_LOCKS = new ConcurrentHashMap<>();

    private final HttpService http;
    private final Endpoints endpoints;
    private final Path runtimes;
    private final Path probeCacheFile;
    private final DownloadManager downloads;
    private final JavaEnvironment env;
    private final JavaProber prober;
    private final Map<String, CachedProbe> probeCache = new ConcurrentHashMap<>();
    private volatile boolean probeCacheLoaded;

    public JavaRuntimeService(HttpService http, Endpoints endpoints, AppPaths paths, DownloadManager downloads) {
        this(http, endpoints, paths, downloads, JavaEnvironment.current(), new ProcessJavaProber());
    }

    /** with an explicit environment and prober (tests simulate other operating systems with these) */
    public JavaRuntimeService(HttpService http, Endpoints endpoints, AppPaths paths, DownloadManager downloads,
                              JavaEnvironment env, JavaProber prober) {
        this.http = http;
        this.endpoints = endpoints;
        this.runtimes = paths.runtimes().toAbsolutePath().normalize();
        this.probeCacheFile = paths.cache().resolve(PROBE_CACHE);
        this.downloads = downloads;
        this.env = env;
        this.prober = prober;
    }

    record CachedProbe(String path, long modified, long size, String version, int major, String vendor, String arch) {
    }

    record ProbeCacheFile(List<CachedProbe> entries) {
    }

    // ---------------------------------------------------------------- detection

    /**
     * Every Java runtime found on this computer, newest first. Each candidate is run once
     * ({@code java -XshowSettings:properties -version}); results are cached by path and modification time.
     */
    public List<JavaInstall> detect() {
        List<Path> candidates = JavaLocations.candidates(env, runtimes);
        Map<Path, Path> unique = new LinkedHashMap<>();
        for (Path c : candidates) {
            Path key = realPath(c);
            if (env.os().isWindows()) {
                key = Path.of(key.toString().toLowerCase(Locale.ROOT));
            }
            unique.putIfAbsent(key, c);
        }
        List<JavaInstall> out = new ArrayList<>();
        Semaphore permits = new Semaphore(4);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<JavaInstall>>> futures = new ArrayList<>();
            for (Path c : unique.values()) {
                futures.add(ex.submit(() -> {
                    permits.acquire();
                    try {
                        return probe(c);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<Optional<JavaInstall>> f : futures) {
                try {
                    f.get().ifPresent(out::add);
                } catch (java.util.concurrent.ExecutionException e) {
                    LOG.fine(() -> "Java probe failed: " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        saveProbeCache();
        out.sort(Comparator.comparingInt(JavaInstall::majorVersion).reversed()
            .thenComparing((a, b) -> JavaProperties.compareVersions(b.version(), a.version())));
        return out;
    }

    /**
     * Probe one runtime. {@code javaExecutable} may be {@code java}, {@code java.exe}, {@code javaw.exe} or a
     * Java home folder. The returned install launches with {@code javaw.exe} on Windows when it exists.
     */
    public Optional<JavaInstall> probe(Path javaExecutable) {
        Optional<Path[]> exes = executables(javaExecutable);
        if (exes.isEmpty()) {
            return Optional.empty();
        }
        Path probeExe = exes.get()[0];
        Path launchExe = exes.get()[1];
        Optional<JavaProperties> props = cachedProbe(probeExe);
        return props.map(p -> new JavaInstall(launchExe, p.majorVersion(), p.version(), p.vendor(), p.arch(),
            isManaged(launchExe)));
    }

    /** [executable to probe (java/java.exe), executable to launch (javaw.exe on Windows when present)] */
    private Optional<Path[]> executables(Path given) {
        if (given == null) {
            return Optional.empty();
        }
        Path p = given.toAbsolutePath().normalize();
        boolean win = env.os().isWindows();
        if (Files.isDirectory(p)) {
            for (Path c : List.of(p.resolve("bin"), p.resolve("Contents").resolve("Home").resolve("bin"),
                p.resolve("jre.bundle").resolve("Contents").resolve("Home").resolve("bin"), p)) {
                Path exe = c.resolve(win ? "java.exe" : "java");
                if (Files.isRegularFile(exe)) {
                    p = exe;
                    break;
                }
            }
        }
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        if (!win) {
            return Optional.of(new Path[] {p, p});
        }
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        Path java = p.resolveSibling("java.exe");
        Path javaw = p.resolveSibling("javaw.exe");
        Path probe = name.equals("javaw.exe") && Files.isRegularFile(java) ? java : p;
        Path launch = Files.isRegularFile(javaw) ? javaw : p;
        return Optional.of(new Path[] {probe, launch});
    }

    private Optional<JavaProperties> cachedProbe(Path exe) {
        loadProbeCache();
        Path real = realPath(exe);
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(real).toMillis();
            size = Files.size(real);
        } catch (IOException e) {
            return Optional.empty();
        }
        String key = real.toString();
        CachedProbe c = probeCache.get(key);
        if (c != null && c.modified() == modified && c.size() == size) {
            return Optional.of(new JavaProperties(c.version(), c.major(), c.vendor(), c.arch()));
        }
        Optional<JavaProperties> p = prober.probe(exe);
        p.ifPresent(props -> probeCache.put(key, new CachedProbe(key, modified, size, props.version(),
            props.majorVersion(), props.vendor(), props.arch())));
        return p;
    }

    private void loadProbeCache() {
        if (probeCacheLoaded) {
            return;
        }
        synchronized (probeCache) {
            if (probeCacheLoaded) {
                return;
            }
            try {
                ProbeCacheFile f = AtomicFiles.readJson(probeCacheFile, ProbeCacheFile.class, () -> new ProbeCacheFile(List.of()))
                    .value();
                if (f.entries() != null) {
                    for (CachedProbe c : f.entries()) {
                        if (c != null && c.path() != null && c.major() > 0) {
                            probeCache.putIfAbsent(c.path(), c);
                        }
                    }
                }
            } catch (LauncherException e) {
                LOG.fine(() -> "Java probe cache unreadable: " + e.getMessage());
            }
            probeCacheLoaded = true;
        }
    }

    private void saveProbeCache() {
        try {
            List<CachedProbe> entries = new ArrayList<>(probeCache.values());
            entries.sort(Comparator.comparing(CachedProbe::path));
            AtomicFiles.writeJson(probeCacheFile, new ProbeCacheFile(entries));
        } catch (LauncherException e) {
            LOG.fine(() -> "Couldn't save the Java probe cache: " + e.getMessage());
        }
    }

    private boolean isManaged(Path exe) {
        return exe.toAbsolutePath().normalize().startsWith(runtimes);
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | SecurityException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    // ---------------------------------------------------------------- selection

    /**
     * Pick the Java to launch a version with:
     * <ol>
     *   <li>{@code overridePath} (a project's choice) when set: it must exist and be at least
     *       {@code requiredMajor}, otherwise {@link ErrorCode#JAVA_NOT_FOUND} names the version needed;</li>
     *   <li>Mojang's runtime {@code component} when {@code preferMojang} (falls through when it can't be
     *       downloaded);</li>
     *   <li>the best installed Java with exactly {@code requiredMajor} (same CPU architecture first);</li>
     *   <li>the closest newer installed Java, except for Java 8 versions (old Minecraft and its mod loaders
     *       break on 9+), which try Mojang's Java 8 first;</li>
     *   <li>Mojang's runtime for the component (or the usual component for {@code requiredMajor}).</li>
     * </ol>
     */
    public JavaInstall select(int requiredMajor, String component, String overridePath, boolean preferMojang,
                              ProgressListener progress, CancellationToken cancel) throws LauncherException {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        int required = Math.max(1, requiredMajor);
        if (overridePath != null && !overridePath.isBlank()) {
            return override(overridePath.trim(), required);
        }
        String comp = component != null && !component.isBlank() ? component : COMPONENTS.get(required);
        LauncherException mojangError = null;
        if (preferMojang && component != null && !component.isBlank()) {
            try {
                return ensureMojangRuntime(component, required, p, c);
            } catch (LauncherException e) {
                if (e.isCancelled()) {
                    throw e;
                }
                mojangError = e;
                LOG.warning("Mojang's Java runtime " + component + " is unavailable, looking for an installed Java: "
                    + e.getMessage());
            }
        }
        c.check();
        List<JavaInstall> found = detect();
        Optional<JavaInstall> exact = best(found, j -> j.majorVersion() == required);
        if (exact.isPresent()) {
            return exact.get();
        }
        Optional<JavaInstall> newer = closestNewer(found, required);
        if (newer.isPresent() && required > 8) {
            return newer.get();
        }
        if (comp != null && mojangError == null) {
            try {
                return ensureMojangRuntime(comp, required, p, c);
            } catch (LauncherException e) {
                if (e.isCancelled()) {
                    throw e;
                }
                mojangError = e;
            }
        }
        if (newer.isPresent()) {
            LOG.warning("No Java " + required + " available; using " + newer.get() + " instead");
            return newer.get();
        }
        throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, mojangError, "install.java.noneFound", required);
    }

    private JavaInstall override(String overridePath, int required) throws LauncherException {
        Path p;
        try {
            p = Path.of(overridePath);
        } catch (InvalidPathException e) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.java.overrideMissing", overridePath, required);
        }
        if (!Files.exists(p)) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.java.overrideMissing", overridePath, required);
        }
        JavaInstall j = probe(p).orElseThrow(() ->
            new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.java.overrideInvalid", overridePath, required));
        if (j.majorVersion() < required) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.java.overrideTooOld", overridePath,
                j.majorVersion(), required);
        }
        return j;
    }

    private Optional<JavaInstall> closestNewer(List<JavaInstall> found, int required) {
        int closest = found.stream().mapToInt(JavaInstall::majorVersion).filter(m -> m > required).min().orElse(-1);
        return closest < 0 ? Optional.empty() : best(found, j -> j.majorVersion() == closest);
    }

    /** native CPU architecture first, then the launcher's own runtimes, then the newest version */
    private Optional<JavaInstall> best(List<JavaInstall> found, java.util.function.Predicate<JavaInstall> filter) {
        String native_ = osArchFamily();
        Comparator<JavaInstall> order = Comparator
            .comparing((JavaInstall j) -> !JavaProperties.archFamily(j.arch()).equals(native_))
            .thenComparing(j -> !j.managed())
            .thenComparing((a, b) -> JavaProperties.compareVersions(b.version(), a.version()));
        return found.stream().filter(filter).min(order);
    }

    private String osArchFamily() {
        return switch (env.os().arch()) {
            case X64 -> "x64";
            case X86 -> "x86";
            case ARM64 -> "arm64";
            case ARM32 -> "arm32";
            default -> "other";
        };
    }

    // ---------------------------------------------------------------- Mojang runtimes

    /**
     * Download (or reuse) Mojang's runtime {@code component} for this OS into
     * {@code game/runtimes/<component>/<platform>/}. Every file is SHA-1 verified; links are created inside
     * the runtime only (copies where the OS doesn't allow links). Skipped when the installed runtime's
     * {@code .version} marker matches Mojang's current manifest; works offline with an installed runtime.
     */
    public JavaInstall ensureMojangRuntime(String component, int majorVersion, ProgressListener progress,
                                           CancellationToken cancel) throws LauncherException {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        if (component == null || !PathSafety.isSafeSegment(component)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(component));
        }
        OsInfo os = env.os();
        if (os.os() == OsInfo.Os.OTHER || (os.os() == OsInfo.Os.LINUX
            && (os.arch() == OsInfo.Arch.ARM64 || os.arch() == OsInfo.Arch.ARM32 || os.arch() == OsInfo.Arch.OTHER))) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.java.runtimeUnavailable", component,
                os.toString(), majorVersion);
        }
        ReentrantLock lock = INSTALL_LOCKS.computeIfAbsent(runtimes + "|" + component, k -> new ReentrantLock());
        try {
            while (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {
                c.check();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LauncherException.cancelled();
        }
        try {
            return installRuntime(component, majorVersion, p, c);
        } finally {
            lock.unlock();
        }
    }

    private JavaInstall installRuntime(String component, int majorVersion, ProgressListener p, CancellationToken c)
        throws LauncherException {
        String platform = env.os().javaRuntimePlatform();
        p.progress(STAGE_JAVA, component, 0, -1);
        Map<String, Map<String, List<MojangRuntimes.Entry>>> index;
        try {
            index = http.getJson(endpoints.javaRuntimes(), MojangRuntimes.INDEX, c);
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                throw e;
            }
            Optional<JavaInstall> offline = installedRuntime(component, majorVersion);
            if (offline.isPresent()) {
                LOG.info("Mojang's runtime list is unreachable; using the installed " + component);
                return offline.get();
            }
            throw e;
        }
        if (index == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "install.java.runtimeIndexInvalid");
        }
        MojangRuntimes.Entry entry = firstEntry(index, platform, component);
        String usedPlatform = platform;
        if (entry == null) {
            String fallback = switch (platform) {
                case "windows-arm64" -> "windows-x64";
                case "mac-os-arm64" -> "mac-os";
                default -> null;
            };
            if (fallback != null) {
                entry = firstEntry(index, fallback, component);
                usedPlatform = fallback;
            }
        }
        if (entry == null || entry.manifest() == null || entry.manifest().url() == null) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.java.runtimeUnavailable", component, platform,
                majorVersion);
        }
        if (!PathSafety.isSafeSegment(usedPlatform)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", usedPlatform);
        }
        Path dir = runtimes.resolve(component).resolve(usedPlatform);
        String manifestSha1 = Hashing.normalize(entry.manifest().sha1());
        String versionName = entry.version() == null ? null : entry.version().name();
        MojangRuntimes.Marker marker = readMarker(dir);
        Path[] exes = runtimeExecutables(dir, usedPlatform);
        if (marker != null && manifestSha1 != null && manifestSha1.equalsIgnoreCase(marker.manifestSha1())
            && Files.isRegularFile(exes[0])) {
            return runtimeInstall(dir, usedPlatform, versionName, majorVersion);
        }

        MojangRuntimes.Manifest manifest = fetchManifest(entry.manifest(), c);
        installFiles(manifest, dir, component, p, c);
        AtomicFiles.writeJson(dir.resolve(MARKER), new MojangRuntimes.Marker(component, usedPlatform, versionName,
            entry.version() == null ? null : entry.version().released(), manifestSha1));
        if (!Files.isRegularFile(runtimeExecutables(dir, usedPlatform)[0])) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.java.runtimeBroken", component);
        }
        LOG.info("Installed Mojang Java runtime " + component + " " + versionName + " for " + usedPlatform);
        return runtimeInstall(dir, usedPlatform, versionName, majorVersion);
    }

    private static MojangRuntimes.Entry firstEntry(Map<String, Map<String, List<MojangRuntimes.Entry>>> index,
                                                   String platform, String component) {
        Map<String, List<MojangRuntimes.Entry>> byComponent = index.get(platform);
        if (byComponent == null) {
            return null;
        }
        List<MojangRuntimes.Entry> entries = byComponent.get(component);
        if (entries == null) {
            return null;
        }
        return entries.stream().filter(e -> e != null && e.manifest() != null).findFirst().orElse(null);
    }

    /** an already installed runtime for this OS (offline use), if its marker and executable are there */
    private Optional<JavaInstall> installedRuntime(String component, int majorVersion) {
        String platform = env.os().javaRuntimePlatform();
        List<String> platforms = new ArrayList<>(List.of(platform));
        if (platform.equals("windows-arm64")) {
            platforms.add("windows-x64");
        } else if (platform.equals("mac-os-arm64")) {
            platforms.add("mac-os");
        }
        for (String pl : platforms) {
            Path dir = runtimes.resolve(component).resolve(pl);
            MojangRuntimes.Marker m = readMarker(dir);
            if (m != null && Files.isRegularFile(runtimeExecutables(dir, pl)[0])) {
                return Optional.of(runtimeInstall(dir, pl, m.name(), majorVersion));
            }
        }
        return Optional.empty();
    }

    private MojangRuntimes.Manifest fetchManifest(MojangRuntimes.Ref ref, CancellationToken c) throws LauncherException {
        URI url;
        try {
            url = URI.create(ref.url().trim());
        } catch (IllegalArgumentException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "error.badUrl", ref.url());
        }
        HttpService.Response r = http.execute(HttpService.Request.get(url), c).requireOk();
        if (HashAlgo.SHA1.isValidHex(ref.sha1()) && !Hashing.hash(r.body(), HashAlgo.SHA1).equalsIgnoreCase(ref.sha1())) {
            throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", url.getPath());
        }
        try {
            MojangRuntimes.Manifest m = Json.read(r.text(), MojangRuntimes.Manifest.class);
            if (m == null || m.files() == null || m.files().isEmpty()) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "install.java.runtimeIndexInvalid");
            }
            return m;
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "install.java.runtimeIndexInvalid");
        }
    }

    private void installFiles(MojangRuntimes.Manifest manifest, Path dir, String component, ProgressListener p,
                              CancellationToken c) throws LauncherException {
        Map<String, MojangRuntimes.FileEntry> files = new TreeMap<>(manifest.files());
        List<DownloadRequest> requests = new ArrayList<>();
        Map<Path, String> links = new LinkedHashMap<>();
        Set<String> expected = new HashSet<>();
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, MojangRuntimes.FileEntry> e : files.entrySet()) {
                MojangRuntimes.FileEntry f = e.getValue();
                if (f == null || f.type() == null) {
                    continue;
                }
                Path target = PathSafety.resolveInside(dir, e.getKey());
                expected.add(key(dir.relativize(target)));
                switch (f.type()) {
                    case "directory" -> {
                        PathSafety.requireNoLinkEscape(dir, target);
                        if (Files.isSymbolicLink(target)) {
                            Files.delete(target);
                        }
                        Files.createDirectories(target);
                    }
                    case "file" -> {
                        MojangRuntimes.Ref raw = f.downloads() == null ? null : f.downloads().get("raw");
                        if (raw == null || raw.url() == null) {
                            throw new LauncherException(ErrorCode.INVALID_DATA, "install.java.runtimeIndexInvalid");
                        }
                        PathSafety.requireNoLinkEscape(dir, target.getParent());
                        if (Files.isSymbolicLink(target)) {
                            Files.delete(target);
                        }
                        URI url;
                        try {
                            url = URI.create(raw.url().trim());
                        } catch (IllegalArgumentException ex) {
                            throw new LauncherException(ErrorCode.INVALID_DATA, ex, "error.badUrl", raw.url());
                        }
                        DownloadRequest r = DownloadRequest.sha1(url, target, HashAlgo.SHA1.isValidHex(raw.sha1())
                            ? raw.sha1() : null, raw.size() == null ? -1 : raw.size()).withLabel(e.getKey());
                        requests.add(f.isExecutable() ? r.asExecutable() : r);
                    }
                    case "link" -> {
                        if (f.target() == null || f.target().isBlank()) {
                            throw new LauncherException(ErrorCode.INVALID_DATA, "install.java.runtimeIndexInvalid");
                        }
                        links.put(target, f.target());
                    }
                    default -> LOG.fine(() -> "Ignoring runtime entry of type " + f.type() + ": " + e.getKey());
                }
            }
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        c.check();
        downloads.downloadAll(I18n.tr("install.group.java", component), requests, STAGE_JAVA, p, c);
        createLinks(dir, links);
        removeStale(dir, expected);
    }

    /** create links (relative to the link's folder), never pointing outside the runtime; copies as a fallback */
    private void createLinks(Path dir, Map<Path, String> links) throws LauncherException {
        Map<Path, Path> pending = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : links.entrySet()) {
            Path link = e.getKey();
            String rawTarget = e.getValue().replace('\\', '/');
            Path resolved;
            try {
                if (rawTarget.startsWith("/") || rawTarget.matches("^[A-Za-z]:.*")) {
                    throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", rawTarget);
                }
                resolved = link.getParent().resolve(rawTarget).normalize();
            } catch (InvalidPathException ex) {
                throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", rawTarget);
            }
            if (!resolved.startsWith(dir) || resolved.equals(dir)) {
                throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", rawTarget);
            }
            PathSafety.requireNoLinkEscape(dir, link.getParent());
            pending.put(link, resolved);
            try {
                Files.createDirectories(link.getParent());
                Path relTarget = link.getParent().relativize(resolved);
                if (Files.isSymbolicLink(link)) {
                    if (Files.readSymbolicLink(link).equals(relTarget)) {
                        pending.remove(link);
                        continue;
                    }
                    Files.delete(link);
                } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS)) {
                        throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", link.toString());
                    }
                    Files.delete(link);
                }
                Files.createSymbolicLink(link, relTarget);
                pending.remove(link);
            } catch (UnsupportedOperationException | FileSystemException e2) {
                // no symbolic links here (Windows without developer mode): copy below
            } catch (IOException e2) {
                throw LauncherException.io(e2, link.toString());
            }
        }
        // copies, in passes so a link to another (copied) link works
        for (int pass = 0; pass < 4 && !pending.isEmpty(); pass++) {
            for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Path, Path> e = it.next();
                if (Files.isRegularFile(e.getValue())) {
                    try {
                        Files.copy(e.getValue(), e.getKey(), StandardCopyOption.REPLACE_EXISTING);
                        e.getKey().toFile().setExecutable(Files.isExecutable(e.getValue()), false);
                    } catch (IOException ex) {
                        throw LauncherException.io(ex, e.getKey().toString());
                    }
                    it.remove();
                }
            }
        }
        for (Path left : pending.keySet()) {
            LOG.fine(() -> "Runtime link target missing, skipped: " + left);
        }
    }

    /** best effort: delete files of an older runtime version that the current manifest no longer lists */
    private void removeStale(Path dir, Set<String> expected) {
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> !p.equals(dir) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> !p.getFileName().toString().equals(MARKER))
                .filter(p -> !expected.contains(key(dir.relativize(p))))
                .forEach(stale::add);
        } catch (IOException | RuntimeException e) {
            return;
        }
        for (Path s : stale) {
            try {
                Files.deleteIfExists(s);
            } catch (IOException e) {
                LOG.fine(() -> "Couldn't remove old runtime file " + s);
            }
        }
    }

    private String key(Path relative) {
        String k = relative.toString().replace('\\', '/');
        return env.os().isWindows() ? k.toLowerCase(Locale.ROOT) : k;
    }

    private static MojangRuntimes.Marker readMarker(Path dir) {
        Path f = dir.resolve(MARKER);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Json.read(Files.readString(f, StandardCharsets.UTF_8), MojangRuntimes.Marker.class);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** [probe executable, launch executable] inside an installed Mojang runtime */
    private Path[] runtimeExecutables(Path dir, String platform) {
        Path bin = platform.startsWith("mac-os")
            ? dir.resolve("jre.bundle").resolve("Contents").resolve("Home").resolve("bin") : dir.resolve("bin");
        if (platform.startsWith("windows")) {
            Path java = bin.resolve("java.exe");
            Path javaw = bin.resolve("javaw.exe");
            return new Path[] {java, Files.isRegularFile(javaw) ? javaw : java};
        }
        Path java = bin.resolve("java");
        return new Path[] {java, java};
    }

    private JavaInstall runtimeInstall(Path dir, String platform, String versionName, int majorVersion) {
        Path[] exes = runtimeExecutables(dir, platform);
        Optional<JavaProperties> props = cachedProbe(exes[0]);
        saveProbeCache();
        if (props.isPresent()) {
            JavaProperties p = props.get();
            return new JavaInstall(exes[1], p.majorVersion(), p.version(), p.vendor(), p.arch(), true);
        }
        LOG.warning("Couldn't run " + exes[0] + " to check it; trusting Mojang's runtime metadata");
        String arch = platform.endsWith("arm64") ? "aarch64" : platform.endsWith("x86") || platform.endsWith("i386")
            ? "x86" : "amd64";
        int major = JavaProperties.majorOf(versionName);
        return new JavaInstall(exes[1], major > 0 ? major : majorVersion, versionName == null ? String.valueOf(majorVersion)
            : versionName, "Mojang", arch, true);
    }
}
