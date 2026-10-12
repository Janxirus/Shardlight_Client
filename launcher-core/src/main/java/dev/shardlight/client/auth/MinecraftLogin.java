package dev.shardlight.client.auth;

import java.time.Instant;

/**
 * The result of signing in to Minecraft services with a Microsoft account.
 *
 * @param uuid        the Minecraft profile UUID, 32 lowercase hex digits without dashes
 * @param username    the Minecraft profile name
 * @param accessToken the Minecraft services access token (secret; passed to the game)
 * @param expiresAt   when {@code accessToken} stops working (usually 24 hours)
 * @param xuid        the Xbox user id (decimal digits), or null when it couldn't be read
 * @param ownsGame    true when the account may play Java Edition: an entitlement was found, or (Game
 *                    Pass) the account has a profile even without a store entitlement
 * @param skinUrl     the active skin's texture URL, or null
 * @param skinVariant "classic" or "slim", or null
 * @param msa         the Microsoft tokens this login was made with (holds the refresh token to store)
 */
public record MinecraftLogin(String uuid, String username, String accessToken, Instant expiresAt, String xuid,
                             boolean ownsGame, String skinUrl, String skinVariant, MsaTokens msa) {
    @Override
    public String toString() {
        return "MinecraftLogin[" + username + ", " + uuid + ", accessToken=<redacted>, expiresAt=" + expiresAt
            + ", xuid=" + xuid + ", ownsGame=" + ownsGame + ", msa=" + msa + "]";
    }
}
