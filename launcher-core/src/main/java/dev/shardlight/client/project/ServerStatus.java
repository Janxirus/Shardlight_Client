package dev.shardlight.client.project;

import java.util.List;

/**
 * The answer of a Server List Ping.
 *
 * @param online        true when the server answered the status request
 * @param playersOnline players online
 * @param playersMax    player slots
 * @param motd          the message of the day as plain text (formatting codes removed; may contain newlines)
 * @param versionName   the server's version text, for example "Paper 1.21.4"
 * @param protocol      the server's protocol number, -1 when unknown
 * @param latencyMs     round trip of the ping packet in milliseconds, -1 when unknown
 * @param faviconBase64 the server icon (PNG), base64 without the data: prefix, or null
 * @param error         a localized reason when {@code online} is false, otherwise null
 * @param motdSpans     the message of the day with its colours and styles, empty when there is none
 */
public record ServerStatus(boolean online, int playersOnline, int playersMax, String motd, String versionName, int protocol,
                           long latencyMs, String faviconBase64, String error, List<MotdSpan> motdSpans) {
    public ServerStatus {
        motdSpans = motdSpans == null ? List.of() : List.copyOf(motdSpans);
    }

    /** a status without styled message-of-the-day text */
    public ServerStatus(boolean online, int playersOnline, int playersMax, String motd, String versionName, int protocol,
                        long latencyMs, String faviconBase64, String error) {
        this(online, playersOnline, playersMax, motd, versionName, protocol, latencyMs, faviconBase64, error, List.of());
    }

    /** an unreachable server with a localized reason */
    public static ServerStatus offline(String error) {
        return new ServerStatus(false, 0, 0, "", "", -1, -1, null, error);
    }
}
