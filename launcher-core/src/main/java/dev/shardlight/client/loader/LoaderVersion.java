package dev.shardlight.client.loader;

/**
 * One version of a mod loader that can be installed for a Minecraft version.
 *
 * @param version     the loader version as the project stores it ({@code 0.16.10} for Fabric, {@code 21.1.77} for
 *                    NeoForge, {@code 54.0.12} for Forge)
 * @param stable      false for betas and pre-releases
 * @param recommended true for the one version the launcher suggests (normally the newest stable one)
 */
public record LoaderVersion(String version, boolean stable, boolean recommended) {
}
