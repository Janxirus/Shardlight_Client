package dev.shardlight.client.modpack;

import dev.shardlight.client.project.Project;

import java.util.List;

/**
 * The result of a modpack import.
 *
 * @param project    the new project
 * @param restricted files the user must download from the website themselves
 * @param warnings   localized notes (skipped optional or server-only files...)
 */
public record ImportReport(Project project, List<RestrictedFile> restricted, List<String> warnings) {
    public ImportReport {
        restricted = restricted == null ? List.of() : List.copyOf(restricted);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
