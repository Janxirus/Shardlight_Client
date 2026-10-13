package dev.shardlight.client.java;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRuntimeServiceTest {
    static final OsInfo WINDOWS = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X64, "10.0");
    static final OsInfo LINUX = new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.X64, "6.8.0");
    static final OsInfo LINUX_ARM = new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.ARM64, "6.8.0");

    /** answers like {@code java -XshowSettings:properties -version} would, by real path */
    static final class FakeProber implements JavaProber {
        final Map<Path, JavaProperties> answers = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();

        void answer(Path exe, String version, String vendor, String arch) throws IOException {
            answers.put(exe.toRealPath(), new JavaProperties(version, JavaProperties.majorOf(version), vendor, arch));
        }

        @Override
        public Optional<JavaProperties> probe(Path exe) {
            calls.incrementAndGet();
            try {
                return Optional.ofNullable(answers.get(exe.toRealPath()));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }

    @TempDir
    Path dir;
    TestHttpServer server;
    Endpoints endpoints;
    AppPaths paths;
    HttpService http;
    DownloadManager downloads;
    FakeProber prober;
    Path jvmDir;

    @BeforeEach
    void setUp() {
        server = new TestHttpServer();
        endpoints = Endpoints.local(server.base());
        paths = new AppPaths(dir.resolve("data"));
        http = HttpService.forTests();
        downloads = new DownloadManager(http, null, 4, 0);
        prober = new FakeProber();
        jvmDir = dir.resolve("usr/lib/jvm");
    }

    @AfterEach
    void tearDown() {
        downloads.close();
        server.close();
    }

    JavaRuntimeService service(OsInfo os) {
        return new JavaRuntimeService(http, endpoints, paths, downloads,
            new JavaEnvironment(os, Map.of(), dir.resolve("home"), List.of(jvmDir)), prober);
    }

    JavaRuntimeService windowsService(Path programFiles) {
        return new JavaRuntimeService(http, endpoints, paths, downloads,
            new JavaEnvironment(WINDOWS, Map.of("ProgramFiles", programFiles.toString()), dir.resolve("home"), List.of()), prober);
    }

    /** a fake Linux JDK under usr/lib/jvm answering the prober */
    Path linuxJdk(String folder, String version, String arch) throws IOException {
        Path java = jvmDir.resolve(folder).resolve("bin/java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\n");
        prober.answer(java, version, "Test Vendor", arch);
        return java;
    }

    // ---------------------------------------------------------------- detection

    @Test
    void detectsProbesOnceAndSortsNewestFirst() throws Exception {
        Path j21 = linuxJdk("java-21-openjdk-amd64", "21.0.5", "amd64");
        Path j17 = linuxJdk("java-17-openjdk-amd64", "17.0.12", "amd64");
        Path j8 = linuxJdk("java-8-openjdk-amd64", "1.8.0_402", "amd64");
        Path broken = jvmDir.resolve("broken/bin/java");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "not java");
        // the same JDK reached through a link is listed once
        Files.createSymbolicLink(jvmDir.resolve("default-java"), jvmDir.resolve("java-21-openjdk-amd64"));

        JavaRuntimeService s = service(LINUX);
        List<JavaInstall> found = s.detect();
        assertEquals(List.of(21, 17, 8), found.stream().map(JavaInstall::majorVersion).toList());
        assertEquals(j21.toRealPath(), found.get(0).executable().toRealPath());
        assertEquals("1.8.0_402", found.get(2).version());
        assertFalse(found.get(0).managed());
        int calls = prober.calls.get();
        assertEquals(4, calls, "three JDKs and the broken one, the link deduplicated");

        s.detect();
        assertEquals(calls + 1, prober.calls.get(), "only the failed probe is retried");
        // a new launcher session reads the probe cache from disk
        JavaRuntimeService next = service(LINUX);
        assertEquals(3, next.detect().size());
        assertEquals(calls + 2, prober.calls.get());
        // an updated JDK (new modification time) is probed again
        Files.setLastModifiedTime(j17, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        prober.answer(j17, "17.0.13", "Test Vendor", "amd64");
        assertTrue(next.detect().stream().anyMatch(j -> j.version().equals("17.0.13")));
        assertTrue(Files.isRegularFile(paths.cache().resolve(JavaRuntimeService.PROBE_CACHE)));
        assertTrue(s.probe(j8).isPresent());
        assertTrue(s.probe(jvmDir.resolve("java-8-openjdk-amd64")).isPresent(), "a Java home folder works too");
        assertTrue(s.probe(dir.resolve("nothing")).isEmpty());
        assertTrue(s.probe(null).isEmpty());
    }

    @Test
    void windowsLaunchesWithJavawButProbesJava() throws Exception {
        Path pf = dir.resolve("Program Files");
        Path bin = pf.resolve("Eclipse Adoptium/jdk-21.0.5.11-hotspot/bin");
        Files.createDirectories(bin);
        Path java = Files.writeString(bin.resolve("java.exe"), "MZ");
        Path javaw = Files.writeString(bin.resolve("javaw.exe"), "MZ");
        prober.answer(java, "21.0.5", "Eclipse Adoptium", "amd64");
        JavaRuntimeService s = windowsService(pf);
        List<JavaInstall> found = s.detect();
        assertEquals(1, found.size());
        assertEquals(javaw.toAbsolutePath().normalize(), found.get(0).executable());
        assertEquals("Eclipse Adoptium", found.get(0).vendor());
        // giving javaw.exe itself probes java.exe next to it
        assertEquals(javaw.toAbsolutePath().normalize(), s.probe(javaw).orElseThrow().executable());
        // without javaw.exe next to it, java.exe is used for launching
        Path bin2 = pf.resolve("Java/jdk-17/bin");
        Files.createDirectories(bin2);
        Path java17 = Files.writeString(bin2.resolve("java.exe"), "MZ");
        prober.answer(java17, "17.0.12", "Oracle Corporation", "amd64");
        assertEquals(java17.toAbsolutePath().normalize(), s.probe(java17).orElseThrow().executable());
    }

    // ---------------------------------------------------------------- selection

    @Test
    void selectPrefersTheExactMajorAndTheNativeArchitecture() throws Exception {
        linuxJdk("java-21-x86", "21.0.9", "x86");
        Path j21 = linuxJdk("java-21-openjdk-amd64", "21.0.5", "amd64");
        linuxJdk("java-21-older-amd64", "21.0.2", "amd64");
        linuxJdk("java-25-openjdk-amd64", "25.0.1", "amd64");
        Path j17 = linuxJdk("java-17-openjdk-amd64", "17.0.12", "amd64");
        JavaRuntimeService s = service(LINUX);
        JavaInstall pick = s.select(21, "java-runtime-delta", null, false, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(j21.toAbsolutePath().normalize(), pick.executable(), "native arch first, then the newest update");
        assertEquals(j17.toAbsolutePath().normalize(),
            s.select(17, null, " ", false, ProgressListener.NONE, CancellationToken.NONE).executable());
        assertEquals(21, s.select(18, null, null, false, ProgressListener.NONE, CancellationToken.NONE).majorVersion(),
            "the closest newer Java when there is no exact match");
        assertEquals(17, s.select(16, null, null, false, ProgressListener.NONE, CancellationToken.NONE).majorVersion());
        assertEquals(0, server.requests().size(), "nothing downloaded");
    }

    @Test
    void projectOverride() throws Exception {
        Path j17 = linuxJdk("java-17-openjdk-amd64", "17.0.12", "amd64");
        Path j25 = linuxJdk("java-25-openjdk-amd64", "25.0.1", "amd64");
        JavaRuntimeService s = service(LINUX);
        assertEquals(j25.toAbsolutePath().normalize(),
            s.select(21, "java-runtime-delta", j25.toString(), true, ProgressListener.NONE, CancellationToken.NONE).executable(),
            "a newer override is fine and wins over Mojang's runtime");

        LauncherException old = assertThrows(LauncherException.class,
            () -> s.select(21, "java-runtime-delta", j17.toString(), true, ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.JAVA_NOT_FOUND, old.code());
        assertEquals("install.java.overrideTooOld", old.messageKey());
        assertTrue(old.getMessage().contains("21"), old.getMessage());

        LauncherException missing = assertThrows(LauncherException.class, () -> s.select(21, null,
            dir.resolve("gone/java").toString(), false, ProgressListener.NONE, CancellationToken.NONE));
        assertEquals("install.java.overrideMissing", missing.messageKey());
        assertTrue(missing.getMessage().contains("Java 21"), missing.getMessage());

        Path notJava = Files.writeString(dir.resolve("notjava"), "hello");
        assertEquals("install.java.overrideInvalid", assertThrows(LauncherException.class, () -> s.select(21, null,
            notJava.toString(), false, ProgressListener.NONE, CancellationToken.NONE)).messageKey());
        assertEquals(0, server.requests().size());
    }

    @Test
    void selectPrefersMojangsRuntimeAndFallsBack() throws Exception {
        Path j21 = linuxJdk("java-21-openjdk-amd64", "21.0.5", "amd64");
        JavaRuntimeService s = service(LINUX);
        // Mojang's list is down and nothing is installed from it: an installed Java is used
        server.text("/runtimes/all.json", 503, "down");
        JavaInstall fallback = s.select(21, "java-runtime-delta", null, true, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(j21.toAbsolutePath().normalize(), fallback.executable());
        assertFalse(fallback.managed());

        RuntimeFixture rt = new RuntimeFixture("linux", "java-runtime-delta", "21.0.7");
        rt.publish();
        JavaInstall mojang = s.select(21, "java-runtime-delta", null, true, ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(mojang.managed());
        assertTrue(mojang.executable().startsWith(paths.runtimes()));
        assertEquals("21.0.7", mojang.version());
        // without preferring Mojang, an installed Java of the right version is enough (the managed one ranks first)
        JavaInstall installed = s.select(21, "java-runtime-delta", null, false, ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(installed.managed());
    }

    @Test
    void javaEightVersionsTryMojangBeforeANewerJava() throws Exception {
        RuntimeFixture rt = new RuntimeFixture("linux", "jre-legacy", "8u202");
        rt.publish();
        linuxJdk("java-17-openjdk-amd64", "17.0.12", "amd64");
        JavaRuntimeService s = service(LINUX);
        JavaInstall eight = s.select(8, null, null, false, ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(eight.managed(), "old Minecraft breaks on Java 9+, so Mojang's Java 8 is downloaded");
        assertEquals(8, eight.majorVersion());
        assertTrue(eight.executable().toString().contains("jre-legacy"));
    }

    @Test
    void nothingFoundIsAClearError() {
        server.text("/runtimes/all.json", 404, "gone");
        JavaRuntimeService s = service(LINUX);
        LauncherException e = assertThrows(LauncherException.class,
            () -> s.select(21, "java-runtime-delta", null, true, ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.JAVA_NOT_FOUND, e.code());
        assertEquals("install.java.noneFound", e.messageKey());
        assertTrue(e.getMessage().contains("21"));
    }

    // ---------------------------------------------------------------- Mojang runtimes

    /** serves all.json and one runtime manifest the way Mojang's piston-meta does */
    final class RuntimeFixture {
        final String platform;
        final String component;
        final String versionName;
        final Map<String, JsonObject> files = new LinkedHashMap<>();
        final Map<String, byte[]> contents = new LinkedHashMap<>();
        String manifestSha1;

        RuntimeFixture(String platform, String component, String versionName) {
            this.platform = platform;
            this.component = component;
            this.versionName = versionName;
            boolean win = platform.startsWith("windows");
            directory("bin");
            directory("lib");
            file(win ? "bin/java.exe" : "bin/java", ("java " + versionName).getBytes(StandardCharsets.UTF_8), !win);
            if (win) {
                file("bin/javaw.exe", ("javaw " + versionName).getBytes(StandardCharsets.UTF_8), false);
            }
            file("lib/modules", ("modules of " + versionName).getBytes(StandardCharsets.UTF_8), false);
            file("legal/java.base/LICENSE", "GPLv2 + CPE".getBytes(StandardCharsets.UTF_8), false);
            link("legal/java.desktop/LICENSE", "../java.base/LICENSE");
            JsonObject lzmaOnly = new JsonObject();
            lzmaOnly.addProperty("type", "unknown-future-type");
            files.put("release-notes", lzmaOnly);
        }

        void directory(String path) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "directory");
            files.put(path, o);
        }

        void file(String path, byte[] data, boolean executable) {
            String sha1 = Hashing.hash(data, HashAlgo.SHA1);
            String url = server.base() + "piston-data/v1/objects/" + sha1 + "/" + path.substring(path.lastIndexOf('/') + 1);
            server.bytes("/piston-data/v1/objects/" + sha1 + "/" + path.substring(path.lastIndexOf('/') + 1), data);
            JsonObject raw = new JsonObject();
            raw.addProperty("sha1", sha1);
            raw.addProperty("size", data.length);
            raw.addProperty("url", url);
            JsonObject lzma = raw.deepCopy();
            lzma.addProperty("url", url + ".lzma");
            JsonObject downloads = new JsonObject();
            downloads.add("lzma", lzma);
            downloads.add("raw", raw);
            JsonObject o = new JsonObject();
            o.add("downloads", downloads);
            o.addProperty("executable", executable);
            o.addProperty("type", "file");
            files.put(path, o);
            contents.put(path, data);
        }

        void link(String path, String target) {
            JsonObject o = new JsonObject();
            o.addProperty("target", target);
            o.addProperty("type", "link");
            files.put(path, o);
        }

        void publish() throws IOException {
            JsonObject manifest = new JsonObject();
            JsonObject f = new JsonObject();
            files.forEach(f::add);
            manifest.add("files", f);
            byte[] data = Json.COMPACT.toJson(manifest).getBytes(StandardCharsets.UTF_8);
            manifestSha1 = Hashing.hash(data, HashAlgo.SHA1);
            String path = "/piston-meta/v1/packages/" + manifestSha1 + "/manifest.json";
            server.bytes(path, data);

            JsonObject entry = new JsonObject();
            JsonObject availability = new JsonObject();
            availability.addProperty("group", 5851);
            availability.addProperty("progress", 100);
            entry.add("availability", availability);
            JsonObject m = new JsonObject();
            m.addProperty("sha1", manifestSha1);
            m.addProperty("size", data.length);
            m.addProperty("url", server.base() + path.substring(1));
            entry.add("manifest", m);
            JsonObject version = new JsonObject();
            version.addProperty("name", versionName);
            version.addProperty("released", "2025-04-15T13:46:01+00:00");
            entry.add("version", version);
            JsonArray list = new JsonArray();
            list.add(entry);

            JsonObject index = new JsonObject();
            for (String pl : List.of("gamecore", "linux", "linux-i386", "mac-os", "mac-os-arm64", "windows-arm64", "windows-x64",
                "windows-x86")) {
                JsonObject comps = new JsonObject();
                comps.add("minecraft-java-exe", new JsonArray());
                if (pl.equals(platform)) {
                    comps.add(component, list);
                }
                index.add(pl, comps);
            }
            server.json("/runtimes/all.json", Json.write(index));
            Path java = runtimeDir().resolve(platform.startsWith("windows") ? "bin/java.exe" : "bin/java");
            Files.createDirectories(java.getParent());
            if (!Files.exists(java)) {
                Files.writeString(java, "placeholder so the prober can resolve it");
                prober.answer(java, versionName.replace("8u202", "1.8.0_202"), "Mojang", "amd64");
                Files.delete(java);
            }
        }

        Path runtimeDir() {
            return paths.runtimes().resolve(component).resolve(platform);
        }
    }

    @Test
    void installsMojangRuntimeWithLinksAndExecutableFlags() throws Exception {
        RuntimeFixture rt = new RuntimeFixture("linux", "java-runtime-delta", "21.0.7");
        rt.publish();
        JavaRuntimeService s = service(LINUX);
        prober.answers.clear();
        AtomicInteger stage = new AtomicInteger();
        JavaInstall j = s.ensureMojangRuntime("java-runtime-delta", 21, (key, detail, done, total) -> {
            if (key.equals(JavaRuntimeService.STAGE_JAVA)) {
                stage.incrementAndGet();
            }
        }, CancellationToken.NONE);
        Path home = rt.runtimeDir();
        Path java = home.resolve("bin/java");
        assertEquals(java, j.executable());
        assertTrue(j.managed());
        assertTrue(stage.get() > 0);
        assertTrue(Files.isExecutable(java));
        assertArrayEquals(rt.contents.get("lib/modules"), Files.readAllBytes(home.resolve("lib/modules")));
        Path link = home.resolve("legal/java.desktop/LICENSE");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("GPLv2 + CPE", Files.readString(link));
        assertTrue(Files.isRegularFile(home.resolve(JavaRuntimeService.MARKER)));
        // nothing probed it (the fake prober doesn't know it), so the metadata version is trusted
        assertEquals(21, j.majorVersion());
        assertEquals("21.0.7", j.version());

        // installed and current: no second download
        int before = server.requests().size();
        JavaInstall again = s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(j.executable(), again.executable());
        assertEquals(before + 1, server.requests().size(), "only all.json was read");

        // offline: the installed runtime is used
        server.text("/runtimes/all.json", 500, "down");
        assertEquals(j.executable(), s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE,
            CancellationToken.NONE).executable());
    }

    @Test
    void windowsRuntimeLaunchesWithJavaw() throws Exception {
        RuntimeFixture rt = new RuntimeFixture("windows-x64", "java-runtime-epsilon", "25.0.1");
        rt.publish();
        JavaRuntimeService s = windowsService(dir.resolve("pf"));
        JavaInstall j = s.ensureMojangRuntime("java-runtime-epsilon", 25, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(rt.runtimeDir().resolve("bin/javaw.exe"), j.executable());
        assertEquals(25, j.majorVersion());
    }

    @Test
    void updatedRuntimeReplacesOldFiles() throws Exception {
        RuntimeFixture v1 = new RuntimeFixture("linux", "java-runtime-delta", "21.0.6");
        v1.file("lib/old-only.so", "old".getBytes(StandardCharsets.UTF_8), false);
        v1.publish();
        JavaRuntimeService s = service(LINUX);
        s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE);
        Path old = v1.runtimeDir().resolve("lib/old-only.so");
        assertTrue(Files.exists(old));
        RuntimeFixture v2 = new RuntimeFixture("linux", "java-runtime-delta", "21.0.7");
        v2.publish();
        s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE);
        assertFalse(Files.exists(old), "files the new manifest doesn't list are removed");
        assertEquals("java 21.0.7", Files.readString(v2.runtimeDir().resolve("bin/java")));
        assertTrue(Files.readString(v2.runtimeDir().resolve(JavaRuntimeService.MARKER)).contains(v2.manifestSha1));
    }

    @Test
    void unavailableRuntimesAndPlatforms() throws Exception {
        RuntimeFixture rt = new RuntimeFixture("linux", "java-runtime-delta", "21.0.7");
        rt.publish();
        JavaRuntimeService s = service(LINUX);
        LauncherException none = assertThrows(LauncherException.class,
            () -> s.ensureMojangRuntime("java-runtime-zeta", 29, ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.JAVA_NOT_FOUND, none.code());
        assertEquals("install.java.runtimeUnavailable", none.messageKey());
        LauncherException arm = assertThrows(LauncherException.class, () -> service(LINUX_ARM)
            .ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE));
        assertEquals("install.java.runtimeUnavailable", arm.messageKey());
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> s.ensureMojangRuntime("../evil", 21, ProgressListener.NONE, CancellationToken.NONE)).code());
        server.json("/runtimes/all.json", "[1, 2, 3]");
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class,
            () -> s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE)).code());
    }

    @Test
    void tamperedManifestIsRejected() throws Exception {
        RuntimeFixture rt = new RuntimeFixture("linux", "java-runtime-delta", "21.0.7");
        rt.publish();
        server.bytes("/piston-meta/v1/packages/" + rt.manifestSha1 + "/manifest.json", "{\"files\":{}}".getBytes(StandardCharsets.UTF_8));
        JavaRuntimeService s = service(LINUX);
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, assertThrows(LauncherException.class,
            () -> s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE)).code());
    }

    @Test
    void maliciousManifestPathsAndLinksAreRejected() throws Exception {
        RuntimeFixture escape = new RuntimeFixture("linux", "java-runtime-delta", "21.0.7");
        escape.file("../../../escape.txt", "x".getBytes(StandardCharsets.UTF_8), false);
        escape.publish();
        JavaRuntimeService s = service(LINUX);
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE)).code());
        assertFalse(Files.exists(paths.runtimes().resolve("escape.txt")));

        RuntimeFixture link = new RuntimeFixture("linux", "java-runtime-delta", "21.0.8");
        link.link("lib/evil", "../../../../../../etc/passwd");
        link.publish();
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE)).code());
        assertFalse(Files.exists(link.runtimeDir().resolve("lib/evil")));

        RuntimeFixture absolute = new RuntimeFixture("linux", "java-runtime-delta", "21.0.9");
        absolute.link("lib/abs", "/etc/passwd");
        absolute.publish();
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> s.ensureMojangRuntime("java-runtime-delta", 21, ProgressListener.NONE, CancellationToken.NONE)).code());
    }

    @Test
    void cancelledBeforeDownloading() throws Exception {
        RuntimeFixture rt = new RuntimeFixture("linux", "java-runtime-delta", "21.0.7");
        rt.publish();
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        assertTrue(assertThrows(LauncherException.class, () -> service(LINUX).ensureMojangRuntime("java-runtime-delta", 21,
            ProgressListener.NONE, cancel)).isCancelled());
        assertFalse(Files.exists(rt.runtimeDir().resolve(JavaRuntimeService.MARKER)));
    }
}
