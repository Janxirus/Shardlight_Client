package dev.shardlight.client.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.Json;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CurseForge (API v1). Usable only with an approved API key supplied by the distributor; without one the
 * provider reports itself unavailable and every call fails with {@code API_KEY_MISSING}. The key goes in
 * the {@code x-api-key} header and is registered with {@link Redactor}, so it never reaches a log.
 *
 * <p>Files whose author disallows third-party downloads ({@code downloadUrl} null or the mod's
 * {@code allowModDistribution} false) are marked {@link CatalogFile#distributionRestricted()}; the launcher
 * never builds a download URL for them and sends the user to the website instead.
 */
public final class CurseForgeCatalog implements CatalogProvider {
    /** provider id */
    public static final String ID = CatalogRegistry.CURSEFORGE;
    /** Minecraft's game id */
    public static final int GAME_ID = 432;
    public static final int CLASS_MODS = 6;
    public static final int CLASS_MODPACKS = 4471;
    public static final int CLASS_RESOURCE_PACKS = 12;
    public static final int CLASS_SHADERS = 6552;
    public static final int CLASS_DATAPACKS = 6945;
    /** CurseForge refuses index + pageSize above this */
    public static final int MAX_RESULT_WINDOW = 10_000;
    private static final int MAX_PAGE = 50;
    private static final int MAX_FILE_PAGES = 20;
    private static final int BATCH = 100;
    private static final Duration DISTRIBUTION_TTL = Duration.ofMinutes(30);
    private static final Pattern MC_VERSION = Pattern.compile("^\\d+(\\.\\d+)+(-[\\w.]+)?$|^\\d{2}w\\d{2}[a-z]$");
    private static final Set<String> LOADER_NAMES = Set.of("forge", "fabric", "quilt", "neoforge");

    private final HttpService http;
    private final URI api;
    private final Supplier<Optional<String>> apiKey;
    /** mod id to (allowModDistribution, time) */
    private final Map<String, Object[]> distribution = new ConcurrentHashMap<>();
    private final Map<Integer, JsonArray> categoryCache = new ConcurrentHashMap<>();

    /**
     * @param apiKey supplies the approved key (from the OS secret store or the distributor's build); empty
     *               when none is configured
     */
    public CurseForgeCatalog(HttpService http, Endpoints endpoints, Supplier<Optional<String>> apiKey) {
        this.http = Objects.requireNonNull(http);
        this.api = endpoints.curseForgeApi();
        this.apiKey = apiKey == null ? Optional::empty : apiKey;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "CurseForge";
    }

    @Override
    public boolean isAvailable() {
        return currentKey().isPresent();
    }

    @Override
    public String unavailableReasonKey() {
        return isAvailable() ? null : "catalog.curseforge.noKey";
    }

    private Optional<String> currentKey() {
        Optional<String> k;
        try {
            k = apiKey.get();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (k == null) {
            return Optional.empty();
        }
        return k.map(String::trim).filter(s -> !s.isEmpty());
    }

    private Map<String, String> headers() throws LauncherException {
        String key = currentKey().orElseThrow(() -> new LauncherException(ErrorCode.API_KEY_MISSING, "catalog.curseforge.noKey"));
        Redactor.register(key);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-api-key", key);
        h.put("Accept", "application/json");
        return h;
    }

    // ------------------------------------------------------------------ search

    @Override
    public SearchPage search(SearchQuery q, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = headers();
        int pageSize = Math.min(MAX_PAGE, q.limit());
        int index = q.offset();
        if (index >= MAX_RESULT_WINDOW) {
            return new SearchPage(List.of(), index, pageSize, MAX_RESULT_WINDOW);
        }
        pageSize = Math.min(pageSize, MAX_RESULT_WINDOW - index);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("gameId", String.valueOf(GAME_ID));
        query.put("classId", String.valueOf(classId(q.kind())));
        if (!q.text().isBlank()) {
            query.put("searchFilter", q.text());
        }
        if (q.minecraftVersion() != null && !q.minecraftVersion().isBlank()) {
            query.put("gameVersion", q.minecraftVersion().trim());
        }
        if ((q.kind() == ContentKind.MOD || q.kind() == ContentKind.MODPACK) && modLoaderType(q.loader()) > 0) {
            query.put("modLoaderType", String.valueOf(modLoaderType(q.loader())));
        }
        if (!q.categories().isEmpty()) {
            List<Long> ids = categoryIds(q.kind(), q.categories(), cancel);
            if (ids.size() == 1) {
                query.put("categoryId", String.valueOf(ids.get(0)));
            } else if (!ids.isEmpty()) {
                query.put("categoryIds", ids.subList(0, Math.min(10, ids.size())).toString().replace(" ", ""));
            }
        }
        query.put("sortField", String.valueOf(sortField(q.sort())));
        query.put("sortOrder", "desc");
        query.put("index", String.valueOf(index));
        query.put("pageSize", String.valueOf(pageSize));
        JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "mods/search", query), h, cancel), api.getHost());
        List<CatalogProject> hits = new ArrayList<>();
        for (JsonObject m : JsonFields.objects(o, "data")) {
            hits.add(parseMod(m, null));
        }
        JsonObject pg = JsonFields.obj(o, "pagination");
        long total = JsonFields.lng(pg, "totalCount", hits.size());
        return new SearchPage(hits, (int) JsonFields.lng(pg, "index", index), (int) JsonFields.lng(pg, "pageSize", pageSize),
            (int) Math.min(total, MAX_RESULT_WINDOW));
    }

    /** CurseForge sortField: 1 featured, 2 popularity, 3 last updated, 6 total downloads, 11 released date */
    static int sortField(SortOrder s) {
        return switch (s == null ? SortOrder.RELEVANCE : s) {
            case RELEVANCE -> 1;
            case FOLLOWS -> 2;
            case UPDATED -> 3;
            case DOWNLOADS -> 6;
            case NEWEST -> 11;
        };
    }

    /** CurseForge classId of a content kind */
    public static int classId(ContentKind kind) {
        return switch (kind == null ? ContentKind.MOD : kind) {
            case MOD -> CLASS_MODS;
            case MODPACK -> CLASS_MODPACKS;
            case RESOURCE_PACK -> CLASS_RESOURCE_PACKS;
            case SHADER_PACK -> CLASS_SHADERS;
            case DATAPACK -> CLASS_DATAPACKS;
        };
    }

    /** the content kind of a CurseForge classId (null when unknown) */
    public static ContentKind kindOf(long classId) {
        if (classId == CLASS_MODS) {
            return ContentKind.MOD;
        }
        if (classId == CLASS_MODPACKS) {
            return ContentKind.MODPACK;
        }
        if (classId == CLASS_RESOURCE_PACKS) {
            return ContentKind.RESOURCE_PACK;
        }
        if (classId == CLASS_SHADERS) {
            return ContentKind.SHADER_PACK;
        }
        if (classId == CLASS_DATAPACKS) {
            return ContentKind.DATAPACK;
        }
        return null;
    }

    /** CurseForge modLoaderType: Forge 1, Fabric 4, Quilt 5, NeoForge 6; 0 for none */
    public static int modLoaderType(LoaderType loader) {
        if (loader == null) {
            return 0;
        }
        return switch (loader) {
            case FORGE -> 1;
            case FABRIC -> 4;
            case QUILT -> 5;
            case NEOFORGE -> 6;
            default -> 0;
        };
    }

    private static String loaderName(long modLoaderType) {
        return switch ((int) modLoaderType) {
            case 1 -> "forge";
            case 4 -> "fabric";
            case 5 -> "quilt";
            case 6 -> "neoforge";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ projects

    @Override
    public CatalogProject project(String idOrSlug, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = headers();
        if (idOrSlug == null || idOrSlug.isBlank()) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "catalog.error.noId");
        }
        String key = idOrSlug.trim();
        JsonObject mod;
        if (key.chars().allMatch(Character::isDigit)) {
            mod = JsonFields.obj(JsonFields.requireObject(get(HttpService.uri(api, "mods/" + key, null), h, cancel),
                api.getHost()), "data");
        } else {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("gameId", String.valueOf(GAME_ID));
            query.put("slug", key);
            JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "mods/search", query), h, cancel), api.getHost());
            mod = JsonFields.objects(o, "data").stream().filter(m -> key.equalsIgnoreCase(JsonFields.str(m, "slug")))
                .min(Comparator.comparingLong(m -> JsonFields.lng(m, "classId", 0) == CLASS_MODS ? 0 : 1)).orElse(null);
        }
        if (mod == null) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", api.getHost(), key);
        }
        String id = String.valueOf(JsonFields.lng(mod, "id", 0));
        String body = null;
        try {
            JsonObject d = JsonFields.requireObject(get(HttpService.uri(api, "mods/" + id + "/description", null), h, cancel),
                api.getHost());
            body = htmlToText(JsonFields.str(d, "data"));
        } catch (LauncherException e) {
            if (e.isCancelled() || e.code() == ErrorCode.API_KEY_MISSING) {
                throw e;
            }
        }
        return parseMod(mod, body);
    }

    /** several mods in one request ({@code POST mods}), keyed by mod id */
    public Map<String, CatalogProject> projectsById(Collection<Long> modIds, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = headers();
        Map<String, CatalogProject> out = new LinkedHashMap<>();
        List<Long> ids = modIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(modIds));
        for (int i = 0; i < ids.size(); i += BATCH) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("modIds", ids.subList(i, Math.min(ids.size(), i + BATCH)));
            body.put("filterPcOnly", true);
            JsonObject o = JsonFields.requireObject(post(HttpService.uri(api, "mods", null), body, h, cancel), api.getHost());
            for (JsonObject m : JsonFields.objects(o, "data")) {
                CatalogProject p = parseMod(m, null);
                out.put(p.id(), p);
            }
        }
        return out;
    }

    private CatalogProject parseMod(JsonObject m, String body) {
        String id = String.valueOf(JsonFields.lng(m, "id", 0));
        Boolean allow = JsonFields.boolOrNull(m, "allowModDistribution");
        distribution.put(id, new Object[] {allow == null || allow, System.currentTimeMillis()});
        String author = null;
        List<JsonObject> authors = JsonFields.objects(m, "authors");
        if (!authors.isEmpty()) {
            author = JsonFields.str(authors.get(0), "name");
        }
        List<String> categories = new ArrayList<>();
        for (JsonObject c : JsonFields.objects(m, "categories")) {
            String n = JsonFields.str(c, "name");
            if (n != null && !categories.contains(n)) {
                categories.add(n);
            }
        }
        Set<String> versions = new LinkedHashSet<>();
        Set<String> loaders = new LinkedHashSet<>();
        for (JsonObject f : JsonFields.objects(m, "latestFilesIndexes")) {
            String gv = JsonFields.str(f, "gameVersion");
            if (gv != null) {
                versions.add(gv);
            }
            String l = loaderName(JsonFields.lng(f, "modLoader", 0));
            if (l != null) {
                loaders.add(l);
            }
        }
        List<URI> gallery = new ArrayList<>();
        for (JsonObject s : JsonFields.objects(m, "screenshots")) {
            URI u = JsonFields.uri(JsonFields.str(s, "url"));
            if (u != null) {
                gallery.add(u);
            }
        }
        JsonObject links = JsonFields.obj(m, "links");
        ContentKind kind = kindOf(JsonFields.lng(m, "classId", CLASS_MODS));
        return new CatalogProject(ID, id, JsonFields.str(m, "slug"), JsonFields.str(m, "name"), JsonFields.str(m, "summary"),
            body, author, JsonFields.uri(JsonFields.str(JsonFields.obj(m, "logo"), "url")), JsonFields.lng(m, "downloadCount", 0),
            JsonFields.lng(m, "thumbsUpCount", 0), categories, new ArrayList<>(versions), new ArrayList<>(loaders),
            kind == null ? ContentKind.MOD : kind, null, null, JsonFields.uri(JsonFields.str(links, "websiteUrl")), gallery,
            null, JsonFields.instant(JsonFields.str(m, "dateModified")));
    }

    // ------------------------------------------------------------------ files

    @Override
    public List<CatalogVersion> versions(String projectId, String minecraftVersion, LoaderType loader, CancellationToken cancel)
        throws LauncherException {
        Map<String, String> h = headers();
        String modId = numericId(projectId);
        boolean allowed = distributionAllowed(modId, h, cancel);
        List<Integer> loaderTypes = new ArrayList<>();
        if (loader == LoaderType.QUILT) {
            loaderTypes.add(5);
            loaderTypes.add(4); // Quilt also loads Fabric mods
        } else {
            loaderTypes.add(modLoaderType(loader));
        }
        Map<String, CatalogVersion> byId = new LinkedHashMap<>();
        for (int lt : loaderTypes) {
            for (int page = 0, index = 0; page < MAX_FILE_PAGES; page++) {
                Map<String, String> query = new LinkedHashMap<>();
                if (minecraftVersion != null && !minecraftVersion.isBlank()) {
                    query.put("gameVersion", minecraftVersion.trim());
                }
                if (lt > 0) {
                    query.put("modLoaderType", String.valueOf(lt));
                }
                query.put("index", String.valueOf(index));
                query.put("pageSize", String.valueOf(MAX_PAGE));
                JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "mods/" + modId + "/files", query), h, cancel),
                    api.getHost());
                List<JsonObject> data = JsonFields.objects(o, "data");
                for (JsonObject f : data) {
                    if (JsonFields.bool(f, "isServerPack", false)) {
                        continue;
                    }
                    CatalogVersion v = parseFile(f, allowed);
                    byId.putIfAbsent(v.id(), v);
                }
                JsonObject pg = JsonFields.obj(o, "pagination");
                long total = JsonFields.lng(pg, "totalCount", data.size());
                index += data.size();
                if (data.size() < MAX_PAGE || index >= total || index + MAX_PAGE > MAX_RESULT_WINDOW) {
                    break;
                }
            }
        }
        List<CatalogVersion> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(CatalogVersion::published, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * One file. {@code versionId} is the numeric file id, or {@code "<modId>:<fileId>"} to use
     * {@code GET mods/{modId}/files/{fileId}}.
     */
    @Override
    public CatalogVersion version(String versionId, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = headers();
        if (versionId != null && versionId.contains(":")) {
            String[] parts = versionId.split(":", 2);
            return file(Long.parseLong(numericId(parts[0])), Long.parseLong(numericId(parts[1])), cancel);
        }
        String fileId = numericId(versionId);
        Map<String, CatalogVersion> m = filesById(List.of(Long.parseLong(fileId)), cancel);
        CatalogVersion v = m.get(fileId);
        if (v == null) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", api.getHost(), "files/" + fileId);
        }
        return v;
    }

    /** {@code GET mods/{modId}/files/{fileId}} */
    public CatalogVersion file(long modId, long fileId, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = headers();
        JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "mods/" + modId + "/files/" + fileId, null), h, cancel),
            api.getHost());
        JsonObject f = JsonFields.obj(o, "data");
        if (f == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "error.invalidResponse", api.getHost());
        }
        return parseFile(f, distributionAllowed(String.valueOf(modId), h, cancel));
    }

    /**
     * Several files in one request ({@code POST mods/files}), keyed by file id; distribution restrictions are
     * resolved with one {@code POST mods} for the mods involved.
     */
    public Map<String, CatalogVersion> filesById(Collection<Long> fileIds, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = headers();
        List<Long> ids = fileIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(fileIds));
        List<JsonObject> raw = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += BATCH) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fileIds", ids.subList(i, Math.min(ids.size(), i + BATCH)));
            JsonObject o = JsonFields.requireObject(post(HttpService.uri(api, "mods/files", null), body, h, cancel), api.getHost());
            raw.addAll(JsonFields.objects(o, "data"));
        }
        Set<Long> unknown = new LinkedHashSet<>();
        for (JsonObject f : raw) {
            String mod = String.valueOf(JsonFields.lng(f, "modId", 0));
            if (cachedDistribution(mod) == null) {
                unknown.add(JsonFields.lng(f, "modId", 0));
            }
        }
        if (!unknown.isEmpty()) {
            projectsById(unknown, cancel); // fills the distribution cache
        }
        Map<String, CatalogVersion> out = new LinkedHashMap<>();
        for (JsonObject f : raw) {
            Boolean allowed = cachedDistribution(String.valueOf(JsonFields.lng(f, "modId", 0)));
            CatalogVersion v = parseFile(f, allowed == null || allowed);
            out.put(v.id(), v);
        }
        return out;
    }

    private CatalogVersion parseFile(JsonObject f, boolean distributionAllowed) {
        String sha1 = null;
        for (JsonObject hash : JsonFields.objects(f, "hashes")) {
            // algo 1 is SHA-1, 2 is MD5 (never used as the only check)
            if (JsonFields.lng(hash, "algo", 0) == 1) {
                sha1 = JsonFields.str(hash, "value");
            }
        }
        URI url = JsonFields.uri(JsonFields.str(f, "downloadUrl"));
        boolean restricted = url == null || !distributionAllowed;
        CatalogFile file = new CatalogFile(restricted ? null : url, JsonFields.str(f, "fileName"),
            JsonFields.lng(f, "fileLength", -1), sha1, null, true, restricted);
        List<String> gameVersions = new ArrayList<>();
        List<String> loaders = new ArrayList<>();
        for (String gv : JsonFields.strings(f, "gameVersions")) {
            String l = gv.trim().toLowerCase(Locale.ROOT);
            if (LOADER_NAMES.contains(l)) {
                if (!loaders.contains(l)) {
                    loaders.add(l);
                }
            } else if (MC_VERSION.matcher(gv.trim()).matches() && !gameVersions.contains(gv.trim())) {
                gameVersions.add(gv.trim());
            }
        }
        List<CatalogDependency> deps = new ArrayList<>();
        for (JsonObject d : JsonFields.objects(f, "dependencies")) {
            long mod = JsonFields.lng(d, "modId", 0);
            if (mod <= 0) {
                continue;
            }
            deps.add(new CatalogDependency(String.valueOf(mod), null, null, relation(JsonFields.lng(d, "relationType", 0))));
        }
        String display = JsonFields.str(f, "displayName");
        return new CatalogVersion(ID, String.valueOf(JsonFields.lng(f, "id", 0)), String.valueOf(JsonFields.lng(f, "modId", 0)),
            display, display, null, gameVersions, loaders, channel(JsonFields.lng(f, "releaseType", 1)),
            JsonFields.instant(JsonFields.str(f, "fileDate")), List.of(file), deps);
    }

    static DependencyType relation(long relationType) {
        return switch ((int) relationType) {
            case 1, 6 -> DependencyType.EMBEDDED; // embedded library, include
            case 3 -> DependencyType.REQUIRED;
            case 5 -> DependencyType.INCOMPATIBLE;
            default -> DependencyType.OPTIONAL; // 2 optional, 4 tool
        };
    }

    private static String channel(long releaseType) {
        return switch ((int) releaseType) {
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "release";
        };
    }

    private boolean distributionAllowed(String modId, Map<String, String> h, CancellationToken cancel) throws LauncherException {
        Boolean cached = cachedDistribution(modId);
        if (cached != null) {
            return cached;
        }
        JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "mods/" + modId, null), h, cancel), api.getHost());
        JsonObject mod = JsonFields.obj(o, "data");
        if (mod != null) {
            parseMod(mod, null);
        }
        Boolean now = cachedDistribution(modId);
        return now == null || now;
    }

    private Boolean cachedDistribution(String modId) {
        Object[] v = distribution.get(modId);
        if (v == null || System.currentTimeMillis() - (Long) v[1] > DISTRIBUTION_TTL.toMillis()) {
            return null;
        }
        return (Boolean) v[0];
    }

    // ------------------------------------------------------------------ categories

    @Override
    public List<String> categories(ContentKind kind, CancellationToken cancel) throws LauncherException {
        List<String> out = new ArrayList<>();
        for (JsonObject c : JsonFields.objects(categoryList(kind, cancel))) {
            String n = JsonFields.str(c, "name");
            if (n != null && !JsonFields.bool(c, "isClass", false) && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private JsonArray categoryList(ContentKind kind, CancellationToken cancel) throws LauncherException {
        int classId = classId(kind);
        JsonArray cached = categoryCache.get(classId);
        if (cached != null) {
            return cached;
        }
        Map<String, String> h = headers();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("gameId", String.valueOf(GAME_ID));
        query.put("classId", String.valueOf(classId));
        JsonObject o = JsonFields.requireObject(get(HttpService.uri(api, "categories", query), h, cancel), api.getHost());
        JsonArray a = JsonFields.arr(o, "data");
        categoryCache.put(classId, a);
        return a;
    }

    private List<Long> categoryIds(ContentKind kind, List<String> names, CancellationToken cancel) throws LauncherException {
        List<Long> out = new ArrayList<>();
        List<JsonObject> all = JsonFields.objects(categoryList(kind, cancel));
        for (String n : names) {
            for (JsonObject c : all) {
                if (n != null && (n.equalsIgnoreCase(JsonFields.str(c, "name")) || n.equalsIgnoreCase(JsonFields.str(c, "slug")))) {
                    out.add(JsonFields.lng(c, "id", 0));
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private JsonElement get(URI uri, Map<String, String> headers, CancellationToken cancel) throws LauncherException {
        return send(new HttpService.Request("GET", uri, headers, null, Duration.ofSeconds(30), true), cancel);
    }

    private JsonElement post(URI uri, Object body, Map<String, String> headers, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Content-Type", "application/json");
        byte[] bytes = Json.COMPACT.toJson(body).getBytes(StandardCharsets.UTF_8);
        return send(new HttpService.Request("POST", uri, h, bytes, Duration.ofSeconds(30), true), cancel);
    }

    private JsonElement send(HttpService.Request req, CancellationToken cancel) throws LauncherException {
        HttpService.Response r = http.execute(req, cancel);
        if (r.status() == 401 || r.status() == 403) {
            throw new LauncherException(ErrorCode.API_KEY_MISSING, "catalog.curseforge.keyRejected", r.status());
        }
        try {
            return r.requireOk().json(JsonElement.class);
        } catch (RuntimeException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "error.invalidResponse", String.valueOf(req.uri().getHost()));
        }
    }

    private static String numericId(String id) throws LauncherException {
        String t = id == null ? "" : id.trim();
        if (t.isEmpty() || t.length() > 18 || !t.chars().allMatch(Character::isDigit)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "catalog.error.badCurseForgeId", String.valueOf(id));
        }
        return t;
    }

    // ------------------------------------------------------------------ HTML

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1\\s*>");
    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr|blockquote|pre|ul|ol|table)\\s*>");
    private static final Pattern ITEM = Pattern.compile("(?i)<li[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");

    /** CurseForge's HTML descriptions as plain text (tags removed, entities decoded, paragraphs kept) */
    public static String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        String s = SCRIPT.matcher(html).replaceAll("");
        s = s.replaceAll("\\r?\\n", " ");
        s = ITEM.matcher(s).replaceAll("• ");
        s = BREAK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        Matcher m = ENTITY.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(entity(m.group(1), m.group())));
        }
        m.appendTail(b);
        String[] lines = b.toString().split("\n", -1);
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String t = line.replaceAll("[ \\t\\u00A0]+", " ").strip();
            if (t.isEmpty()) {
                if (++blank > 1 || out.isEmpty()) {
                    continue;
                }
            } else {
                blank = 0;
            }
            out.append(t).append('\n');
        }
        return out.toString().strip();
    }

    private static String entity(String e, String raw) {
        try {
            if (e.startsWith("#x") || e.startsWith("#X")) {
                return new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            }
            if (e.startsWith("#")) {
                return new String(Character.toChars(Integer.parseInt(e.substring(1))));
            }
        } catch (IllegalArgumentException ex) {
            return raw;
        }
        return switch (e) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            case "ndash" -> "–";
            case "mdash" -> "—";
            case "hellip" -> "…";
            case "copy" -> "©";
            default -> raw;
        };
    }
}
