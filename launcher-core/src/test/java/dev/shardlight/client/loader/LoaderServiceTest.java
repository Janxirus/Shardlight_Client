package dev.shardlight.client.loader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.MinecraftFixtures;
import dev.shardlight.client.minecraft.VersionManifestService;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fabric and Quilt through a local copy of their metadata APIs, and the service that picks and installs loaders. */
class LoaderServiceTest {
    static final String FABRIC_PROFILE = "/fabric/v2/versions/loader/1.21.4/0.16.10/profile/json";

    @TempDir
    Path dir;
    TestHttpServer server;
    MinecraftFixtures fx;
    AppPaths paths;
    HttpService http;
    DownloadManager downloads;
    GameInstaller installer;
    FabricProvider fabric;
    QuiltProvider quilt;
    LoaderService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestHttpServer();
        fx = new MinecraftFixtures(server);
        paths = new AppPaths(dir.resolve("data"));
        paths.createDirectories();
        http = HttpService.forTests();
        downloads = new DownloadManager(http, paths.files(), 4, 1);
        VersionManifestService manifest = new VersionManifestService(http, fx.endpoints, paths);
        installer = new GameInstaller(http, fx.endpoints, paths, downloads, manifest, OsInfo.current());
        fabric = new FabricProvider(http, fx.endpoints, paths);
        quilt = new QuiltProvider(http, fx.endpoints, paths);
        service = new LoaderService(List.of(fabric, quilt), installer);

        server.json("/fabric/v2/versions/game", """
            [{"version":"1.21.4","stable":true},{"version":"25w03a","stable":false},{"version":"1.20.1","stable":true}]
            """);
        server.json("/fabric/v2/versions/loader/1.21.4", """
            [
              {"loader":{"separator":".","build":11,"maven":"net.fabricmc:fabric-loader:0.16.11-beta.1","version":"0.16.11-beta.1","stable":false}},
              {"loader":{"separator":".","build":10,"maven":"net.fabricmc:fabric-loader:0.16.10","version":"0.16.10","stable":true}},
              {"loader":{"separator":".","build":10,"maven":"net.fabricmc:fabric-loader:0.16.10","version":"0.16.10","stable":true}},
              {"loader":{"separator":".","build":9,"maven":"net.fabricmc:fabric-loader:0.16.9","version":"0.16.9","stable":true}},
              {"loader":{"separator":".","build":1,"maven":"evil","version":"../../escape","stable":true}},
              {"loader":null}
            ]
            """);
        server.json(FABRIC_PROFILE, MinecraftFixtures.template("fabric-loader-0.16.10-1.21.4.json"));
        server.json("/quilt/v3/versions/loader/1.21.4", """
            [
              {"loader":{"separator":".","build":90,"maven":"org.quiltmc:quilt-loader:0.28.0-beta.5","version":"0.28.0-beta.5"}},
              {"loader":{"separator":".","build":89,"maven":"org.quiltmc:quilt-loader:0.27.1","version":"0.27.1"}},
              {"loader":{"separator":".","build":88,"maven":"org.quiltmc:quilt-loader:0.27.0","version":"0.27.0"}}
            ]
            """);
    }

    @AfterEach
    void tearDown() {
        downloads.close();
        server.close();
    }

    /** the vanilla version a loader profile inherits from, as if it had been installed before */
    void vanillaInstalled() throws Exception {
        MinecraftFixtures.writeVersion(paths.versions(), "1.21.4", MinecraftFixtures.template("1.21.4.json"));
    }

    String quiltProfile(String loader) {
        JsonObject o = JsonParser.parseString(MinecraftFixtures.template("fabric-loader-0.16.10-1.21.4.json")).getAsJsonObject();
        o.addProperty("id", "quilt-loader-" + loader + "-1.21.4");
        o.addProperty("mainClass", "org.quiltmc.loader.impl.launch.knot.KnotClient");
        return Json.write(o);
    }

    @Test
    void fabricVersionsAreListedNewestFirstWithTheNewestStableRecommended() throws Exception {
        List<LoaderVersion> versions = fabric.versions("1.21.4", CancellationToken.NONE);
        assertEquals(List.of(
            new LoaderVersion("0.16.11-beta.1", false, false),
            new LoaderVersion("0.16.10", true, true),
            new LoaderVersion("0.16.9", true, false)), versions, "duplicates, broken and path-escaping entries are dropped");

        fabric.versions("1.21.4", CancellationToken.NONE);
        assertEquals(1, server.hits("/fabric/v2/versions/loader/1.21.4"), "the list is kept for a while");
        assertEquals(versions, service.versions(LoaderType.FABRIC, "1.21.4", CancellationToken.NONE));
        assertTrue(service.versions(LoaderType.VANILLA, "1.21.4", CancellationToken.NONE).isEmpty());
        assertTrue(service.versions(LoaderType.FORGE, "1.21.4", CancellationToken.NONE).isEmpty(), "no Forge provider here");
    }

    @Test
    void anUnknownMinecraftVersionHasNoLoaderVersions() throws Exception {
        assertTrue(fabric.versions("2.0", CancellationToken.NONE).isEmpty());
        assertThrows(LauncherException.class, () -> fabric.versions("../1.21.4", CancellationToken.NONE));
    }

    @Test
    void supportedMinecraftVersionsComeFromTheGameList() throws Exception {
        assertTrue(fabric.supportsMinecraft("1.21.4", CancellationToken.NONE));
        assertTrue(fabric.supportsMinecraft("25w03a", CancellationToken.NONE));
        assertFalse(fabric.supportsMinecraft("1.2.5", CancellationToken.NONE));
        assertFalse(fabric.supportsMinecraft("../x", CancellationToken.NONE));
        assertEquals(1, server.hits("/fabric/v2/versions/game"));
    }

    @Test
    void quiltCountsSuffixedVersionsAsUnstable() throws Exception {
        assertEquals(List.of(
            new LoaderVersion("0.28.0-beta.5", false, false),
            new LoaderVersion("0.27.1", true, true),
            new LoaderVersion("0.27.0", true, false)), quilt.versions("1.21.4", CancellationToken.NONE));
        assertEquals("quilt-loader-0.27.1-1.21.4", quilt.versionId("1.21.4", "0.27.1"));
    }

    @Test
    void installingFabricSavesItsProfile() throws Exception {
        List<String> stages = new ArrayList<>();
        ProgressListener progress = (stage, detail, done, total) -> stages.add(stage);
        String id = fabric.install("1.21.4", "0.16.10", progress, CancellationToken.NONE);
        assertEquals("fabric-loader-0.16.10-1.21.4", id);
        assertEquals(fabric.versionId("1.21.4", "0.16.10"), id);
        Path json = paths.versions().resolve(id).resolve(id + ".json");
        assertTrue(Files.isRegularFile(json));
        assertEquals("1.21.4", installer.readVersionJson(id).inheritsFrom());
        assertTrue(stages.contains(MetaLoaderProvider.STAGE_PROFILE));
    }

    @Test
    void badProfilesAreRefusedBeforeAnythingIsWritten() throws Exception {
        LauncherException missing = assertThrows(LauncherException.class,
            () -> fabric.install("1.21.4", "0.1.0", null, null));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, missing.code());

        JsonObject wrongParent = JsonParser.parseString(MinecraftFixtures.template("fabric-loader-0.16.10-1.21.4.json"))
            .getAsJsonObject();
        wrongParent.addProperty("inheritsFrom", "1.20.1");
        server.json(FABRIC_PROFILE, Json.write(wrongParent));
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class,
            () -> fabric.install("1.21.4", "0.16.10", null, null)).code());

        JsonObject escaping = wrongParent.deepCopy();
        escaping.addProperty("inheritsFrom", "1.21.4");
        escaping.addProperty("id", "../../outside");
        server.json(FABRIC_PROFILE, Json.write(escaping));
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> fabric.install("1.21.4", "0.16.10", null, null)).code());

        server.json(FABRIC_PROFILE, "{ not json");
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class,
            () -> fabric.install("1.21.4", "0.16.10", null, null)).code());

        assertFalse(Files.exists(paths.versions().resolve("fabric-loader-0.16.10-1.21.4")));
        assertFalse(Files.exists(dir.resolve("outside.json")));
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> fabric.install("1.21.4", "../0.16.10", null, null)).code());
    }

    @Test
    void ensureInstalledPicksTheRecommendedVersionAndWorksOfflineAfterwards() throws Exception {
        vanillaInstalled();
        assertEquals("1.21.4", service.ensureInstalled(" 1.21.4 ", LoaderSpec.vanilla(), null, null));
        assertEquals("1.21.4", service.ensureInstalled("1.21.4", null, null, null));

        String id = service.ensureInstalled("1.21.4", new LoaderSpec(LoaderType.FABRIC, " "), null, CancellationToken.NONE);
        assertEquals("fabric-loader-0.16.10-1.21.4", id, "no version means the recommended one");
        assertEquals(1, server.hits(FABRIC_PROFILE));

        String again = service.ensureInstalled("1.21.4", new LoaderSpec(LoaderType.FABRIC, "0.16.10"), null, null);
        assertEquals(id, again);
        assertEquals(1, server.hits(FABRIC_PROFILE), "an installed loader starts without the network");

        service.reinstall("1.21.4", new LoaderSpec(LoaderType.FABRIC, "0.16.10"), null, null);
        assertEquals(2, server.hits(FABRIC_PROFILE));

        Files.writeString(paths.versions().resolve(id).resolve(id + ".json"), "{ broken");
        service.ensureInstalled("1.21.4", new LoaderSpec(LoaderType.FABRIC, "0.16.10"), null, null);
        assertEquals(3, server.hits(FABRIC_PROFILE), "an unreadable version file is installed again");
    }

    @Test
    void quiltInstallsThroughTheSameService() throws Exception {
        server.json("/quilt/v3/versions/loader/1.21.4/0.27.1/profile/json", quiltProfile("0.27.1"));
        String id = service.ensureInstalled("1.21.4", new LoaderSpec(LoaderType.QUILT, null), null, null);
        assertEquals("quilt-loader-0.27.1-1.21.4", id);
    }

    @Test
    void loadersWithoutVersionsOrProvidersAreExplained() {
        LauncherException none = assertThrows(LauncherException.class,
            () -> service.ensureInstalled("1.2.5", new LoaderSpec(LoaderType.FABRIC, null), null, null));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, none.code());
        assertEquals("loader.error.noVersions", none.messageKey());

        LauncherException forge = assertThrows(LauncherException.class,
            () -> service.ensureInstalled("1.21.4", new LoaderSpec(LoaderType.FORGE, "54.0.12"), null, null));
        assertEquals("loader.error.noProvider", forge.messageKey());

        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> service.ensureInstalled("../1.21.4", LoaderSpec.vanilla(), null, null)).code());
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> service.ensureInstalled(null, LoaderSpec.vanilla(), null, null)).code());
    }

    @Test
    void cancellingStopsBeforeTheNetwork() {
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        LauncherException e = assertThrows(LauncherException.class,
            () -> service.ensureInstalled("1.21.4", new LoaderSpec(LoaderType.FABRIC, null), null, cancel));
        assertTrue(e.isCancelled());
    }

    @Test
    void loaderTypesParseTheirCommonSpellings() {
        assertEquals(LoaderType.FABRIC, LoaderType.parse("fabric"));
        assertEquals(LoaderType.FABRIC, LoaderType.parse("fabric-loader"));
        assertEquals(LoaderType.QUILT, LoaderType.parse("quilt_loader"));
        assertEquals(LoaderType.NEOFORGE, LoaderType.parse("NeoForge"));
        assertEquals(LoaderType.NEOFORGE, LoaderType.parse("neo-forge"));
        assertEquals(LoaderType.VANILLA, LoaderType.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> LoaderType.parse("liteloader"));
        assertTrue(LoaderType.QUILT.acceptsModsFor(LoaderType.FABRIC));
        assertFalse(LoaderType.FABRIC.acceptsModsFor(LoaderType.QUILT));
        assertNull(new LoaderSpec(LoaderType.VANILLA, "1.0").version, "vanilla has no loader version");
        assertEquals("Fabric 0.19.5", new LoaderSpec(LoaderType.FABRIC, "0.19.5").toString());
    }

    @Test
    void loaderVersionsCompareNumerically() {
        List<String> v = new ArrayList<>(List.of("21.1.9", "21.1.77", "21.4.0-beta", "21.4.0", "21.10.1", "0.9"));
        v.sort(LoaderVersions.ORDER.reversed());
        assertEquals(List.of("21.10.1", "21.4.0", "21.4.0-beta", "21.1.77", "21.1.9", "0.9"), v);
        assertEquals(0, LoaderVersions.compare("1.0", "1.0.0"));
        assertTrue(LoaderVersions.compare("1.0-beta.2", "1.0-beta.1") > 0);
    }
}
