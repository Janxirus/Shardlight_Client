package dev.shardlight.client.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version predicates as mods declare them, evaluated against a concrete version (usually the project's
 * Minecraft version).
 *
 * <ul>
 *   <li>Fabric/Quilt: {@code *}, {@code 1.21.x}, {@code 1.21.*}, {@code =1.21}, {@code >=1.21},
 *       {@code <1.22}, {@code ~26.3} (same minor), {@code ^1.21} (same major); space-separated terms must all
 *       match; alternatives (a JSON array in fabric.mod.json) are joined with {@code ||}.</li>
 *   <li>Forge/NeoForge Maven ranges: {@code [1.21,1.22)}, {@code [1.20.1]}, {@code (,1.21]},
 *       {@code [1.20,1.20.4],[1.21,)}; a bare version is only a recommendation and matches anything.</li>
 * </ul>
 *
 * Versions compare numerically by dot-separated parts ({@code 1.21} equals {@code 1.21.0}); a pre-release
 * ({@code 26.3-rc1}, {@code 1.21-pre2}) sorts before its release. Snapshot names ({@code 26w14a}) have no
 * order, so predicates about them are unknown.
 */
public final class VersionPredicates {
    private static final Pattern MAVEN_PART = Pattern.compile("([\\[(])\\s*([^,\\])]*?)\\s*(?:,\\s*([^\\])]*?)\\s*)?([\\])])");

    private VersionPredicates() {
    }

    /**
     * Does {@code version} satisfy {@code predicate}? Empty when either can't be understood (callers should
     * then not warn).
     */
    public static Optional<Boolean> test(String predicate, String version) {
        if (predicate == null) {
            return Optional.empty();
        }
        String p = predicate.trim();
        if (p.isEmpty() || p.equals("*") || p.equalsIgnoreCase("x")) {
            return Optional.of(true);
        }
        Optional<Version> v = Version.parse(version);
        if (v.isEmpty()) {
            return Optional.empty();
        }
        if (p.startsWith("[") || p.startsWith("(")) {
            return maven(p, v.get());
        }
        boolean unknown = false;
        for (String alternative : p.split("\\|\\|")) {
            String alt = alternative.trim();
            if (alt.isEmpty()) {
                continue;
            }
            boolean all = true;
            boolean altUnknown = false;
            for (String term : alt.split("\\s+")) {
                Optional<Boolean> r = single(term, v.get());
                if (r.isEmpty()) {
                    altUnknown = true;
                } else if (!r.get()) {
                    all = false;
                }
            }
            if (all && !altUnknown) {
                return Optional.of(true);
            }
            if (all) {
                unknown = true;
            }
        }
        return unknown ? Optional.empty() : Optional.of(false);
    }

    /** compare two versions; empty when either has no numeric order (snapshots, garbage) */
    public static Optional<Integer> compare(String a, String b) {
        Optional<Version> va = Version.parse(a);
        Optional<Version> vb = Version.parse(b);
        if (va.isEmpty() || vb.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(va.get().compareTo(vb.get()));
    }

    // ------------------------------------------------------------------ Fabric style

    private static Optional<Boolean> single(String term, Version v) {
        String t = term.trim();
        String op = "";
        for (String o : new String[] {">=", "<=", ">", "<", "=", "~", "^"}) {
            if (t.startsWith(o)) {
                op = o;
                t = t.substring(o.length()).trim();
                break;
            }
        }
        if (t.isEmpty()) {
            return Optional.empty();
        }
        if (t.equals("*") || t.equalsIgnoreCase("x")) {
            return Optional.of(true);
        }
        String lower = t.toLowerCase(Locale.ROOT);
        boolean wildcard = lower.endsWith(".x") || lower.endsWith(".*");
        if (wildcard) {
            Optional<Version> base = Version.parse(t.substring(0, t.length() - 2));
            if (base.isEmpty() || base.get().pre != null) {
                return Optional.empty();
            }
            Version lo = base.get();
            Version hi = lo.bump(lo.parts.size() - 1);
            return Optional.of(switch (op) {
                case ">=" -> v.compareTo(lo) >= 0;
                case ">" -> v.compareTo(hi) >= 0;
                case "<" -> v.compareTo(lo) < 0;
                case "<=" -> v.compareTo(hi) < 0;
                default -> v.compareTo(lo) >= 0 && v.compareTo(hi) < 0;
            });
        }
        Optional<Version> pv = Version.parse(t);
        if (pv.isEmpty()) {
            return Optional.empty();
        }
        Version x = pv.get();
        int c = v.compareTo(x);
        return Optional.of(switch (op) {
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case "<" -> c < 0;
            case "~" -> c >= 0 && v.compareTo(x.parts.size() >= 2 ? x.bump(1) : x.bump(0)) < 0;
            case "^" -> c >= 0 && v.compareTo(x.bump(0)) < 0;
            default -> c == 0;
        });
    }

    // ------------------------------------------------------------------ Maven style

    private static Optional<Boolean> maven(String p, Version v) {
        Matcher m = MAVEN_PART.matcher(p);
        int pos = 0;
        boolean any = false;
        boolean parsed = false;
        while (m.find()) {
            String between = p.substring(pos, m.start()).replace(",", "").trim();
            if (!between.isEmpty()) {
                return Optional.empty();
            }
            pos = m.end();
            parsed = true;
            boolean loInc = m.group(1).equals("[");
            boolean hiInc = m.group(4).equals("]");
            String lo = m.group(2);
            String hi = m.group(3);
            if (hi == null) {
                // [1.20.1] exact
                Optional<Version> e = Version.parse(lo);
                if (e.isEmpty()) {
                    return Optional.empty();
                }
                any |= v.compareTo(e.get()) == 0;
                continue;
            }
            boolean ok = true;
            if (!lo.isBlank()) {
                Optional<Version> l = Version.parse(lo);
                if (l.isEmpty()) {
                    return Optional.empty();
                }
                int c = v.compareTo(l.get());
                ok = loInc ? c >= 0 : c > 0;
            }
            if (ok && !hi.isBlank()) {
                Optional<Version> h = Version.parse(hi);
                if (h.isEmpty()) {
                    return Optional.empty();
                }
                int c = v.compareTo(h.get());
                ok = hiInc ? c <= 0 : c < 0;
            }
            any |= ok;
        }
        if (!parsed || !p.substring(pos).replace(",", "").isBlank()) {
            return Optional.empty();
        }
        return Optional.of(any);
    }

    // ------------------------------------------------------------------ versions

    /** numeric parts plus an optional pre-release tag */
    record Version(List<Integer> parts, String pre) implements Comparable<Version> {
        static Optional<Version> parse(String s) {
            if (s == null) {
                return Optional.empty();
            }
            String t = s.trim();
            int plus = t.indexOf('+');
            if (plus >= 0) {
                t = t.substring(0, plus);
            }
            String pre = null;
            int dash = t.indexOf('-');
            if (dash >= 0) {
                pre = t.substring(dash + 1);
                t = t.substring(0, dash);
                if (pre.isEmpty()) {
                    return Optional.empty();
                }
            }
            if (t.isEmpty()) {
                return Optional.empty();
            }
            List<Integer> parts = new ArrayList<>();
            for (String p : t.split("\\.", -1)) {
                if (p.isEmpty() || p.length() > 9 || !p.chars().allMatch(Character::isDigit)) {
                    return Optional.empty();
                }
                parts.add(Integer.parseInt(p));
            }
            return Optional.of(new Version(List.copyOf(parts), pre));
        }

        /** the next version at {@code index} (1.21.4 bump(1) = 1.22) */
        Version bump(int index) {
            List<Integer> p = new ArrayList<>(parts.subList(0, index + 1));
            p.set(index, p.get(index) + 1);
            return new Version(List.copyOf(p), null);
        }

        @Override
        public int compareTo(Version o) {
            int n = Math.max(parts.size(), o.parts.size());
            for (int k = 0; k < n; k++) {
                int a = k < parts.size() ? parts.get(k) : 0;
                int b = k < o.parts.size() ? o.parts.get(k) : 0;
                if (a != b) {
                    return Integer.compare(a, b);
                }
            }
            if (pre == null || o.pre == null) {
                return pre == null ? (o.pre == null ? 0 : 1) : -1;
            }
            return comparePre(pre, o.pre);
        }

        private static int comparePre(String a, String b) {
            String[] x = a.split("\\.");
            String[] y = b.split("\\.");
            for (int k = 0; k < Math.min(x.length, y.length); k++) {
                int c = natural(x[k], y[k]);
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(x.length, y.length);
        }

        /** compare text with embedded numbers naturally: rc2 < rc10 */
        private static int natural(String a, String b) {
            int i = 0;
            int j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i);
                char cb = b.charAt(j);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int si = i;
                    int sj = j;
                    while (i < a.length() && Character.isDigit(a.charAt(i))) {
                        i++;
                    }
                    while (j < b.length() && Character.isDigit(b.charAt(j))) {
                        j++;
                    }
                    String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                    String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                    int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                    if (c != 0) {
                        return c;
                    }
                } else {
                    int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                    if (c != 0) {
                        return c;
                    }
                    i++;
                    j++;
                }
            }
            return Integer.compare(a.length() - i, b.length() - j);
        }
    }
}
