package dev.shardlight.client.live;

import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.loader.LoaderService;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.loader.LoaderVersion;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.ResolvedVersion;
import dev.shardlight.client.minecraft.VerifyReport;
import dev.shardlight.client.minecraft.VersionManifestService;
import dev.shardlight.client.minecraft.model.VersionJson;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real Forge and NeoForge installers (downloaded from their Maven repositories) for one Minecraft
 * version each, the way the launcher does for a project, and checks what they leave behind: the loader's version
 * file inherits from the Minecraft version, names a main class, and every library the launcher expects exists
 * with the right size and hash. Downloads the game, a Java runtime and the loader libraries, so it only runs
 * from CI with {@code -PinstallerTests}. The Minecraft version is {@code SHARDLIGHT_INSTALLER_MC} or 26.3.
 */
@Tag("installer")
class LoaderInstallersLiveTest {
    static final String MC = System.getenv("SHARDLIGHT_INSTALLER_MC") == null || System.getenv("SHARDLIGHT_INSTALLER_MC").isBlank()
        ? "26.3" : System.getenv("SHARDLIGHT_INSTALLER_MC").trim();

    @TempDir
    static Path data;
    static DownloadManager downloads;
    static GameInstaller installer;
    static LoaderService loaders;
    static AppPaths paths;

    @BeforeAll
    static void setUp() throws Exception {
        paths = new AppPaths(data.resolve("data"));
        paths.createDirectories();
        HttpService http = HttpService.create();
        downloads = new DownloadManager(http, paths.files(), 4, 3);
        VersionManifestService manifest = new VersionManifestService(http, Endpoints.DEFAULT, paths);
        installer = new GameInstaller(http, Endpoints.DEFAULT, paths, downloads, manifest);
        JavaRuntimeService java = new JavaRuntimeService(http, Endpoints.DEFAULT, paths, downloads);
        loaders = LoaderService.createDefault(http, Endpoints.DEFAULT, paths, downloads, installer, java);
    }

    @AfterAll
    static void tearDown() {
        if (downloads != null) {
            downloads.close();
        }
    }

    @Test
    void forgeInstallsForTheMinecraftVersion() throws Exception {
        installAndCheck(LoaderType.FORGE);
    }

    @Test
    void neoForgeInstallsForTheMinecraftVersion() throws Exception {
        installAndCheck(LoaderType.NEOFORGE);
    }

    private void installAndCheck(LoaderType type) throws Exception {
        List<LoaderVersion> available = loaders.versions(type, MC, CancellationToken.NONE);
        assertFalse(available.isEmpty(), type.displayName() + " lists no version for Minecraft " + MC);
        LoaderVersion pick = available.stream().filter(LoaderVersion::recommended).findFirst().orElse(available.get(0));
        System.out.println(type.displayName() + ": " + available.size() + " versions for " + MC + ", installing " + pick.version());

        String id = loaders.ensureInstalled(MC, new LoaderSpec(type, pick.version()), ProgressListener.NONE, CancellationToken.NONE);

        assertEquals(loaders.provider(type).orElseThrow().versionId(MC, pick.version()), id,
            "the installer created a version id other than the one the launcher expects");
        Path json = paths.versions().resolve(id).resolve(id + ".json");
        assertTrue(Files.isRegularFile(json), "no version file at " + json);
        VersionJson v = installer.readVersionJson(id);
        assertEquals(id, v.id());
        assertEquals(MC, v.inheritsFrom());
        assertNotNull(v.mainClass(), "the version file names no main class");

        ResolvedVersion resolved = installer.resolve(id);
        assertEquals(List.of(id, MC), resolved.chain());
        assertNotNull(resolved.clientDownload(), "the vanilla client is part of the chain");

        VerifyReport report = installer.verify(id, ProgressListener.NONE, CancellationToken.NONE);
        assertTrue(report.checkedFiles() > 0);
        assertTrue(report.isOk(), "missing: " + report.missing().stream().map(Path::toString).collect(Collectors.joining(", "))
            + "; corrupt: " + report.corrupt().stream().map(Path::toString).collect(Collectors.joining(", ")));
    }
}
