package dev.shardlight.client.project;

/**
 * What removing a project affects, so the confirmation can say it exactly.
 *
 * @param projectId     the project id
 * @param name          its display name
 * @param worlds        folders in {@code saves/}
 * @param screenshots   files in {@code screenshots/}
 * @param mods          jars in {@code mods/} (enabled or disabled)
 * @param resourcePacks packs in {@code resourcepacks/}
 * @param totalBytes    size of the whole project folder
 * @param hasConfig     whether {@code config/} holds anything
 */
public record RemovalPlan(String projectId, String name, int worlds, int screenshots, int mods, int resourcePacks,
                          long totalBytes, boolean hasConfig) {
}
