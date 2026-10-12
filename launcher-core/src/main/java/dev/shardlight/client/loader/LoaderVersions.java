package dev.shardlight.client.loader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Ordering of loader version strings ({@code 21.1.77}, {@code 21.4.0-beta}, {@code 54.0.12}). */
final class LoaderVersions {
    /** numeric parts compared as numbers; a release sorts above its own pre-releases ({@code 1.0} > {@code 1.0-beta}) */
    static final Comparator<String> ORDER = LoaderVersions::compare;

    private LoaderVersions() {
    }

    static int compare(String a, String b) {
        String[] x = split(a);
        String[] y = split(b);
        List<Long> nx = numbers(x[0]);
        List<Long> ny = numbers(y[0]);
        for (int i = 0; i < Math.max(nx.size(), ny.size()); i++) {
            long p = i < nx.size() ? nx.get(i) : 0;
            long q = i < ny.size() ? ny.get(i) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        if (x[1].isEmpty() != y[1].isEmpty()) {
            return x[1].isEmpty() ? 1 : -1;
        }
        return x[1].compareTo(y[1]);
    }

    /** {release part, pre-release suffix or ""} */
    private static String[] split(String v) {
        String s = v == null ? "" : v.trim();
        int dash = s.indexOf('-');
        return dash < 0 ? new String[] {s, ""} : new String[] {s.substring(0, dash), s.substring(dash + 1)};
    }

    private static List<Long> numbers(String s) {
        List<Long> out = new ArrayList<>();
        for (String part : s.split("[^0-9]+")) {
            if (!part.isEmpty() && part.length() < 18) {
                out.add(Long.parseLong(part));
            }
        }
        return out;
    }
}
