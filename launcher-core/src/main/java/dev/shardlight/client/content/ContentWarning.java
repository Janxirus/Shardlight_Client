package dev.shardlight.client.content;

import dev.shardlight.client.catalog.Severity;
import dev.shardlight.client.i18n.I18n;

import java.util.List;

/**
 * A problem with a project's installed content, found from the files' own metadata. Metadata can't catch
 * every runtime incompatibility, so the absence of warnings is no guarantee.
 *
 * @param type       what kind of problem
 * @param severity   ERROR when the game will very likely refuse to start
 * @param entryIds   the content entries involved
 * @param messageKey i18n key (content.properties)
 * @param args       message arguments
 */
public record ContentWarning(Type type, Severity severity, List<String> entryIds, String messageKey, List<String> args) {
    /** the kinds of warning */
    public enum Type {
        /** two enabled mods declare the same mod id (the loader refuses to start) */
        DUPLICATE_MOD_ID,
        /** a mod made for another loader than the project's */
        WRONG_LOADER,
        /** enabled mods in a project without a mod loader */
        MODS_WITHOUT_LOADER,
        /** a mod whose declared Minecraft range excludes the project's version */
        MINECRAFT_VERSION,
        /** a mod meant only for dedicated servers */
        SERVER_ONLY,
        /** a mod that declares it breaks with another installed mod */
        BREAKS,
        /** the entry's file is gone */
        MISSING_FILE
    }

    public ContentWarning {
        entryIds = entryIds == null ? List.of() : List.copyOf(entryIds);
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** the localized message */
    public String message() {
        return I18n.tr(messageKey, args.toArray());
    }
}
