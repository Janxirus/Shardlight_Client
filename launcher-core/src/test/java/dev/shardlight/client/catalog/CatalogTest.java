package dev.shardlight.client.catalog;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Modrinth and CurseForge against a local copy of their APIs, and the install planner on an in-memory catalog. */
class CatalogTest {
    static final String CF_KEY = "$2a$10$curseforge-test-key-0123456789abcdef";

    TestHttpServer server;
    HttpService http;
    Endpoints endpoints;

    @BeforeEach
    void setUp() {
        server = new TestHttpServer();
        http = HttpService.forTests();
        endpoints = Endpoints.local(server.base());
    }

    @AfterEach
    void tearDown() {
        server.close();
        Redactor.forget(CF_KEY);
    }

    static String decoded(TestHttpServer.Recorded r) {
        return r.query() == null ? "" : URLDecoder.decode(r.query(), StandardCharsets.UTF_8);
    }

    TestHttpServer.Recorded last(String path) {
        List<TestHttpServer.Recorded> all = server.requests().stream().filter(r -> r.path().equals(path)).toList();
        return all.get(all.size() - 1);
    }

    // ---------------------------------------------------------------- Modrinth

    @Test
    void modrinthSearchSendsFacetsAndReadsHits() throws Exception {
        server.json("/modrinth/v2/search", """
            {"hits":[{"project_id":"AANobbMI","slug":"sodium","title":"Sodium","description":"Fast rendering","author":"jellysquid3",
              "project_type":"mod","downloads":1000,"follows":50,"categories":["fabric","quilt","optimization"],
              "display_categories":["optimization","fabric"],"versions":["26.3"],"icon_url":"https://cdn.example/icon.png",
              "client_side":"required","server_side":"unsupported","gallery":["https://cdn.example/1.png"],"license":"LGPL-3.0",
              "date_modified":"2026-10-01T10:00:00Z"}],
             "offset":0,"limit":20,"total_hits":1}
            """);
        ModrinthCatalog modrinth = new ModrinthCatalog(http, endpoints);
        SearchPage page = modrinth.search(new SearchQuery("sodium", ContentKind.MOD, "26.3", LoaderType.QUILT,
            List.of("optimization"), SortOrder.DOWNLOADS, 0, 500), CancellationToken.NONE);

        String q = decoded(last("/modrinth/v2/search"));
        assertTrue(q.contains("query=sodium"), q);
        assertTrue(q.contains("facets=[[\"project_type:mod\"],[\"versions:26.3\"],[\"categories:quilt\",\"categories:fabric\"],"
            + "[\"categories:optimization\"]]"), q);
        assertTrue(q.contains("index=downloads"), q);
        assertTrue(q.contains("limit=100"), "the page size is capped: " + q);

        assertEquals(1, page.total());
        CatalogProject p = page.hits().get(0);
        assertEquals("AANobbMI", p.id());
        assertEquals("Sodium", p.title());
        assertEquals(List.of("optimization"), p.categories(), "loader names aren't categories");
        assertEquals(List.of("fabric", "quilt"), p.loaders());
        assertEquals(URI.create("https://modrinth.com/mod/sodium"), p.pageUrl());
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), p.updated());
    }

    @Test
    void modrinthVersionsAreNewestFirstWithFilesAndDependencies() throws Exception {
        server.json("/modrinth/v2/project/AANobbMI/version", """
            [
              {"id":"old","project_id":"AANobbMI","version_number":"0.6.0","version_type":"release","game_versions":["26.3"],
               "loaders":["fabric"],"date_published":"2026-01-01T00:00:00Z","files":[],"dependencies":[]},
              {"id":"new","project_id":"AANobbMI","version_number":"0.7.0","version_type":"beta","game_versions":["26.3"],
               "loaders":["fabric","quilt"],"date_published":"2026-09-01T00:00:00Z",
               "files":[{"url":"https://cdn.modrinth.com/data/AANobbMI/sodium.jar","filename":"sodium.jar","size":123,
                        "primary":true,"hashes":{"sha1":"0123456789abcdef0123456789abcdef01234567","sha512":"ab"}}],
               "dependencies":[{"project_id":"P7dR8mSH","dependency_type":"required"},{"project_id":"x","dependency_type":"weird"}]}
            ]
            """);
        ModrinthCatalog modrinth = new ModrinthCatalog(http, endpoints);
        List<CatalogVersion> v = modrinth.versions("AANobbMI", "26.3", LoaderType.FABRIC, CancellationToken.NONE);
        assertEquals(List.of("new", "old"), v.stream().map(CatalogVersion::id).toList());
        String q = decoded(last("/modrinth/v2/project/AANobbMI/version"));
        assertTrue(q.contains("loaders=[\"fabric\"]") && q.contains("game_versions=[\"26.3\"]"), q);
        CatalogVersion newest = v.get(0);
        assertEquals("beta", newest.channel());
        assertEquals(1, newest.channelRank());
        assertEquals("sodium.jar", newest.primaryFile().orElseThrow().fileName());
        assertEquals(DependencyType.REQUIRED, newest.dependencies().get(0).type());
        assertEquals(DependencyType.OPTIONAL, newest.dependencies().get(1).type(), "unknown types are optional");
        assertTrue(v.get(1).primaryFile().isEmpty());
        assertThrows(LauncherException.class, () -> modrinth.versions(" ", null, null, CancellationToken.NONE));
    }

    @Test
    void modrinthLooksUpFilesByHash() throws Exception {
        String sha1 = "0123456789abcdef0123456789abcdef01234567";
        server.json("/modrinth/v2/version_files", "{\"" + sha1 + "\":{\"id\":\"v1\",\"project_id\":\"p1\",\"version_number\":\"1.0\","
            + "\"files\":[],\"dependencies\":[]}}");
        ModrinthCatalog modrinth = new ModrinthCatalog(http, endpoints);
        Map<String, CatalogVersion> found = modrinth.versionsByHash(List.of(sha1.toUpperCase(), "not-a-hash", sha1), CancellationToken.NONE);
        assertEquals("v1", found.get(sha1).id());
        String body = last("/modrinth/v2/version_files").bodyText();
        assertTrue(body.contains("\"algorithm\":\"sha1\""), body);
        assertEquals(1, body.split(sha1, -1).length - 1, "hashes are cleaned and de-duplicated: " + body);
        assertTrue(modrinth.versionsByHash(List.of(), CancellationToken.NONE).isEmpty());
    }

    @Test
    void modrinthCategoriesAreCachedPerType() throws Exception {
        server.json("/modrinth/v2/tag/category", """
            [{"name":"optimization","project_type":"mod"},{"name":"realistic","project_type":"resourcepack"},
             {"name":"optimization","project_type":"mod"}]
            """);
        ModrinthCatalog modrinth = new ModrinthCatalog(http, endpoints);
        assertEquals(List.of("optimization"), modrinth.categories(ContentKind.MOD, CancellationToken.NONE));
        assertEquals(List.of("realistic"), modrinth.categories(ContentKind.RESOURCE_PACK, CancellationToken.NONE));
        assertEquals(1, server.hits("/modrinth/v2/tag/category"));
    }

    @Test
    void modrinthErrorsAreLauncherErrors() {
        server.text("/modrinth/v2/project/broken/version", 200, "<html>not json</html>");
        ModrinthCatalog modrinth = new ModrinthCatalog(http, endpoints);
        assertThrows(LauncherException.class, () -> modrinth.versions("broken", null, null, CancellationToken.NONE));
        LauncherException missing = assertThrows(LauncherException.class, () -> modrinth.version("nope", CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, missing.code());
    }

    // ---------------------------------------------------------------- CurseForge

    @Test
    void curseForgeWithoutAKeyIsUnavailable() {
        CurseForgeCatalog cf = new CurseForgeCatalog(http, endpoints, Optional::empty);
        assertFalse(cf.isAvailable());
        assertEquals("catalog.curseforge.noKey", cf.unavailableReasonKey());
        LauncherException e = assertThrows(LauncherException.class, () -> cf.search(SearchQuery.mods("x"), CancellationToken.NONE));
        assertEquals(ErrorCode.API_KEY_MISSING, e.code());
        assertTrue(server.requests().isEmpty(), "nothing is sent without a key");
        assertFalse(new CurseForgeCatalog(http, endpoints, () -> Optional.of("  ")).isAvailable());
        assertFalse(new CurseForgeCatalog(http, endpoints, () -> {
            throw new IllegalStateException("secret store locked");
        }).isAvailable());
    }

    @Test
    void curseForgeSendsTheKeyAndHidesItFromLogs() throws Exception {
        server.json("/curseforge/v1/mods/search", """
            {"data":[{"id":394468,"slug":"sodium","name":"Sodium","summary":"Fast","classId":6,"allowModDistribution":true,
                      "authors":[{"name":"jellysquid3"}],"categories":[{"name":"Performance"}],
                      "latestFilesIndexes":[{"gameVersion":"26.3","modLoader":4}],"links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/sodium"}}],
             "pagination":{"index":0,"pageSize":20,"totalCount":1}}
            """);
        CurseForgeCatalog cf = new CurseForgeCatalog(http, endpoints, () -> Optional.of(CF_KEY));
        SearchPage page = cf.search(new SearchQuery("sodium", ContentKind.MOD, "26.3", LoaderType.FABRIC, List.of(),
            SortOrder.DOWNLOADS, 0, 100), CancellationToken.NONE);
        TestHttpServer.Recorded r = last("/curseforge/v1/mods/search");
        assertEquals(CF_KEY, r.header("x-api-key"));
        String q = decoded(r);
        assertTrue(q.contains("gameId=432") && q.contains("classId=6") && q.contains("modLoaderType=4") && q.contains("sortField=6")
            && q.contains("pageSize=50"), q);
        CatalogProject p = page.hits().get(0);
        assertEquals("394468", p.id());
        assertEquals("jellysquid3", p.author());
        assertEquals(List.of("fabric"), p.loaders());
        assertEquals(List.of("26.3"), p.gameVersions());
        assertFalse(Redactor.redact("key=" + CF_KEY).contains(CF_KEY), "the key is masked in anything logged");
    }

    @Test
    void curseForgeFilesWhoseAuthorForbidsDownloadsAreMarked() throws Exception {
        server.json("/curseforge/v1/mods/111", "{\"data\":{\"id\":111,\"name\":\"Closed\",\"allowModDistribution\":false}}");
        server.json("/curseforge/v1/mods/111/files", """
            {"data":[
              {"id":5001,"modId":111,"displayName":"Closed 1.0","fileName":"closed-1.0.jar","fileLength":10,"releaseType":1,
               "downloadUrl":"https://edge.forgecdn.net/files/5001/closed-1.0.jar","gameVersions":["26.3","Fabric","Client"],
               "hashes":[{"algo":2,"value":"md5"},{"algo":1,"value":"0123456789abcdef0123456789abcdef01234567"}],
               "fileDate":"2026-09-01T00:00:00Z","dependencies":[{"modId":306612,"relationType":3},{"modId":0,"relationType":3}]},
              {"id":5002,"modId":111,"displayName":"Server pack","isServerPack":true}
            ],"pagination":{"index":0,"pageSize":50,"totalCount":2}}
            """);
        CurseForgeCatalog cf = new CurseForgeCatalog(http, endpoints, () -> Optional.of(CF_KEY));
        List<CatalogVersion> v = cf.versions("111", "26.3", LoaderType.FABRIC, CancellationToken.NONE);
        assertEquals(1, v.size(), "server packs are left out");
        CatalogFile f = v.get(0).primaryFile().orElseThrow();
        assertTrue(f.distributionRestricted());
        assertNull(f.url(), "no download link is ever built for a restricted file");
        assertFalse(f.downloadable());
        assertEquals("0123456789abcdef0123456789abcdef01234567", f.sha1());
        assertEquals(List.of("26.3"), v.get(0).gameVersions());
        assertEquals(List.of("fabric"), v.get(0).loaders());
        assertEquals(List.of(new CatalogDependency("306612", null, null, DependencyType.REQUIRED)), v.get(0).dependencies());
    }

    @Test
    void curseForgeDescriptionsBecomePlainText() {
        assertEquals("Hello & welcome\n• one\n• two\n\nBye ©",
            CurseForgeCatalog.htmlToText("<p>Hello &amp; welcome</p><script>alert(1)</script><ul><li>one</li><li>two</li></ul>"
                + "<p>Bye&nbsp;&copy;</p>"));
        assertEquals("A\nB", CurseForgeCatalog.htmlToText("A<br>B"));
        assertNull(CurseForgeCatalog.htmlToText(null));
        assertEquals(DependencyType.EMBEDDED, CurseForgeCatalog.relation(1));
        assertEquals(DependencyType.INCOMPATIBLE, CurseForgeCatalog.relation(5));
        assertEquals(DependencyType.OPTIONAL, CurseForgeCatalog.relation(4));
    }

    // ---------------------------------------------------------------- planner

    /** an in-memory catalog: projects by id and their versions */
    static final class FakeCatalog implements CatalogProvider {
        final Map<String, CatalogProject> projects = new HashMap<>();
        final Map<String, List<CatalogVersion>> versions = new HashMap<>();
        final AtomicInteger versionCalls = new AtomicInteger();

        CatalogVersion add(String projectId, String slug, String versionId, List<String> mc, List<String> loaders,
                           String channel, boolean downloadable, CatalogDependency... deps) {
            projects.putIfAbsent(projectId, new CatalogProject("modrinth", projectId, slug, slug + " title", "", null, null, null, 0, 0,
                null, null, null, ContentKind.MOD, null, null, URI.create("https://modrinth.com/mod/" + slug), null, null, null));
            CatalogFile file = new CatalogFile(downloadable ? URI.create("https://cdn.example/" + versionId + ".jar") : null,
                versionId + ".jar", 10, null, null, true, !downloadable);
            CatalogVersion v = new CatalogVersion("modrinth", versionId, projectId, versionId, versionId, null, mc, loaders, channel,
                Instant.ofEpochSecond(versions.getOrDefault(projectId, List.of()).size() + 1L), List.of(file), List.of(deps));
            versions.computeIfAbsent(projectId, k -> new ArrayList<>()).add(v);
            return v;
        }

        @Override
        public String id() {
            return "modrinth";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String unavailableReasonKey() {
            return null;
        }

        @Override
        public SearchPage search(SearchQuery query, CancellationToken cancel) {
            return new SearchPage(List.of(), 0, 0, 0);
        }

        @Override
        public CatalogProject project(String idOrSlug, CancellationToken cancel) throws LauncherException {
            CatalogProject p = projects.get(idOrSlug);
            if (p == null) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", "fake", idOrSlug);
            }
            return p;
        }

        @Override
        public List<CatalogVersion> versions(String projectId, String minecraftVersion, LoaderType loader, CancellationToken cancel) {
            versionCalls.incrementAndGet();
            return versions.getOrDefault(projectId, List.of()).stream()
                .filter(v -> minecraftVersion == null || v.gameVersions().contains(minecraftVersion))
                .sorted((a, b) -> b.published().compareTo(a.published())).toList();
        }

        @Override
        public CatalogVersion version(String versionId, CancellationToken cancel) throws LauncherException {
            return versions.values().stream().flatMap(List::stream).filter(v -> v.id().equals(versionId)).findFirst()
                .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", "fake", versionId));
        }

        @Override
        public List<String> categories(ContentKind kind, CancellationToken cancel) {
            return List.of();
        }
    }

    static Project fabric() {
        Project p = new Project();
        p.id = "p";
        p.minecraftVersion = "26.3";
        p.loader = new LoaderSpec(LoaderType.FABRIC, "0.19.5");
        return p;
    }

    static ContentEntry installed(String projectId, String modId, boolean enabled) {
        ContentEntry e = new ContentEntry();
        e.kind = ContentKind.MOD;
        e.source = ContentSource.MODRINTH;
        e.projectId = projectId;
        e.modId = modId;
        e.title = modId;
        e.fileName = modId + ".jar";
        e.enabled = enabled;
        return e;
    }

    static CatalogDependency required(String projectId) {
        return new CatalogDependency(projectId, null, null, DependencyType.REQUIRED);
    }

    static List<String> keys(InstallPlan plan) {
        return plan.issues().stream().map(PlanIssue::messageKey).toList();
    }

    final List<String> fabric263 = List.of("26.3");
    final List<String> fabricLoader = List.of("fabric");

    @Test
    void requiredDependenciesArePlannedRecursivelyOnce() throws Exception {
        FakeCatalog c = new FakeCatalog();
        c.add("api", "fabric-api", "api-old", fabric263, fabricLoader, "release", true);
        c.add("api", "fabric-api", "api-beta", fabric263, fabricLoader, "beta", true);
        c.add("api", "fabric-api", "api-1.21", List.of("1.21.4"), fabricLoader, "release", true);
        c.add("lib", "cloth-config", "lib-1", fabric263, fabricLoader, "release", true, required("api"), required("root"));
        c.add("extra", "extra", "extra-1", fabric263, fabricLoader, "release", true);
        CatalogVersion root = c.add("root", "sodium-extra", "root-1", fabric263, fabricLoader, "release", true,
            required("lib"), required("api"), new CatalogDependency("extra", null, null, DependencyType.OPTIONAL),
            new CatalogDependency("bundled", null, null, DependencyType.EMBEDDED));

        InstallPlan plan = new InstallPlanner().plan(fabric(), c, root, ContentKind.MOD, false, CancellationToken.NONE);

        assertTrue(plan.canInstall(), plan.issues().toString());
        assertEquals(List.of("root-1", "lib-1", "api-old"), plan.files().stream().map(f -> f.version().id()).toList(),
            "root first; a release beats a newer beta; the 1.21.4 build doesn't fit; the cycle back to root stops");
        assertFalse(plan.files().get(0).dependency());
        assertEquals("root", plan.files().get(1).requiredBy());
        assertEquals(List.of("extra"), plan.optionalSuggestions().stream().map(CatalogDependency::projectId).toList());
        assertFalse(plan.summary().isBlank());
    }

    @Test
    void installedModsAreNotDownloadedAgain() throws Exception {
        FakeCatalog c = new FakeCatalog();
        c.add("api", "fabric-api", "api-1", fabric263, fabricLoader, "release", true);
        c.add("lib", "cloth-config", "lib-1", fabric263, fabricLoader, "release", true);
        CatalogVersion root = c.add("root", "root", "root-1", fabric263, fabricLoader, "release", true, required("api"), required("lib"));
        Project p = fabric();
        p.content.add(installed("api", "fabric-api", true));
        p.content.add(installed(null, "cloth_config", false)); // added by hand, known by its mod id
        InstallPlan plan = new InstallPlanner().plan(p, c, root, ContentKind.MOD, false, CancellationToken.NONE);
        assertEquals(List.of("root-1"), plan.files().stream().map(f -> f.version().id()).toList());
        assertTrue(keys(plan).contains("content.plan.dependencyDisabled"), "a disabled dependency is pointed out");
    }

    @Test
    void wrongVersionsAreErrorsUnlessTheUserAcceptsTheRisk() throws Exception {
        FakeCatalog c = new FakeCatalog();
        CatalogVersion forgeOnly = c.add("root", "root", "root-forge", List.of("1.21.4"), List.of("forge"), "release", true);
        InstallPlan refused = new InstallPlanner().plan(fabric(), c, forgeOnly, ContentKind.MOD, false, CancellationToken.NONE);
        assertFalse(refused.canInstall());
        assertTrue(keys(refused).containsAll(List.of("content.plan.wrongLoader", "content.plan.wrongMinecraft")));
        assertTrue(refused.issues().stream().allMatch(PlanIssue::overridable));

        InstallPlan accepted = new InstallPlanner().plan(fabric(), c, forgeOnly, ContentKind.MOD, true, CancellationToken.NONE);
        assertTrue(accepted.canInstall());
        assertTrue(keys(accepted).contains("content.plan.riskAccepted"));
    }

    @Test
    void quiltProjectsTakeFabricMods() throws Exception {
        FakeCatalog c = new FakeCatalog();
        CatalogVersion root = c.add("root", "root", "root-1", fabric263, fabricLoader, "release", true);
        Project p = fabric();
        p.loader = new LoaderSpec(LoaderType.QUILT, "0.29.0");
        assertTrue(new InstallPlanner().plan(p, c, root, ContentKind.MOD, false, CancellationToken.NONE).canInstall());
    }

    @Test
    void blockingProblemsAreFound() throws Exception {
        FakeCatalog c = new FakeCatalog();
        c.add("optifabric", "optifabric", "of-1", fabric263, fabricLoader, "release", true);
        CatalogVersion clash = c.add("root", "root", "root-1", fabric263, fabricLoader, "release", true,
            new CatalogDependency("optifabric", null, null, DependencyType.INCOMPATIBLE), required("ghost"));
        Project p = fabric();
        p.content.add(installed("optifabric", "optifabric", true));
        InstallPlan plan = new InstallPlanner().plan(p, c, clash, ContentKind.MOD, false, CancellationToken.NONE);
        assertFalse(plan.canInstall());
        assertTrue(keys(plan).contains("content.plan.incompatibleInstalled"));
        assertTrue(keys(plan).contains("content.plan.dependencyUnavailable"), "a dependency with no fitting version");

        CatalogVersion restricted = c.add("closed", "closed", "closed-1", fabric263, fabricLoader, "release", false);
        InstallPlan r = new InstallPlanner().plan(fabric(), c, restricted, ContentKind.MOD, false, CancellationToken.NONE);
        assertFalse(r.canInstall());
        assertTrue(keys(r).contains("content.plan.restricted"));
        assertTrue(r.issues().get(r.issues().size() - 1).args().contains("https://modrinth.com/mod/closed"),
            "the error links to the website instead");

        Project vanilla = fabric();
        vanilla.loader = LoaderSpec.vanilla();
        assertTrue(keys(new InstallPlanner().plan(vanilla, c, c.add("m", "m", "m-1", fabric263, fabricLoader, "release", true),
            ContentKind.MOD, false, CancellationToken.NONE)).contains("content.plan.vanillaNoMods"));
        assertTrue(keys(new InstallPlanner().plan(fabric(), c, restricted, ContentKind.MODPACK, false, CancellationToken.NONE))
            .contains("content.plan.kindUnsupported"));
    }

    @Test
    void resourcePacksFromOtherVersionsOnlyWarn() throws Exception {
        FakeCatalog c = new FakeCatalog();
        CatalogVersion pack = c.add("faithful", "faithful-32x", "f-1", List.of("1.21.4"), List.of("minecraft"), "release", true);
        Project vanilla = fabric();
        vanilla.loader = LoaderSpec.vanilla();
        InstallPlan plan = new InstallPlanner().plan(vanilla, c, pack, ContentKind.RESOURCE_PACK, false, CancellationToken.NONE);
        assertTrue(plan.canInstall());
        assertEquals(List.of("content.plan.packVersionMismatch"), keys(plan));
    }

    @Test
    void aLongChainStopsWithAnError() throws Exception {
        FakeCatalog c = new FakeCatalog();
        CatalogVersion root = null;
        for (int i = 60; i >= 0; i--) {
            root = c.add("m" + i, "m" + i, "m" + i + "-1", fabric263, fabricLoader, "release", true,
                i < 60 ? new CatalogDependency[] {required("m" + (i + 1))} : new CatalogDependency[0]);
        }
        InstallPlan plan = new InstallPlanner().plan(fabric(), c, root, ContentKind.MOD, false, CancellationToken.NONE);
        assertFalse(plan.canInstall());
        assertTrue(keys(plan).contains("content.plan.tooManyDependencies"));
        assertTrue(plan.files().size() <= InstallPlanner.MAX_DEPTH + 1);
    }

    @Test
    void theRegistryKnowsItsProviders() {
        CurseForgeCatalog cf = new CurseForgeCatalog(http, endpoints, Optional::empty);
        ModrinthCatalog mr = new ModrinthCatalog(http, endpoints);
        CatalogRegistry registry = new CatalogRegistry(mr, cf);
        assertEquals(List.of(mr, cf), registry.all());
        assertEquals(List.of(mr), registry.available());
        assertEquals(cf, registry.get("CurseForge").orElse(registry.get("curseforge").orElseThrow()));
        assertEquals(ContentSource.CURSEFORGE, CatalogRegistry.sourceOf("curseforge"));
        assertEquals("modrinth", CatalogRegistry.providerIdOf(ContentSource.MODRINTH));
    }
}
