package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.model.AssetIndexInfo;
import dev.shardlight.client.minecraft.model.DownloadInfo;
import dev.shardlight.client.minecraft.model.Library;
import dev.shardlight.client.minecraft.model.LoggingConfig;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.OsInfo;
import dev.shardlight.client.util.PathSafety;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Turns a {@link ResolvedVersion} into an {@link InstallPlan} for one OS: which libraries apply (rules),
 * where every file goes, where to fetch it and how to check it.
 *
 * <p>Libraries: {@code downloads.artifact} when present; otherwise the Maven path of the name under the
 * library's {@code url} (Fabric, Quilt) or Mojang's library repository. Legacy natives
 * ({@code natives: {os: classifier}}) are separate jars that are only extracted; modern native jars
 * ({@code ...:natives-windows}) are on the classpath and extracted as well.
 */
final class InstallPlanner {
    private static final Logger LOG = Logger.getLogger(InstallPlanner.class.getName());
    static final String META_INF = "META-INF/";

    private final AppPaths paths;
    private final Endpoints endpoints;
    private final OsInfo os;
    private final VersionResolver resolver;

    InstallPlanner(AppPaths paths, Endpoints endpoints, OsInfo os) {
        this.paths = paths;
        this.endpoints = endpoints;
        this.os = os;
        this.resolver = new VersionResolver(paths.versions());
    }

    InstallPlan plan(ResolvedVersion v) throws LauncherException {
        if (v.mainClass() == null) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "install.error.noMainClass", v.id());
        }
        PlannedFile[] client = planClient(v);

        Map<Path, PlannedFile> files = new LinkedHashMap<>();
        Map<String, Path> classpathByKey = new LinkedHashMap<>();
        Set<String> classpathPaths = new HashSet<>();
        List<Path> classpath = new ArrayList<>();
        Set<Path> nativeJars = new LinkedHashSet<>();
        Set<String> excludes = new LinkedHashSet<>();
        excludes.add(META_INF);

        for (Library lib : v.libraries()) {
            if (!Rules.allows(lib.rules(), os)) {
                continue;
            }
            MavenCoordinate coord = lib.name() == null ? null : MavenCoordinate.parse(lib.name());
            DownloadInfo artifact = lib.downloads() == null ? null : lib.downloads().artifact();
            if (lib.hasNatives()) {
                String classifier = lib.natives().get(os.mojangName());
                if (classifier != null && !classifier.isBlank()) {
                    classifier = classifier.replace("${arch}", os.bitness());
                    DownloadInfo info = lib.downloads() == null ? null : lib.downloads().classifiers().get(classifier);
                    PlannedFile f = libraryFile(info, coord == null ? null : coord.withClassifier(classifier), lib, false);
                    if (f != null) {
                        files.putIfAbsent(f.path(), f);
                        nativeJars.add(f.path());
                        excludes.addAll(lib.extractExcludes());
                    }
                }
                if (artifact != null) {
                    addClasspath(libraryFile(artifact, coord, lib, true), coord, files, classpathByKey, classpathPaths, classpath);
                }
                continue;
            }
            boolean classifiersOnly = lib.downloads() != null && artifact == null && !lib.downloads().classifiers().isEmpty();
            if (classifiersOnly) {
                continue;
            }
            PlannedFile f = libraryFile(artifact, coord, lib, true);
            if (f == null) {
                continue;
            }
            boolean added = addClasspath(f, coord, files, classpathByKey, classpathPaths, classpath);
            if (added && coord != null && coord.isNativesClassifier()) {
                nativeJars.add(f.path());
                excludes.addAll(lib.extractExcludes());
            }
        }
        classpath.add(client[0].path());

        PlannedFile assetIndex = planAssetIndex(v);
        PlannedFile logging = null;
        String loggingArgument = null;
        LoggingConfig lc = v.logging() == null ? null : v.logging().client();
        if (lc != null && lc.file() != null && lc.file().id() != null && lc.argument() != null) {
            if (!PathSafety.isSafeFileName(lc.file().id())) {
                throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", lc.file().id());
            }
            Path target = paths.assets().resolve("log_configs").resolve(lc.file().id());
            logging = new PlannedFile(target, url(lc.file().url()), sha1Algo(lc.file().sha1()), sha1(lc.file().sha1()),
                size(lc.file().size()), lc.file().id());
            loggingArgument = lc.argument().replace("${path}", target.toString());
        }
        return new InstallPlan(v, client[0], client[1], List.copyOf(files.values()), List.copyOf(classpath),
            List.copyOf(nativeJars), List.copyOf(excludes), assetIndex, v.assetIndexId(), logging, loggingArgument);
    }

    /** [classpath jar, source jar or null] */
    private PlannedFile[] planClient(ResolvedVersion v) throws LauncherException {
        Path target = resolver.jarPath(v.jarId());
        DownloadInfo info = v.clientDownload();
        if (info != null && info.hasUrl()) {
            Path ownerJar = resolver.jarPath(v.clientOwnerId());
            PlannedFile source = new PlannedFile(ownerJar, url(info.url()), sha1Algo(info.sha1()), sha1(info.sha1()),
                info.sizeOrUnknown(), "Minecraft " + v.clientOwnerId());
            if (sameFile(ownerJar, target)) {
                return new PlannedFile[] {source, null};
            }
            PlannedFile local = new PlannedFile(target, null, source.algo(), source.hash(), source.size(), v.jarId() + ".jar");
            return new PlannedFile[] {local, source};
        }
        if (Files.isRegularFile(target)) {
            return new PlannedFile[] {new PlannedFile(target, null, info == null ? null : sha1Algo(info.sha1()),
                info == null ? null : sha1(info.sha1()), info == null ? -1 : info.sizeOrUnknown(), v.jarId() + ".jar"), null};
        }
        throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "install.error.noClientJar", v.id());
    }

    private PlannedFile planAssetIndex(ResolvedVersion v) throws LauncherException {
        String id = v.assetIndexId();
        if (!PathSafety.isSafeSegment(id + ".json")) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", id);
        }
        Path target = paths.assets().resolve("indexes").resolve(id + ".json");
        AssetIndexInfo ai = v.assetIndex();
        if (ai != null && ai.url() != null && !ai.url().isBlank()) {
            return new PlannedFile(target, url(ai.url()), sha1Algo(ai.sha1()), sha1(ai.sha1()), size(ai.size()), id + ".json");
        }
        if (Files.isRegularFile(target)) {
            return new PlannedFile(target, null, null, null, -1, id + ".json");
        }
        LOG.warning("Version " + v.id() + " names no downloadable asset index; assets are skipped");
        return null;
    }

    private boolean addClasspath(PlannedFile f, MavenCoordinate coord, Map<Path, PlannedFile> files,
                                 Map<String, Path> byKey, Set<String> seenPaths, List<Path> classpath) {
        if (f == null) {
            return false;
        }
        String key = coord == null ? f.path().toString() : coord.key();
        String pathKey = os.isWindows() ? f.path().toString().toLowerCase(Locale.ROOT) : f.path().toString();
        if (byKey.containsKey(key) || !seenPaths.add(pathKey)) {
            return false;
        }
        byKey.put(key, f.path());
        files.putIfAbsent(f.path(), f);
        classpath.add(f.path());
        return true;
    }

    /**
     * The file for a library artifact or native classifier.
     *
     * @param info          the explicit download entry, or null
     * @param coord         the Maven coordinate (with the classifier for natives), or null
     * @param topLevelHash  true to use the library's top-level hashes and size when {@code info} has none
     */
    private PlannedFile libraryFile(DownloadInfo info, MavenCoordinate coord, Library lib, boolean topLevelHash)
        throws LauncherException {
        String rel = info != null && info.path() != null && !info.path().isBlank() ? info.path()
            : coord != null ? coord.path() : null;
        if (rel == null) {
            LOG.warning("Skipping a library without a name or path: " + lib);
            return null;
        }
        Path target = PathSafety.resolveInside(paths.libraries(), rel);
        String label = coord != null ? coord.toString() : rel;
        URI url;
        if (info != null) {
            url = info.hasUrl() ? url(info.url()) : null;
        } else {
            String base = lib.url() != null && !lib.url().isBlank() ? lib.url().trim() : endpoints.libraries().toString();
            url = resolveUrl(url(base.endsWith("/") ? base : base + "/"), rel);
        }
        HashAlgo algo = null;
        String hash = null;
        long size = -1;
        if (info != null) {
            algo = sha1Algo(info.sha1());
            hash = sha1(info.sha1());
            size = info.sizeOrUnknown();
        }
        if (hash == null && topLevelHash) {
            if (HashAlgo.SHA1.isValidHex(lib.sha1())) {
                algo = HashAlgo.SHA1;
                hash = lib.sha1().toLowerCase(Locale.ROOT);
            } else if (HashAlgo.SHA512.isValidHex(lib.sha512())) {
                algo = HashAlgo.SHA512;
                hash = lib.sha512().toLowerCase(Locale.ROOT);
            } else if (HashAlgo.SHA256.isValidHex(lib.sha256())) {
                algo = HashAlgo.SHA256;
                hash = lib.sha256().toLowerCase(Locale.ROOT);
            }
        }
        if (size < 0 && topLevelHash && info == null) {
            size = size(lib.size());
        }
        return new PlannedFile(target, url, algo, hash, size, label);
    }

    /** parse a URL from metadata; plain http to a public host is upgraded to https */
    static URI url(String s) throws LauncherException {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(s.trim());
            if ("http".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !isLoopback(u.getHost())) {
                u = URI.create("https" + s.trim().substring(4));
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "error.badUrl", s);
        }
    }

    /** {@code base} + a relative repository path, percent-encoding anything unusual in the path */
    static URI resolveUrl(URI base, String relativePath) throws LauncherException {
        StringBuilder b = new StringBuilder();
        for (String seg : relativePath.replace('\\', '/').split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append('/');
            }
            for (byte c : seg.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                int ch = c & 0xff;
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || "-._~+!$&'()*,;=@".indexOf(ch) >= 0) {
                    b.append((char) ch);
                } else {
                    b.append('%').append(String.format(Locale.ROOT, "%02X", ch));
                }
            }
        }
        try {
            return HttpService.uri(base, b.toString(), null);
        } catch (IllegalArgumentException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "error.badUrl", base + relativePath);
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
    }

    static HashAlgo sha1Algo(String sha1) {
        return HashAlgo.SHA1.isValidHex(sha1) ? HashAlgo.SHA1 : null;
    }

    static String sha1(String sha1) {
        return HashAlgo.SHA1.isValidHex(sha1) ? sha1.toLowerCase(Locale.ROOT) : null;
    }

    static long size(Long size) {
        return size == null || size <= 0 ? -1 : size;
    }

    private boolean sameFile(Path a, Path b) {
        Path x = a.toAbsolutePath().normalize();
        Path y = b.toAbsolutePath().normalize();
        return os.isWindows() ? x.toString().equalsIgnoreCase(y.toString()) : x.equals(y);
    }
}
