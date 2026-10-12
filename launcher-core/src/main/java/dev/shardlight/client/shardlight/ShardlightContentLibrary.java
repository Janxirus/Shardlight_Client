package dev.shardlight.client.shardlight;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.PathSafety;
import dev.shardlight.client.util.SafeZip;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The user's Shardlight content library: module jars and theme packs imported once and installed into any
 * number of projects. Files live under {@code <data>/content} ({@code modules/<module>/...jar},
 * {@code packs/...zip}) with an {@code index.json} listing each item's SHA-256, size, version and import
 * time.
 *
 * <p>Imports accept a Shardlight mod jar (its {@code fabric.mod.json} must name one of the four module mod
 * ids), a single theme pack (with {@code pack.mcmeta} and a Shardlight pack file name), or the "All Packs"
 * bundle (nested pack zips at any folder depth). Every archive, outer and nested, is checked with
 * {@link SafeZip} before anything is read or extracted. All methods are blocking and thread-safe.
 */
public final class ShardlightContentLibrary {
    /** GitHub's REST API */
    public static final URI DEFAULT_GITHUB_API = URI.create("https://api.github.com/");
    /** the index format this launcher writes */
    public static final int INDEX_SCHEMA_VERSION = 1;

    private static final Logger LOG = Logger.getLogger(ShardlightContentLibrary.class.getName());
    private static final long MAX_DOWNLOAD_BYTES = 128L << 20;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> KINDS = Set.of(LibraryItem.MODULE, LibraryItem.UI_PACK, LibraryItem.SOUND_PACK);

    private final AppPaths paths;
    private final HttpService http;
    private final DownloadManager downloads;
    private final ShardlightCatalog catalog;
    private final URI githubApi;
    private final ReentrantLock lock = new ReentrantLock();

    /** a library talking to the real GitHub API */
    public ShardlightContentLibrary(AppPaths paths, HttpService http, DownloadManager downloads, ShardlightCatalog catalog) {
        this(paths, http, downloads, catalog, DEFAULT_GITHUB_API);
    }

    /** a library with another GitHub API base (tests point it at a local server) */
    public ShardlightContentLibrary(AppPaths paths, HttpService http, DownloadManager downloads, ShardlightCatalog catalog,
                                    URI githubApi) {
        this.paths = Objects.requireNonNull(paths);
        this.http = http;
        this.downloads = downloads;
        this.catalog = Objects.requireNonNull(catalog);
        this.githubApi = githubApi == null ? DEFAULT_GITHUB_API : githubApi;
    }

    public ShardlightCatalog catalog() {
        return catalog;
    }

    /** the library folder, {@code <data>/content} */
    public Path root() {
        return paths.content();
    }

    // ---------------------------------------------------------------- import

    /**
     * Import a module jar, a theme pack or the All Packs bundle. Throws when the file is missing or isn't a
     * safe archive (path traversal, zip bomb...); everything else that can't be used is listed in
     * {@link ImportResult#skipped()} with a reason. Re-importing a file already in the library adds nothing.
     */
    public ImportResult importFile(Path file, CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(file, "file");
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        if (!Files.isRegularFile(file)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "shardlight.import.fileMissing", file.toString());
        }
        Path stage = newStage();
        try {
            Staging s = new Staging();
            stage(file, file.getFileName().toString(), stage, s, true, c);
            c.check();
            List<LibraryItem> added = new ArrayList<>();
            List<String> skipped = new ArrayList<>(s.skipped);
            for (Outcome o : commit(s.candidates)) {
                if (o.isNew()) {
                    added.add(o.item());
                } else {
                    skipped.add(line(o.candidate().originalName(), I18n.tr("shardlight.import.alreadyInLibrary")));
                }
            }
            LOG.info("Imported " + file.getFileName() + ": " + added.size() + " added, " + skipped.size() + " skipped");
            return new ImportResult(added, skipped);
        } finally {
            FileOps.deleteTree(stage);
        }
    }

    private void stage(Path file, String name, Path stageDir, Staging s, boolean outer, CancellationToken cancel)
        throws LauncherException {
        cancel.check();
        List<SafeZip.Entry> entries = SafeZip.list(file, SafeZip.Limits.SMALL);
        Set<String> names = new HashSet<>();
        for (SafeZip.Entry e : entries) {
            names.add(e.name());
        }
        if (names.contains(FabricModJson.ENTRY)) {
            stageJar(file, name, s);
            return;
        }
        if (names.contains("pack.mcmeta")) {
            stagePack(file, name, s);
            return;
        }
        boolean hasNested = entries.stream().anyMatch(e -> !e.directory() && isArchiveName(e.name()));
        if (!outer || !hasNested) {
            s.skipped.add(line(name, I18n.tr("shardlight.import.unrecognized")));
            return;
        }
        Path out = stageDir.resolve("bundle-" + UUID.randomUUID().toString().substring(0, 8));
        List<Path> extracted = SafeZip.extract(file, "", out, SafeZip.Limits.SMALL, ShardlightContentLibrary::isArchiveName, cancel);
        for (Path p : extracted) {
            String nestedName = out.relativize(p).toString().replace('\\', '/');
            try {
                stage(p, p.getFileName().toString(), stageDir, s, false, cancel);
            } catch (LauncherException e) {
                if (e.isCancelled()) {
                    throw e;
                }
                LOG.warning("Skipped " + nestedName + " in " + name + ": " + e.getMessage());
                s.skipped.add(line(nestedName, e.userMessage()));
            }
        }
    }

    private void stageJar(Path file, String name, Staging s) throws LauncherException {
        byte[] data = SafeZip.read(file, FabricModJson.ENTRY, 1 << 20);
        FabricModJson mod = FabricModJson.parse(data == null ? new byte[0] : data, name);
        Optional<ShardlightModule> module = catalog.moduleByModId(mod.id());
        if (module.isEmpty()) {
            s.skipped.add(line(name, I18n.tr("shardlight.import.foreignMod", mod.id())));
            return;
        }
        if (!mod.isBuilt()) {
            s.skipped.add(line(name, I18n.tr("shardlight.import.unbuiltJar", mod.version())));
            return;
        }
        String target = PathSafety.sanitizeFileName(name);
        if (!target.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            target = PathSafety.sanitizeFileName(module.get().modId() + "-" + mod.version()) + ".jar";
        }
        s.candidates.add(new Candidate(LibraryItem.MODULE, module.get().id(), mod.version(), file, name, target,
            sha256(file), size(file), mod.minecraftRanges(), false));
    }

    private void stagePack(Path file, String name, Staging s) throws LauncherException {
        byte[] meta = SafeZip.read(file, "pack.mcmeta", 256 * 1024);
        if (!isPackMeta(meta)) {
            s.skipped.add(line(name, I18n.tr("shardlight.import.badPack")));
            return;
        }
        Optional<ShardlightCatalog.ThemePackMatch> match = catalog.matchThemePack(name);
        if (match.isEmpty()) {
            s.skipped.add(line(name, I18n.tr("shardlight.import.unknownPack")));
            return;
        }
        String target = PathSafety.sanitizeFileName(name);
        if (!target.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            target = target + ".zip";
        }
        String sha = sha256(file);
        s.candidates.add(new Candidate(match.get().sounds() ? LibraryItem.SOUND_PACK : LibraryItem.UI_PACK,
            match.get().theme().id(), sha.substring(0, 8), file, name, target, sha, size(file), List.of(),
            match.get().exactName()));
    }

    private static boolean isPackMeta(byte[] meta) {
        if (meta == null) {
            return false;
        }
        try {
            String text = new String(meta, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() && e.getAsJsonObject().has("pack") && e.getAsJsonObject().get("pack").isJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return false;
        }
    }

    private static boolean isArchiveName(String entryName) {
        String n = entryName.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".jar");
    }

    // ---------------------------------------------------------------- commit

    private List<Outcome> commit(List<Candidate> candidates) throws LauncherException {
        lock.lock();
        try {
            IndexFile idx = readIndex();
            List<Outcome> out = new ArrayList<>();
            for (Candidate c : candidates) {
                out.add(commitOne(idx, c));
            }
            if (out.stream().anyMatch(Outcome::isNew)) {
                writeIndex(idx);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    private Outcome commitOne(IndexFile idx, Candidate c) throws LauncherException {
        for (IndexEntry e : idx.items) {
            if (e.sha256.equals(c.sha256()) && e.kind.equals(c.kind()) && Files.isRegularFile(resolve(e))) {
                return new Outcome(c, toItem(e), false);
            }
        }
        // a different build of the same module version, or a pack with the same file name, replaces the old one
        List<IndexEntry> replaced = new ArrayList<>();
        for (IndexEntry e : idx.items) {
            boolean sameSlot = e.kind.equals(c.kind()) && e.target.equalsIgnoreCase(c.targetId())
                && (c.kind().equals(LibraryItem.MODULE) ? ModVersions.compare(e.version, c.version()) == 0
                : e.originalName != null && e.originalName.equalsIgnoreCase(c.originalName()));
            if (sameSlot) {
                replaced.add(e);
            }
        }
        for (IndexEntry e : replaced) {
            FileOps.delete(resolve(e));
            idx.items.remove(e);
            LOG.info("Replaced library item " + e.id);
        }
        Path dir = c.kind().equals(LibraryItem.MODULE) ? root().resolve("modules").resolve(c.targetId()) : root().resolve("packs");
        Path target = PathSafety.resolveInside(dir, c.targetName());
        if (Files.exists(target)) {
            target = PathSafety.resolveInside(dir, withSuffix(c.targetName(), c.sha256().substring(0, 8)));
        }
        FileOps.copyAtomic(c.source(), target);
        // the source could have changed between hashing and copying: the stored file must be what we checked
        if (!Hashing.matches(target, HashAlgo.SHA256, c.sha256())) {
            FileOps.delete(target);
            throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "shardlight.import.changed", c.originalName());
        }
        IndexEntry e = new IndexEntry();
        e.id = c.kind() + ":" + c.targetId() + ":" + c.sha256().substring(0, 12);
        e.kind = c.kind();
        e.target = c.targetId();
        e.version = c.version();
        e.file = root().relativize(target).toString().replace('\\', '/');
        e.sha256 = c.sha256();
        e.size = c.size();
        e.importedAt = System.currentTimeMillis();
        e.originalName = c.originalName();
        e.minecraft = c.minecraftRanges().isEmpty() ? null : new ArrayList<>(c.minecraftRanges());
        e.exactName = c.exactName();
        idx.items.add(e);
        return new Outcome(c, toItem(e), true);
    }

    private static String withSuffix(String fileName, String suffix) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) + "-" + suffix + fileName.substring(dot) : fileName + "-" + suffix;
    }

    // ---------------------------------------------------------------- queries

    /** every item whose file is present, modules first, then UI packs, then sound packs */
    public List<LibraryItem> items() throws LauncherException {
        lock.lock();
        try {
            return readIndex().items.stream()
                .filter(e -> Files.isRegularFile(resolve(e)))
                .sorted(ORDER)
                .map(this::toItem)
                .toList();
        } finally {
            lock.unlock();
        }
    }

    /** one item by id */
    public Optional<LibraryItem> item(String itemId) throws LauncherException {
        return items().stream().filter(i -> i.id().equals(itemId)).findFirst();
    }

    /**
     * The newest jar of a module that accepts this Minecraft version (by the jar's own
     * {@code depends.minecraft} range); null {@code minecraftVersion} accepts any.
     */
    public Optional<LibraryItem> moduleJar(String moduleId, String minecraftVersion) throws LauncherException {
        if (moduleId == null) {
            return Optional.empty();
        }
        lock.lock();
        try {
            return readIndex().items.stream()
                .filter(e -> e.kind.equals(LibraryItem.MODULE) && e.target.equalsIgnoreCase(moduleId))
                .filter(e -> minecraftVersion == null || ModVersions.matchesAny(e.minecraft, minecraftVersion))
                .filter(e -> Files.isRegularFile(resolve(e)))
                .max(Comparator.<IndexEntry, String>comparing(e -> e.version, ModVersions::compare)
                    .thenComparingLong(e -> e.importedAt))
                .map(this::toItem);
        } finally {
            lock.unlock();
        }
    }

    /**
     * The texture pack ({@code sounds == false}) or sound pack of a theme: the one with the catalog's exact
     * file name if present, otherwise the most recently imported.
     */
    public Optional<LibraryItem> themePack(String themeId, boolean sounds) throws LauncherException {
        if (themeId == null) {
            return Optional.empty();
        }
        String kind = sounds ? LibraryItem.SOUND_PACK : LibraryItem.UI_PACK;
        lock.lock();
        try {
            return readIndex().items.stream()
                .filter(e -> e.kind.equals(kind) && e.target.equalsIgnoreCase(themeId))
                .filter(e -> Files.isRegularFile(resolve(e)))
                .max(Comparator.<IndexEntry>comparingInt(e -> e.exactName ? 1 : 0).thenComparingLong(e -> e.importedAt))
                .map(this::toItem);
        } finally {
            lock.unlock();
        }
    }

    /** delete an item's file and forget it; projects that installed a copy keep their copy */
    public void remove(String itemId) throws LauncherException {
        lock.lock();
        try {
            IndexFile idx = readIndex();
            IndexEntry found = null;
            for (IndexEntry e : idx.items) {
                if (e.id.equals(itemId)) {
                    found = e;
                }
            }
            if (found == null) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "shardlight.library.itemNotFound", String.valueOf(itemId));
            }
            FileOps.delete(resolve(found));
            idx.items.remove(found);
            writeIndex(idx);
            LOG.info("Removed library item " + itemId);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- GitHub releases

    /**
     * Download the jar of a module's latest GitHub release into the library and return it (the existing item
     * when that jar was imported before). Empty when the release has no jar asset. A missing or private
     * repository (HTTP 404) becomes {@link ErrorCode#NOT_FOUND} with the friendly
     * {@code shardlight.download.notPublished} message; the jar is validated like an import.
     */
    public Optional<LibraryItem> downloadLatestRelease(String moduleId, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        ShardlightModule module = catalog.module(moduleId)
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "shardlight.module.unknown", String.valueOf(moduleId)));
        Optional<ShardlightModule.Source> source = module.githubRelease();
        if (source.isEmpty()) {
            return Optional.empty();
        }
        if (http == null || downloads == null) {
            throw LauncherException.internal("library has no network services");
        }
        String moduleName = I18n.tr(module.nameKey());
        p.stage("shardlight.stage.checkRelease");
        URI api = HttpService.uri(githubApi, "repos/" + source.get().repo() + "/releases/latest", null);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        HttpService.Response r = http.execute(HttpService.Request.get(api).withHeaders(headers), c);
        if (r.status() == 404) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "shardlight.download.notPublished", moduleName);
        }
        if ((r.status() == 403 || r.status() == 429) && r.header("X-RateLimit-Remaining").map(String::trim).orElse("").equals("0")) {
            throw new LauncherException(ErrorCode.RATE_LIMITED, "shardlight.download.rateLimited");
        }
        r.requireOk();
        GitHubRelease release = r.json(GitHubRelease.class);
        Optional<GitHubAsset> asset = pickAsset(release, source.get().assetPattern());
        if (asset.isEmpty()) {
            LOG.info("The latest " + source.get().repo() + " release has no usable jar asset");
            return Optional.empty();
        }
        GitHubAsset a = asset.get();
        URI url;
        try {
            url = new URI(a.downloadUrl);
        } catch (URISyntaxException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "error.invalidResponse", api.getHost());
        }
        http.validate(url);
        Path stage = newStage();
        try {
            String fileName = PathSafety.sanitizeFileName(a.name);
            Path target = stage.resolve(fileName);
            DownloadRequest req = new DownloadRequest(url, List.of(), target, null, null, a.size, moduleName + " " +
                (release.tagName == null ? "" : release.tagName), false, false);
            String digest = a.digest == null ? "" : a.digest.trim().toLowerCase(Locale.ROOT);
            if (digest.startsWith("sha256:") && SHA256.matcher(digest.substring(7)).matches()) {
                req = req.withHash(HashAlgo.SHA256, digest.substring(7));
            }
            downloads.downloadAll(I18n.tr("shardlight.download.group", moduleName), List.of(req),
                "shardlight.stage.download", p, c);
            Staging s = new Staging();
            stage(target, fileName, stage, s, false, c);
            if (s.candidates.size() != 1 || !s.candidates.get(0).kind().equals(LibraryItem.MODULE)) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.download.notAModule", fileName,
                    String.join("; ", s.skipped));
            }
            Candidate cand = s.candidates.get(0);
            if (!cand.targetId().equals(module.id())) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.download.wrongModule", fileName, moduleName);
            }
            c.check();
            return Optional.of(commit(List.of(cand)).get(0).item());
        } finally {
            FileOps.deleteTree(stage);
        }
    }

    private static Optional<GitHubAsset> pickAsset(GitHubRelease release, String pattern) {
        if (release == null || release.assets == null) {
            return Optional.empty();
        }
        Pattern glob = globToRegex(pattern == null ? "*.jar" : pattern);
        return release.assets.stream()
            .filter(a -> a != null && a.name != null && a.downloadUrl != null)
            .filter(a -> glob.matcher(a.name).matches())
            .filter(a -> {
                String n = a.name.toLowerCase(Locale.ROOT);
                return !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar") && !n.endsWith("-dev.jar");
            })
            .filter(a -> a.state == null || a.state.equals("uploaded"))
            .filter(a -> a.size > 0 && a.size <= MAX_DOWNLOAD_BYTES)
            .findFirst();
    }

    static Pattern globToRegex(String glob) {
        StringBuilder b = new StringBuilder();
        for (char ch : glob.toCharArray()) {
            switch (ch) {
                case '*' -> b.append(".*");
                case '?' -> b.append('.');
                default -> b.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(b.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ---------------------------------------------------------------- index storage

    private Path indexFile() {
        return root().resolve("index.json");
    }

    private IndexFile readIndex() throws LauncherException {
        AtomicFiles.ReadResult<IndexFile> r = AtomicFiles.readJson(indexFile(), IndexFile.class, IndexFile::new);
        if (r.wasRecovered()) {
            LOG.warning("The Shardlight library index was unreadable and was set aside as " + r.recoveredFrom().getFileName());
        }
        IndexFile f = r.value();
        if (f.items == null) {
            f.items = new ArrayList<>();
        }
        f.items.removeIf(e -> !isValid(e));
        return f;
    }

    private boolean isValid(IndexEntry e) {
        if (e == null || e.id == null || e.kind == null || !KINDS.contains(e.kind) || e.target == null || e.file == null
            || e.sha256 == null || !SHA256.matcher(e.sha256).matches()) {
            return false;
        }
        String f = e.file.replace('\\', '/');
        if (!f.startsWith("modules/") && !f.startsWith("packs/")) {
            return false;
        }
        try {
            PathSafety.resolveInside(root(), f);
            return true;
        } catch (LauncherException ex) {
            LOG.warning("Ignoring an unsafe library index entry: " + e.file);
            return false;
        }
    }

    private void writeIndex(IndexFile idx) throws LauncherException {
        if (idx.schemaVersion > INDEX_SCHEMA_VERSION) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.library.newerIndex");
        }
        idx.schemaVersion = INDEX_SCHEMA_VERSION;
        FileOps.write(indexFile(), (dev.shardlight.client.util.Json.write(idx) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private Path resolve(IndexEntry e) {
        return root().resolve(e.file.replace('\\', '/')).normalize();
    }

    private LibraryItem toItem(IndexEntry e) {
        return new LibraryItem(e.id, e.kind, e.target, e.version, resolve(e), e.sha256, e.size, e.importedAt);
    }

    private static final Comparator<IndexEntry> ORDER = Comparator
        .<IndexEntry>comparingInt(e -> e.kind.equals(LibraryItem.MODULE) ? 0 : e.kind.equals(LibraryItem.UI_PACK) ? 1 : 2)
        .thenComparing(e -> e.target.toLowerCase(Locale.ROOT))
        .thenComparing((IndexEntry a, IndexEntry b) -> ModVersions.compare(b.version, a.version))
        .thenComparing(e -> e.id);

    // ---------------------------------------------------------------- staging helpers

    /** a fresh private folder under {@code content/.incoming}; stale ones from crashed runs are cleared */
    private Path newStage() throws LauncherException {
        Path incoming = root().resolve(".incoming");
        try {
            Files.createDirectories(incoming);
            Instant old = Instant.now().minus(Duration.ofDays(1));
            try (Stream<Path> s = Files.list(incoming)) {
                for (Path p : s.toList()) {
                    FileTime t = Files.getLastModifiedTime(p);
                    if (t.toInstant().isBefore(old)) {
                        FileOps.deleteTree(p);
                    }
                }
            }
            return Files.createDirectory(incoming.resolve(UUID.randomUUID().toString()));
        } catch (IOException e) {
            throw LauncherException.io(e, incoming.toString());
        }
    }

    private static String sha256(Path file) throws LauncherException {
        try {
            return Hashing.hash(file, HashAlgo.SHA256);
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
    }

    private static long size(Path file) throws LauncherException {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
    }

    private static String line(String name, String reason) {
        return I18n.tr("shardlight.import.skippedLine", name, reason);
    }

    /** files found while staging an import */
    private static final class Staging {
        final List<Candidate> candidates = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
    }

    /** one usable file found while staging */
    private record Candidate(String kind, String targetId, String version, Path source, String originalName,
                             String targetName, String sha256, long size, List<String> minecraftRanges, boolean exactName) {
    }

    private record Outcome(Candidate candidate, LibraryItem item, boolean isNew) {
    }

    /** index.json */
    private static final class IndexFile {
        int schemaVersion = INDEX_SCHEMA_VERSION;
        List<IndexEntry> items = new ArrayList<>();
    }

    /** one index.json item; {@code file} is relative to the library folder with forward slashes */
    private static final class IndexEntry {
        String id;
        String kind;
        @SerializedName("moduleOrThemeId")
        String target;
        String version;
        String file;
        String sha256;
        long size;
        long importedAt;
        String originalName;
        /** the jar's Minecraft ranges from fabric.mod.json (modules only) */
        List<String> minecraft;
        /** the pack has the catalog's own file name */
        boolean exactName;
    }

    /** GitHub's release object (only the fields used) */
    private static final class GitHubRelease {
        @SerializedName("tag_name")
        String tagName;
        List<GitHubAsset> assets;
    }

    /** GitHub's release asset object (only the fields used) */
    private static final class GitHubAsset {
        String name;
        String state;
        long size;
        @SerializedName("browser_download_url")
        String downloadUrl;
        /** "sha256:<hex>" on assets uploaded since mid-2025 */
        String digest;
    }
}
