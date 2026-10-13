package dev.shardlight.client.loader;

import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;

/**
 * Quilt through its metadata API v3 ({@link Endpoints#quiltMeta()}), which has the same shapes as Fabric's: the
 * profile id is {@code quilt-loader-<loader>-<mc>}. Quilt's loader entries carry no {@code stable} flag, so
 * versions with a pre-release suffix ({@code 0.28.0-beta.5}) count as unstable.
 */
public final class QuiltProvider extends MetaLoaderProvider {
    public QuiltProvider(HttpService http, Endpoints endpoints, AppPaths paths) {
        super(http, endpoints.quiltMeta(), paths);
    }

    @Override
    public LoaderType type() {
        return LoaderType.QUILT;
    }

    @Override
    String profilePrefix() {
        return "quilt-loader";
    }

    @Override
    boolean isStable(LoaderInfo loader) {
        boolean plain = !loader.version().contains("-");
        return loader.stable() == null ? plain : loader.stable() && plain;
    }
}
