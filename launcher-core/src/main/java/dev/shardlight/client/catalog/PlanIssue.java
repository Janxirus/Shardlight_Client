package dev.shardlight.client.catalog;

import dev.shardlight.client.i18n.I18n;

import java.util.List;

/**
 * Something the install planner found.
 *
 * @param severity    ERROR blocks the plan ({@link InstallPlan#canInstall()} is false)
 * @param messageKey  i18n key (content.properties)
 * @param args        message arguments
 * @param overridable true when planning again with {@code allowIncompatible} turns this ERROR into a WARNING
 *                    (the user accepted the risk)
 */
public record PlanIssue(Severity severity, String messageKey, List<String> args, boolean overridable) {
    public PlanIssue {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** the localized message */
    public String message() {
        return I18n.tr(messageKey, args.toArray());
    }
}
