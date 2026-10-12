package dev.shardlight.client.catalog;

import dev.shardlight.client.i18n.I18n;

import java.util.List;
import java.util.Optional;

/**
 * What installing a catalog version would do: the files to download (root first, then dependencies), the
 * issues found and the optional dependencies to suggest. Install only when {@link #canInstall()}.
 *
 * @param files               files to download, root first
 * @param issues              problems found while planning
 * @param optionalSuggestions optional dependencies (not installed automatically)
 */
public record InstallPlan(List<PlannedFile> files, List<PlanIssue> issues, List<CatalogDependency> optionalSuggestions) {
    /** i18n key of the plan summary; the message says metadata can't catch every runtime incompatibility */
    public static final String SUMMARY_KEY = "content.plan.summary";

    public InstallPlan {
        files = files == null ? List.of() : List.copyOf(files);
        issues = issues == null ? List.of() : List.copyOf(issues);
        optionalSuggestions = optionalSuggestions == null ? List.of() : List.copyOf(optionalSuggestions);
    }

    /** true when there is something to install and no ERROR issue */
    public boolean canInstall() {
        return !files.isEmpty() && issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
    }

    /** the i18n key of the summary line ({@link #SUMMARY_KEY}); arguments are {@link #summaryArgs()} */
    public String summaryKey() {
        return SUMMARY_KEY;
    }

    /** files to install, dependencies among them */
    public List<String> summaryArgs() {
        long deps = files.stream().filter(PlannedFile::dependency).count();
        return List.of(String.valueOf(files.size()), String.valueOf(deps));
    }

    /** the localized summary */
    public String summary() {
        return I18n.tr(SUMMARY_KEY, summaryArgs().toArray());
    }

    /** the planned file of a catalog project (to show "required by" titles) */
    public Optional<PlannedFile> byProjectId(String projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        return files.stream().filter(f -> projectId.equals(f.version().projectId())).findFirst();
    }
}
