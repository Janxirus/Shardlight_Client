package dev.shardlight.client.shardlight;

import java.util.List;

/**
 * What an import added to the Shardlight content library.
 *
 * @param added   new library items
 * @param skipped one localized line per file that was not added, "{@code <file>: <reason>}" (not a
 *                Shardlight module, unknown pack, already in the library...)
 */
public record ImportResult(List<LibraryItem> added, List<String> skipped) {
    public ImportResult {
        added = added == null ? List.of() : List.copyOf(added);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
}
