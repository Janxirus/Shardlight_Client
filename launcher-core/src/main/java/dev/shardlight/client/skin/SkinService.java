package dev.shardlight.client.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.account.AccountService;
import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.Json;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The signed-in account's skin and capes, through the Minecraft services API: read what the account wears and
 * owns, upload a skin, choose a cape. Needs a working sign-in; when there is none (or Minecraft has not yet
 * approved the launcher's Microsoft app) every call fails with a plain message and the local
 * {@link SkinLibrary} keeps working on its own.
 *
 * <p>The access token is fetched per call from {@link AccountService} and is never logged or kept here.
 */
public final class SkinService {
    /** the only host pictures are fetched from */
    public static final String TEXTURE_HOST = "textures.minecraft.net";
    private static final int MAX_TEXTURE_BYTES = 256 * 1024;

    /** a skin the account owns */
    public record Skin(String id, String url, SkinVariant variant, boolean active) {
    }

    /** a cape the account owns; {@code alias} is its name, for example "Migrator" */
    public record Cape(String id, String url, String alias, boolean active) {
    }

    /** what an account wears and owns */
    public record Wardrobe(List<Skin> skins, List<Cape> capes) {
        public Optional<Skin> activeSkin() {
            return skins.stream().filter(Skin::active).findFirst();
        }

        public Optional<Cape> activeCape() {
            return capes.stream().filter(Cape::active).findFirst();
        }
    }

    private final HttpService http;
    private final Endpoints endpoints;
    private final AccountService accounts;
    private final Function<String, URI> textures;

    public SkinService(HttpService http, Endpoints endpoints, AccountService accounts) {
        this(http, endpoints, accounts, SkinService::textureUri);
    }

    /** {@code textures} turns a picture address from the API into the one to fetch (tests point it at a local server) */
    public SkinService(HttpService http, Endpoints endpoints, AccountService accounts, Function<String, URI> textures) {
        this.http = http;
        this.endpoints = endpoints;
        this.accounts = accounts;
        this.textures = textures;
    }

    /** an address on Mojang's texture host as an https URI; null for anything else */
    public static URI textureUri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI u = new URI(url.trim());
            if (u.getHost() == null || !TEXTURE_HOST.equalsIgnoreCase(u.getHost()) || u.getRawUserInfo() != null) {
                return null;
            }
            return new URI("https", null, TEXTURE_HOST, -1, u.getPath(), null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- reading

    /** the skins and capes of {@code accountId} */
    public Wardrobe wardrobe(String accountId, CancellationToken cancel) throws LauncherException {
        String token = token(accountId, cancel);
        HttpService.Response r = http.execute(HttpService.Request.get(profileUri()).withHeaders(bearer(token)), cancel);
        return parseWardrobe(check(r));
    }

    /** a skin or cape picture (at most 256 KB) from the texture host */
    public byte[] texture(String url, CancellationToken cancel) throws LauncherException {
        URI u = textures.apply(url);
        if (u == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.badTexture");
        }
        HttpService.Response r = http.execute(HttpService.Request.get(u), cancel).requireOk();
        if (r.body().length > MAX_TEXTURE_BYTES) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.badTexture");
        }
        return r.body();
    }

    // ---------------------------------------------------------------- changing

    /**
     * Make {@code png} the account's skin. The file is checked first (a PNG of 64 x 64 or 64 x 32). The account
     * list is updated so the head in the sidebar and Home follows.
     */
    public Wardrobe uploadSkin(String accountId, byte[] png, SkinVariant variant, CancellationToken cancel)
        throws LauncherException {
        SkinImage.readSkin(png);
        String token = token(accountId, cancel);
        String boundary = "shardlight-" + UUID.randomUUID();
        byte[] body = multipart(boundary, variant, png);
        Map<String, String> headers = bearer(token);
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        URI uri = HttpService.uri(endpoints.minecraftServices(), "minecraft/profile/skins", null);
        HttpService.Request req = new HttpService.Request("POST", uri, headers, body, Duration.ofSeconds(60), false);
        Wardrobe w = parseWardrobe(check(http.execute(req, cancel)));
        remember(accountId, w);
        return w;
    }

    /** wear {@code capeId}, or no cape when it is null */
    public Wardrobe setCape(String accountId, String capeId, CancellationToken cancel) throws LauncherException {
        String token = token(accountId, cancel);
        HttpService.Request req;
        if (capeId == null) {
            URI uri = HttpService.uri(endpoints.minecraftServices(), "minecraft/profile/capes/active", null);
            req = new HttpService.Request("DELETE", uri, bearer(token), null, Duration.ofSeconds(30), false);
        } else {
            JsonObject o = new JsonObject();
            o.addProperty("capeId", capeId);
            Map<String, String> headers = bearer(token);
            headers.put("Content-Type", "application/json");
            URI uri = HttpService.uri(endpoints.minecraftServices(), "minecraft/profile/capes/active", null);
            req = new HttpService.Request("PUT", uri, headers, Json.COMPACT.toJson(o).getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(30), false);
        }
        return parseWardrobe(check(http.execute(req, cancel)));
    }

    // ---------------------------------------------------------------- internals

    private String token(String accountId, CancellationToken cancel) throws LauncherException {
        MinecraftSession s = accounts.session(accountId, cancel);
        return s.accessToken();
    }

    private void remember(String accountId, Wardrobe w) {
        w.activeSkin().ifPresent(s -> {
            try {
                accounts.updateSkin(accountId, s.url(), s.variant().api());
            } catch (LauncherException ignored) {
                // the head in the app catches up at the next sign-in refresh
            }
        });
    }

    private URI profileUri() {
        return HttpService.uri(endpoints.minecraftServices(), "minecraft/profile", null);
    }

    private static Map<String, String> bearer(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        return h;
    }

    /** turn the status of an answer into a message the player can act on */
    private static HttpService.Response check(HttpService.Response r) throws LauncherException {
        if (r.ok()) {
            return r;
        }
        switch (r.status()) {
            case 400 -> throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.rejected");
            case 401, 403 -> throw new LauncherException(ErrorCode.AUTH_FAILED, "skin.error.denied", r.status());
            case 404 -> throw new LauncherException(ErrorCode.NOT_FOUND, "skin.error.noProfile");
            case 429 -> throw new LauncherException(ErrorCode.RATE_LIMITED, "skin.error.rateLimited");
            default -> throw new LauncherException(ErrorCode.HTTP_STATUS, "skin.error.status", r.status());
        }
    }

    static Wardrobe parseWardrobe(HttpService.Response r) throws LauncherException {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(r.text());
            if (!e.isJsonObject()) {
                throw bad();
            }
            o = e.getAsJsonObject();
        } catch (JsonParseException e) {
            throw bad();
        }
        List<Skin> skins = new ArrayList<>();
        for (JsonObject s : objects(o.get("skins"))) {
            String url = text(s, "url");
            if (textureUri(url) == null) {
                continue;
            }
            skins.add(new Skin(text(s, "id"), url, SkinVariant.parse(text(s, "variant")), isActive(s)));
        }
        List<Cape> capes = new ArrayList<>();
        for (JsonObject c : objects(o.get("capes"))) {
            String id = text(c, "id");
            String url = text(c, "url");
            if (id == null || textureUri(url) == null) {
                continue;
            }
            capes.add(new Cape(id, url, text(c, "alias"), isActive(c)));
        }
        return new Wardrobe(List.copyOf(skins), List.copyOf(capes));
    }

    private static List<JsonObject> objects(JsonElement e) {
        List<JsonObject> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement x : a) {
                if (x.isJsonObject()) {
                    out.add(x.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static boolean isActive(JsonObject o) {
        return "ACTIVE".equalsIgnoreCase(text(o, "state"));
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static LauncherException bad() {
        return new LauncherException(ErrorCode.INVALID_DATA, "error.invalidResponse", "Minecraft");
    }

    static byte[] multipart(String boundary, SkinVariant variant, byte[] png) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 400);
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
            + variant.api().toLowerCase(Locale.ROOT) + "\r\n"
            + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(png);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
