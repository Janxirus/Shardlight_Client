package dev.shardlight.client.loader;

import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.java.JavaEnvironment;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.java.ProcessJavaProber;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.MinecraftFixtures;
import dev.shardlight.client.minecraft.VersionManifestService;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forge and NeoForge: version lists from Maven metadata, and installing by running a stand-in installer jar (it
 * writes the version JSON a real installer would) with the Java this test runs on.
 */
class InstallerLoaderProviderTest {
    static final String SECRET = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJpbnN0YWxsZXIifQ.aW5zdGFsbGVyLXNpZ25hdHVyZQ";
    static final String NEO_METADATA = """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>net.neoforged</groupId>
          <artifactId>neoforge</artifactId>
          <versioning>
            <versions>
              <version>20.2.88</version>
              <version>21.0.167</version>
              <version>21.4.0-beta</version>
              <version>21.4.9</version>
              <version>21.4.10</version>
              <version>21.4.100</version>
              <version>26.3.5</version>
              <version>../evil</version>
            </versions>
          </versioning>
        </metadata>
        """;
    static byte[] installerJar;
    static byte[] failingInstallerJar;

    @TempDir
    static Path compileDir;
    @TempDir
    Path dir;
    TestHttpServer server;
    MinecraftFixtures fx;
    AppPaths paths;
    DownloadManager downloads;
    NeoForgeProvider neo;
    ForgeProvider forge;

    @BeforeAll
    static void buildInstallers() throws Exception {
        String source = """
            package fake;

            import java.nio.file.*;
            import java.util.*;

            public class Installer {
                public static void main(String[] args) throws Exception {
                    List<String> a = Arrays.asList(args);
                    Path game = Paths.get(a.get(a.indexOf("--installClient") + 1));
                    if (!Files.exists(game.resolve("launcher_profiles.json"))) {
                        System.out.println("There is no minecraft launcher profile");
                        System.exit(2);
                    }
                    System.out.println("Using token %s".formatted("%SECRET%"));
                    if (System.getProperty("fail") != null || Files.exists(Paths.get("fail"))) {
                        System.exit(3);
                    }
                    String version = "neoforge-21.4.10";
                    Path dir = game.resolve("versions").resolve(version);
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve(version + ".json"), "{\\"id\\":\\"" + version + "\\",\\"inheritsFrom\\":"
                        + "\\"1.21.4\\",\\"type\\":\\"release\\",\\"mainClass\\":\\"cpw.mods.bootstraplauncher.BootstrapLauncher\\","
                        + "\\"libraries\\":[]}");
                    System.out.println("Successfully installed client into launcher.");
                }
            }
            """.replace("%SECRET%", SECRET);
        installerJar = jar(compile(source, "ok"));
        failingInstallerJar = jar(compile(source.replace("System.getProperty(\"fail\") != null", "true"), "fail"));
    }

    static byte[] compile(String source, String name) throws Exception {
        Path src = compileDir.resolve(name + "/src/fake/Installer.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = compileDir.resolve(name + "/classes");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, "--release", "17", "-d", classes.toString(), src.toString());
        assertEquals(0, rc, err.toString(StandardCharsets.UTF_8));
        return Files.readAllBytes(classes.resolve("fake/Installer.class"));
    }

    static byte[] jar(byte[] mainClass) throws Exception {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "fake.Installer");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bos, m)) {
            out.putNextEntry(new JarEntry("fake/Installer.class"));
            out.write(mainClass);
            out.closeEntry();
        }
        return bos.toByteArray();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new TestHttpServer();
        fx = new MinecraftFixtures(server);
        paths = new AppPaths(dir.resolve("data"));
        paths.createDirectories();
        HttpService http = HttpService.forTests();
        downloads = new DownloadManager(http, paths.files(), 4, 1);
        downloads.setBackoffMillis(5);
        VersionManifestService manifest = new VersionManifestService(http, fx.endpoints, paths);
        manifest.setFailureQuietMillis(0);
        GameInstaller installer = new GameInstaller(http, fx.endpoints, paths, downloads, manifest, OsInfo.current());
        // only the Java running this test is offered, so the installer runs on a known runtime
        JavaEnvironment env = new JavaEnvironment(OsInfo.current(), Map.of("JAVA_HOME", System.getProperty("java.home")),
            dir.resolve("home"), List.of());
        JavaRuntimeService java = new JavaRuntimeService(http, fx.endpoints, paths, downloads, env, new ProcessJavaProber());
        neo = new NeoForgeProvider(http, fx.endpoints, paths, downloads, installer, java);
        forge = new ForgeProvider(http, fx.endpoints, paths, downloads, installer, java);
        server.text("/neoforge-maven/net/neoforged/neoforge/maven-metadata.xml", 200, NEO_METADATA);
    }

    @AfterEach
    void tearDown() {
        downloads.close();
        server.close();
    }

    void serveInstaller(byte[] jar) {
        String base = "/neoforge-maven/net/neoforged/neoforge/21.4.10/neoforge-21.4.10-installer.jar";
        server.bytes(base, jar);
        server.text(base + ".sha1", 200, Hashing.hash(jar, HashAlgo.SHA1) + "  neoforge-21.4.10-installer.jar\n");
    }

    @Test
    void neoForgeVersionsBelongToTheirMinecraftVersion() throws Exception {
        List<LoaderVersion> v = neo.versions("1.21.4", CancellationToken.NONE);
        assertEquals(List.of(
            new LoaderVersion("21.4.100", true, true),
            new LoaderVersion("21.4.10", true, false),
            new LoaderVersion("21.4.9", true, false),
            new LoaderVersion("21.4.0-beta", false, false)), v);
        assertEquals(List.of("26.3.5"), neo.versions("26.3", CancellationToken.NONE).stream().map(LoaderVersion::version).toList());
        assertTrue(neo.supportsMinecraft("1.21", CancellationToken.NONE));
        assertFalse(neo.supportsMinecraft("1.19.2", CancellationToken.NONE));
        assertEquals(1, server.hits("/neoforge-maven/net/neoforged/neoforge/maven-metadata.xml"), "metadata is kept for a while");
        assertTrue(neo.isExperimental());
        assertEquals("neoforge-21.4.10", neo.versionId("1.21.4", "21.4.10"));
    }

    @Test
    void neoForgeVersionSchemeMapsToMinecraft() {
        assertEquals("1.20.2", NeoForgeProvider.minecraftOf("20.2.88"));
        assertEquals("1.21", NeoForgeProvider.minecraftOf("21.0.167"));
        assertEquals("1.21.4", NeoForgeProvider.minecraftOf("21.4.0-beta"));
        assertEquals("26.3", NeoForgeProvider.minecraftOf("26.3.5"));
        assertEquals("26.3.1", NeoForgeProvider.minecraftOf("26.3.1.4"));
        assertNull(NeoForgeProvider.minecraftOf("1.20.1-47.3.0"));
        assertNull(NeoForgeProvider.minecraftOf("21.x.1"));
        assertNull(NeoForgeProvider.minecraftOf(null));
    }

    @Test
    void forgeRecommendsItsPromotedBuild() throws Exception {
        server.text("/forge-maven/net/minecraftforge/forge/maven-metadata.xml", 200, """
            <metadata><versioning><versions>
              <version>1.20.1-47.2.0</version><version>1.20.1-47.3.0</version><version>1.20.1-47.3.12</version>
              <version>1.21.4-54.0.12</version>
            </versions></versioning></metadata>
            """);
        server.json("/forge-maven/net/minecraftforge/forge/promotions_slim.json",
            "{\"homepage\":\"x\",\"promos\":{\"1.20.1-recommended\":\"47.3.0\",\"1.20.1-latest\":\"47.3.12\",\"1.21.4-latest\":\"54.0.12\"}}");
        assertEquals(List.of(
            new LoaderVersion("47.3.12", true, false),
            new LoaderVersion("47.3.0", true, true),
            new LoaderVersion("47.2.0", true, false)), forge.versions("1.20.1", CancellationToken.NONE));
        assertTrue(forge.versions("1.21.4", CancellationToken.NONE).get(0).recommended());
        assertTrue(forge.versions("1.12.2", CancellationToken.NONE).isEmpty());
        assertEquals("1.20.1-forge-47.3.0", forge.versionId("1.20.1", "47.3.0"));
        assertEquals("1.20.1-47.3.0", forge.mavenVersion("1.20.1", "47.3.0"));
    }

    @Test
    void forgeWithoutPromotionsRecommendsTheNewest() throws Exception {
        server.text("/forge-maven/net/minecraftforge/forge/maven-metadata.xml", 200,
            "<metadata><versioning><versions><version>1.20.1-47.2.0</version><version>1.20.1-47.10.1</version>"
                + "</versions></versioning></metadata>");
        List<LoaderVersion> v = forge.versions("1.20.1", CancellationToken.NONE);
        assertEquals("47.10.1", v.get(0).version());
        assertTrue(v.get(0).recommended());
    }

    @Test
    void metadataWithADoctypeIsRefused() {
        byte[] xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE metadata [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>
            """.getBytes(StandardCharsets.UTF_8);
        LauncherException e = assertThrows(LauncherException.class, () -> InstallerLoaderProvider.parseMetadata(xxe, "NeoForge"));
        assertEquals(ErrorCode.INVALID_DATA, e.code());
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class,
            () -> InstallerLoaderProvider.parseMetadata("not xml".getBytes(StandardCharsets.UTF_8), "NeoForge")).code());
    }

    @Test
    void installingRunsTheInstallerAndFindsTheNewVersion() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        serveInstaller(installerJar);

        String id = neo.install("1.21.4", "21.4.10", null, CancellationToken.NONE);

        assertEquals("neoforge-21.4.10", id);
        assertTrue(Files.isRegularFile(paths.versions().resolve(id).resolve(id + ".json")));
        assertTrue(Files.isRegularFile(paths.game().resolve(InstallerLoaderProvider.LAUNCHER_PROFILES)),
            "the installers need the official launcher's profile list");
        assertTrue(Files.isRegularFile(paths.versions().resolve("1.21.4").resolve("1.21.4.json")), "vanilla is installed first");
        Path log = paths.logs().resolve("loader-neoforge-21.4.10.log");
        String text = Files.readString(log);
        assertTrue(text.contains("Successfully installed client"), text);
        assertFalse(text.contains(SECRET), "installer output is redacted");
    }

    @Test
    void aFailingInstallerPointsAtItsLog() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        serveInstaller(failingInstallerJar);
        LauncherException e = assertThrows(LauncherException.class,
            () -> neo.install("1.21.4", "21.4.10", null, CancellationToken.NONE));
        assertEquals("loader.error.installerFailed", e.messageKey());
        assertEquals(3, e.args()[1]);
    }

    @Test
    void aTamperedInstallerIsNotRun() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        serveInstaller(installerJar);
        server.bytes("/neoforge-maven/net/neoforged/neoforge/21.4.10/neoforge-21.4.10-installer.jar", failingInstallerJar);
        assertThrows(LauncherException.class, () -> neo.install("1.21.4", "21.4.10", null, CancellationToken.NONE));
        assertFalse(Files.exists(paths.versions().resolve("neoforge-21.4.10")));
        assertFalse(Files.exists(paths.logs().resolve("loader-neoforge-21.4.10.log")), "the installer never started");
    }

    @Test
    void anUnknownVersionIsUnsupported() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        LauncherException e = assertThrows(LauncherException.class,
            () -> neo.install("1.21.4", "21.4.999", null, CancellationToken.NONE));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, e.code());
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> neo.install("1.21.4", "../21.4.10", null, CancellationToken.NONE)).code());
    }
}
