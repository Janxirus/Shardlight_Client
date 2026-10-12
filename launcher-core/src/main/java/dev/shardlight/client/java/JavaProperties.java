package dev.shardlight.client.java;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code java -XshowSettings:properties -version} reports about a runtime.
 *
 * @param version      {@code java.version}
 * @param majorVersion feature release derived from {@code java.specification.version} ({@code 1.8} is 8)
 * @param vendor       {@code java.vendor}
 * @param arch         {@code os.arch}
 */
public record JavaProperties(String version, int majorVersion, String vendor, String arch) {
    private static final Pattern PROPERTY = Pattern.compile("^ {4}(\\S+) = (.*)$");
    private static final Pattern VERSION_LINE = Pattern.compile("version \"([^\"]+)\"");

    /**
     * Parse the output of {@code java -XshowSettings:properties -version} (it goes to stderr). Empty when
     * the output doesn't look like a Java runtime's.
     */
    public static Optional<JavaProperties> parse(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> props = new java.util.HashMap<>();
        String versionLine = null;
        for (String raw : output.split("\\R")) {
            Matcher m = PROPERTY.matcher(raw);
            if (m.matches()) {
                props.putIfAbsent(m.group(1), m.group(2).trim());
            } else if (versionLine == null) {
                Matcher v = VERSION_LINE.matcher(raw);
                if (v.find()) {
                    versionLine = v.group(1);
                }
            }
        }
        String version = props.getOrDefault("java.version", versionLine);
        int major = majorOf(props.get("java.specification.version"));
        if (major <= 0) {
            major = majorOf(version);
        }
        if (version == null || major <= 0) {
            return Optional.empty();
        }
        String vendor = props.getOrDefault("java.vendor", "");
        String arch = props.getOrDefault("os.arch", "");
        return Optional.of(new JavaProperties(version, major, vendor, arch.toLowerCase(Locale.ROOT)));
    }

    /** {@code 1.8.0_402} and {@code 1.8} give 8; {@code 21.0.7} and {@code 21} give 21; -1 when unparseable */
    public static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        String s = version.trim();
        if (s.startsWith("1.")) {
            s = s.substring(2);
        }
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 4) {
            return -1;
        }
        return Integer.parseInt(s.substring(0, end));
    }

    /** compares versions such as {@code 21.0.7+6}, {@code 17.0.12} and {@code 1.8.0_402} numerically */
    public static int compareVersions(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int[] numbers(String v) {
        if (v == null) {
            return new int[0];
        }
        String[] parts = v.split("[^0-9]+");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            if (!p.isEmpty() && p.length() < 10) {
                out[n++] = Integer.parseInt(p);
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** the CPU family of an {@code os.arch} value: x64, x86, arm64, arm32 or other */
    public static String archFamily(String arch) {
        String a = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "aarch64", "arm64" -> "arm64";
            case "arm", "arm32", "armv7", "armv7l", "aarch32" -> "arm32";
            default -> "other";
        };
    }
}
