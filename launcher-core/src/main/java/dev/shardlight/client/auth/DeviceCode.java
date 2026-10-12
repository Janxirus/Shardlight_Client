package dev.shardlight.client.auth;

import java.net.URI;

/**
 * A pending Microsoft device-code sign-in. The UI shows {@link #userCode} and a link to
 * {@link #verificationUri}; the player opens it in any browser, enters the code and signs in there.
 *
 * @param userCode          the short code the player types (for example "ABCD-EFGH")
 * @param verificationUri   where to enter it (https://www.microsoft.com/link); always https
 * @param expiresInSeconds  how long the code stays valid
 * @param intervalSeconds   how often the launcher may ask whether sign-in finished
 * @param deviceCode        the secret code the launcher polls with (never shown or logged)
 * @param message           Microsoft's own instruction text (localized by Microsoft)
 */
public record DeviceCode(String userCode, URI verificationUri, int expiresInSeconds, int intervalSeconds,
                         String deviceCode, String message) {
    @Override
    public String toString() {
        return "DeviceCode[userCode=" + userCode + ", verificationUri=" + verificationUri + ", expiresIn="
            + expiresInSeconds + "s, interval=" + intervalSeconds + "s, deviceCode=<redacted>]";
    }
}
