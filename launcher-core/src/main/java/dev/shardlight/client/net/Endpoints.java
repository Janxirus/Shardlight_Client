package dev.shardlight.client.net;

import java.net.URI;

/**
 * Every external service the launcher talks to. Tests replace them with a local server; nothing else
 * should hard-code these hosts.
 *
 * @param versionManifest     Mojang's version list (piston-meta)
 * @param resources           Mojang's asset object store; objects live at {@code <resources>/<ab>/<hash>}
 * @param libraries           Mojang's library Maven repository (fallback when a library has no URL)
 * @param javaRuntimes        Mojang's Java runtime index (all.json)
 * @param fabricMeta          Fabric's metadata API (v2)
 * @param quiltMeta           Quilt's metadata API (v3)
 * @param forgeMaven          Forge's Maven repository (installers)
 * @param neoForgeMaven       NeoForge's Maven repository (installers)
 * @param modrinthApi         Modrinth API v2
 * @param curseForgeApi       CurseForge API v1 (needs an approved API key)
 * @param microsoftLogin      Microsoft identity platform, consumer tenant
 * @param xboxUserAuth        Xbox Live user authentication
 * @param xboxXsts            Xbox Live XSTS authorization
 * @param minecraftServices   Minecraft services (login, entitlements, profile)
 */
public record Endpoints(
    URI versionManifest,
    URI resources,
    URI libraries,
    URI javaRuntimes,
    URI fabricMeta,
    URI quiltMeta,
    URI forgeMaven,
    URI neoForgeMaven,
    URI modrinthApi,
    URI curseForgeApi,
    URI microsoftLogin,
    URI xboxUserAuth,
    URI xboxXsts,
    URI minecraftServices) {

    public static final Endpoints DEFAULT = new Endpoints(
        URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"),
        URI.create("https://resources.download.minecraft.net/"),
        URI.create("https://libraries.minecraft.net/"),
        URI.create("https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"),
        URI.create("https://meta.fabricmc.net/v2/"),
        URI.create("https://meta.quiltmc.org/v3/"),
        URI.create("https://maven.minecraftforge.net/"),
        URI.create("https://maven.neoforged.net/releases/"),
        URI.create("https://api.modrinth.com/v2/"),
        URI.create("https://api.curseforge.com/v1/"),
        URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/"),
        URI.create("https://user.auth.xboxlive.com/user/authenticate"),
        URI.create("https://xsts.auth.xboxlive.com/xsts/authorize"),
        URI.create("https://api.minecraftservices.com/"));

    /** every endpoint on one local test server, under path prefixes named after the service */
    public static Endpoints local(URI base) {
        String b = base.toString().endsWith("/") ? base.toString() : base + "/";
        return new Endpoints(
            URI.create(b + "mojang/version_manifest_v2.json"),
            URI.create(b + "resources/"),
            URI.create(b + "libraries/"),
            URI.create(b + "runtimes/all.json"),
            URI.create(b + "fabric/v2/"),
            URI.create(b + "quilt/v3/"),
            URI.create(b + "forge-maven/"),
            URI.create(b + "neoforge-maven/"),
            URI.create(b + "modrinth/v2/"),
            URI.create(b + "curseforge/v1/"),
            URI.create(b + "msa/"),
            URI.create(b + "xbl/user/authenticate"),
            URI.create(b + "xsts/xsts/authorize"),
            URI.create(b + "mcs/"));
    }
}
