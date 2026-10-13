package dev.shardlight.client.app;

import dev.shardlight.client.catalog.CatalogDependency;
import dev.shardlight.client.catalog.CatalogFile;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.DependencyType;
import dev.shardlight.client.catalog.SearchPage;
import dev.shardlight.client.catalog.SearchQuery;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.task.CancellationToken;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * --capture only: a catalog with a few made-up mods, so the screenshots of Discover and the mod page show filled
 * pages without a network. It can also be slow (to show loading), offline or empty. Never used otherwise.
 */
final class CaptureCatalog implements CatalogProvider {
    enum Mode { OK, SLOW, OFFLINE, EMPTY }

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final URI FILE = URI.create("https://cdn.example.invalid/mod.jar");

    private static final String BODY = String.join("\n\n",
        "A made-up mod that exists only to show how a mod page looks.",
        "## What it does",
        "It describes itself in a few short paragraphs, so the description tab has real text to lay out.",
        "- Works with the version of the selected project\n- Needs the Fabric API\n- Changes nothing until you install it",
        "## Good to know",
        "This page is an example. Nothing here can be installed.");

    private static final List<CatalogProject> PROJECTS = List.of(
        project("ex-lumen", "lumen-optimizer", "Lumen Optimizer", "Smoother frame rates with fewer stutters on older computers.",
            "Example Studio", 18_400_000L, 121_000L, List.of("optimization", "utility")),
        project("ex-trails", "quiet-trails", "Quiet Trails", "Marks the paths you walk most and fades them out over time.",
            "Trailwork", 2_310_000L, 18_500L, List.of("adventure", "utility")),
        project("ex-chestnut", "chestnut-storage", "Chestnut Storage", "Wooden chests that sort themselves and tell you what is inside.",
            "Chestnut Team", 6_890_000L, 44_200L, List.of("storage", "decoration")),
        project("ex-compass", "wayfinder-compass", "Wayfinder Compass", "A compass that points to the places you saved.",
            "Mapmakers", 940_000L, 9_800L, List.of("adventure", "utility")),
        project("ex-hud", "tidy-hud", "Tidy HUD", "Moves, hides and resizes the parts of the on-screen display.",
            "Example Studio", 4_120_000L, 30_700L, List.of("utility", "decoration")),
        project("ex-fabric", "fabric-api", "Fabric API", "The library most Fabric mods need.",
            "Fabric", 120_000_000L, 700_000L, List.of("library")));

    private final Mode mode;

    CaptureCatalog(Mode mode) {
        this.mode = mode;
    }

    private static CatalogProject project(String id, String slug, String title, String summary, String author, long downloads,
                                          long follows, List<String> categories) {
        return new CatalogProject("modrinth", id, slug, title, summary, BODY, author, null, downloads, follows, categories,
            List.of("26.3", "1.21.4"), List.of("fabric"), ContentKind.MOD, "required", "optional", null, List.of(),
            "MIT", NOW);
    }

    @Override
    public String id() {
        return "modrinth";
    }

    @Override
    public String displayName() {
        return "Modrinth";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String unavailableReasonKey() {
        return null;
    }

    private void gate(CancellationToken cancel) throws LauncherException {
        if (mode == Mode.OFFLINE) {
            throw new LauncherException(ErrorCode.NETWORK, "error.network", "Modrinth", "offline");
        }
        if (mode == Mode.SLOW) {
            for (int i = 0; i < 600 && !cancel.isCancelled(); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            cancel.check();
        }
    }

    @Override
    public SearchPage search(SearchQuery query, CancellationToken cancel) throws LauncherException {
        gate(cancel);
        if (mode == Mode.EMPTY) {
            return new SearchPage(List.of(), 0, query.limit(), 0);
        }
        String q = query.text() == null ? "" : query.text().toLowerCase(Locale.ROOT);
        List<CatalogProject> hits = PROJECTS.stream().filter(p -> q.isEmpty() || p.title().toLowerCase(Locale.ROOT).contains(q))
            .toList();
        return new SearchPage(hits, 0, query.limit(), hits.size());
    }

    @Override
    public CatalogProject project(String idOrSlug, CancellationToken cancel) throws LauncherException {
        gate(cancel);
        return PROJECTS.stream().filter(p -> p.id().equals(idOrSlug) || p.slug().equals(idOrSlug)).findFirst()
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", "Modrinth", idOrSlug));
    }

    @Override
    public List<CatalogVersion> versions(String projectId, String minecraftVersion, LoaderType loader, CancellationToken cancel)
        throws LauncherException {
        gate(cancel);
        boolean needsApi = !projectId.equals("ex-fabric");
        List<CatalogDependency> deps = needsApi
            ? List.of(new CatalogDependency("ex-fabric", null, null, DependencyType.REQUIRED)) : List.of();
        return List.of(
            version(projectId, "2.1.0", "release", List.of("26.3"), 2_457_600L, NOW, deps),
            version(projectId, "2.1.0-beta.2", "beta", List.of("26.3"), 2_480_000L, NOW.minusSeconds(9 * 86_400L), deps),
            version(projectId, "2.0.3", "release", List.of("1.21.4"), 2_301_000L, NOW.minusSeconds(60 * 86_400L), deps));
    }

    private static CatalogVersion version(String projectId, String number, String channel, List<String> games, long size,
                                          Instant when, List<CatalogDependency> deps) {
        String file = projectId + "-" + number + ".jar";
        return new CatalogVersion("modrinth", projectId + "@" + number, projectId, number, number, "Example release notes.",
            games, List.of("fabric"), channel, when,
            List.of(new CatalogFile(FILE, file, size, "0000000000000000000000000000000000000000", null, true, false)), deps);
    }

    @Override
    public CatalogVersion version(String versionId, CancellationToken cancel) throws LauncherException {
        gate(cancel);
        String projectId = versionId.substring(0, Math.max(0, versionId.indexOf('@')));
        return versions(projectId, null, null, cancel).stream().filter(v -> v.id().equals(versionId)).findFirst()
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", "Modrinth", versionId));
    }

    @Override
    public List<String> categories(ContentKind kind, CancellationToken cancel) throws LauncherException {
        gate(cancel);
        return List.of("adventure", "decoration", "library", "optimization", "storage", "utility");
    }
}
