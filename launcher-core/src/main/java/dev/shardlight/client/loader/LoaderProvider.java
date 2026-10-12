package dev.shardlight.client.loader;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;

import java.util.List;

/**
 * One mod loader: lists its versions for a Minecraft version and installs one, which writes
 * {@code versions/<id>/<id>.json} (inheriting from the vanilla version) into the shared game folder.
 * Implementations are blocking and thread-safe.
 */
public interface LoaderProvider {
    /** the loader this provider installs */
    LoaderType type();

    /** the loader's versions for {@code minecraftVersion}, newest first; empty when it doesn't support it */
    List<LoaderVersion> versions(String minecraftVersion, CancellationToken cancel) throws LauncherException;

    /** true when the loader has at least one version for {@code minecraftVersion} */
    boolean supportsMinecraft(String minecraftVersion, CancellationToken cancel) throws LauncherException;

    /**
     * Install {@code loaderVersion} for {@code minecraftVersion}: write {@code versions/<id>/<id>.json} and anything
     * else the loader needs, and return the version id to launch.
     */
    String install(String minecraftVersion, String loaderVersion, ProgressListener progress, CancellationToken cancel)
        throws LauncherException;

    /**
     * The version id {@link #install} produces for this combination, computed without any network access (so an
     * installed loader can be found offline). Installers may name versions differently; {@link #install} returns
     * the real id.
     */
    String versionId(String minecraftVersion, String loaderVersion);

    /** true when launching through this loader hasn't been tested end to end yet (Forge, NeoForge) */
    boolean isExperimental();
}
