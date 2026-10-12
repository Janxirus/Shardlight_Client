package dev.shardlight.client.auth;

/**
 * What the game needs to start for a signed-in player. Produced by the account service after a successful
 * (or refreshed) Microsoft sign-in; never written to disk.
 *
 * @param username    the Minecraft profile name
 * @param uuid        the profile UUID without dashes
 * @param accessToken the Minecraft services access token (secret)
 * @param xuid        the Xbox user id
 * @param userType    always "msa"
 * @param clientId    the Azure application id used for sign-in (passed as ${clientid})
 */
public record MinecraftSession(String username, String uuid, String accessToken, String xuid, String userType, String clientId) {
    @Override
    public String toString() {
        return "MinecraftSession[" + username + ", " + uuid + ", token=<redacted>]";
    }
}
