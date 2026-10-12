package dev.shardlight.client.project;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders Minecraft version names without the version manifest: releases ({@code 1.21.4}, {@code 26.1}),
 * pre-releases and release candidates ({@code 1.21-pre1}, {@code 1.14 Pre-Release 2}, {@code 26.1-rc-1}),
 * the 2026 snapshot naming ({@code 26.1-snapshot-3}) and weekly snapshots ({@code 24w14a}). A weekly
 * snapshot can only be placed by year against a release, so pairs from the same year (and names this class
 * doesn't understand, such as April Fools versions) compare as unknown.
 */
public final class GameVersionOrder {
    private static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern STAGED = Pattern.compile(
        "(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-| )(snapshot|pre-release|pre|rc|release candidate)[- ]?(\\d+)");
    private static final Pattern WEEKLY = Pattern.compile("(\\d{2})w(\\d{2})([a-z])");
    private static final int SNAPSHOT = 0;
    private static final int PRE = 1;
    private static final int RC = 2;
    private static final int FINAL = 3;

    private GameVersionOrder() {
    }

    private record Parsed(int major, int minor, int patch, int stage, int stageNum, boolean weekly, int year, int week,
                          int letter) {
        int releaseYear() {
            if (weekly) {
                return year;
            }
            if (major >= 26) {
                return 2000 + major;
            }
            if (major != 1) {
                return -1;
            }
            return switch (minor) {
                case 0 -> 2011;
                case 1, 2, 3, 4 -> 2012;
                case 5, 6, 7 -> 2013;
                case 8 -> 2014;
                case 9, 10, 11 -> 2016;
                case 12 -> 2017;
                case 13 -> 2018;
                case 14, 15 -> 2019;
                case 16 -> 2020;
                case 17, 18 -> 2021;
                case 19 -> 2022;
                case 20 -> patch <= 4 ? 2023 : 2024;
                case 21 -> patch <= 4 ? 2024 : 2025;
                default -> minor > 21 ? 2025 : -1;
            };
        }
    }

    /**
     * Negative when {@code a} is older than {@code b}, zero when equal, positive when newer; empty when the
     * order can't be told.
     */
    public static OptionalInt compare(String a, String b) {
        if (a == null || b == null) {
            return OptionalInt.empty();
        }
        if (a.strip().equalsIgnoreCase(b.strip())) {
            return OptionalInt.of(0);
        }
        Parsed x = parse(a);
        Parsed y = parse(b);
        if (x == null || y == null) {
            return OptionalInt.empty();
        }
        if (x.weekly && y.weekly) {
            int c = Integer.compare(x.year, y.year);
            c = c != 0 ? c : Integer.compare(x.week, y.week);
            return OptionalInt.of(c != 0 ? c : Integer.compare(x.letter, y.letter));
        }
        if (x.weekly || y.weekly) {
            int ya = x.releaseYear();
            int yb = y.releaseYear();
            if (ya < 0 || yb < 0 || ya == yb) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(Integer.compare(ya, yb));
        }
        int c = Integer.compare(x.major, y.major);
        c = c != 0 ? c : Integer.compare(x.minor, y.minor);
        c = c != 0 ? c : Integer.compare(x.patch, y.patch);
        c = c != 0 ? c : Integer.compare(x.stage, y.stage);
        return OptionalInt.of(c != 0 ? c : Integer.compare(x.stageNum, y.stageNum));
    }

    /**
     * True when a world saved by {@code worldVersion} may be newer than {@code gameVersion}, so opening it
     * risks damage. Conservative: when the order can't be told the answer is true.
     */
    public static boolean mayBeNewer(String worldVersion, String gameVersion) {
        if (worldVersion == null || worldVersion.isBlank() || gameVersion == null || gameVersion.isBlank()) {
            return false;
        }
        OptionalInt c = compare(worldVersion, gameVersion);
        return c.isEmpty() || c.getAsInt() > 0;
    }

    private static Parsed parse(String raw) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        try {
            Matcher m = RELEASE.matcher(s);
            if (m.matches()) {
                return new Parsed(num(m, 1), num(m, 2), num(m, 3), FINAL, 0, false, 0, 0, 0);
            }
            m = STAGED.matcher(s);
            if (m.matches()) {
                int stage = switch (m.group(4)) {
                    case "snapshot" -> SNAPSHOT;
                    case "rc", "release candidate" -> RC;
                    default -> PRE;
                };
                return new Parsed(num(m, 1), num(m, 2), num(m, 3), stage, num(m, 5), false, 0, 0, 0);
            }
            m = WEEKLY.matcher(s);
            if (m.matches()) {
                return new Parsed(0, 0, 0, SNAPSHOT, 0, true, 2000 + num(m, 1), num(m, 2), m.group(3).charAt(0));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static int num(Matcher m, int group) {
        String g = m.group(group);
        return g == null ? 0 : Integer.parseInt(g);
    }
}
