package dev.shardlight.client.modpack;

import java.nio.file.Path;

/**
 * The "create a modpack" form.
 *
 * @param format                      MRPACK or CURSEFORGE
 * @param title                       pack name
 * @param version                     pack version
 * @param summary                     short description, may be empty
 * @param icon                        a PNG icon, may be null; neither format has an icon field, so it is
 *                                    stored as {@code overrides/icon.png}
 * @param out                         where to write the pack
 * @param includeConfig               put {@code config/} into overrides
 * @param includeResourcePacks        (mrpack) include enabled resource packs
 * @param includeShaderPacks          (mrpack) include enabled shader packs
 * @param includeLocalModsAsOverrides (mrpack) put non-Modrinth files into overrides (user confirmed they may
 *                                    redistribute them)
 */
public record ModpackCreatorOptions(Format format, String title, String version, String summary, Path icon, Path out,
                                    boolean includeConfig, boolean includeResourcePacks, boolean includeShaderPacks,
                                    boolean includeLocalModsAsOverrides) {
    /** pack formats */
    public enum Format {
        /** Modrinth .mrpack */
        MRPACK,
        /** CurseForge manifest zip */
        CURSEFORGE
    }
}
