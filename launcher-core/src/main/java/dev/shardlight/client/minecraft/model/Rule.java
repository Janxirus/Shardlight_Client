package dev.shardlight.client.minecraft.model;

import java.util.Map;

/**
 * One entry of a {@code rules} list: {@code {"action": "allow", "os": {...}, "features": {...}}}.
 *
 * @param action   "allow" or "disallow"
 * @param os       OS condition, or null
 * @param features feature conditions (is_demo_user, has_custom_resolution, quick play...), possibly empty
 */
public record Rule(String action, OsRule os, Map<String, Boolean> features) {
    public Rule {
        features = ModelCopies.map(features);
    }

    public boolean isAllow() {
        return "allow".equals(action);
    }

    public static Rule allow() {
        return new Rule("allow", null, null);
    }

    public static Rule allowOs(String osName) {
        return new Rule("allow", new OsRule(osName, null, null), null);
    }

    public static Rule disallowOs(String osName) {
        return new Rule("disallow", new OsRule(osName, null, null), null);
    }
}
