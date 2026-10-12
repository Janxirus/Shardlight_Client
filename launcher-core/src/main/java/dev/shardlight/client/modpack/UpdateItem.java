package dev.shardlight.client.modpack;

/**
 * One line of an update preview.
 *
 * @param path     game-directory-relative path with forward slashes
 * @param action   what the update does
 * @param disabled true when the player turned this mod off ({@code .jar.disabled}); the change is applied to that file
 */
public record UpdateItem(String path, UpdateAction action, boolean disabled) {
}
