package dev.shardlight.client.minecraft.model;

/**
 * {@code javaVersion} of a version.
 *
 * @param component    Mojang runtime component ({@code jre-legacy}, {@code java-runtime-delta}...)
 * @param majorVersion required Java feature release (8, 17, 21, 25)
 */
public record JavaVersionInfo(String component, Integer majorVersion) {
}
