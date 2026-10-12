package dev.shardlight.client.update;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Semantic Versioning 2.0.0 version ({@code MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]}), ordered by semver
 * precedence: numeric parts compared as numbers, a pre-release sorts before its release
 * ({@code 1.0.0-rc.1 < 1.0.0}), pre-release identifiers compared one by one (numeric ones numerically and
 * below alphanumeric ones, a shorter list first when all else is equal), build metadata ignored. A leading
 * {@code v} is accepted ({@code v1.2.3}). Immutable; {@link #equals} agrees with {@link #compareTo}, so
 * {@code 1.0.0+a} equals {@code 1.0.0+b}.
 */
public final class SemVer implements Comparable<SemVer> {
    private static final String NUM = "0|[1-9]\\d*";
    private static final String PRE_ID = "0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*";
    private static final Pattern PATTERN = Pattern.compile(
        "^[vV]?(" + NUM + ")\\.(" + NUM + ")\\.(" + NUM + ")"
            + "(?:-((?:" + PRE_ID + ")(?:\\.(?:" + PRE_ID + "))*))?"
            + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");
    /** longer than anything real; keeps hostile input from costing much */
    private static final int MAX_LENGTH = 256;

    private final String major;
    private final String minor;
    private final String patch;
    private final List<String> preRelease;
    private final String build;

    private SemVer(String major, String minor, String patch, List<String> preRelease, String build) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
        this.build = build;
    }

    /** parse a version; throws {@link IllegalArgumentException} when it isn't semver 2.0.0 */
    public static SemVer parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException("Not a semantic version: " + text));
    }

    /** parse a version, or empty when it isn't semver 2.0.0 */
    public static Optional<SemVer> tryParse(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
        return Optional.of(new SemVer(m.group(1), m.group(2), m.group(3), pre, m.group(5) == null ? "" : m.group(5)));
    }

    /** the major version as text (it may be larger than a long) */
    public String major() {
        return major;
    }

    /** the minor version as text */
    public String minor() {
        return minor;
    }

    /** the patch version as text */
    public String patch() {
        return patch;
    }

    /** the dot-separated pre-release identifiers ({@code [beta, 2]}), empty for a release */
    public List<String> preRelease() {
        return preRelease;
    }

    /** the build metadata after {@code +}, or "" */
    public String build() {
        return build;
    }

    /** true for a pre-release ({@code 1.2.0-beta.1}) */
    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    @Override
    public int compareTo(SemVer o) {
        int c = compareNumeric(major, o.major);
        if (c == 0) {
            c = compareNumeric(minor, o.minor);
        }
        if (c == 0) {
            c = compareNumeric(patch, o.patch);
        }
        if (c != 0) {
            return c;
        }
        if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
            // a release outranks any of its pre-releases
            return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty());
        }
        for (int i = 0; i < Math.min(preRelease.size(), o.preRelease.size()); i++) {
            String a = preRelease.get(i);
            String b = o.preRelease.get(i);
            boolean an = isNumeric(a);
            boolean bn = isNumeric(b);
            if (an && bn) {
                c = compareNumeric(a, b);
            } else if (an != bn) {
                c = an ? -1 : 1;
            } else {
                c = a.compareTo(b); // ASCII order
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(preRelease.size(), o.preRelease.size());
    }

    /** true when this version has higher precedence than {@code other} */
    public boolean isNewerThan(SemVer other) {
        return compareTo(other) > 0;
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** compare unsigned decimal numbers without leading zeros, of any length */
    private static int compareNumeric(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }

    /** the version without a leading v, build metadata included */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(major).append('.').append(minor).append('.').append(patch);
        if (!preRelease.isEmpty()) {
            sb.append('-').append(String.join(".", preRelease));
        }
        if (!build.isEmpty()) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }
}
