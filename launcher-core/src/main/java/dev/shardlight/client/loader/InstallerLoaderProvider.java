package dev.shardlight.client.loader;

import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.java.JavaInstall;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.InstalledVersion;
import dev.shardlight.client.minecraft.VersionResolver;
import dev.shardlight.client.minecraft.model.VersionJson;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.PathSafety;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * A loader installed by running its official installer (Forge, NeoForge): versions come from the Maven
 * repository's {@code maven-metadata.xml}; installing downloads {@code <artifact>-<version>-installer.jar}, checks
 * it against the {@code .sha1} file next to it, makes sure the vanilla version is installed, writes a minimal
 * {@code launcher_profiles.json} when the game folder has none, then runs
 * {@code java -jar installer.jar --installClient <game folder>} (output in {@code logs/loader-*.log}) and finds the
 * new version by comparing {@code versions/} before and after.
 *
 * <p>Experimental: the installers' processors were exercised only with a stand-in installer in tests; launching
 * these versions hasn't been tested end to end.
 */
abstract class InstallerLoaderProvider implements LoaderProvider {
    static final String STAGE_INSTALLER = "loader.stage.installer";
    static final String STAGE_RUNNING = "loader.stage.running";
    static final String LAUNCHER_PROFILES = "launcher_profiles.json";
    private static final Logger LOG = Logger.getLogger(InstallerLoaderProvider.class.getName());
    private static final long CACHE_MILLIS = 10 * 60_000L;

    final HttpService http;
    final URI maven;
    final AppPaths paths;
    private final DownloadManager downloads;
    private final GameInstaller installer;
    private final JavaRuntimeService java;
    private final VersionResolver resolver;
    private final Map<String, CachedList> metadata = new ConcurrentHashMap<>();
    private volatile Duration timeout = Duration.ofMinutes(20);

    private record CachedList(List<String> versions, long at) {
    }

    InstallerLoaderProvider(HttpService http, URI maven, AppPaths paths, DownloadManager downloads, GameInstaller installer,
                            JavaRuntimeService java) {
        this.http = http;
        this.maven = maven;
        this.paths = paths;
        this.downloads = downloads;
        this.installer = installer;
        this.java = java;
        this.resolver = new VersionResolver(paths.versions());
    }

    /** the Maven group as a path ({@code net/neoforged}) */
    abstract String groupPath();

    /** the Maven artifact ({@code neoforge}, {@code forge}) */
    abstract String artifact();

    /** the loader versions for {@code minecraftVersion} among every Maven version, newest first */
    abstract List<LoaderVersion> select(List<String> mavenVersions, String minecraftVersion, CancellationToken cancel)
        throws LauncherException;

    /** the Maven version of the installer for this loader version */
    abstract String mavenVersion(String minecraftVersion, String loaderVersion);

    /** the longest time an installer may run (processors download and remap the game); 20 minutes by default */
    public void setInstallerTimeout(Duration d) {
        timeout = d == null || d.isNegative() || d.isZero() ? Duration.ofMinutes(20) : d;
    }

    @Override
    public List<LoaderVersion> versions(String minecraftVersion, CancellationToken cancel) throws LauncherException {
        if (!MetaLoaderProvider.isSafe(minecraftVersion)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(minecraftVersion));
        }
        return select(mavenVersions(cancel == null ? CancellationToken.NONE : cancel), minecraftVersion, cancel);
    }

    @Override
    public boolean supportsMinecraft(String minecraftVersion, CancellationToken cancel) throws LauncherException {
        return MetaLoaderProvider.isSafe(minecraftVersion) && !versions(minecraftVersion, cancel).isEmpty();
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    /** every version listed in {@code <group>/<artifact>/maven-metadata.xml} */
    List<String> mavenVersions(CancellationToken cancel) throws LauncherException {
        CachedList c = metadata.get("all");
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_MILLIS) {
            return c.versions();
        }
        URI uri = HttpService.uri(maven, groupPath() + "/" + artifact() + "/maven-metadata.xml", null);
        byte[] xml = http.execute(HttpService.Request.get(uri), cancel).requireOk().body();
        List<String> out = parseMetadata(xml, type().displayName());
        metadata.put("all", new CachedList(out, System.currentTimeMillis()));
        return out;
    }

    /** the {@code <version>} elements of a Maven metadata document; doctypes and external entities are refused */
    static List<String> parseMetadata(byte[] xml, String what) throws LauncherException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList nl = d.getElementsByTagName("version");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < nl.getLength(); i++) {
                String v = nl.item(i).getTextContent();
                if (v != null && MetaLoaderProvider.isSafe(v.trim())) {
                    out.add(v.trim());
                }
            }
            return List.copyOf(out);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "loader.error.metadataInvalid", what);
        }
    }

    @Override
    public String install(String minecraftVersion, String loaderVersion, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        if (!MetaLoaderProvider.isSafe(minecraftVersion) || !MetaLoaderProvider.isSafe(loaderVersion)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", minecraftVersion + " " + loaderVersion);
        }
        String full = mavenVersion(minecraftVersion, loaderVersion);
        if (!MetaLoaderProvider.isSafe(full)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", full);
        }
        // the installer reads the vanilla version's JSON and jar from the game folder
        InstalledVersion vanilla = installer.install(minecraftVersion, p, c);
        Path jar = downloadInstaller(minecraftVersion, loaderVersion, full, p, c);
        ensureLauncherProfiles();
        JavaInstall j = java.select(vanilla.javaMajorVersion(), vanilla.javaComponent(), null, true, p, c);
        Set<String> before = versionIds();
        Path log = runInstaller(consoleJava(j), jar, full, p, c);
        String id = detect(before, minecraftVersion, loaderVersion, log);
        LOG.info("Installed " + type().displayName() + " " + loaderVersion + " for Minecraft " + minecraftVersion + " as " + id);
        return id;
    }

    private Path downloadInstaller(String mc, String loaderVersion, String full, ProgressListener p, CancellationToken c)
        throws LauncherException {
        String file = artifact() + "-" + full + "-installer.jar";
        URI url = HttpService.uri(maven, groupPath() + "/" + artifact() + "/" + HttpService.encodePath(full) + "/"
            + HttpService.encodePath(file), null);
        p.progress(STAGE_INSTALLER, file, 0, -1);
        String sha1;
        try {
            sha1 = http.getText(URI.create(url + ".sha1"), c).trim();
        } catch (LauncherException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, e, "loader.error.unsupported", type().displayName(),
                    loaderVersion, mc);
            }
            throw e;
        }
        int space = sha1.indexOf(' ');
        sha1 = (space > 0 ? sha1.substring(0, space) : sha1).trim().toLowerCase(Locale.ROOT);
        if (!HashAlgo.SHA1.isValidHex(sha1)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "loader.error.metadataInvalid", type().displayName());
        }
        Path target = paths.cache().resolve("loaders").resolve(file);
        downloads.downloadAll(I18n.tr("loader.group", type().displayName(), full),
            List.of(DownloadRequest.sha1(url, target, sha1, -1).withLabel(file)), STAGE_INSTALLER, p, c);
        return target;
    }

    /** the installers refuse to run without the official launcher's profile list */
    void ensureLauncherProfiles() throws LauncherException {
        Path f = paths.game().resolve(LAUNCHER_PROFILES);
        if (!Files.exists(f)) {
            AtomicFiles.writeString(f, "{\"profiles\":{}}\n");
        }
    }

    /** {@code java.exe} next to {@code javaw.exe}, so the installer has a console for its output */
    static Path consoleJava(JavaInstall j) {
        Path exe = j.executable();
        if (exe.getFileName().toString().equalsIgnoreCase("javaw.exe")) {
            Path console = exe.resolveSibling("java.exe");
            if (Files.isRegularFile(console)) {
                return console;
            }
        }
        return exe;
    }

    private Path runInstaller(Path javaExe, Path jar, String full, ProgressListener p, CancellationToken c)
        throws LauncherException {
        Path work = paths.cache().resolve("loaders").resolve("work-" + artifact() + "-" + full);
        Path log = paths.logs().resolve("loader-" + artifact() + "-" + full + ".log");
        try {
            Files.createDirectories(work);
            Files.createDirectories(log.getParent());
        } catch (IOException e) {
            throw LauncherException.io(e, work.toString());
        }
        c.check();
        List<String> cmd = List.of(javaExe.toAbsolutePath().toString(), "-jar", jar.toAbsolutePath().toString(),
            "--installClient", paths.game().toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true);
        String label = type().displayName() + " " + full;
        p.progress(STAGE_RUNNING, label, 0, -1);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, e, "loader.error.installerStart", type().displayName(),
                String.valueOf(e.getMessage()));
        }
        c.onCancel(() -> destroyTree(proc));
        AtomicLong lines = new AtomicLong();
        AtomicReference<String> last = new AtomicReference<>("");
        Thread pump = Thread.ofVirtual().name("loader-installer-output").start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
                out.write("# " + String.join(" ", cmd));
                out.newLine();
                String line;
                while ((line = in.readLine()) != null) {
                    String safe = Redactor.redact(line);
                    out.write(safe);
                    out.newLine();
                    last.set(safe.length() > 200 ? safe.substring(0, 200) : safe);
                    lines.incrementAndGet();
                }
            } catch (IOException e) {
                LOG.fine(() -> "Installer output ended: " + e.getMessage());
            }
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!proc.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (c.isCancelled()) {
                    destroyTree(proc);
                    throw LauncherException.cancelled();
                }
                if (System.nanoTime() > deadline) {
                    destroyTree(proc);
                    throw new LauncherException(ErrorCode.NETWORK, "loader.error.installerTimeout", type().displayName());
                }
                p.progress(STAGE_RUNNING, last.get().isEmpty() ? label : last.get(), lines.get(), -1);
            }
            pump.join(5000);
        } catch (InterruptedException e) {
            destroyTree(proc);
            Thread.currentThread().interrupt();
            throw LauncherException.cancelled();
        }
        c.check();
        if (proc.exitValue() != 0) {
            LOG.warning(label + " installer failed with exit code " + proc.exitValue() + "; see " + log);
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "loader.error.installerFailed", type().displayName(),
                proc.exitValue(), log.toString());
        }
        return log;
    }

    private static void destroyTree(Process proc) {
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
    }

    /** ids of the versions in {@code versions/} that have their JSON */
    Set<String> versionIds() {
        Set<String> out = new TreeSet<>();
        Path dir = paths.versions();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path d : ds) {
                String name = d.getFileName().toString();
                if (PathSafety.isSafeSegment(name) && Files.isRegularFile(d.resolve(name + ".json"))) {
                    out.add(name);
                }
            }
        } catch (IOException e) {
            LOG.fine(() -> "Couldn't list " + dir + ": " + e.getMessage());
        }
        return out;
    }

    private String detect(Set<String> before, String mc, String loaderVersion, Path log) throws LauncherException {
        List<String> created = new ArrayList<>();
        for (String id : versionIds()) {
            if (!before.contains(id) && inheritsFrom(id, mc)) {
                created.add(id);
            }
        }
        if (created.size() == 1) {
            return created.get(0);
        }
        for (String id : created) {
            if (id.contains(loaderVersion)) {
                return id;
            }
        }
        if (!created.isEmpty()) {
            return created.get(0);
        }
        // reinstalled over an existing version: nothing new appeared
        String expected = versionId(mc, loaderVersion);
        if (inheritsFrom(expected, mc)) {
            return expected;
        }
        throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "loader.error.installerNoVersion", type().displayName(),
            log.toString());
    }

    private boolean inheritsFrom(String id, String mc) {
        try {
            VersionJson v = resolver.read(id);
            return mc.equals(v.inheritsFrom());
        } catch (LauncherException e) {
            return false;
        }
    }
}
