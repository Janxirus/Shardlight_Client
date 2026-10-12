package dev.shardlight.client.modpack;

/**
 * Options for exporting a project as a Modrinth .mrpack.
 *
 * @param name                        pack name
 * @param version                     pack version ("1.0.0")
 * @param summary                     short description, may be empty
 * @param includeConfig               put {@code config/} into overrides
 * @param includeResourcePacks        include enabled resource packs
 * @param includeShaderPacks          include enabled shader packs
 * @param includeLocalModsAsOverrides put files that aren't on Modrinth (local, Shardlight, modpack files)
 *                                    into overrides; only when the user confirms they may redistribute them
 */
public record MrpackExportOptions(String name, String version, String summary, boolean includeConfig,
                                  boolean includeResourcePacks, boolean includeShaderPacks,
                                  boolean includeLocalModsAsOverrides) {
}
