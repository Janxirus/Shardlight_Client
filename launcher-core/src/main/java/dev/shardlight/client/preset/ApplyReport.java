package dev.shardlight.client.preset;

import java.util.List;

/**
 * What applying (or reverting) a preset did.
 *
 * @param preset       the preset now in effect; after {@link PresetEngine#revertLast} the preset that was in
 *                     effect before the reverted one, or null when the files are back to their pre-launcher values
 * @param applied      the settings whose effective value changed
 * @param filesWritten files written, relative to the game directory with forward slashes
 *                     (for example {@code config/shardmap.json}, {@code config/shardlight/launcher-preset.json})
 * @param warnings     localized lines worth showing (Ultra Quality overhead, unreadable config left alone...)
 */
public record ApplyReport(Preset preset, List<SettingChange> applied,
                          List<String> filesWritten, List<String> warnings) {
    public ApplyReport {
        applied = applied == null ? List.of() : List.copyOf(applied);
        filesWritten = filesWritten == null ? List.of() : List.copyOf(filesWritten);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
