package dev.shardlight.client.launch;

/**
 * How to start the game this time, on top of the project's own settings.
 *
 * @param quickPlayWorld  world folder (inside {@code saves}) to open straight away, or null
 * @param quickPlayServer server address ({@code host[:port]}) to join straight away, or null
 * @param demo            start in demo mode
 */
public record LaunchOptions(String quickPlayWorld, String quickPlayServer, boolean demo) {
    /** start normally, at the title screen */
    public static final LaunchOptions NONE = new LaunchOptions(null, null, false);

    public LaunchOptions {
        quickPlayWorld = blankToNull(quickPlayWorld);
        quickPlayServer = blankToNull(quickPlayServer);
        if (quickPlayWorld != null && quickPlayServer != null) {
            throw new IllegalArgumentException("open a world or join a server, not both");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
