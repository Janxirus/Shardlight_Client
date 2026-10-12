package dev.shardlight.client.project;

/**
 * One entry of the in-game multiplayer list ({@code servers.dat}).
 *
 * @param name           display name
 * @param address        "host", "host:port" or "[ipv6]:port"
 * @param iconBase64     the server's cached 64x64 PNG favicon, base64 without a data: prefix, or null
 * @param acceptTextures server resource packs: true = accept, false = decline, null = ask (Minecraft's default)
 * @param favourite      launcher-only flag, kept in {@code servers-meta.json}
 */
public record ServerEntry(String name, String address, String iconBase64, Boolean acceptTextures, boolean favourite) {
}
