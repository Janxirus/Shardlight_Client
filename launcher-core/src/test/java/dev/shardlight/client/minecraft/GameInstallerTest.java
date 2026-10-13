package dev.shardlight.client.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameInstallerTest {
    static final OsInfo WINDOWS = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X64, "10.0");
    static final OsInfo WINDOWS_32 = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X86, "10.0");
    static final OsInfo MAC = new OsInfo(OsInfo.Os.MACOS, OsInfo.Arch.ARM64, "14.5");
    static final OsInfo LINUX = new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.X64, "6.8.0");

    @TempDir
    Path dir;
    TestHttpServer server;
    MinecraftFixtures fx;
    AppPaths paths;
    HttpService http;
    DownloadManager downloads;
    VersionManifestService manifest;

    @BeforeEach
    void setUp() {
        server = new TestHttpServer();
        fx = new MinecraftFixtures(server);
        paths = new AppPaths(dir.resolve("data"));
        http = HttpService.forTests();
        downloads = new DownloadManager(http, paths.files(), 6, 1);
        downloads.setBackoffMillis(5);
        manifest = new VersionManifestService(http, fx.endpoints, paths);
        manifest.setFailureQuietMillis(0);
    }

    @AfterEach
    void tearDown() {
        downloads.close();
        server.close();
    }

    GameInstaller installer(OsInfo os) {
        return new GameInstaller(http, fx.endpoints, paths, downloads, manifest, os);
    }

    static Set<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).collect(Collectors.toSet());
    }

    static final class Stages implements ProgressListener {
        final Set<String> keys = ConcurrentHashMap.newKeySet();

        @Override
        public void progress(String stageKey, String detail, long done, long total) {
            keys.add(stageKey);
        }
    }

    @Test
    void estimatesWhatIsStillMissingWithoutInstalling() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        GameInstaller installer = installer(WINDOWS);
        GameInstaller.InstallEstimate before = installer.estimate("1.21.4", CancellationToken.NONE);
        assertTrue(before.files() > 0);
        assertTrue(before.bytes() > 0);
        assertFalse(Files.exists(paths.versions().resolve("1.21.4/1.21.4.jar")), "estimating does not install");
        installer.install("1.21.4", new Stages(), CancellationToken.NONE);
        GameInstaller.InstallEstimate after = installer.estimate("1.21.4", CancellationToken.NONE);
        assertEquals(0, after.files());
        assertEquals(0, after.bytes());
    }

    @Test
    void installsModernVanillaForWindows() throws Exception {
        String json = fx.publishTemplate("1.21.4.json", "release");
        Stages stages = new Stages();
        InstalledVersion iv = installer(WINDOWS).install("1.21.4", stages, CancellationToken.NONE);

        Path versionJson = paths.versions().resolve("1.21.4/1.21.4.json");
        assertEquals(json, Files.readString(versionJson));
        Path clientJar = paths.versions().resolve("1.21.4/1.21.4.jar");
        assertEquals(clientJar, iv.clientJar());
        assertArrayEquals(MinecraftFixtures.clientJar("1.21.4"), Files.readAllBytes(clientJar));

        List<Path> cp = iv.classpath();
        assertEquals(clientJar, cp.get(cp.size() - 1), "client jar last");
        assertEquals(cp.size(), new HashSet<>(cp).size(), "no duplicates");
        Set<String> cpNames = names(cp);
        assertTrue(cpNames.containsAll(Set.of("lwjgl-3.3.3.jar", "lwjgl-3.3.3-natives-windows.jar",
            "lwjgl-3.3.3-natives-windows-arm64.jar", "lwjgl-3.3.3-natives-windows-x86.jar", "lwjgl-glfw-3.3.3-natives-windows.jar",
            "guava-33.3.1-jre.jar", "authlib-6.0.57.jar")));
        assertFalse(cpNames.contains("java-objc-bridge-1.1.jar"));
        assertFalse(cpNames.contains("lwjgl-3.3.3-natives-linux.jar"));
        assertFalse(cpNames.contains("lwjgl-3.3.3-natives-macos.jar"));
        assertEquals("oshi-core-6.6.5.jar", cp.get(0).getFileName().toString(), "JSON order kept");
        assertEquals(Set.of("lwjgl-3.3.3-natives-windows.jar", "lwjgl-3.3.3-natives-windows-arm64.jar",
            "lwjgl-3.3.3-natives-windows-x86.jar", "lwjgl-glfw-3.3.3-natives-windows.jar"), names(iv.nativeJars()));
        assertTrue(iv.nativeExcludes().contains("META-INF/"));
        for (Path p : cp) {
            assertTrue(Files.isRegularFile(p), p.toString());
            assertTrue(p.startsWith(paths.libraries()) || p.equals(clientJar));
        }

        assertEquals("19", iv.assetIndexId());
        assertEquals(paths.assets(), iv.assetsRoot());
        assertTrue(Files.isRegularFile(paths.assets().resolve("indexes/19.json")));
        assertFalse(iv.virtualAssets());
        assertFalse(iv.mapToResources());
        int objects = 0;
        try (var s = Files.walk(paths.assets().resolve("objects"))) {
            objects = (int) s.filter(Files::isRegularFile).count();
        }
        assertEquals(fx.assetObjects, objects);

        Path logConfig = paths.assets().resolve("log_configs/client-1.21.2.xml");
        assertTrue(Files.isRegularFile(logConfig));
        assertEquals("-Dlog4j.configurationFile=" + logConfig, iv.loggingArgument());
        assertEquals(21, iv.javaMajorVersion());
        assertEquals("java-runtime-delta", iv.javaComponent());
        assertEquals("1.21.4", iv.id());
        assertTrue(stages.keys.containsAll(Set.of(GameInstaller.STAGE_METADATA, GameInstaller.STAGE_CLIENT,
            GameInstaller.STAGE_LIBRARIES, GameInstaller.STAGE_ASSETS)), stages.keys.toString());

        // a second install downloads nothing
        int before = server.requests().size();
        InstalledVersion again = installer(WINDOWS).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(before, server.requests().size());
        assertEquals(iv.classpath(), again.classpath());
        assertEquals(iv, installer(WINDOWS).installed("1.21.4"));
        assertTrue(installer(WINDOWS).isInstalled("1.21.4"));
        assertFalse(installer(WINDOWS).isInstalled("1.21.5"));
    }

    @Test
    void librariesFollowTheOperatingSystem() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        InstalledVersion mac = installer(MAC).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(names(mac.classpath()).containsAll(Set.of("java-objc-bridge-1.1.jar", "lwjgl-3.3.3-natives-macos.jar",
            "lwjgl-3.3.3-natives-macos-arm64.jar", "lwjgl-glfw-3.3.3-natives-macos.jar")));
        assertFalse(names(mac.classpath()).contains("lwjgl-3.3.3-natives-windows.jar"));
        InstalledVersion linux = installer(LINUX).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(Set.of("lwjgl-3.3.3-natives-linux.jar", "lwjgl-glfw-3.3.3-natives-linux.jar"), names(linux.nativeJars()));
        assertFalse(names(linux.classpath()).contains("java-objc-bridge-1.1.jar"));
    }

    @Test
    void installsLegacyVersionWithNativesMap() throws Exception {
        fx.publishTemplate("1.8.9.json", "release");
        InstalledVersion win = installer(WINDOWS).install("1.8.9", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(Set.of("lwjgl-platform-2.9.4-nightly-20150209-natives-windows.jar", "jinput-platform-2.0.5-natives-windows.jar",
            "twitch-platform-6.5-natives-windows-64.jar", "twitch-external-platform-4.5-natives-windows-64.jar"), names(win.nativeJars()));
        Set<String> cp = names(win.classpath());
        assertTrue(cp.containsAll(Set.of("lwjgl-2.9.4-nightly-20150209.jar", "lwjgl_util-2.9.4-nightly-20150209.jar",
            "twitch-6.5.jar", "jinput-2.0.5.jar", "1.8.9.jar")));
        assertFalse(cp.contains("lwjgl-2.9.2-nightly-20140822.jar"), "the macOS-only LWJGL stays out");
        assertTrue(cp.stream().noneMatch(n -> n.contains("natives")), "legacy natives are never on the classpath");
        for (Path n : win.nativeJars()) {
            assertTrue(Files.isRegularFile(n));
        }
        assertEquals(8, win.javaMajorVersion());
        assertEquals("jre-legacy", win.javaComponent());
        assertEquals("1.8", win.assetIndexId());
        assertTrue(win.loggingArgument().endsWith("client-1.7.xml"));

        InstalledVersion win32 = installer(WINDOWS_32).install("1.8.9", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(names(win32.nativeJars()).contains("twitch-platform-6.5-natives-windows-32.jar"));

        InstalledVersion mac = installer(MAC).install("1.8.9", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(names(mac.classpath()).contains("lwjgl-2.9.2-nightly-20140822.jar"));
        assertFalse(names(mac.classpath()).contains("lwjgl-2.9.4-nightly-20150209.jar"));
        assertEquals(Set.of("lwjgl-platform-2.9.2-nightly-20140822-natives-osx.jar", "jinput-platform-2.0.5-natives-osx.jar",
            "twitch-platform-6.5-natives-osx.jar"), names(mac.nativeJars()));

        InstalledVersion linux = installer(LINUX).install("1.8.9", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(Set.of("lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar", "jinput-platform-2.0.5-natives-linux.jar"),
            names(linux.nativeJars()), "twitch is disallowed on Linux");
    }

    @Test
    void installsFabricOnTopOfVanilla() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        String fabricJson = fx.prepare("fabric-loader-0.16.10-1.21.4.json");
        String id = "fabric-loader-0.16.10-1.21.4";
        MinecraftFixtures.writeVersion(paths.versions(), id, fabricJson);

        InstalledVersion iv = installer(WINDOWS).install(id, ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(Files.isRegularFile(paths.versions().resolve("1.21.4/1.21.4.json")), "the parent was fetched");
        Path parentJar = paths.versions().resolve("1.21.4/1.21.4.jar");
        Path childJar = paths.versions().resolve(id + "/" + id + ".jar");
        assertEquals(childJar, iv.clientJar());
        assertArrayEquals(Files.readAllBytes(parentJar), Files.readAllBytes(childJar));
        assertEquals("asm-9.7.1.jar", iv.classpath().get(0).getFileName().toString());
        assertEquals(childJar, iv.classpath().get(iv.classpath().size() - 1));
        assertFalse(iv.classpath().contains(parentJar));
        assertTrue(names(iv.classpath()).containsAll(Set.of("fabric-loader-0.16.10.jar", "intermediary-1.21.4.jar",
            "sponge-mixin-0.15.4+mixin.0.8.7.jar", "lwjgl-3.3.3.jar")));
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", iv.version().mainClass());
        assertEquals(paths.libraries().resolve("net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"),
            iv.classpath().get(4));
        assertTrue(server.hits("/fabric-maven/net/fabricmc/intermediary/1.21.4/intermediary-1.21.4.jar") == 1);
        assertEquals(21, iv.javaMajorVersion());
        assertEquals("19", iv.assetIndexId());

        // a damaged copy is replaced from the parent's verified jar
        Files.writeString(childJar, "empty jar a loader installer left behind");
        InstalledVersion again = installer(WINDOWS).install(id, ProgressListener.NONE, CancellationToken.NONE);
        assertArrayEquals(Files.readAllBytes(parentJar), Files.readAllBytes(again.clientJar()));
    }

    @Test
    void fabricLibraryWithWrongHashFails() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        JsonObject fabric = JsonParser.parseString(fx.prepare("fabric-loader-0.16.10-1.21.4.json")).getAsJsonObject();
        fabric.getAsJsonArray("libraries").get(0).getAsJsonObject().addProperty("sha1", "0".repeat(40));
        MinecraftFixtures.writeVersion(paths.versions(), "fab", Json.write(fabric).replace("fabric-loader-0.16.10-1.21.4", "fab"));
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(LINUX).install("fab", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code());
        assertFalse(Files.exists(paths.libraries().resolve("org/ow2/asm/asm/9.7.1/asm-9.7.1.jar")));
    }

    @Test
    void unknownVersionIsNotFound() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("1.99.9", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        assertEquals("install.error.versionNotFound", e.messageKey());
        MinecraftFixtures.writeVersion(paths.versions(), "orphan", "{\"id\":\"orphan\",\"inheritsFrom\":\"0.0.1\"}");
        LauncherException p = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("orphan", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals("install.error.parentMissing", p.messageKey());
    }

    @Test
    void manifestUnreachableAndNothingInstalled() {
        server.text("/mojang/version_manifest_v2.json", 503, "maintenance");
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals("install.error.manifestUnavailable", e.messageKey());
    }

    @Test
    void clientChecksumMismatchLeavesNoJar() throws Exception {
        JsonObject o = JsonParser.parseString(fx.prepare("1.21.4.json")).getAsJsonObject();
        o.getAsJsonObject("downloads").getAsJsonObject("client").addProperty("sha1", "1".repeat(40));
        fx.publish("1.21.4", "release", Json.write(o));
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code());
        assertFalse(Files.exists(paths.versions().resolve("1.21.4/1.21.4.jar")));
    }

    @Test
    void versionJsonChecksumIsVerified() throws Exception {
        String json = fx.prepare("1.21.4.json");
        fx.publish("1.21.4", "release", json);
        // the server now serves different bytes than the manifest's sha1 promises
        server.bytes(fx.publish("1.21.4", "release", json).path(), (json + " ").getBytes(StandardCharsets.UTF_8));
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code());
        assertFalse(Files.exists(paths.versions().resolve("1.21.4/1.21.4.json")));
    }

    @Test
    void unsupportedVersions() throws Exception {
        MinecraftFixtures.writeVersion(paths.versions(), "nojar", "{\"id\":\"nojar\",\"mainClass\":\"a.B\"}");
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("nojar", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, e.code());
        assertEquals("install.error.noClientJar", e.messageKey());
        MinecraftFixtures.writeVersion(paths.versions(), "nomain", "{\"id\":\"nomain\",\"downloads\":{\"client\":{\"url\":\"https://x/y.jar\"}}}");
        LauncherException m = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("nomain", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, m.code());
        assertEquals("install.error.noMainClass", m.messageKey());
    }

    @Test
    void verifyFindsMissingAndCorruptFilesAndRepairFixesThem() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        GameInstaller gi = installer(WINDOWS);
        InstalledVersion iv = gi.install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        VerifyReport clean = gi.verify("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(clean.isOk(), clean.toString());
        assertTrue(clean.checkedFiles() > fx.assetObjects + 10);

        Path lib = iv.classpath().get(1);
        Files.writeString(lib, "garbage");
        Path nativeJar = iv.nativeJars().get(0);
        Files.delete(nativeJar);
        Path object;
        try (var s = Files.walk(paths.assets().resolve("objects"))) {
            object = s.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        byte[] obj = Files.readAllBytes(object);
        obj[0] ^= 1;
        Files.write(object, obj); // same size, wrong hash
        Files.writeString(iv.clientJar(), "not a jar");

        Stages stages = new Stages();
        VerifyReport bad = gi.verify("1.21.4", stages, CancellationToken.NONE);
        assertFalse(bad.isOk());
        assertEquals(List.of(nativeJar), bad.missing());
        assertEquals(Set.of(lib, object, iv.clientJar()), new HashSet<>(bad.corrupt()));
        assertEquals(4, bad.problemCount());
        assertTrue(stages.keys.contains(GameInstaller.STAGE_VERIFY));

        // a plain install doesn't notice an asset object with the right size; repair does
        InstalledVersion repaired = gi.repair("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(iv, repaired);
        assertTrue(gi.verify("1.21.4", ProgressListener.NONE, CancellationToken.NONE).isOk());
        assertEquals(Hashing.hash(Files.readAllBytes(object), HashAlgo.SHA1), object.getFileName().toString());
    }

    @Test
    void repairRestoresADamagedVersionFileAndAssetIndex() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        GameInstaller gi = installer(LINUX);
        gi.install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        Path json = paths.versions().resolve("1.21.4/1.21.4.json");
        String good = Files.readString(json);
        Files.writeString(json, "{ broken");
        assertEquals(ErrorCode.INVALID_DATA,
            assertThrows(LauncherException.class, () -> gi.verify("1.21.4", ProgressListener.NONE, CancellationToken.NONE)).code());
        gi.repair("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(good, Files.readString(json));

        Path index = paths.assets().resolve("indexes/19.json");
        Files.writeString(index, "[]");
        VerifyReport r = gi.verify("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(List.of(index), r.corrupt());
        gi.repair("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(gi.verify("1.21.4", ProgressListener.NONE, CancellationToken.NONE).isOk());

        // a missing version: repair simply installs it
        Path versionDir = paths.versions().resolve("1.21.4");
        Files.delete(json);
        assertEquals(ErrorCode.NOT_FOUND,
            assertThrows(LauncherException.class, () -> gi.verify("1.21.4", ProgressListener.NONE, CancellationToken.NONE)).code());
        gi.repair("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(Files.exists(versionDir.resolve("1.21.4.json")));
    }

    @Test
    void updatedVersionFileIsFetchedAgain() throws Exception {
        String json = fx.publishTemplate("1.21.4.json", "release");
        GameInstaller gi = installer(WINDOWS);
        gi.install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        o.addProperty("time", "2025-02-01T00:00:00+00:00");
        String updated = Json.write(o);
        fx.publish("1.21.4", "release", updated);
        manifest.invalidate();
        gi.install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(updated, Files.readString(paths.versions().resolve("1.21.4/1.21.4.json")));
    }

    @Test
    void installedVersionsWorkOffline() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        installer(WINDOWS).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        // a fresh launcher session with no network at all
        Files.deleteIfExists(paths.cache().resolve("version_manifest_v2.json"));
        Endpoints dead = Endpoints.local(URI.create("http://127.0.0.1:9/"));
        VersionManifestService offlineManifest = new VersionManifestService(http, dead, paths);
        GameInstaller offline = new GameInstaller(http, dead, paths, downloads, offlineManifest, WINDOWS);
        InstalledVersion iv = offline.install("1.21.4", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(Files.isRegularFile(iv.clientJar()));
        assertThrows(LauncherException.class, () -> offline.install("1.20.1", ProgressListener.NONE, CancellationToken.NONE));
    }

    @Test
    void legacyVirtualAndPreSixAssetIndexes() throws Exception {
        String base = server.base().toString();
        byte[] legacyIndex = fx.assetIndex("legacy", true, false);
        fx.serveAt("/piston-meta/legacy.json", legacyIndex);
        byte[] preIndex = fx.assetIndex("pre-1.6", false, true);
        fx.serveAt("/piston-meta/pre-1.6.json", preIndex);
        byte[] client = MinecraftFixtures.clientJar("old");
        fx.serveAt("/piston-data/old.jar", client);
        String template = """
            {"id":"%s","type":"old_beta","mainClass":"net.minecraft.launchwrapper.Launch",
             "minecraftArguments":"${auth_player_name} ${auth_session} --gameDir ${game_directory} --assetsDir ${game_assets}",
             "downloads":{"client":{"sha1":"%s","size":%d,"url":"%spiston-data/old.jar"}},
             "assetIndex":{"id":"%s","sha1":"%s","size":%d,"url":"%spiston-meta/%s.json"},"assets":"%s","libraries":[]}""";
        String legacy = template.formatted("1.6.4", Hashing.hash(client, HashAlgo.SHA1), client.length, base, "legacy",
            Hashing.hash(legacyIndex, HashAlgo.SHA1), legacyIndex.length, base, "legacy", "legacy");
        String pre = template.formatted("b1.7.3", Hashing.hash(client, HashAlgo.SHA1), client.length, base, "pre-1.6",
            Hashing.hash(preIndex, HashAlgo.SHA1), preIndex.length, base, "pre-1.6", "pre-1.6");
        fx.publish("1.6.4", "release", legacy);
        fx.publish("b1.7.3", "old_beta", pre);

        InstalledVersion v = installer(WINDOWS).install("1.6.4", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(v.virtualAssets());
        assertFalse(v.mapToResources());
        assertEquals(paths.assets().resolve("virtual/legacy"), v.virtualAssetsDir());
        Path copy = v.virtualAssetsDir().resolve("minecraft/sounds/duplicate.ogg");
        assertTrue(Files.isRegularFile(copy));
        assertEquals(8, v.javaMajorVersion(), "no javaVersion means Java 8");
        assertNull(v.javaComponent());
        assertNull(v.loggingArgument());
        assertTrue(v.nativeJars().isEmpty());
        assertTrue(installer(WINDOWS).verify("1.6.4", ProgressListener.NONE, CancellationToken.NONE).isOk());
        Files.writeString(copy, "x");
        assertEquals(List.of(copy), installer(WINDOWS).verify("1.6.4", ProgressListener.NONE, CancellationToken.NONE).corrupt());
        installer(WINDOWS).repair("1.6.4", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(installer(WINDOWS).verify("1.6.4", ProgressListener.NONE, CancellationToken.NONE).isOk());

        InstalledVersion b = installer(WINDOWS).install("b1.7.3", ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(b.mapToResources());
        assertFalse(b.virtualAssets());
        Path gameDir = dir.resolve("project/minecraft");
        int copied = LaunchPreparation.copyMappedResources(b, gameDir, CancellationToken.NONE);
        assertEquals(fx.assetObjects + 1, copied);
        assertTrue(Files.isRegularFile(gameDir.resolve("resources/minecraft/sounds/duplicate.ogg")));
        assertEquals(0, LaunchPreparation.copyMappedResources(b, gameDir, CancellationToken.NONE), "already there");
        assertEquals(0, LaunchPreparation.copyMappedResources(v, gameDir, CancellationToken.NONE), "only for map_to_resources");
    }

    @Test
    void largeAssetIndexGoesThroughInOneBatch() throws Exception {
        fx.assetObjects = 600;
        fx.publishTemplate("26.1.json", "release");
        InstalledVersion iv = installer(WINDOWS).install("26.1", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(25, iv.javaMajorVersion());
        assertEquals("java-runtime-epsilon", iv.javaComponent());
        assertEquals("29", iv.assetIndexId());
        long n;
        try (var s = Files.walk(paths.assets().resolve("objects"))) {
            n = s.filter(Files::isRegularFile).count();
        }
        assertEquals(600, n);
    }

    @Test
    void cancellationStopsTheInstall() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("1.21.4", ProgressListener.NONE, cancel));
        assertTrue(e.isCancelled());

        CancellationToken mid = new CancellationToken();
        ProgressListener cancelAtLibraries = (stage, detail, done, total) -> {
            if (stage.equals(GameInstaller.STAGE_LIBRARIES)) {
                mid.cancel();
            }
        };
        LauncherException e2 = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("1.21.4", cancelAtLibraries, mid));
        assertTrue(e2.isCancelled());
        assertFalse(Files.exists(paths.assets().resolve("indexes/19.json")), "stopped before assets");
    }

    @Test
    void maliciousMetadataIsRejected() throws Exception {
        String base = server.base().toString();
        byte[] client = MinecraftFixtures.clientJar("evil");
        fx.serveAt("/piston-data/evil.jar", client);
        String clientInfo = "\"downloads\":{\"client\":{\"sha1\":\"" + Hashing.hash(client, HashAlgo.SHA1) + "\",\"size\":"
            + client.length + ",\"url\":\"" + base + "piston-data/evil.jar\"}}";

        MinecraftFixtures.writeVersion(paths.versions(), "evil1", "{\"id\":\"evil1\",\"mainClass\":\"a.B\"," + clientInfo
            + ",\"libraries\":[{\"name\":\"a:b:1\",\"downloads\":{\"artifact\":{\"path\":\"../../../escape.jar\",\"url\":\""
            + base + "x.jar\"}}}]}");
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> installer(LINUX).install("evil1", ProgressListener.NONE, CancellationToken.NONE)).code());

        MinecraftFixtures.writeVersion(paths.versions(), "evil2", "{\"id\":\"evil2\",\"mainClass\":\"a.B\"," + clientInfo
            + ",\"libraries\":[{\"name\":\"../..:b:1\",\"url\":\"" + base + "\"}]}");
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> installer(LINUX).install("evil2", ProgressListener.NONE, CancellationToken.NONE)).code());

        MinecraftFixtures.writeVersion(paths.versions(), "evil3", "{\"id\":\"evil3\",\"mainClass\":\"a.B\"," + clientInfo
            + ",\"logging\":{\"client\":{\"argument\":\"-Dx=${path}\",\"file\":{\"id\":\"..\\\\..\\\\evil.xml\",\"url\":\""
            + base + "l.xml\"}}}}");
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> installer(LINUX).install("evil3", ProgressListener.NONE, CancellationToken.NONE)).code());

        byte[] badIndex = "{\"objects\":{\"a.png\":{\"hash\":\"../../../../etc/passwd\",\"size\":1}}}".getBytes(StandardCharsets.UTF_8);
        fx.serveAt("/piston-meta/bad.json", badIndex);
        MinecraftFixtures.writeVersion(paths.versions(), "evil4", "{\"id\":\"evil4\",\"mainClass\":\"a.B\"," + clientInfo
            + ",\"assetIndex\":{\"id\":\"bad\",\"url\":\"" + base + "piston-meta/bad.json\"}}");
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class,
            () -> installer(LINUX).install("evil4", ProgressListener.NONE, CancellationToken.NONE)).code());

        byte[] escapeIndex = ("{\"virtual\":true,\"objects\":{\"../../../escape.txt\":{\"hash\":\""
            + Hashing.hash(client, HashAlgo.SHA1) + "\",\"size\":" + client.length + "}}}").getBytes(StandardCharsets.UTF_8);
        fx.serveAt("/piston-meta/escape.json", escapeIndex);
        fx.serveAt("/resources/" + Hashing.hash(client, HashAlgo.SHA1).substring(0, 2) + "/" + Hashing.hash(client, HashAlgo.SHA1), client);
        MinecraftFixtures.writeVersion(paths.versions(), "evil5", "{\"id\":\"evil5\",\"mainClass\":\"a.B\"," + clientInfo
            + ",\"assetIndex\":{\"id\":\"escape\",\"url\":\"" + base + "piston-meta/escape.json\"}}");
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> installer(LINUX).install("evil5", ProgressListener.NONE, CancellationToken.NONE)).code());
        assertFalse(Files.exists(paths.assets().resolve("../escape.txt").normalize()));

        MinecraftFixtures.writeVersion(paths.versions(), "evil6", "{\"id\":\"evil6\",\"mainClass\":\"a.B\"," + clientInfo
            + ",\"assetIndex\":{\"id\":\"../../idx\",\"url\":\"" + base + "piston-meta/escape.json\"}}");
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> installer(LINUX).install("evil6", ProgressListener.NONE, CancellationToken.NONE)).code());

        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> installer(LINUX).install("../evil", ProgressListener.NONE, CancellationToken.NONE)).code());
    }

    @Test
    void locallyGeneratedLoaderLibraries() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        String child = """
            {"id":"neoforge-21.1.77","inheritsFrom":"1.21.4","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
             "arguments":{"game":["--fml.neoForgeVersion","21.1.77"],"jvm":["-DlibraryDirectory=${library_directory}"]},
             "libraries":[{"name":"net.neoforged:neoforge:21.1.77:client","downloads":{"artifact":{
               "path":"net/neoforged/neoforge/21.1.77/neoforge-21.1.77-client.jar","sha1":"","size":0,"url":""}}}]}""";
        MinecraftFixtures.writeVersion(paths.versions(), "neoforge-21.1.77", child);
        LauncherException e = assertThrows(LauncherException.class,
            () -> installer(WINDOWS).install("neoforge-21.1.77", ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.DEPENDENCY_MISSING, e.code());
        Path generated = paths.libraries().resolve("net/neoforged/neoforge/21.1.77/neoforge-21.1.77-client.jar");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "made by the installer's processors");
        InstalledVersion iv = installer(WINDOWS).install("neoforge-21.1.77", ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(generated, iv.classpath().get(0));
        assertEquals("neoforge-21.1.77.jar", iv.clientJar().getFileName().toString());
    }

    @Test
    void concurrentInstallsOfTheSameVersion() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        fx.prepare("fabric-loader-0.16.10-1.21.4.json");
        MinecraftFixtures.writeVersion(paths.versions(), "fabric-loader-0.16.10-1.21.4",
            fx.prepare("fabric-loader-0.16.10-1.21.4.json"));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<InstalledVersion>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> installer(WINDOWS).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE)));
            futures.add(pool.submit(() -> installer(WINDOWS).install("1.21.4", ProgressListener.NONE, CancellationToken.NONE)));
            futures.add(pool.submit(() -> installer(WINDOWS).install("fabric-loader-0.16.10-1.21.4", ProgressListener.NONE,
                CancellationToken.NONE)));
            InstalledVersion a = futures.get(0).get();
            assertEquals(a, futures.get(1).get());
            assertNotEquals(a.clientJar(), futures.get(2).get().clientJar());
        } finally {
            pool.shutdownNow();
        }
        assertTrue(installer(WINDOWS).verify("fabric-loader-0.16.10-1.21.4", ProgressListener.NONE, CancellationToken.NONE).isOk());
    }

    @Test
    void hashlessLibrariesAreAcceptedWhenPresent() throws Exception {
        fx.publishTemplate("1.21.4.json", "release");
        String id = "fabric-loader-0.16.10-1.21.4";
        MinecraftFixtures.writeVersion(paths.versions(), id, fx.prepare("fabric-loader-0.16.10-1.21.4.json"));
        installer(LINUX).install(id, ProgressListener.NONE, CancellationToken.NONE);
        int hits = server.hits("/fabric-maven/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar");
        installer(LINUX).install(id, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(hits, server.hits("/fabric-maven/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"));
        Files.delete(paths.libraries().resolve("net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"));
        VerifyReport r = installer(LINUX).verify(id, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(1, r.missing().size());
    }
}
