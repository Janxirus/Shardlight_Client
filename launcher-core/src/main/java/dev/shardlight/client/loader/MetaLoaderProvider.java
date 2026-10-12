package dev.shardlight.client.loader;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.MavenCoordinate;
import dev.shardlight.client.minecraft.VersionResolver;
import dev.shardlight.client.minecraft.model.Library;
import dev.shardlight.client.minecraft.model.VersionJson;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.PathSafety;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A loader with a metadata API that hands out ready-made launcher profiles (Fabric meta v2, Quilt meta v3):
 * {@code versions/game}, {@code versions/loader/<mc>} and {@code versions/loader/<mc>/<loader>/profile/json}. The
 * profile is a version JSON inheriting from vanilla; installing means saving it as {@code versions/<id>/<id>.json}
 * (its libraries are downloaded by the game installer). Version lists are kept in memory for a few minutes.
 */
abstract class MetaLoaderProvider implements LoaderProvider {
    static final String STAGE_PROFILE = "loader.stage.profile";
    private static final Logger LOG = Logger.getLogger(MetaLoaderProvider.class.getName());
    private static final long CACHE_MILLIS = 10 * 60_000L;
    private static final Type LOADER_LIST = TypeToken.getParameterized(List.class, LoaderEntry.class).getType();
    private static final Type GAME_LIST = TypeToken.getParameterized(List.class, GameEntry.class).getType();

    private final HttpService http;
    private final URI meta;
    private final VersionResolver resolver;
    private final Map<String, Cached<List<LoaderVersion>>> versionCache = new ConcurrentHashMap<>();
    private volatile Cached<Set<String>> games;

    /** {@code loader} of an entry of {@code versions/loader/<mc>} */
    record LoaderInfo(String separator, Integer build, String maven, String version, Boolean stable) {
    }

    /** an entry of {@code versions/loader/<mc>} (intermediary/hashed and launcherMeta are not needed here) */
    record LoaderEntry(LoaderInfo loader) {
    }

    /** an entry of {@code versions/game} */
    record GameEntry(String version, Boolean stable) {
    }

    private record Cached<T>(T value, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < CACHE_MILLIS;
        }
    }

    MetaLoaderProvider(HttpService http, URI meta, AppPaths paths) {
        this.http = http;
        this.meta = meta;
        this.resolver = new VersionResolver(paths.versions());
    }

    /** the profile id prefix ({@code fabric-loader}, {@code quilt-loader}) */
    abstract String profilePrefix();

    /** whether a loader entry is a stable release */
    abstract boolean isStable(LoaderInfo loader);

    @Override
    public List<LoaderVersion> versions(String minecraftVersion, CancellationToken cancel) throws LauncherException {
        requireSafe(minecraftVersion);
        Cached<List<LoaderVersion>> c = versionCache.get(minecraftVersion);
        if (c != null && c.fresh()) {
            return c.value();
        }
        URI uri = HttpService.uri(meta, "versions/loader/" + HttpService.encodePath(minecraftVersion), null);
        HttpService.Response r = get(uri, cancel);
        List<LoaderVersion> out = new ArrayList<>();
        if (r.status() == 400 || r.status() == 404) {
            versionCache.put(minecraftVersion, new Cached<>(List.of(), System.currentTimeMillis()));
            return List.of();
        }
        List<LoaderEntry> entries = r.requireOk().json(LOADER_LIST);
        boolean recommended = false;
        Set<String> seen = new HashSet<>();
        for (LoaderEntry e : entries) {
            if (e == null || e.loader() == null || e.loader().version() == null || !isSafe(e.loader().version())
                || !seen.add(e.loader().version())) {
                continue;
            }
            boolean stable = isStable(e.loader());
            out.add(new LoaderVersion(e.loader().version(), stable, stable && !recommended));
            recommended |= stable;
        }
        List<LoaderVersion> list = Collections.unmodifiableList(out);
        versionCache.put(minecraftVersion, new Cached<>(list, System.currentTimeMillis()));
        return list;
    }

    @Override
    public boolean supportsMinecraft(String minecraftVersion, CancellationToken cancel) throws LauncherException {
        if (minecraftVersion == null || !isSafe(minecraftVersion)) {
            return false;
        }
        Cached<Set<String>> g = games;
        if (g == null || !g.fresh()) {
            List<GameEntry> list = get(HttpService.uri(meta, "versions/game", null), cancel).requireOk().json(GAME_LIST);
            Set<String> ids = new HashSet<>();
            for (GameEntry e : list) {
                if (e != null && e.version() != null) {
                    ids.add(e.version());
                }
            }
            g = new Cached<>(Set.copyOf(ids), System.currentTimeMillis());
            games = g;
        }
        return g.value().contains(minecraftVersion);
    }

    @Override
    public String install(String minecraftVersion, String loaderVersion, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        requireSafe(minecraftVersion);
        requireSafe(loaderVersion);
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        p.progress(STAGE_PROFILE, type().displayName() + " " + loaderVersion, 0, 1);
        URI uri = HttpService.uri(meta, "versions/loader/" + HttpService.encodePath(minecraftVersion) + "/"
            + HttpService.encodePath(loaderVersion) + "/profile/json", null);
        HttpService.Response r = get(uri, c);
        if (r.status() == 400 || r.status() == 404) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "loader.error.unsupported", type().displayName(),
                loaderVersion, minecraftVersion);
        }
        String text = r.requireOk().text();
        VersionJson v;
        try {
            v = VersionJson.parse(text);
        } catch (JsonParseException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "loader.error.profileInvalid", type().displayName(),
                minecraftVersion);
        }
        validate(v, minecraftVersion);
        AtomicFiles.writeString(resolver.jsonPath(v.id()), text);
        p.progress(STAGE_PROFILE, type().displayName() + " " + loaderVersion, 1, 1);
        LOG.info("Installed " + type().displayName() + " " + loaderVersion + " for Minecraft " + minecraftVersion + " as " + v.id());
        return v.id();
    }

    private void validate(VersionJson v, String minecraftVersion) throws LauncherException {
        if (v.id() == null || !PathSafety.isSafeSegment(v.id())) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(v.id()));
        }
        if (v.mainClass() == null || v.mainClass().isBlank() || !minecraftVersion.equals(v.inheritsFrom())) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "loader.error.profileInvalid", type().displayName(),
                minecraftVersion);
        }
        for (Library lib : v.libraries()) {
            MavenCoordinate.parse(lib.name()); // a bad or path-escaping name fails here, before anything is written
        }
    }

    @Override
    public String versionId(String minecraftVersion, String loaderVersion) {
        return profilePrefix() + "-" + loaderVersion + "-" + minecraftVersion;
    }

    @Override
    public boolean isExperimental() {
        return false;
    }

    private HttpService.Response get(URI uri, CancellationToken cancel) throws LauncherException {
        return http.execute(HttpService.Request.get(uri).withHeaders(Map.of("Accept", "application/json")), cancel);
    }

    static boolean isSafe(String version) {
        return version != null && PathSafety.isSafeSegment(version) && version.equals(version.strip());
    }

    private static void requireSafe(String version) throws LauncherException {
        if (!isSafe(version)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(version));
        }
    }
}
