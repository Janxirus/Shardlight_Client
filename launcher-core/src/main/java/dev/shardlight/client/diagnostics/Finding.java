package dev.shardlight.client.diagnostics;

import java.util.List;

/**
 * One thing the log analysis recognised, with an actionable suggestion. The UI shows
 * {@code I18n.tr(titleKey)} and {@code I18n.tr(detailKey, args...)}.
 *
 * @param severity  how likely this is the cause
 * @param titleKey  i18n key of a short title
 * @param detailKey i18n key of the explanation and what to do; {@code args} fill its placeholders
 * @param args      values for the detail message (mod names, versions, file names)
 * @param modId     the mod this is about (the mod to update or remove), or null
 */
public record Finding(Severity severity, String titleKey, String detailKey, List<String> args, String modId) {
    public Finding {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** the arguments as an array, for {@code I18n.tr(detailKey, finding.argArray())} */
    public Object[] argArray() {
        return args.toArray();
    }
}
