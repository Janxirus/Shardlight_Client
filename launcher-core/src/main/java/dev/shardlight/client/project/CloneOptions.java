package dev.shardlight.client.project;

/**
 * What {@link ProjectService#duplicate} copies besides the setup (mods, config, packs, options, servers).
 *
 * @param worlds      copy {@code saves/}
 * @param screenshots copy {@code screenshots/}
 * @param logs        copy {@code logs/} and {@code crash-reports/}
 */
public record CloneOptions(boolean worlds, boolean screenshots, boolean logs) {
    /** everything */
    public static final CloneOptions EVERYTHING = new CloneOptions(true, true, true);
    /** the setup only: no worlds, screenshots or logs */
    public static final CloneOptions SETUP_ONLY = new CloneOptions(false, false, false);

    /** whether a path relative to the game directory ('/' separated) is copied */
    boolean includes(String rel) {
        if (FileTrees.isSessionLock(rel)) {
            return false;
        }
        int slash = rel.indexOf('/');
        String top = slash < 0 ? rel : rel.substring(0, slash);
        return switch (top) {
            case "saves" -> worlds;
            case "screenshots" -> screenshots;
            case "logs", "crash-reports" -> logs;
            default -> true;
        };
    }
}
