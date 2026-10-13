package dev.shardlight.client.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.catalog.CatalogFile;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogRegistry;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.CurseForgeCatalog;
import dev.shardlight.client.catalog.JsonFields;
import dev.shardlight.client.catalog.ModrinthCatalog;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentService;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.content.ExistingFile;
import dev.shardlight.client.content.ModMetadata;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
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
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Modpacks: inspect, import and export Modrinth {@code .mrpack} files and CurseForge modpack zips, and a
 * simple creator.
 *
 * <p>Imports validate the whole archive first (paths, entry counts, sizes: {@link SafeZip}), check every
 * manifest path with {@link PathSafety}, download only from the allowed hosts (Modrinth's rule:
 * cdn.modrinth.com, github.com, raw.githubusercontent.com, gitlab.com) with SHA-1 and SHA-512 verified,
 * then apply {@code overrides/} and {@code client-overrides/}. A failed import hands the half-made project
 * to {@link ProjectFactory#discard}. CurseForge imports need the CurseForge catalog with an approved key
 * ({@code API_KEY_MISSING} otherwise) and never fetch files whose authors disallow it; those are reported.
 *
 * <p>Exports write atomically. Modrinth files become download links; local or Shardlight files are only
 * included (as overrides) when the user confirms they may redistribute them; CurseForge files are never
 * redistributed. Every method blocks; call them off the UI thread.
 */
public final class ModpackService {
    private static final Logger LOG = Logger.getLogger(ModpackService.class.getName());
    /** hosts a .mrpack may download from (Modrinth's documented allowlist) */
    public static final Set<String> DEFAULT_ALLOWED_HOSTS = Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com",
        "gitlab.com");
    static final String MRPACK_INDEX = "modrinth.index.json";
    static final String CF_MANIFEST = "manifest.json";
    private static final long MAX_MANIFEST_BYTES = 16L << 20;
    private static final long MAX_PACK_DOWNLOAD_BYTES = 64L << 30;
    private static final long MAX_ICON_BYTES = 4L << 20;
    private static final long MAX_OVERRIDE_FILE_BYTES = 256L << 20;
    static final Pattern MODRINTH_CDN = Pattern.compile("^/data/([A-Za-z0-9]{8})/versions/([A-Za-z0-9]{8})/[^/]+$");
    private static final Map<String, LoaderType> MRPACK_LOADERS = Map.of(
        "fabric-loader", LoaderType.FABRIC, "quilt-loader", LoaderType.QUILT, "forge", LoaderType.FORGE,
        "neoforge", LoaderType.NEOFORGE);

    private final AppPaths paths;
    private final ProjectAccess projects;
    private final ProjectFactory factory;
    private final ContentService content;
    private final DownloadManager downloads;
    private final CatalogRegistry catalogs;
    private final Set<String> allowedHosts;

    /** with Modrinth's download host allowlist */
    public ModpackService(AppPaths paths, ProjectAccess projects, ProjectFactory factory, ContentService content,
                          DownloadManager downloads, CatalogRegistry catalogs) {
        this(paths, projects, factory, content, downloads, catalogs, DEFAULT_ALLOWED_HOSTS);
    }

    /** with another download host allowlist (tests allow 127.0.0.1) */
    public ModpackService(AppPaths paths, ProjectAccess projects, ProjectFactory factory, ContentService content,
                          DownloadManager downloads, CatalogRegistry catalogs, Set<String> allowedHosts) {
        this.paths = Objects.requireNonNull(paths);
        this.projects = Objects.requireNonNull(projects);
        this.factory = Objects.requireNonNull(factory);
        this.content = Objects.requireNonNull(content);
        this.downloads = Objects.requireNonNull(downloads);
        this.catalogs = catalogs == null ? new CatalogRegistry() : catalogs;
        Set<String> hosts = new HashSet<>();
        for (String h : allowedHosts == null ? DEFAULT_ALLOWED_HOSTS : allowedHosts) {
            hosts.add(h.toLowerCase(Locale.ROOT));
        }
        this.allowedHosts = Set.copyOf(hosts);
    }

    /** the launcher folders */
    public AppPaths paths() {
        return paths;
    }

    // ================================================================== inspect

    /** read a modpack's manifest without importing it */
    public ModpackInfo inspect(Path file) throws LauncherException {
        Format f = detect(file);
        if (f == Format.MRPACK) {
            Mrpack m = Mrpack.parse(readEntry(file, MRPACK_INDEX));
            List<String> problems = new ArrayList<>(m.problems());
            int count = 0;
            for (Mrpack.FileRef r : m.files()) {
                if ("unsupported".equals(r.envClient())) {
                    continue;
                }
                count++;
                try {
                    PathSafety.resolveInside(Path.of("probe").toAbsolutePath(), r.path());
                } catch (LauncherException e) {
                    problems.add(I18n.tr("modpack.problem.unsafePath", r.path()));
                }
                if (allowedUrls(r.downloads()).isEmpty()) {
                    problems.add(I18n.tr("modpack.problem.noAllowedUrl", r.path()));
                }
                if (!HashAlgo.SHA1.isValidHex(r.sha1())) {
                    problems.add(I18n.tr("modpack.problem.badHash", r.path()));
                }
            }
            return new ModpackInfo(ModpackInfo.MRPACK, m.name(), m.versionId(), m.summary(), m.minecraft(), m.loader(), count, problems);
        }
        CurseManifest cm = CurseManifest.parse(readEntry(file, CF_MANIFEST));
        List<String> problems = new ArrayList<>(cm.problems());
        Optional<CatalogProvider> cf = catalogs.get(CatalogRegistry.CURSEFORGE);
        if (cf.isEmpty() || !cf.get().isAvailable()) {
            problems.add(I18n.tr("modpack.error.curseForgeKey"));
        }
        int required = (int) cm.files().stream().filter(CurseManifest.FileRef::required).count();
        return new ModpackInfo(ModpackInfo.CURSEFORGE, cm.name(), cm.version(), cm.author() == null ? "" : cm.author(),
            cm.minecraft(), cm.loader(), required, problems);
    }

    enum Format { MRPACK, CURSEFORGE }

    static Format detect(Path file) throws LauncherException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "modpack.error.fileMissing", String.valueOf(file));
        }
        Set<String> names = new HashSet<>();
        for (SafeZip.Entry e : SafeZip.list(file, SafeZip.Limits.DEFAULT)) {
            names.add(e.name());
        }
        if (names.contains(MRPACK_INDEX)) {
            return Format.MRPACK;
        }
        if (names.contains(CF_MANIFEST)) {
            byte[] b = SafeZip.read(file, CF_MANIFEST, MAX_MANIFEST_BYTES);
            try {
                JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
                if (e.isJsonObject() && "minecraftModpack".equals(JsonFields.str(e.getAsJsonObject(), "manifestType"))) {
                    return Format.CURSEFORGE;
                }
            } catch (JsonParseException | IllegalStateException ignored) {
                // not a CurseForge manifest
            }
        }
        throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "modpack.error.unknownFormat", file.getFileName().toString());
    }

    static String readEntry(Path zip, String name) throws LauncherException {
        byte[] b = SafeZip.read(zip, name, MAX_MANIFEST_BYTES);
        if (b == null) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "modpack.error.unknownFormat", zip.getFileName().toString());
        }
        String s = new String(b, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    // ================================================================== mrpack import

    /** import a .mrpack as a new project; see {@link #importMrpackDetailed} */
    public Project importMrpack(Path file, String projectName, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        return importMrpackDetailed(file, projectName, progress, cancel).project();
    }

    /**
     * Import a .mrpack as a new project: every file whose {@code env.client} isn't "unsupported" is
     * downloaded (allowed hosts only, SHA-1 and SHA-512 verified), {@code overrides/} then
     * {@code client-overrides/} are applied, and the content is recorded (Modrinth CDN files as MODRINTH
     * with their ids, others as MODPACK) and scanned.
     */
    public ImportReport importMrpackDetailed(Path file, String projectName, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        pl.stage("modpack.stage.reading");
        if (detect(file) != Format.MRPACK) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "modpack.error.unknownFormat", file.getFileName().toString());
        }
        Mrpack m = Mrpack.parse(readEntry(file, MRPACK_INDEX));
        m.requireValid();
        List<String> warnings = new ArrayList<>();
        List<Mrpack.FileRef> wanted = new ArrayList<>();
        Path probe = Path.of("probe").toAbsolutePath();
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (Mrpack.FileRef r : m.files()) {
            if ("unsupported".equals(r.envClient())) {
                warnings.add(I18n.tr("modpack.import.serverOnlySkipped", r.path()));
                continue;
            }
            PathSafety.resolveInside(probe, r.path()); // throws UNSAFE_PATH for ../, absolute, reserved names
            if (!seen.add(r.path().replace('\\', '/').toLowerCase(Locale.ROOT))) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.error.duplicatePath", r.path());
            }
            if (!HashAlgo.SHA1.isValidHex(r.sha1()) || (r.sha512() != null && !HashAlgo.SHA512.isValidHex(r.sha512()))) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.error.badHash", r.path());
            }
            if (allowedUrls(r.downloads()).isEmpty()) {
                throw new LauncherException(ErrorCode.UNSAFE_PATH, "modpack.error.noAllowedUrl", r.path());
            }
            total += Math.max(0, r.fileSize());
            wanted.add(r);
        }
        if (total > MAX_PACK_DOWNLOAD_BYTES) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.error.tooLarge", file.getFileName().toString());
        }
        String name = projectName == null || projectName.isBlank() ? m.name() : projectName.strip();
        ct.check();
        Project project = factory.create(name, m.minecraft(), m.loader());
        try {
            Path gameDir = projects.gameDir(project);
            Files.createDirectories(gameDir);
            List<DownloadRequest> requests = new ArrayList<>();
            List<Path> targets = new ArrayList<>();
            for (Mrpack.FileRef r : wanted) {
                Path target = PathSafety.resolveInside(gameDir, r.path());
                PathSafety.requireNoLinkEscape(gameDir, target.getParent());
                List<URI> urls = allowedUrls(r.downloads());
                requests.add(new DownloadRequest(urls.get(0), urls.subList(1, urls.size()), target, HashAlgo.SHA1,
                    r.sha1().toLowerCase(Locale.ROOT), r.fileSize() > 0 ? r.fileSize() : -1, target.getFileName().toString(),
                    true, false));
                targets.add(target);
            }
            pl.stage("modpack.stage.downloading");
            downloads.downloadAll(I18n.tr("modpack.download.group", name), requests, "modpack.stage.downloading", pl, ct);
            for (int i = 0; i < wanted.size(); i++) {
                String sha512 = wanted.get(i).sha512();
                if (sha512 != null && !Hashing.matches(targets.get(i), HashAlgo.SHA512, sha512)) {
                    Files.deleteIfExists(targets.get(i));
                    throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", wanted.get(i).path());
                }
            }
            ct.check();
            pl.stage("modpack.stage.overrides");
            SafeZip.extract(file, "overrides/", gameDir, SafeZip.Limits.DEFAULT, null, ct);
            SafeZip.extract(file, "client-overrides/", gameDir, SafeZip.Limits.DEFAULT, null, ct);
            pl.stage("modpack.stage.finishing");
            ModpackUpdater.baselineOf(m, file).write(projects.projectDir(project.id));
            List<ExistingFile> records = new ArrayList<>();
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                Mrpack.FileRef r = wanted.get(i);
                ContentKind kind = contentKindOf(r.path());
                if (kind == null) {
                    continue;
                }
                String fileName = r.path().replace('\\', '/').substring(r.path().replace('\\', '/').indexOf('/') + 1);
                URI url = allowedUrls(r.downloads()).get(0);
                Matcher cdn = MODRINTH_CDN.matcher(url.getPath() == null ? "" : url.getPath());
                boolean modrinth = cdn.matches();
                records.add(new ExistingFile(kind, fileName, modrinth ? ContentSource.MODRINTH : ContentSource.MODPACK,
                    modrinth ? cdn.group(1) : null, modrinth ? cdn.group(2) : null, null, null, null, url.toString()));
                indexes.add(i);
            }
            records = enrichFromModrinth(records, wanted, indexes, m, ct);
            content.recordExisting(project, records);
            content.scan(project);
            Project saved = projects.get(project.id).orElse(project);
            return new ImportReport(saved, List.of(), warnings);
        } catch (LauncherException | RuntimeException e) {
            discardQuietly(project);
            throw e;
        } catch (IOException e) {
            discardQuietly(project);
            throw LauncherException.io(e, String.valueOf(project.id));
        }
    }

    /** best effort: titles, pages and ids from Modrinth (network failures are ignored) */
    List<ExistingFile> enrichFromModrinth(List<ExistingFile> records, List<Mrpack.FileRef> wanted, List<Integer> indexes,
                                                  Mrpack m, CancellationToken ct) throws LauncherException {
        Optional<CatalogProvider> p = catalogs.get(CatalogRegistry.MODRINTH);
        if (p.isEmpty() || !(p.get() instanceof ModrinthCatalog mr) || records.isEmpty()) {
            return records;
        }
        List<ExistingFile> out = new ArrayList<>(records);
        try {
            List<String> unknownHashes = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).projectId() == null) {
                    unknownHashes.add(wanted.get(indexes.get(i)).sha1());
                }
            }
            Map<String, CatalogVersion> byHash = unknownHashes.isEmpty() ? Map.of() : mr.versionsByHash(unknownHashes, ct);
            for (int i = 0; i < out.size(); i++) {
                ExistingFile f = out.get(i);
                CatalogVersion v = f.projectId() == null ? byHash.get(wanted.get(indexes.get(i)).sha1().toLowerCase(Locale.ROOT)) : null;
                if (v != null) {
                    out.set(i, new ExistingFile(f.kind(), f.fileName(), ContentSource.MODRINTH, v.projectId(), v.id(),
                        v.versionNumber(), null, null, f.downloadUrl()));
                }
            }
            List<String> ids = out.stream().map(ExistingFile::projectId).filter(Objects::nonNull).distinct().toList();
            Map<String, CatalogProject> projectsById = ids.isEmpty() ? Map.of() : mr.projects(ids, ct);
            for (int i = 0; i < out.size(); i++) {
                ExistingFile f = out.get(i);
                CatalogProject cp = f.projectId() == null ? null : projectsById.get(f.projectId());
                if (cp != null) {
                    out.set(i, new ExistingFile(f.kind(), f.fileName(), f.source(), f.projectId(), f.versionId(), f.versionNumber(),
                        cp.title(), cp.pageUrl() == null ? null : cp.pageUrl().toString(), f.downloadUrl()));
                }
            }
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                throw e;
            }
            LOG.info("Couldn't look up modpack files on Modrinth (" + m.name() + "): " + e.getMessage());
        }
        return out;
    }

    List<URI> allowedUrls(List<String> urls) {
        List<URI> out = new ArrayList<>();
        for (String s : urls) {
            URI u = JsonFields.uri(s);
            if (u != null && u.getHost() != null && allowedHosts.contains(u.getHost().toLowerCase(Locale.ROOT))
                && u.getUserInfo() == null) {
                out.add(u);
            }
        }
        return out;
    }

    /** mods/x.jar, resourcepacks/x.zip, shaderpacks/x.zip directly in their folder */
    static ContentKind contentKindOf(String path) {
        String p = path.replace('\\', '/');
        int slash = p.indexOf('/');
        if (slash <= 0 || p.indexOf('/', slash + 1) >= 0 || slash == p.length() - 1) {
            return null;
        }
        return switch (p.substring(0, slash)) {
            case GameDirs.MODS -> ContentKind.MOD;
            case GameDirs.RESOURCE_PACKS -> ContentKind.RESOURCE_PACK;
            case GameDirs.SHADER_PACKS -> ContentKind.SHADER_PACK;
            default -> null;
        };
    }

    private void discardQuietly(Project project) {
        try {
            factory.discard(project);
        } catch (LauncherException | RuntimeException e) {
            LOG.warning("Couldn't discard the half-imported project " + project.id + ": " + e.getMessage());
        }
    }

    // ================================================================== CurseForge import

    /** import a CurseForge modpack zip as a new project; see {@link #importCurseForgeDetailed} */
    public Project importCurseForge(Path zip, String projectName, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        return importCurseForgeDetailed(zip, projectName, progress, cancel).project();
    }

    /**
     * Import a CurseForge modpack zip: the files of {@code manifest.json} are resolved through the CurseForge
     * catalog ({@code API_KEY_MISSING} when it isn't available), downloadable ones fetched (SHA-1 and size
     * verified), files whose authors disallow third-party downloads reported in
     * {@link ImportReport#restricted()}, and the overrides folder applied.
     */
    public ImportReport importCurseForgeDetailed(Path zip, String projectName, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        pl.stage("modpack.stage.reading");
        if (detect(zip) != Format.CURSEFORGE) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "modpack.error.unknownFormat", zip.getFileName().toString());
        }
        CurseManifest cm = CurseManifest.parse(readEntry(zip, CF_MANIFEST));
        cm.requireValid();
        CatalogProvider provider = catalogs.get(CatalogRegistry.CURSEFORGE).filter(CatalogProvider::isAvailable)
            .orElseThrow(() -> new LauncherException(ErrorCode.API_KEY_MISSING, "modpack.error.curseForgeKey"));
        List<String> warnings = new ArrayList<>();
        List<CurseManifest.FileRef> wanted = new ArrayList<>();
        for (CurseManifest.FileRef f : cm.files()) {
            if (!f.required()) {
                warnings.add(I18n.tr("modpack.import.optionalSkipped", f.projectId(), f.fileId()));
            } else {
                wanted.add(f);
            }
        }
        pl.stage("modpack.stage.resolving");
        Map<String, CatalogVersion> files = new LinkedHashMap<>();
        Map<String, CatalogProject> mods = new LinkedHashMap<>();
        if (provider instanceof CurseForgeCatalog cf) {
            files.putAll(cf.filesById(wanted.stream().map(CurseManifest.FileRef::fileId).toList(), ct));
            mods.putAll(cf.projectsById(wanted.stream().map(CurseManifest.FileRef::projectId).toList(), ct));
        } else {
            for (CurseManifest.FileRef f : wanted) {
                ct.check();
                files.put(String.valueOf(f.fileId()), provider.version(String.valueOf(f.fileId()), ct));
                mods.put(String.valueOf(f.projectId()), provider.project(String.valueOf(f.projectId()), ct));
            }
        }
        String name = projectName == null || projectName.isBlank() ? cm.name() : projectName.strip();
        ct.check();
        Project project = factory.create(name, cm.minecraft(), cm.loader());
        try {
            Path gameDir = projects.gameDir(project);
            Files.createDirectories(gameDir);
            List<RestrictedFile> restricted = new ArrayList<>();
            List<DownloadRequest> requests = new ArrayList<>();
            List<ExistingFile> records = new ArrayList<>();
            Set<String> targetsSeen = new HashSet<>();
            for (CurseManifest.FileRef ref : wanted) {
                CatalogVersion v = files.get(String.valueOf(ref.fileId()));
                CatalogProject mod = mods.get(String.valueOf(ref.projectId()));
                if (v == null || v.primaryFile().isEmpty()) {
                    warnings.add(I18n.tr("modpack.import.fileNotFound", ref.projectId(), ref.fileId()));
                    continue;
                }
                CatalogFile f = v.primaryFile().get();
                ContentKind kind = mod == null || mod.kind() == null ? ContentKind.MOD : mod.kind();
                String title = mod != null ? mod.title() : v.name();
                if (kind.folder() == null || kind == ContentKind.DATAPACK) {
                    warnings.add(I18n.tr("modpack.import.kindSkipped", title));
                    continue;
                }
                String fileName = PathSafety.sanitizeFileName(f.fileName());
                URI page = mod == null ? null : mod.pageUrl();
                if (!f.downloadable()) {
                    restricted.add(new RestrictedFile(title, fileName, page, kind.folder(), String.valueOf(ref.projectId()),
                        String.valueOf(ref.fileId())));
                    continue;
                }
                Path target = PathSafety.resolveInside(gameDir.resolve(kind.folder()), fileName);
                if (!targetsSeen.add(target.toString().toLowerCase(Locale.ROOT))) {
                    warnings.add(I18n.tr("modpack.import.duplicateFile", fileName));
                    continue;
                }
                PathSafety.requireNoLinkEscape(gameDir, target.getParent());
                String sha1 = f.sha1() != null && HashAlgo.SHA1.isValidHex(f.sha1().trim()) ? f.sha1().trim().toLowerCase(Locale.ROOT) : null;
                requests.add(new DownloadRequest(f.url(), List.of(), target, sha1 == null ? null : HashAlgo.SHA1, sha1,
                    f.size() > 0 ? f.size() : -1, title, sha1 != null, false));
                records.add(new ExistingFile(kind, fileName, ContentSource.CURSEFORGE, String.valueOf(ref.projectId()),
                    String.valueOf(ref.fileId()), v.versionNumber(), title, page == null ? null : page.toString(), f.url().toString()));
            }
            pl.stage("modpack.stage.downloading");
            downloads.downloadAll(I18n.tr("modpack.download.group", name), requests, "modpack.stage.downloading", pl, ct);
            ct.check();
            pl.stage("modpack.stage.overrides");
            SafeZip.extract(zip, cm.overrides() + "/", gameDir, SafeZip.Limits.DEFAULT, null, ct);
            pl.stage("modpack.stage.finishing");
            recordCurseBaseline(project, cm, zip, records);
            content.recordExisting(project, records);
            content.scan(project);
            Project saved = projects.get(project.id).orElse(project);
            return new ImportReport(saved, restricted, warnings);
        } catch (LauncherException | RuntimeException e) {
            discardQuietly(project);
            throw e;
        } catch (IOException e) {
            discardQuietly(project);
            throw LauncherException.io(e, String.valueOf(project.id));
        }
    }

    /** remember the files as the pack shipped them (CurseForge packs can't be updated in place yet, but the original is kept) */
    private void recordCurseBaseline(Project project, CurseManifest cm, Path zip, List<ExistingFile> records) throws LauncherException {
        Path gameDir = projects.gameDir(project);
        PackBaseline b = new PackBaseline();
        b.format = "curseforge";
        b.packName = cm.name();
        b.packVersion = cm.version();
        b.minecraft = cm.minecraft();
        b.loader = cm.loader();
        for (ExistingFile f : records) {
            Path file = gameDir.resolve(f.kind().folder()).resolve(f.fileName());
            if (Files.isRegularFile(file)) {
                try {
                    b.files.put(f.kind().folder() + "/" + f.fileName(), Hashing.hash(file, HashAlgo.SHA1));
                } catch (IOException e) {
                    throw LauncherException.io(e, file.toString());
                }
            }
        }
        b.files.putAll(ModpackUpdater.overrideHashes(zip, cm.overrides() + "/"));
        b.write(projects.projectDir(project.id));
    }

    // ================================================================== mrpack export

    /** export a project as .mrpack; see {@link #exportMrpackDetailed} */
    public Path exportMrpack(Project project, MrpackExportOptions opts, Path out) throws LauncherException {
        return exportMrpackDetailed(project, opts, out).file();
    }

    /**
     * Export a project as a Modrinth .mrpack: enabled Modrinth files become {@code files[]} entries (hashes,
     * size, env from the mod's own metadata, the CDN download URL); local, Shardlight and other non-Modrinth
     * files go into {@code overrides/} only when {@link MrpackExportOptions#includeLocalModsAsOverrides()};
     * CurseForge files are never redistributed (a warning says so). Written atomically.
     */
    public ExportReport exportMrpackDetailed(Project project, MrpackExportOptions opts, Path out) throws LauncherException {
        return writeMrpack(project, opts, out, null);
    }

    private ExportReport writeMrpack(Project project, MrpackExportOptions opts, Path out, Path icon) throws LauncherException {
        Objects.requireNonNull(opts, "opts");
        requireOut(out);
        List<ContentEntry> entries = content.scan(project);
        Project p = projects.get(project.id).orElse(project);
        Path gameDir = projects.gameDir(p);
        List<String> warnings = new ArrayList<>();
        JsonArray files = new JsonArray();
        Map<String, Path> overrides = new LinkedHashMap<>();
        for (ContentEntry e : entries) {
            if (e.kind == null || e.kind.folder() == null || e.kind == ContentKind.DATAPACK) {
                continue;
            }
            if ((e.kind == ContentKind.RESOURCE_PACK && !opts.includeResourcePacks())
                || (e.kind == ContentKind.SHADER_PACK && !opts.includeShaderPacks())) {
                continue;
            }
            if (!e.enabled || e.missing || e.fileName == null || !PathSafety.isSafeFileName(e.fileName)) {
                if (!e.enabled) {
                    warnings.add(I18n.tr("modpack.export.disabledSkipped", title(e)));
                }
                continue;
            }
            Path file = PathSafety.resolveInside(gameDir.resolve(e.kind.folder()), e.fileName);
            String zipPath = e.kind.folder() + "/" + e.fileName;
            if (e.source == ContentSource.CURSEFORGE) {
                warnings.add(I18n.tr("modpack.export.curseForgeSkipped", title(e)));
                continue;
            }
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                String[] h = hashes(file);
                URI url = linkFor(e, h[0]);
                if (url != null) {
                    JsonObject f = new JsonObject();
                    f.addProperty("path", zipPath);
                    JsonObject hashes = new JsonObject();
                    hashes.addProperty("sha1", h[0]);
                    hashes.addProperty("sha512", h[1]);
                    f.add("hashes", hashes);
                    f.add("env", env(e, file));
                    JsonArray dl = new JsonArray();
                    dl.add(url.toString());
                    f.add("downloads", dl);
                    f.addProperty("fileSize", sizeOf(file));
                    files.add(f);
                    continue;
                }
            }
            if (opts.includeLocalModsAsOverrides()) {
                addTree(overrides, "overrides/" + zipPath, file, warnings);
            } else {
                warnings.add(I18n.tr("modpack.export.localSkipped", title(e)));
            }
        }
        if (opts.includeConfig()) {
            addTree(overrides, "overrides/" + GameDirs.CONFIG, gameDir.resolve(GameDirs.CONFIG), warnings);
        }
        if (icon != null) {
            overrides.put("overrides/icon.png", icon);
            warnings.add(I18n.tr("modpack.export.iconAsOverride"));
        }
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", blankTo(opts.version(), "1.0.0"));
        index.addProperty("name", blankTo(opts.name(), p.name));
        if (opts.summary() != null && !opts.summary().isBlank()) {
            index.addProperty("summary", opts.summary().strip());
        }
        index.add("files", files);
        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", p.minecraftVersion);
        if (p.loader != null && !p.loader.isVanilla() && p.loader.version != null) {
            String key = switch (p.loader.type) {
                case FABRIC -> "fabric-loader";
                case QUILT -> "quilt-loader";
                case FORGE -> "forge";
                case NEOFORGE -> "neoforge";
                default -> null;
            };
            if (key != null) {
                deps.addProperty(key, p.loader.version);
            }
        }
        index.add("dependencies", deps);
        Map<String, byte[]> generated = new LinkedHashMap<>();
        generated.put(MRPACK_INDEX, (Json.GSON.toJson(index) + "\n").getBytes(StandardCharsets.UTF_8));
        writeZip(out, generated, overrides);
        return new ExportReport(out, warnings);
    }

    /** the Modrinth link for an entry whose file is unchanged, or null */
    private URI linkFor(ContentEntry e, String sha1) {
        if (e.sha1 == null || !e.sha1.equalsIgnoreCase(sha1)) {
            return null;
        }
        URI stored = JsonFields.uri(e.downloadUrl);
        boolean storedAllowed = stored != null && stored.getHost() != null && allowedHosts.contains(stored.getHost().toLowerCase(Locale.ROOT));
        if (e.source == ContentSource.MODRINTH) {
            if (storedAllowed) {
                return stored;
            }
            if (e.projectId != null && e.versionId != null && e.projectId.matches("[A-Za-z0-9]{1,64}")
                && e.versionId.matches("[A-Za-z0-9]{1,64}")) {
                return URI.create("https://cdn.modrinth.com/data/" + e.projectId + "/versions/" + e.versionId + "/"
                    + HttpService.encodePath(e.fileName));
            }
            return null;
        }
        // a modpack file that came from an allowed host keeps its link
        return e.source == ContentSource.MODPACK && storedAllowed ? stored : null;
    }

    private JsonObject env(ContentEntry e, Path file) {
        String client = "required";
        String server = "required";
        if (e.kind != ContentKind.MOD) {
            server = "unsupported";
        } else {
            String environment = content.metadataReader().read(file).map(ModMetadata::environment).orElse("*");
            if ("client".equals(environment)) {
                server = "unsupported";
            } else if ("server".equals(environment)) {
                client = "unsupported";
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("client", client);
        o.addProperty("server", server);
        return o;
    }

    // ================================================================== CurseForge export

    /** export a CurseForge manifest zip; see {@link #exportCurseForgeDetailed} */
    public Path exportCurseForge(Project project, String name, String version, Path out) throws LauncherException {
        return exportCurseForgeDetailed(project, name, version, out).file();
    }

    /**
     * Export a CurseForge modpack zip: {@code manifest.json} lists the enabled CurseForge-sourced entries
     * only (other content can't be referenced and is listed as a warning), {@code config/} goes into the
     * overrides, plus a {@code modlist.html}. Written atomically.
     */
    public ExportReport exportCurseForgeDetailed(Project project, String name, String version, Path out) throws LauncherException {
        return writeCurseForge(project, name, version, out, null);
    }

    private ExportReport writeCurseForge(Project project, String name, String version, Path out, Path icon) throws LauncherException {
        requireOut(out);
        List<ContentEntry> entries = content.scan(project);
        Project p = projects.get(project.id).orElse(project);
        Path gameDir = projects.gameDir(p);
        List<String> warnings = new ArrayList<>();
        JsonArray files = new JsonArray();
        StringBuilder modlist = new StringBuilder("<ul>\n");
        for (ContentEntry e : entries) {
            if (!e.enabled || e.missing || e.kind == null || e.kind.folder() == null) {
                continue;
            }
            long projectId = parseId(e.projectId);
            long fileId = parseId(e.versionId);
            if (e.source != ContentSource.CURSEFORGE || projectId <= 0 || fileId <= 0) {
                warnings.add(I18n.tr("modpack.export.notOnCurseForge", title(e)));
                continue;
            }
            JsonObject f = new JsonObject();
            f.addProperty("projectID", projectId);
            f.addProperty("fileID", fileId);
            f.addProperty("required", true);
            files.add(f);
            String href = e.pageUrl == null ? null : JsonFields.uri(e.pageUrl) == null ? null : e.pageUrl;
            modlist.append("<li>").append(href == null ? "" : "<a href=\"" + html(href) + "\">").append(html(title(e)))
                .append(href == null ? "" : "</a>").append("</li>\n");
        }
        modlist.append("</ul>\n");
        JsonObject mc = new JsonObject();
        mc.addProperty("version", p.minecraftVersion);
        JsonArray loaders = new JsonArray();
        if (p.loader != null && !p.loader.isVanilla() && p.loader.version != null && p.loader.type.catalogId() != null) {
            JsonObject l = new JsonObject();
            l.addProperty("id", p.loader.type.catalogId() + "-" + p.loader.version);
            l.addProperty("primary", true);
            loaders.add(l);
        }
        mc.add("modLoaders", loaders);
        JsonObject manifest = new JsonObject();
        manifest.add("minecraft", mc);
        manifest.addProperty("manifestType", "minecraftModpack");
        manifest.addProperty("manifestVersion", 1);
        manifest.addProperty("name", blankTo(name, p.name));
        manifest.addProperty("version", blankTo(version, "1.0.0"));
        manifest.addProperty("author", "");
        manifest.add("files", files);
        manifest.addProperty("overrides", "overrides");
        Map<String, Path> overrides = new LinkedHashMap<>();
        addTree(overrides, "overrides/" + GameDirs.CONFIG, gameDir.resolve(GameDirs.CONFIG), warnings);
        if (icon != null) {
            overrides.put("overrides/icon.png", icon);
            warnings.add(I18n.tr("modpack.export.iconAsOverride"));
        }
        Map<String, byte[]> generated = new LinkedHashMap<>();
        generated.put(CF_MANIFEST, (Json.GSON.toJson(manifest) + "\n").getBytes(StandardCharsets.UTF_8));
        generated.put("modlist.html", modlist.toString().getBytes(StandardCharsets.UTF_8));
        writeZip(out, generated, overrides);
        return new ExportReport(out, warnings);
    }

    // ================================================================== creator

    /** create a modpack from a project in the chosen format; see {@link #createModpackDetailed} */
    public Path createModpack(Project project, ModpackCreatorOptions opts) throws LauncherException {
        return createModpackDetailed(project, opts).file();
    }

    /**
     * The modpack creator: title, version, summary and an optional PNG icon in either format. Neither
     * .mrpack nor the CurseForge manifest has an icon field, so the icon is stored as
     * {@code overrides/icon.png} (and the report says so); the CurseForge format has no summary field.
     */
    public ExportReport createModpackDetailed(Project project, ModpackCreatorOptions opts) throws LauncherException {
        Objects.requireNonNull(opts, "opts");
        if (opts.title() == null || opts.title().isBlank()) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.error.titleMissing");
        }
        Path icon = opts.icon();
        if (icon != null) {
            requirePng(icon);
        }
        ModpackCreatorOptions.Format format = opts.format() == null ? ModpackCreatorOptions.Format.MRPACK : opts.format();
        if (format == ModpackCreatorOptions.Format.CURSEFORGE) {
            return writeCurseForge(project, opts.title(), opts.version(), opts.out(), icon);
        }
        MrpackExportOptions m = new MrpackExportOptions(opts.title(), opts.version(), opts.summary(), opts.includeConfig(),
            opts.includeResourcePacks(), opts.includeShaderPacks(), opts.includeLocalModsAsOverrides());
        return writeMrpack(project, m, opts.out(), icon);
    }

    private static void requirePng(Path icon) throws LauncherException {
        try {
            if (!Files.isRegularFile(icon) || Files.size(icon) > MAX_ICON_BYTES) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.error.badIcon", icon.getFileName().toString());
            }
            byte[] sig = new byte[8];
            try (InputStream in = Files.newInputStream(icon)) {
                if (in.readNBytes(sig, 0, 8) != 8 || (sig[0] & 0xff) != 0x89 || sig[1] != 'P' || sig[2] != 'N' || sig[3] != 'G') {
                    throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.error.badIcon", icon.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw LauncherException.io(e, icon.toString());
        }
    }

    // ================================================================== zip writing

    /** add a file, or every regular file under a folder (links skipped), as zip entries under {@code prefix} */
    private static void addTree(Map<String, Path> out, String prefix, Path root, List<String> warnings) throws LauncherException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return;
        }
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            out.putIfAbsent(prefix, root);
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE; // links and devices are never packed
                    }
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (attrs.size() > MAX_OVERRIDE_FILE_BYTES) {
                        warnings.add(I18n.tr("modpack.export.fileTooLarge", rel));
                        return FileVisitResult.CONTINUE;
                    }
                    out.putIfAbsent(prefix + "/" + rel, file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw LauncherException.io(e, root.toString());
        }
    }

    /** write the zip to a temporary file next to {@code out}, then move it over {@code out} */
    private static void writeZip(Path out, Map<String, byte[]> generated, Map<String, Path> files) throws LauncherException {
        Path target = out.toAbsolutePath().normalize();
        Path tmp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Set<String> names = new LinkedHashSet<>();
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, byte[]> g : generated.entrySet()) {
                    if (names.add(g.getKey().toLowerCase(Locale.ROOT))) {
                        zip.putNextEntry(new ZipEntry(g.getKey()));
                        zip.write(g.getValue());
                        zip.closeEntry();
                    }
                }
                for (Map.Entry<String, Path> f : files.entrySet()) {
                    if (!names.add(f.getKey().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(f.getKey()));
                    Files.copy(f.getValue(), zip);
                    zip.closeEntry();
                }
            }
            try (var ch = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            AtomicFiles.moveReplacing(tmp, target);
        } catch (IOException e) {
            throw LauncherException.io(e, target.toString());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // ================================================================== helpers

    private static void requireOut(Path out) throws LauncherException {
        if (out == null || out.getFileName() == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.error.noOutput");
        }
    }

    private static String[] hashes(Path file) throws LauncherException {
        try {
            return new String[] {Hashing.hash(file, HashAlgo.SHA1), Hashing.hash(file, HashAlgo.SHA512)};
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
    }

    private static long sizeOf(Path p) throws LauncherException {
        try {
            return Files.size(p);
        } catch (IOException e) {
            throw LauncherException.io(e, p.toString());
        }
    }

    private static long parseId(String s) {
        if (s == null || s.isBlank() || s.length() > 18 || !s.trim().chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Long.parseLong(s.trim());
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? (def == null ? "" : def) : s.strip();
    }

    private static String title(ContentEntry e) {
        return e.title != null && !e.title.isBlank() ? e.title : String.valueOf(e.fileName);
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** for diagnostics */
    @Override
    public String toString() {
        return "ModpackService{allowedHosts=" + allowedHosts + "}";
    }

    /** loader spec from a manifest loader id such as "fabric-0.19.5" or "neoforge-21.1.80" */
    static LoaderSpec curseLoader(String id) {
        if (id == null || id.isBlank()) {
            return LoaderSpec.vanilla();
        }
        String s = id.trim();
        int dash = s.indexOf('-');
        String type = dash < 0 ? s : s.substring(0, dash);
        String version = dash < 0 ? null : s.substring(dash + 1);
        try {
            LoaderType t = LoaderType.parse(type);
            return t == LoaderType.VANILLA ? LoaderSpec.vanilla() : new LoaderSpec(t, version);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Map<String, LoaderType> mrpackLoaders() {
        return MRPACK_LOADERS;
    }
}
