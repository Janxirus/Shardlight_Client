package dev.shardlight.client.account;

/**
 * A signed-in Microsoft account with a Minecraft: Java Edition profile. Holds no secrets: tokens live in
 * the OS secret store under keys derived from {@link #id}.
 */
public final class Account {
    /** the Minecraft profile UUID without dashes */
    public String id;
    public String username;
    /** Xbox user id (passed to the game as auth_xuid) */
    public String xuid;
    public String type = "msa";
    public long addedAt;
    public long lastUsedAt;
    public String skinUrl;
    /** "classic" or "slim" */
    public String skinVariant;
    /** whether the last entitlement check found Minecraft: Java Edition */
    public boolean ownsGame;
    public long entitlementCheckedAt;

    @Override
    public String toString() {
        return username + " (" + id + ")";
    }
}
