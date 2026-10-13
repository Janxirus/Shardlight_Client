package dev.shardlight.client.shardlight;

import java.util.List;

/**
 * What {@link ShardlightService#apply} changed in a project.
 *
 * @param added                     file names copied into {@code mods/} or {@code resourcepacks/}
 * @param removed                   file names deleted (deselected modules, replaced jars, old theme packs)
 * @param catalogDependenciesNeeded catalog projects the integrator still has to install with the content
 *                                  services, as {@code source:project} (for example {@code modrinth:fabric-api})
 * @param warnings                  localized lines worth showing (missing pack, untested loader...)
 */
public record ApplyResult(List<String> added, List<String> removed, List<String> catalogDependenciesNeeded,
                          List<String> warnings) {
    public ApplyResult {
        added = added == null ? List.of() : List.copyOf(added);
        removed = removed == null ? List.of() : List.copyOf(removed);
        catalogDependenciesNeeded = catalogDependenciesNeeded == null ? List.of() : List.copyOf(catalogDependenciesNeeded);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
