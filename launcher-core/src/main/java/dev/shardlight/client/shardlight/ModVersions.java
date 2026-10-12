package dev.shardlight.client.shardlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Version ordering and Fabric dependency ranges for mod, loader and Minecraft versions. Lenient: versions
 * are dot-separated components (numeric ones compared as numbers), an optional {@code -pre-release} sorts
 * before the release, and {@code +build} metadata is ignored. Ranges follow Fabric's {@code fabric.mod.json}
 * syntax: {@code *}, {@code 1.2.3}, {@code >=1.2}, {@code <2}, {@code ~26.3} (same minor),
 * {@code ^1.2} (same major), {@code 1.20.x}, space-separated terms that must all match.
 */
public final class ModVersions {
    private ModVersions() {
    }

    /** negative, zero or positive as {@code a} is older, equal to or newer than {@code b}; null sorts first */
    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        Parsed x = parse(a);
        Parsed y = parse(b);
        int n = Math.max(x.core.size(), y.core.size());
        for (int i = 0; i < n; i++) {
            String p = i < x.core.size() ? x.core.get(i) : "0";
            String q = i < y.core.size() ? y.core.get(i) : "0";
            int c = compareIdentifier(p, q);
            if (c != 0) {
                return c;
            }
        }
        if (x.pre.isEmpty() || y.pre.isEmpty()) {
            return x.pre.isEmpty() ? (y.pre.isEmpty() ? 0 : 1) : -1;
        }
        int m = Math.min(x.pre.size(), y.pre.size());
        for (int i = 0; i < m; i++) {
            int c = compareIdentifier(x.pre.get(i), y.pre.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.pre.size(), y.pre.size());
    }

    /** true when {@code version} satisfies a Fabric range; an empty, null or unparsable range accepts everything */
    public static boolean matches(String range, String version) {
        if (range == null || range.isBlank() || version == null) {
            return true;
        }
        for (String term : range.trim().split("\\s+")) {
            if (!matchesTerm(term, version.trim())) {
                return false;
            }
        }
        return true;
    }

    /** true when any of the ranges matches (Fabric's array form); an empty list accepts everything */
    public static boolean matchesAny(List<String> ranges, String version) {
        if (ranges == null || ranges.isEmpty()) {
            return true;
        }
        for (String r : ranges) {
            if (matches(r, version)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTerm(String term, String version) {
        if (term.equals("*")) {
            return true;
        }
        if (term.startsWith(">=")) {
            return compare(version, term.substring(2)) >= 0;
        }
        if (term.startsWith("<=")) {
            return compare(version, term.substring(2)) <= 0;
        }
        if (term.startsWith(">")) {
            return compare(version, term.substring(1)) > 0;
        }
        if (term.startsWith("<")) {
            return compare(version, term.substring(1)) < 0;
        }
        if (term.startsWith("=")) {
            return compare(version, term.substring(1)) == 0;
        }
        if (term.startsWith("~")) {
            String low = term.substring(1);
            List<String> c = parse(low).core;
            String high = c.size() >= 2 ? c.get(0) + "." + bump(c.get(1)) : bump(c.get(0));
            return compare(version, low) >= 0 && compare(version, high + "-") < 0;
        }
        if (term.startsWith("^")) {
            String low = term.substring(1);
            List<String> c = parse(low).core;
            return compare(version, low) >= 0 && compare(version, bump(c.get(0)) + "-") < 0;
        }
        List<String> c = parse(term).core;
        int wildcard = -1;
        for (int i = 0; i < c.size(); i++) {
            String s = c.get(i).toLowerCase(Locale.ROOT);
            if (s.equals("x") || s.equals("*")) {
                wildcard = i;
                break;
            }
        }
        if (wildcard >= 0) {
            List<String> v = parse(version).core;
            for (int i = 0; i < wildcard; i++) {
                String have = i < v.size() ? v.get(i) : "0";
                if (compareIdentifier(have, c.get(i)) != 0) {
                    return false;
                }
            }
            return true;
        }
        return compare(version, term) == 0;
    }

    private static String bump(String component) {
        try {
            return Long.toString(Long.parseLong(component) + 1);
        } catch (NumberFormatException e) {
            return component + "\uFFFF";
        }
    }

    private static int compareIdentifier(String p, String q) {
        boolean pn = isNumber(p);
        boolean qn = isNumber(q);
        if (pn && qn) {
            String a = stripZeros(p);
            String b = stripZeros(q);
            return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
        }
        if (pn != qn) {
            return pn ? -1 : 1;
        }
        return p.compareTo(q);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String stripZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    private record Parsed(List<String> core, List<String> pre) {
    }

    private static Parsed parse(String version) {
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        String pre = "";
        int dash = v.indexOf('-');
        if (dash >= 0) {
            pre = v.substring(dash + 1);
            v = v.substring(0, dash);
        }
        List<String> core = new ArrayList<>(List.of(v.split("\\.", -1)));
        core.removeIf(String::isEmpty);
        if (core.isEmpty()) {
            core.add("0");
        }
        List<String> preParts = new ArrayList<>();
        if (dash >= 0) {
            for (String s : pre.split("\\.", -1)) {
                preParts.add(s);
            }
        }
        return new Parsed(core, preParts);
    }
}
