package dev.shardlight.client.modpack;

import dev.shardlight.client.loader.LoaderSpec;

import java.util.List;

/**
 * What a modpack archive contains, read without importing it.
 *
 * @param format           {@link #MRPACK} or {@link #CURSEFORGE}
 * @param name             pack name
 * @param version          pack version
 * @param summary          description (may be empty)
 * @param minecraftVersion the Minecraft version it needs
 * @param loader           the loader it needs (vanilla when none)
 * @param fileCount        number of files to download
 * @param problems         localized problems found (empty when the pack looks fine)
 */
public record ModpackInfo(String format, String name, String version, String summary, String minecraftVersion,
                          LoaderSpec loader, int fileCount, List<String> problems) {
    /** a Modrinth .mrpack */
    public static final String MRPACK = "mrpack";
    /** a CurseForge modpack zip */
    public static final String CURSEFORGE = "curseforge";

    public ModpackInfo {
        problems = problems == null ? List.of() : List.copyOf(problems);
        summary = summary == null ? "" : summary;
    }
}
