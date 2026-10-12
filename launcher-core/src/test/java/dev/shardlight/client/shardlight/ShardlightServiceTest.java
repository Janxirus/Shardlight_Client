package dev.shardlight.client.shardlight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.game.OptionsTxt;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.testutil.TestProjects;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Shardlight content library (imports, GitHub releases) and installing modules and theme packs into projects. */
class ShardlightServiceTest {
    static final String UI_PACK = "Shardlight UI - Ember (Molten Forge).zip";
    static final String SOUND_PACK = "Shardlight Sounds - Ember (Molten Forge).zip";

    @TempDir
    Path dir;
    AppPaths paths;
    TestProjects projects;
    ShardlightCatalog catalog;
    ShardlightContentLibrary library;
    ShardlightService service;
    TestHttpServer server;
    DownloadManager downloads;

    @BeforeEach
    void setUp() throws Exception {
        paths = new AppPaths(dir.resolve("data"));
        paths.createDirectories();
        projects = new TestProjects(paths);
        catalog = ShardlightCatalog.load();
        server = new TestHttpServer();
        HttpService http = HttpService.forTests();
        downloads = new DownloadManager(http, paths.files(), 2, 1);
        downloads.setBackoffMillis(5);
        library = new ShardlightContentLibrary(paths, http, downloads, catalog, server.uri("/github/"));
        service = new ShardlightService(paths, projects, catalog, library);
    }

    @AfterEach
    void tearDown() {
        downloads.close();
        server.close();
    }

    // ---------------------------------------------------------------- files

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] modJar(String modId, String version, String minecraft) throws IOException {
        return zip(Map.of("fabric.mod.json", utf8("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"" + version
            + "\",\"depends\":{\"minecraft\":\"" + minecraft + "\",\"fabricloader\":\">=0.19.5\"}}"), "a/A.class", new byte[64]));
    }

    static byte[] pack(String description) throws IOException {
        return zip(Map.of("pack.mcmeta", utf8("{\"pack\":{\"pack_format\":75,\"description\":\"" + description + "\"}}"),
            "assets/minecraft/textures/gui/x.png", new byte[32]));
    }

    Path file(String name, byte[] data) throws IOException {
        Path f = dir.resolve("incoming").resolve(name);
        Files.createDirectories(f.getParent());
        return Files.write(f, data);
    }

    LibraryItem importOne(String name, byte[] data) throws Exception {
        ImportResult r = library.importFile(file(name, data), CancellationToken.NONE);
        assertEquals(1, r.added().size(), r.skipped().toString());
        return r.added().get(0);
    }

    /** the four modules for 26.3 and the Ember packs, in the library */
    void fullLibrary() throws Exception {
        importOne("softglass-4.1.0.jar", modJar("softglass", "4.1.0", "~26.3"));
        importOne("shardmap-1.5.0.jar", modJar("shardmap", "1.5.0", "~26.3"));
        importOne("shardparty-1.2.1.jar", modJar("shardparty", "1.2.1", "26.3"));
        importOne("shardcompanion-1.2.1.jar", modJar("shardcompanion", "1.2.1", "26.3.x"));
        importOne(UI_PACK, pack("Ember"));
        importOne(SOUND_PACK, pack("Ember sounds"));
    }

    Project fabricProject(String id) throws LauncherException {
        return projects.create(id, "26.3", LoaderType.FABRIC, "0.19.5");
    }

    static ContentEntry mod(String modId, ContentSource source) {
        ContentEntry e = new ContentEntry();
        e.id = modId;
        e.kind = ContentKind.MOD;
        e.source = source;
        e.modId = modId;
        e.projectId = modId;
        e.fileName = modId + ".jar";
        e.enabled = true;
        return e;
    }

    // ---------------------------------------------------------------- catalog

    @Test
    void theBundledCatalogHasTheFourModulesAndFifteenThemes() throws Exception {
        assertEquals(List.of("ui", "map", "party", "companion"),
            catalog.modules().stream().map(ShardlightModule::id).toList());
        assertEquals(15, catalog.themes().size());
        assertEquals("softglass", catalog.module("UI").orElseThrow().modId(), "the UI mod keeps its legacy mod id");
        assertEquals("map", catalog.moduleByModId("shardmap").orElseThrow().id());
        assertEquals(List.of("map", "ui"), service.knownModules(List.of("map", "nope", "UI")).stream().sorted().toList());
    }

    @Test
    void themePacksAreRecognisedByTheirFileNames() {
        var exact = catalog.matchThemePack(UI_PACK).orElseThrow();
        assertEquals("EMBER", exact.theme().id());
        assertFalse(exact.sounds());
        assertTrue(exact.exactName());
        var sounds = catalog.matchThemePack("downloads/" + SOUND_PACK.toLowerCase()).orElseThrow();
        assertTrue(sounds.sounds());
        assertEquals("EMBER", catalog.matchThemePack("Shardlight UI - Molten Forge.zip").orElseThrow().theme().id(),
            "the pack title works as a name");
        assertEquals("EMBER", catalog.matchThemePack("Shardlight UI - Ember (1).zip").orElseThrow().theme().id(),
            "browser copies are accepted");
        assertFalse(catalog.matchThemePack("Shardlight UI - Ember (1).zip").orElseThrow().exactName());
        assertTrue(catalog.matchThemePack("Faithful 32x.zip").isEmpty());
        assertTrue(catalog.matchThemePack("Shardlight UI - Nonexistent.zip").isEmpty());
    }

    // ---------------------------------------------------------------- library

    @Test
    void importingAModuleJarAndAgainAddsItOnce() throws Exception {
        LibraryItem ui = importOne("softglass-4.1.0.jar", modJar("softglass", "4.1.0", "~26.3"));
        assertEquals(LibraryItem.MODULE, ui.kind());
        assertEquals("ui", ui.moduleOrThemeId());
        assertEquals("4.1.0", ui.version());
        assertTrue(Files.isRegularFile(ui.file()));
        assertTrue(ui.file().startsWith(library.root()));
        assertEquals(Hashing.hash(ui.file(), HashAlgo.SHA256), ui.sha256());

        ImportResult again = library.importFile(file("copy.jar", modJar("softglass", "4.1.0", "~26.3")), null);
        assertTrue(again.added().isEmpty());
        assertEquals(1, again.skipped().size());
        assertEquals(1, library.items().size());
    }

    @Test
    void foreignUnbuiltAndUnknownFilesAreSkippedWithAReason() throws Exception {
        assertTrue(library.importFile(file("sodium.jar", modJar("sodium", "0.7.0", "26.3")), null).added().isEmpty());
        ImportResult unbuilt = library.importFile(file("softglass-dev.jar", modJar("softglass", "${version}", "26.3")), null);
        assertTrue(unbuilt.added().isEmpty());
        assertEquals(1, unbuilt.skipped().size());
        assertTrue(library.importFile(file("Faithful.zip", pack("Faithful")), null).added().isEmpty());
        assertTrue(library.importFile(file("notes.zip", zip(Map.of("readme.txt", utf8("hi")))), null).added().isEmpty());
        assertTrue(library.items().isEmpty());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class,
            () -> library.importFile(dir.resolve("missing.jar"), null)).code());
    }

    @Test
    void theAllPacksBundleImportsEveryNestedPack() throws Exception {
        Map<String, byte[]> bundle = new LinkedHashMap<>();
        bundle.put("All Packs/UI/" + UI_PACK, pack("Ember"));
        bundle.put("All Packs/Sounds/" + SOUND_PACK, pack("Ember sounds"));
        bundle.put("All Packs/UI/Shardlight UI - Steampunk (Brass Engine).zip", pack("Steampunk"));
        bundle.put("All Packs/Other/Faithful.zip", pack("Faithful"));
        bundle.put("All Packs/README.txt", utf8("Put these in resourcepacks"));
        ImportResult r = library.importFile(file("Shardlight All Packs.zip", zip(bundle)), CancellationToken.NONE);

        assertEquals(3, r.added().size(), r.skipped().toString());
        assertEquals(1, r.skipped().size(), "the unknown pack is skipped; the readme is ignored");
        assertTrue(library.themePack("EMBER", false).isPresent());
        assertTrue(library.themePack("ember", true).isPresent());
        assertTrue(library.themePack("STEAMPUNK", false).isPresent());
        assertTrue(library.themePack("STEAMPUNK", true).isEmpty());
        List<String> kinds = library.items().stream().map(LibraryItem::kind).toList();
        assertEquals(List.of(LibraryItem.UI_PACK, LibraryItem.UI_PACK, LibraryItem.SOUND_PACK), kinds,
            "listed modules first, then UI packs, then sound packs");
    }

    @Test
    void anUnsafeArchiveIsRefused() throws Exception {
        Path evil = file("evil.zip", zip(Map.of("../../escape.zip", pack("x"), "pack.zip", pack("y"))));
        assertThrows(LauncherException.class, () -> library.importFile(evil, null));
        assertTrue(library.items().isEmpty());
        assertFalse(Files.exists(dir.resolve("escape.zip")));
    }

    @Test
    void theNewestJarForTheMinecraftVersionIsChosen() throws Exception {
        importOne("shardmap-1.4.0.jar", modJar("shardmap", "1.4.0", "~26.3"));
        importOne("shardmap-1.10.0.jar", modJar("shardmap", "1.10.0", "~26.3"));
        importOne("shardmap-2.0.0.jar", modJar("shardmap", "2.0.0", "26.4"));
        assertEquals("1.10.0", library.moduleJar("map", "26.3").orElseThrow().version(), "versions compare as numbers");
        assertEquals("2.0.0", library.moduleJar("map", "26.4").orElseThrow().version());
        assertEquals("2.0.0", library.moduleJar("map", null).orElseThrow().version());
        assertTrue(library.moduleJar("map", "1.21.4").isEmpty());
        assertTrue(library.moduleJar("party", "26.3").isEmpty());

        LibraryItem newest = library.moduleJar("map", "26.3").orElseThrow();
        library.remove(newest.id());
        assertFalse(Files.exists(newest.file()));
        assertEquals("1.4.0", library.moduleJar("map", "26.3").orElseThrow().version());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class, () -> library.remove(newest.id())).code());
    }

    @Test
    void aRebuiltJarOfTheSameVersionReplacesTheOldOne() throws Exception {
        LibraryItem first = importOne("shardmap-1.5.0.jar", modJar("shardmap", "1.5.0", "~26.3"));
        LibraryItem rebuilt = importOne("shardmap-1.5.0.jar", modJar("shardmap", "1.5.0", ">=26.3"));
        assertNotEquals(first.sha256(), rebuilt.sha256());
        assertEquals(1, library.items().size());
        assertFalse(Files.exists(first.file()) && !first.file().equals(rebuilt.file()));
    }

    // ---------------------------------------------------------------- GitHub releases

    void release(String assetName, byte[] jar, boolean withDigest) {
        String digest = withDigest ? ",\"digest\":\"sha256:" + Hashing.hash(jar, HashAlgo.SHA256) + "\"" : "";
        server.json("/github/repos/Janxirus/Shardlight_map/releases/latest", "{\"tag_name\":\"v1.5.0\",\"assets\":["
            + "{\"name\":\"shardmap-1.5.0-sources.jar\",\"state\":\"uploaded\",\"size\":10,\"browser_download_url\":\""
            + server.uri("/dl/sources.jar") + "\"},"
            + "{\"name\":\"" + assetName + "\",\"state\":\"uploaded\",\"size\":" + jar.length + ",\"browser_download_url\":\""
            + server.uri("/dl/" + assetName) + "\"" + digest + "}]}");
        server.bytes("/dl/" + assetName, jar);
    }

    @Test
    void theLatestReleaseJarIsDownloadedIntoTheLibrary() throws Exception {
        byte[] jar = modJar("shardmap", "1.5.0", "~26.3");
        release("shardmap-1.5.0.jar", jar, true);
        LibraryItem item = library.downloadLatestRelease("map", null, CancellationToken.NONE).orElseThrow();
        assertEquals("map", item.moduleOrThemeId());
        assertEquals("1.5.0", item.version());
        assertEquals(0, server.hits("/dl/sources.jar"), "source jars are never picked");
        assertEquals("application/vnd.github+json", server.requests().get(0).header("Accept"));

        LibraryItem again = library.downloadLatestRelease("map", null, CancellationToken.NONE).orElseThrow();
        assertEquals(item.id(), again.id());
        assertEquals(1, library.items().size());
    }

    @Test
    void releaseProblemsAreExplained() throws Exception {
        LauncherException missing = assertThrows(LauncherException.class,
            () -> library.downloadLatestRelease("map", null, CancellationToken.NONE));
        assertEquals("shardlight.download.notPublished", missing.messageKey(), "a private or unpublished repository");

        server.handle("/github/repos/Janxirus/Shardlight_map/releases/latest", ex -> {
            ex.getResponseHeaders().add("X-RateLimit-Remaining", "0");
            TestHttpServer.respond(ex, 403, "{\"message\":\"API rate limit exceeded\"}");
        });
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(LauncherException.class,
            () -> library.downloadLatestRelease("map", null, CancellationToken.NONE)).code());

        release("shardmap-1.5.0.jar", modJar("shardparty", "1.2.1", "26.3"), false);
        assertEquals("shardlight.download.wrongModule", assertThrows(LauncherException.class,
            () -> library.downloadLatestRelease("map", null, CancellationToken.NONE)).messageKey());

        byte[] good = modJar("shardmap", "1.5.0", "~26.3");
        release("shardmap-1.5.0.jar", good, true);
        server.bytes("/dl/shardmap-1.5.0.jar", modJar("shardmap", "1.5.0-tampered", "~26.3"));
        assertThrows(LauncherException.class, () -> library.downloadLatestRelease("map", null, CancellationToken.NONE));
        assertTrue(library.items().isEmpty());

        server.json("/github/repos/Janxirus/Shardlight_map/releases/latest", "{\"tag_name\":\"v1\",\"assets\":[]}");
        assertTrue(library.downloadLatestRelease("map", null, CancellationToken.NONE).isEmpty());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class,
            () -> library.downloadLatestRelease("nope", null, null)).code());
    }

    @Test
    void assetPatternsAreGlobs() {
        assertTrue(ShardlightContentLibrary.globToRegex("*.jar").matcher("Shardmap-1.5.0.JAR").matches());
        assertTrue(ShardlightContentLibrary.globToRegex("shardmap-?.jar").matcher("shardmap-1.jar").matches());
        assertFalse(ShardlightContentLibrary.globToRegex("*.jar").matcher("notes.txt").matches());
        assertFalse(ShardlightContentLibrary.globToRegex("a.b").matcher("axb").matches(), "dots are literal");
    }

    // ---------------------------------------------------------------- check

    @Test
    void anEmptySelectionAlwaysFits() throws Exception {
        CompatibilityReport r = service.check(projects.create("vanilla", "26.3", LoaderType.VANILLA, null), Set.of());
        assertTrue(r.ok());
        assertTrue(r.has("shardlight.check.none"));
        assertEquals(0, r.requiredJavaMajor());
    }

    @Test
    void theCheckExplainsLoaderAndVersionProblems() throws Exception {
        fullLibrary();
        assertTrue(service.check(projects.create("v", "26.3", LoaderType.VANILLA, null), Set.of("ui")).has("shardlight.check.needsFabric"));
        CompatibilityReport neo = service.check(projects.create("n", "26.3", LoaderType.NEOFORGE, "26.3.5"), Set.of("ui"));
        assertFalse(neo.ok());
        assertTrue(neo.has("shardlight.check.wrongLoader"));
        CompatibilityReport quilt = service.check(projects.create("q", "26.3", LoaderType.QUILT, "0.29.0"), Set.of("ui"));
        assertTrue(quilt.ok(), "Quilt may work");
        assertTrue(quilt.has("shardlight.check.quiltUntested"));
        CompatibilityReport old = service.check(projects.create("o", "26.3", LoaderType.FABRIC, "0.16.10"), Set.of("map"));
        assertTrue(old.has("shardlight.check.loaderTooOld"));
        assertFalse(old.ok());
        CompatibilityReport mc = service.check(projects.create("m", "1.21.4", LoaderType.FABRIC, "0.19.5"), Set.of("map"));
        assertTrue(mc.has("shardlight.check.minecraftUnsupported"));
        assertEquals(List.of("map"), mc.missingLibraryItems(), "the 26.3 jar doesn't fit 1.21.4");
        assertTrue(service.check(fabricProject("x"), Set.of("bogus")).has("shardlight.check.unknownModule"));
    }

    @Test
    void theCheckListsWhatIsStillNeeded() throws Exception {
        importOne("shardmap-1.5.0.jar", modJar("shardmap", "1.5.0", "~26.3"));
        Project p = fabricProject("survival");
        CompatibilityReport r = service.check(p, Set.of("map", "party"));
        assertTrue(r.ok());
        assertEquals(List.of("party"), r.missingLibraryItems());
        assertEquals(1, r.catalogDependencies().size(), "Fabric API once, not once per module");
        assertTrue(r.catalogDependencies().get(0).contains("fabric-api"));
        assertEquals(25, r.requiredJavaMajor());
        assertTrue(r.has("shardlight.check.worksBetterWith"));
        assertFalse(r.canApply());

        p.content.add(mod("fabric-api", ContentSource.MODRINTH));
        assertTrue(service.check(p, Set.of("map")).catalogDependencies().isEmpty(), "Fabric API is already installed");
        p.content.get(0).enabled = false;
        assertTrue(service.check(p, Set.of("map")).has("shardlight.check.dependencyDisabled"));
    }

    // ---------------------------------------------------------------- apply

    @Test
    void applyInstallsModulesThemePacksAndTheUiTheme() throws Exception {
        fullLibrary();
        Project p = fabricProject("survival");
        Path game = projects.gameDir(p);
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/shardlight.json"), "{\"hudScale\":1.5,\"theme\":\"FROSTBOUND\"}");
        Files.writeString(game.resolve(GameDirs.OPTIONS), "lang:en_us\nresourcePacks:[\"vanilla\",\"file/Faithful.zip\"]\n");

        ApplyResult r = service.apply(p, Set.of("ui", "map"), "EMBER", null, CancellationToken.NONE);

        assertEquals(Set.of("softglass-4.1.0.jar", "shardmap-1.5.0.jar", UI_PACK, SOUND_PACK), Set.copyOf(r.added()));
        assertTrue(r.removed().isEmpty());
        assertEquals(1, r.catalogDependenciesNeeded().size());
        assertTrue(Files.isRegularFile(game.resolve("mods/softglass-4.1.0.jar")));
        assertTrue(Files.isRegularFile(game.resolve("mods/shardmap-1.5.0.jar")));
        assertTrue(Files.isRegularFile(game.resolve("resourcepacks/" + UI_PACK)));

        List<String> packs = OptionsTxt.read(game.resolve(GameDirs.OPTIONS)).getList("resourcePacks");
        assertEquals(List.of("vanilla", "file/Faithful.zip", "file/" + UI_PACK, "file/" + SOUND_PACK), packs,
            "the theme goes on top and the player's own packs stay");
        JsonObject config = JsonParser.parseString(Files.readString(game.resolve("config/shardlight.json"))).getAsJsonObject();
        assertEquals("EMBER", config.get("theme").getAsString());
        assertEquals(1.5, config.get("hudScale").getAsDouble(), "other settings are kept");

        Project saved = projects.get("survival").orElseThrow();
        assertEquals(Set.of("ui", "map"), saved.shardlightModules);
        assertEquals("EMBER", saved.shardlightTheme);
        assertEquals(4, saved.content.stream().filter(e -> e.source == ContentSource.SHARDLIGHT).count());
        assertEquals(saved.content.size(), p.content.size(), "the caller's project is updated too");

        ApplyResult again = service.apply(saved, Set.of("ui", "map"), "EMBER", null, null);
        assertTrue(again.added().isEmpty(), "nothing changes when everything is already in place");
        assertTrue(again.removed().isEmpty());
    }

    @Test
    void deselectingRemovesOnlyShardlightFiles() throws Exception {
        fullLibrary();
        Project p = fabricProject("survival");
        Path game = projects.gameDir(p);
        service.apply(p, Set.of("ui", "map"), "EMBER", null, null);
        Files.writeString(game.resolve("mods/sodium.jar"), "player's mod");

        ApplyResult r = service.apply(projects.get("survival").orElseThrow(), Set.of("map"), null, null, null);

        assertEquals(Set.of("softglass-4.1.0.jar", UI_PACK, SOUND_PACK), Set.copyOf(r.removed()));
        assertFalse(Files.exists(game.resolve("mods/softglass-4.1.0.jar")), "a deselected module's code can't load any more");
        assertTrue(Files.exists(game.resolve("mods/shardmap-1.5.0.jar")));
        assertTrue(Files.exists(game.resolve("mods/sodium.jar")));
        assertFalse(Files.exists(game.resolve("resourcepacks/" + UI_PACK)));
        assertFalse(OptionsTxt.read(game.resolve(GameDirs.OPTIONS)).getList("resourcePacks").contains("file/" + UI_PACK));
        Project saved = projects.get("survival").orElseThrow();
        assertEquals(Set.of("map"), saved.shardlightModules);
        assertEquals(null, saved.shardlightTheme);
        assertTrue(library.moduleJar("ui", "26.3").isPresent(), "the library keeps its original");

        service.apply(saved, Set.of(), null, null, null);
        assertFalse(Files.exists(game.resolve("mods/shardmap-1.5.0.jar")));
        assertTrue(Files.exists(game.resolve("mods/sodium.jar")));
    }

    @Test
    void aPlayersFileWithTheSameNameIsNotOverwritten() throws Exception {
        fullLibrary();
        Project p = fabricProject("survival");
        Path game = projects.gameDir(p);
        Files.writeString(game.resolve("mods/shardmap-1.5.0.jar"), "the player's own build");
        ApplyResult r = service.apply(p, Set.of("map"), null, null, null);
        assertEquals("the player's own build", Files.readString(game.resolve("mods/shardmap-1.5.0.jar")));
        assertEquals(1, r.added().size());
        assertTrue(r.added().get(0).startsWith("shardmap-1.5.0-shardlight-"), r.added().toString());
    }

    @Test
    void aCopyFromElsewhereIsKept() throws Exception {
        fullLibrary();
        Project p = fabricProject("survival");
        p.content.add(mod("shardmap", ContentSource.MODRINTH));
        projects.save(p);
        ApplyResult r = service.apply(p, Set.of("map"), null, null, null);
        assertTrue(r.added().isEmpty());
        assertEquals(1, r.warnings().stream().filter(w -> w.contains("shardmap.jar")).count(), r.warnings().toString());
    }

    @Test
    void applyRefusesWhatCantWork() throws Exception {
        Project vanilla = projects.create("vanilla", "26.3", LoaderType.VANILLA, null);
        importOne("softglass-4.1.0.jar", modJar("softglass", "4.1.0", "~26.3"));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, assertThrows(LauncherException.class,
            () -> service.apply(vanilla, Set.of("ui"), null, null, null)).code());
        assertEquals(ErrorCode.DEPENDENCY_MISSING, assertThrows(LauncherException.class,
            () -> service.apply(fabricProject("f"), Set.of("map"), null, null, null)).code());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class,
            () -> service.apply(fabricProject("g"), Set.of("ui"), "NOT_A_THEME", null, null)).code());
        assertFalse(Files.exists(projects.gameDir(vanilla).resolve("mods/softglass-4.1.0.jar")));
    }

    @Test
    void themePacksWorkWithoutAnyModule() throws Exception {
        importOne(UI_PACK, pack("Ember"));
        Project vanilla = projects.create("vanilla", "26.3", LoaderType.VANILLA, null);
        ApplyResult r = service.apply(vanilla, Set.of(), "EMBER", null, null);
        assertEquals(List.of(UI_PACK), r.added());
        assertTrue(r.warnings().isEmpty() || r.warnings().stream().noneMatch(w -> w.contains(UI_PACK)));
        List<String> packs = OptionsTxt.read(projects.gameDir(vanilla).resolve(GameDirs.OPTIONS)).getList("resourcePacks");
        assertEquals(List.of("vanilla", "file/" + UI_PACK), packs, "a new list starts with vanilla");
    }
}
