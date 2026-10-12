package dev.shardlight.client.auth;

import java.time.Instant;

/**
 * Microsoft account OAuth tokens.
 *
 * @param accessToken  the Microsoft access token (scope XboxLive.signin), used once for Xbox Live
 * @param refreshToken the long-lived refresh token (offline_access), stored in the secret store; may be
 *                     null when Microsoft didn't issue one
 * @param expiresAt    when {@code accessToken} stops working
 */
public record MsaTokens(String accessToken, String refreshToken, Instant expiresAt) {
    @Override
    public String toString() {
        return "MsaTokens[accessToken=<redacted>, refreshToken=" + (refreshToken == null ? "none" : "<redacted>")
            + ", expiresAt=" + expiresAt + "]";
    }
}
