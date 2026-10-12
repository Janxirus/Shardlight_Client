package dev.shardlight.client.minecraft;

import dev.shardlight.client.minecraft.model.OsRule;
import dev.shardlight.client.minecraft.model.Rule;
import dev.shardlight.client.util.OsInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates {@code rules} lists exactly like the official launcher: an empty list allows; otherwise the
 * result starts as "disallowed" and every rule that matches the current environment sets it to its own
 * action, so the last matching rule wins.
 */
public final class Rules {
    public static final String DEMO_USER = "is_demo_user";
    public static final String CUSTOM_RESOLUTION = "has_custom_resolution";
    public static final String QUICK_PLAYS_SUPPORT = "has_quick_plays_support";
    public static final String QUICK_PLAY_SINGLEPLAYER = "is_quick_play_singleplayer";
    public static final String QUICK_PLAY_MULTIPLAYER = "is_quick_play_multiplayer";
    public static final String QUICK_PLAY_REALMS = "is_quick_play_realms";

    /** every quick play feature name */
    public static final Set<String> QUICK_PLAY_FEATURES = Set.of(QUICK_PLAYS_SUPPORT, QUICK_PLAY_SINGLEPLAYER,
        QUICK_PLAY_MULTIPLAYER, QUICK_PLAY_REALMS);

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    private static final Pattern NEVER = Pattern.compile("(?!)");

    private Rules() {
    }

    /** true when {@code rules} allow the item on {@code os} with the given features (absent features are false) */
    public static boolean allows(List<Rule> rules, OsInfo os, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule r : rules) {
            if (r != null && matches(r, os, features)) {
                allowed = r.isAllow();
            }
        }
        return allowed;
    }

    /** rules that only look at the OS (libraries) */
    public static boolean allows(List<Rule> rules, OsInfo os) {
        return allows(rules, os, Map.of());
    }

    /** true when every condition of {@code rule} holds */
    public static boolean matches(Rule rule, OsInfo os, Map<String, Boolean> features) {
        OsRule o = rule.os();
        if (o != null) {
            if (o.name() != null && !o.name().isBlank() && !osNameMatches(o.name(), os)) {
                return false;
            }
            if (o.version() != null && !o.version().isEmpty() && !pattern(o.version()).matcher(os.version()).find()) {
                return false;
            }
            if (o.arch() != null && !o.arch().isBlank() && !archMatches(o.arch(), os)) {
                return false;
            }
        }
        for (Map.Entry<String, Boolean> f : rule.features().entrySet()) {
            boolean have = features != null && Boolean.TRUE.equals(features.get(f.getKey()));
            if (have != Boolean.TRUE.equals(f.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** true when any argument rule of the list mentions one of {@code featureNames} */
    public static boolean mentionsFeature(List<Rule> rules, Set<String> featureNames) {
        for (Rule r : rules) {
            for (String k : r.features().keySet()) {
                if (featureNames.contains(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** a feature map for argument rules */
    public static Map<String, Boolean> features(boolean demo, boolean customResolution, boolean quickPlaySupport,
                                                boolean quickPlaySingleplayer, boolean quickPlayMultiplayer,
                                                boolean quickPlayRealms) {
        Map<String, Boolean> m = new HashMap<>();
        m.put(DEMO_USER, demo);
        m.put(CUSTOM_RESOLUTION, customResolution);
        m.put(QUICK_PLAYS_SUPPORT, quickPlaySupport);
        m.put(QUICK_PLAY_SINGLEPLAYER, quickPlaySingleplayer);
        m.put(QUICK_PLAY_MULTIPLAYER, quickPlayMultiplayer);
        m.put(QUICK_PLAY_REALMS, quickPlayRealms);
        return Map.copyOf(m);
    }

    private static boolean osNameMatches(String name, OsInfo os) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("macos") || n.equals("mac")) {
            n = "osx";
        }
        return switch (os.os()) {
            case WINDOWS -> n.equals("windows");
            case MACOS -> n.equals("osx");
            case LINUX -> n.equals("linux");
            default -> false;
        };
    }

    private static boolean archMatches(String arch, OsInfo os) {
        String a = arch.trim().toLowerCase(Locale.ROOT);
        return switch (os.arch()) {
            case X86 -> a.equals("x86") || a.equals("i386") || a.equals("i686");
            case X64 -> a.equals("x86_64") || a.equals("x64") || a.equals("amd64");
            case ARM64 -> a.equals("arm64") || a.equals("aarch64");
            case ARM32 -> a.equals("arm32") || a.equals("arm");
            default -> false;
        };
    }

    private static Pattern pattern(String regex) {
        return PATTERNS.computeIfAbsent(regex, r -> {
            try {
                return Pattern.compile(r);
            } catch (PatternSyntaxException e) {
                return NEVER;
            }
        });
    }
}
