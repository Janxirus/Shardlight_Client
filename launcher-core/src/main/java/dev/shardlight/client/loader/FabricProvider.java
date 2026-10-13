package dev.shardlight.client.loader;

import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;

/**
 * Fabric through its metadata API v2 ({@link Endpoints#fabricMeta()}): {@code versions/game},
 * {@code versions/loader/<mc>} and {@code versions/loader/<mc>/<loader>/profile/json}, which gives a version JSON
 * with the id {@code fabric-loader-<loader>-<mc>}. The recommended version is the newest stable one.
 */
public final class FabricProvider extends MetaLoaderProvider {
    public FabricProvider(HttpService http, Endpoints endpoints, AppPaths paths) {
        super(http, endpoints.fabricMeta(), paths);
    }

    @Override
    public LoaderType type() {
        return LoaderType.FABRIC;
    }

    @Override
    String profilePrefix() {
        return "fabric-loader";
    }

    @Override
    boolean isStable(LoaderInfo loader) {
        return loader.stable() == null ? !loader.version().contains("-") : loader.stable();
    }
}
