package dev.shardlight.client.shardlight;

import dev.shardlight.client.i18n.I18n;

import java.util.List;

/**
 * Whether a set of Shardlight modules can be installed into a project, and what else is needed.
 *
 * @param messages             errors (block installing), warnings and info lines, each an i18n key with arguments
 * @param missingLibraryItems  selected module ids with no suitable jar in the content library yet (import or
 *                             download them first)
 * @param catalogDependencies  catalog projects to install with the content services, as {@code source:project}
 *                             (for example {@code modrinth:fabric-api}); empty when already installed
 * @param requiredJavaMajor    Java feature version the selected modules need (0 when nothing is selected)
 */
public record CompatibilityReport(List<Message> messages, List<String> missingLibraryItems,
                                  List<String> catalogDependencies, int requiredJavaMajor) {
    /** how serious a message is */
    public enum Severity {
        /** the selection can't be installed into this project */
        ERROR,
        /** it can be installed but may not work as expected */
        WARNING,
        /** good to know */
        INFO
    }

    /**
     * One line of the report.
     *
     * @param severity how serious it is
     * @param key      i18n key (bundle {@code i18n/shardlight})
     * @param args     message arguments, already as text
     */
    public record Message(Severity severity, String key, List<String> args) {
        public Message {
            args = args == null ? List.of() : List.copyOf(args);
        }

        /** the localized text */
        public String text() {
            return I18n.tr(key, args.toArray());
        }
    }

    public CompatibilityReport {
        messages = messages == null ? List.of() : List.copyOf(messages);
        missingLibraryItems = missingLibraryItems == null ? List.of() : List.copyOf(missingLibraryItems);
        catalogDependencies = catalogDependencies == null ? List.of() : List.copyOf(catalogDependencies);
    }

    /** no errors: the selection is compatible with the project (an empty selection always is) */
    public boolean ok() {
        return errors().isEmpty();
    }

    /** compatible and every selected module's jar is in the library, so {@code apply} can run now */
    public boolean canApply() {
        return ok() && missingLibraryItems.isEmpty();
    }

    public List<Message> errors() {
        return of(Severity.ERROR);
    }

    public List<Message> warnings() {
        return of(Severity.WARNING);
    }

    public List<Message> infos() {
        return of(Severity.INFO);
    }

    /** true when some message has this key */
    public boolean has(String key) {
        return messages.stream().anyMatch(m -> m.key().equals(key));
    }

    private List<Message> of(Severity s) {
        return messages.stream().filter(m -> m.severity() == s).toList();
    }
}
