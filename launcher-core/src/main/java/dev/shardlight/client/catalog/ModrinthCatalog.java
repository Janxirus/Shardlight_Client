package dev.shardlight.client.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.net.RateLimiter;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Json;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Modrinth (API v2, no key needed). Requests carry the launcher's User-Agent and are limited to 300 per
 * minute for the API host. Search filters are Modrinth facets: one AND group per filter, for example
 * {@code [["project_type:mod"],["versions:26.3"],["categories:fabric"],["categories:optimization"]]}.
 */
public final class ModrinthCatalog implements CatalogProvider {
    private static final Logger LOG = Logger.getLogger(ModrinthCatalog.class.getName());
    /** provider id */
    public static final String ID = CatalogRegistry.MODRINTH;
    /** the website, for project page links */
    public static final URI SITE = URI.create("https://modrinth.com/");
    /** Modrinth's documented limit */
    public static final int REQUESTS_PER_MINUTE = 300;
    private static final int MAX_LIMIT = 100;
    private static final int HASH_CHUNK = 500;
    /** loader names Modrinth mixes into search-hit categories */
    private static final Set<String> LOADER_NAMES = Set.of("fabric", "quilt", "forge", "neoforge", "liteloader", "modloader",
        "rift", "iris", "optifine", "canvas", "vanilla", "minecraft", "datapack", "bukkit", "spigot", "paper", "purpur",
        "folia", "sponge", "velocity", "bungeecord", "waterfall", "babric", "bta-babric", "java-agent", "legacy-fabric",
        "nilloader", "ornithe");

    private final HttpService http;
    private final URI api;
    private volatile JsonArray categoryTags;

    /** registers the rate limiter for the API host on {@code http} */
    public ModrinthCatalog(HttpService http, Endpoints endpoints) {
        this.http = Objects.requireNonNull(http);
        this.api = endpoints.modrinthApi();
        String host = api.getHost();
        if (host != null && http.rateLimiter(host).isEmpty()) {
            http.setRateLimit(host, new RateLimiter(REQUESTS_PER_MINUTE, 20));
        }
    }

    @Override
    public String id() {
        return ID;
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

    // ------------------------------------------------------------------ search

    @Override
    public SearchPage search(SearchQuery q, CancellationToken cancel) throws LauncherException {
        List<List<String>> facets = new ArrayList<>();
        facets.add(List.of("project_type:" + q.kind().modrinthType()));
        if (q.minecraftVersion() != null && !q.minecraftVersion().isBlank()) {
            facets.add(List.of("versions:" + q.minecraftVersion().trim()));
        }
        if (usesLoader(q.kind()) && q.loader() != null && q.loader().catalogId() != null) {
            facets.add(q.loader() == LoaderType.QUILT ? List.of("categories:quilt", "categories:fabric")
                : List.of("categories:" + q.loader().catalogId()));
        }
        for (String c : q.categories()) {
            if (c != null && !c.isBlank()) {
                facets.add(List.of("categories:" + c.trim()));
            }
        }
        int limit = Math.min(MAX_LIMIT, q.limit());
        Map<String, String> query = new LinkedHashMap<>();
        if (!q.text().isBlank()) {
            query.put("query", q.text());
        }
        query.put("facets", Json.COMPACT.toJson(facets));
        query.put("index", index(q.sort()));
        query.put("offset", String.valueOf(q.offset()));
        query.put("limit", String.valueOf(limit));
        JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "search", query), cancel), api.getHost());
        List<CatalogProject> hits = new ArrayList<>();
        for (JsonObject h : JsonFields.objects(o, "hits")) {
            hits.add(parseHit(h, q.kind()));
        }
        return new SearchPage(hits, (int) JsonFields.lng(o, "offset", q.offset()), (int) JsonFields.lng(o, "limit", limit),
            (int) Math.min(Integer.MAX_VALUE, JsonFields.lng(o, "total_hits", hits.size())));
    }

    static String index(SortOrder s) {
        return switch (s == null ? SortOrder.RELEVANCE : s) {
            case RELEVANCE -> "relevance";
            case DOWNLOADS -> "downloads";
            case FOLLOWS -> "follows";
            case NEWEST -> "newest";
            case UPDATED -> "updated";
        };
    }

    private static boolean usesLoader(ContentKind kind) {
        return kind == ContentKind.MOD || kind == ContentKind.MODPACK;
    }

    private CatalogProject parseHit(JsonObject h, ContentKind fallback) {
        String type = JsonFields.str(h, "project_type");
        List<String> categories = JsonFields.strings(h, "categories");
        List<String> display = h.has("display_categories") ? JsonFields.strings(h, "display_categories") : categories;
        List<String> loaders = new ArrayList<>();
        List<String> cats = new ArrayList<>();
        for (String c : categories) {
            if (LOADER_NAMES.contains(c.toLowerCase(Locale.ROOT))) {
                loaders.add(c);
            }
        }
        for (String c : display) {
            if (!LOADER_NAMES.contains(c.toLowerCase(Locale.ROOT))) {
                cats.add(c);
            }
        }
        List<URI> gallery = new ArrayList<>();
        for (String g : JsonFields.strings(h, "gallery")) {
            URI u = JsonFields.uri(g);
            if (u != null) {
                gallery.add(u);
            }
        }
        String slug = JsonFields.str(h, "slug");
        String id = JsonFields.str(h, "project_id");
        return new CatalogProject(ID, id, slug, JsonFields.str(h, "title"), JsonFields.str(h, "description"), null,
            JsonFields.str(h, "author"), JsonFields.uri(JsonFields.str(h, "icon_url")), JsonFields.lng(h, "downloads", 0),
            JsonFields.lng(h, "follows", 0), cats, JsonFields.strings(h, "versions"), loaders, kindOf(type, fallback),
            JsonFields.str(h, "client_side"), JsonFields.str(h, "server_side"), pageUrl(type, slug, id), gallery,
            JsonFields.str(h, "license"), JsonFields.instant(JsonFields.str(h, "date_modified")));
    }

    // ------------------------------------------------------------------ projects

    @Override
    public CatalogProject project(String idOrSlug, CancellationToken cancel) throws LauncherException {
        requireId(idOrSlug);
        JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "project/" + HttpService.encodePath(idOrSlug.trim()), null),
            cancel), api.getHost());
        String id = JsonFields.str(o, "id");
        String author = null;
        try {
            author = owner(id == null ? idOrSlug : id, cancel);
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                throw e;
            }
            LOG.fine(() -> "No team for " + idOrSlug + ": " + e.getMessage());
        }
        return parseProject(o, author);
    }

    /**
     * Several projects in one request ({@code GET projects?ids=[...]}), keyed by project id. Unknown ids are
     * simply absent.
     */
    public Map<String, CatalogProject> projects(List<String> ids, CancellationToken cancel) throws LauncherException {
        Map<String, CatalogProject> out = new LinkedHashMap<>();
        List<String> clean = ids == null ? List.of() : ids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        for (int i = 0; i < clean.size(); i += 100) {
            List<String> chunk = clean.subList(i, Math.min(clean.size(), i + 100));
            JsonArray a = JsonFields.requireArray(get(HttpService.uri(api, "projects", Map.of("ids", Json.COMPACT.toJson(chunk))),
                cancel), api.getHost());
            for (JsonObject o : JsonFields.objects(a)) {
                CatalogProject p = parseProject(o, null);
                out.put(p.id(), p);
            }
        }
        return out;
    }

    private String owner(String projectId, CancellationToken cancel) throws LauncherException {
        JsonArray members = JsonFields.requireArray(get(HttpService.uri(api, "project/" + HttpService.encodePath(projectId) + "/members",
            null), cancel), api.getHost());
        String first = null;
        for (JsonObject m : JsonFields.objects(members)) {
            String name = JsonFields.str(JsonFields.obj(m, "user"), "username");
            if (name == null) {
                continue;
            }
            if ("Owner".equalsIgnoreCase(JsonFields.str(m, "role")) || JsonFields.bool(m, "is_owner", false)) {
                return name;
            }
            if (first == null) {
                first = name;
            }
        }
        return first;
    }

    private CatalogProject parseProject(JsonObject o, String author) {
        String type = JsonFields.str(o, "project_type");
        String slug = JsonFields.str(o, "slug");
        String id = JsonFields.str(o, "id");
        List<URI> gallery = new ArrayList<>();
        List<JsonObject> items = new ArrayList<>(JsonFields.objects(o, "gallery"));
        // featured image first, then Modrinth's ordering
        items.sort(Comparator.comparing((JsonObject g) -> !JsonFields.bool(g, "featured", false))
            .thenComparingLong(g -> JsonFields.lng(g, "ordering", 0)));
        for (JsonObject g : items) {
            URI u = JsonFields.uri(JsonFields.str(g, "url"));
            if (u != null) {
                gallery.add(u);
            }
        }
        JsonObject license = JsonFields.obj(o, "license");
        String lic = license == null ? JsonFields.str(o, "license") : firstNonBlank(JsonFields.str(license, "id"),
            JsonFields.str(license, "name"));
        List<String> categories = new ArrayList<>(JsonFields.strings(o, "categories"));
        return new CatalogProject(ID, id, slug, JsonFields.str(o, "title"), JsonFields.str(o, "description"),
            JsonFields.str(o, "body"), author, JsonFields.uri(JsonFields.str(o, "icon_url")), JsonFields.lng(o, "downloads", 0),
            JsonFields.lng(o, "followers", 0), categories, JsonFields.strings(o, "game_versions"), JsonFields.strings(o, "loaders"),
            kindOf(type, ContentKind.MOD), JsonFields.str(o, "client_side"), JsonFields.str(o, "server_side"),
            pageUrl(type, slug, id), gallery, lic, JsonFields.instant(JsonFields.str(o, "updated")));
    }

    // ------------------------------------------------------------------ versions

    @Override
    public List<CatalogVersion> versions(String projectId, String minecraftVersion, LoaderType loader, CancellationToken cancel)
        throws LauncherException {
        requireId(projectId);
        Map<String, String> query = new LinkedHashMap<>();
        if (loader != null && loader.catalogId() != null) {
            List<String> loaders = loader == LoaderType.QUILT ? List.of("quilt", "fabric") : List.of(loader.catalogId());
            query.put("loaders", Json.COMPACT.toJson(loaders));
        }
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            query.put("game_versions", Json.COMPACT.toJson(List.of(minecraftVersion.trim())));
        }
        JsonArray a = JsonFields.requireArray(get(HttpService.uri(api, "project/" + HttpService.encodePath(projectId.trim()) + "/version",
            query), cancel), api.getHost());
        List<CatalogVersion> out = new ArrayList<>();
        for (JsonObject v : JsonFields.objects(a)) {
            out.add(parseVersion(v));
        }
        out.sort(Comparator.comparing(CatalogVersion::published, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Override
    public CatalogVersion version(String versionId, CancellationToken cancel) throws LauncherException {
        requireId(versionId);
        return parseVersion(JsonFields.requireObject(get(HttpService.uri(api, "version/" + HttpService.encodePath(versionId.trim()),
            null), cancel), api.getHost()));
    }

    /**
     * The versions whose files have these SHA-1 hashes ({@code POST version_files}), keyed by hash
     * (lowercase). Hashes Modrinth doesn't know are absent.
     */
    public Map<String, CatalogVersion> versionsByHash(List<String> sha1s, CancellationToken cancel) throws LauncherException {
        return byHash("version_files", sha1s, null, null, cancel);
    }

    /**
     * The newest version for this Minecraft version and loader of each project whose file has one of these
     * SHA-1 hashes ({@code POST version_files/update}), keyed by the given hash (lowercase). Null filters are
     * left out of the request.
     */
    public Map<String, CatalogVersion> latestVersionsByHash(List<String> sha1s, String minecraftVersion, LoaderType loader,
                                                            CancellationToken cancel) throws LauncherException {
        List<String> loaders = loader == null || loader.catalogId() == null ? null
            : loader == LoaderType.QUILT ? List.of("quilt", "fabric") : List.of(loader.catalogId());
        List<String> versions = minecraftVersion == null || minecraftVersion.isBlank() ? null : List.of(minecraftVersion.trim());
        return byHash("version_files/update", sha1s, loaders, versions, cancel);
    }

    private Map<String, CatalogVersion> byHash(String path, List<String> sha1s, List<String> loaders, List<String> versions,
                                               CancellationToken cancel) throws LauncherException {
        Map<String, CatalogVersion> out = new LinkedHashMap<>();
        Set<String> hashes = new LinkedHashSet<>();
        if (sha1s != null) {
            for (String h : sha1s) {
                if (h != null && HashAlgo.SHA1.isValidHex(h.trim())) {
                    hashes.add(h.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> all = new ArrayList<>(hashes);
        for (int i = 0; i < all.size(); i += HASH_CHUNK) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("hashes", all.subList(i, Math.min(all.size(), i + HASH_CHUNK)));
            body.put("algorithm", "sha1");
            if (loaders != null) {
                body.put("loaders", loaders);
            }
            if (versions != null) {
                body.put("game_versions", versions);
            }
            HttpService.Response r = http.postJson(HttpService.uri(api, path, null), body, Map.of(), true, cancel).requireOk();
            JsonObject o = JsonFields.requireObject(r.json(JsonElement.class), api.getHost());
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    out.put(e.getKey().toLowerCase(Locale.ROOT), parseVersion(e.getValue().getAsJsonObject()));
                }
            }
        }
        return out;
    }

    static CatalogVersion parseVersion(JsonObject v) {
        List<CatalogFile> files = new ArrayList<>();
        for (JsonObject f : JsonFields.objects(v, "files")) {
            JsonObject hashes = JsonFields.obj(f, "hashes");
            files.add(new CatalogFile(JsonFields.uri(JsonFields.str(f, "url")), JsonFields.str(f, "filename"),
                JsonFields.lng(f, "size", -1), JsonFields.str(hashes, "sha1"), JsonFields.str(hashes, "sha512"),
                JsonFields.bool(f, "primary", false), false));
        }
        List<CatalogDependency> deps = new ArrayList<>();
        for (JsonObject d : JsonFields.objects(v, "dependencies")) {
            deps.add(new CatalogDependency(JsonFields.str(d, "project_id"), JsonFields.str(d, "version_id"),
                JsonFields.str(d, "file_name"), dependencyType(JsonFields.str(d, "dependency_type"))));
        }
        String type = JsonFields.str(v, "version_type");
        return new CatalogVersion(ID, JsonFields.str(v, "id"), JsonFields.str(v, "project_id"), JsonFields.str(v, "version_number"),
            JsonFields.str(v, "name"), JsonFields.str(v, "changelog"), JsonFields.strings(v, "game_versions"),
            JsonFields.strings(v, "loaders"), type == null ? "release" : type.toLowerCase(Locale.ROOT),
            JsonFields.instant(JsonFields.str(v, "date_published")), files, deps);
    }

    static DependencyType dependencyType(String s) {
        if (s == null) {
            return DependencyType.OPTIONAL;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "required" -> DependencyType.REQUIRED;
            case "incompatible" -> DependencyType.INCOMPATIBLE;
            case "embedded" -> DependencyType.EMBEDDED;
            default -> DependencyType.OPTIONAL;
        };
    }

    // ------------------------------------------------------------------ categories

    @Override
    public List<String> categories(ContentKind kind, CancellationToken cancel) throws LauncherException {
        JsonArray tags = categoryTags;
        if (tags == null) {
            tags = JsonFields.requireArray(get(HttpService.uri(api, "tag/category", null), cancel), api.getHost());
            categoryTags = tags;
        }
        String type = (kind == null ? ContentKind.MOD : kind).modrinthType();
        List<String> out = new ArrayList<>();
        for (JsonObject t : JsonFields.objects(tags)) {
            String name = JsonFields.str(t, "name");
            if (name != null && type.equals(JsonFields.str(t, "project_type")) && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private JsonElement get(URI uri, CancellationToken cancel) throws LauncherException {
        try {
            return http.getJson(uri, JsonElement.class, cancel);
        } catch (RuntimeException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "error.invalidResponse", String.valueOf(uri.getHost()));
        }
    }

    /** {@code https://modrinth.com/<type>/<slug>} */
    public static URI pageUrl(String projectType, String slug, String id) {
        String type = projectType == null || projectType.isBlank() ? "mod" : projectType.toLowerCase(Locale.ROOT);
        String name = slug != null && !slug.isBlank() ? slug : id;
        if (name == null || !type.matches("[a-z]+")) {
            return null;
        }
        return SITE.resolve(type + "/" + HttpService.encodePath(name));
    }

    private static ContentKind kindOf(String type, ContentKind fallback) {
        if (type == null) {
            return fallback;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "mod" -> ContentKind.MOD;
            case "resourcepack" -> ContentKind.RESOURCE_PACK;
            case "shader" -> ContentKind.SHADER_PACK;
            case "datapack" -> ContentKind.DATAPACK;
            case "modpack" -> ContentKind.MODPACK;
            default -> fallback;
        };
    }

    private static void requireId(String id) throws LauncherException {
        if (id == null || id.isBlank()) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "catalog.error.noId");
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
