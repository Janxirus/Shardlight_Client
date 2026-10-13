package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.PathSafety;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Validation of display names and generation of folder ids. */
final class Names {
    /** longest project name, in characters (code points) */
    static final int MAX_NAME = 64;
    /** longest project id */
    static final int MAX_ID = 40;
    /** longest world name */
    static final int MAX_WORLD_NAME = 64;

    private static final Set<String> WINDOWS_RESERVED = Set.of(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private Names() {
    }

    /**
     * A project display name: trimmed, 1-64 characters, no control or line-break characters, and at least
     * one letter or digit. Returns the trimmed name.
     */
    static String requireProjectName(String name) throws LauncherException {
        String n = name == null ? "" : name.strip();
        int length = n.codePointCount(0, n.length());
        if (length == 0) {
            throw invalid("project.error.nameEmpty");
        }
        if (length > MAX_NAME) {
            throw invalid("project.error.nameTooLong", MAX_NAME);
        }
        if (hasControl(n)) {
            throw invalid("project.error.nameInvalid");
        }
        if (n.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw invalid("project.error.nameOnlySymbols");
        }
        return n;
    }

    /** a world name: trimmed, 1-64 characters, no control characters */
    static String requireWorldName(String name) throws LauncherException {
        String n = name == null ? "" : name.strip();
        int length = n.codePointCount(0, n.length());
        if (length == 0) {
            throw invalid("project.world.error.nameEmpty");
        }
        if (length > MAX_WORLD_NAME) {
            throw invalid("project.world.error.nameTooLong", MAX_WORLD_NAME);
        }
        if (hasControl(n)) {
            throw invalid("project.world.error.nameInvalid");
        }
        return n;
    }

    static boolean hasControl(String s) {
        return s.codePoints().anyMatch(cp -> Character.isISOControl(cp)
            || Character.getType(cp) == Character.LINE_SEPARATOR
            || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR
            || cp == 0xFEFF);
    }

    /** lowercase a-z, 0-9 and single dashes, at most 40 characters; "project" when nothing is left */
    static String slug(String name) {
        String out = slugOrEmpty(name, MAX_ID);
        return out.isEmpty() ? "project" : out;
    }

    /** a short label for file names: like {@link #slug} but at most {@code max} characters, "" when empty */
    static String fileLabel(String label, int max) {
        return slugOrEmpty(label, max);
    }

    private static String slugOrEmpty(String name, int max) {
        String s = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFKD);
        s = MARKS.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                b.append(c);
                dash = false;
            } else if (b.length() > 0 && !dash) {
                b.append('-');
                dash = true;
            }
        }
        return trimDashes(b.length() > max ? b.substring(0, max) : b.toString());
    }

    static boolean isWindowsReserved(String id) {
        return WINDOWS_RESERVED.contains(id.toLowerCase(Locale.ROOT));
    }

    /** true for an id this launcher could have generated */
    static boolean isGeneratedId(String id) {
        return id != null && ID.matcher(id).matches() && !id.endsWith("-") && !isWindowsReserved(id);
    }

    /**
     * The first of {@code base}, {@code base-2}, {@code base-3}... that is not a Windows reserved name and
     * for which {@code taken} (given lowercase ids) is false. The result never exceeds 40 characters.
     */
    static String uniqueId(String base, Predicate<String> taken) {
        String b = base == null || base.isBlank() ? "project" : base;
        if (!isWindowsReserved(b) && !taken.test(b.toLowerCase(Locale.ROOT))) {
            return b;
        }
        for (int i = 2; ; i++) {
            String suffix = "-" + i;
            String head = b.length() + suffix.length() > MAX_ID ? trimDashes(b.substring(0, MAX_ID - suffix.length())) : b;
            String candidate = (head.isEmpty() ? "project" : head) + suffix;
            if (!isWindowsReserved(candidate) && !taken.test(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    /** a builtin icon reference ("builtin:grass"); accepts a bare id or the prefixed form */
    static String builtinIcon(String icon) throws LauncherException {
        if (icon == null || icon.isBlank()) {
            return ProjectService.DEFAULT_ICON;
        }
        String id = icon.strip();
        if (id.startsWith("builtin:")) {
            id = id.substring("builtin:".length());
        }
        if (!TOKEN.matcher(id).matches()) {
            throw invalid("project.error.iconId", icon);
        }
        return "builtin:" + id;
    }

    /** a lowercase token such as a Shardlight module or theme id; null stays null */
    static String token(String value, String errorKey) throws LauncherException {
        if (value == null) {
            return null;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(v).matches()) {
            throw invalid(errorKey, value);
        }
        return v;
    }

    /** a version string that is safe as a folder name (Minecraft and loader versions) */
    static String requireVersion(String version, String errorKey) throws LauncherException {
        String v = version == null ? "" : version.strip();
        if (v.isEmpty() || v.length() > 64 || !PathSafety.isSafeSegment(v) || hasControl(v)) {
            throw invalid(errorKey, String.valueOf(version));
        }
        return v;
    }

    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }

    static LauncherException invalid(String key, Object... args) {
        return new LauncherException(ErrorCode.INVALID_DATA, key, args);
    }
}
