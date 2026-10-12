package dev.shardlight.client.modpack;

import java.nio.file.Path;
import java.util.List;

/**
 * The result of a modpack export.
 *
 * @param file     the written pack
 * @param warnings localized notes about what was left out (CurseForge files that may not be redistributed,
 *                 local files without permission, the icon stored as an override...)
 */
public record ExportReport(Path file, List<String> warnings) {
    public ExportReport {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
