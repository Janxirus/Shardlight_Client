package dev.shardlight.client.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.Json;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Microsoft sign-in for Minecraft: Java Edition, with no password ever entering the launcher.
 *
 * <ol>
 *   <li>OAuth 2.0 device-code flow on the consumer tenant ({@link #startDeviceCode}, {@link #pollDeviceCode});
 *       the player signs in on Microsoft's own page in any browser. {@link #refresh} renews the tokens later.</li>
 *   <li>{@link #loginToMinecraft}: Xbox Live user token, XSTS token for Minecraft services, the Minecraft
 *       access token, then the entitlement and profile checks that decide whether the account may play.</li>
 * </ol>
 *
 * <p>Sign-in needs an Azure application id registered by whoever distributes the launcher and approved for
 * Minecraft services (see docs/SIGN_IN.md). There is no built-in id: without one, every call throws
 * {@link ErrorCode#AUTH_NOT_CONFIGURED}. Every token is registered with {@link Redactor} as soon as it is
 * received. The service holds no mutable state and is safe to use from several threads.
 */
public final class MicrosoftAuthService {
    private static final Logger LOG = Logger.getLogger(MicrosoftAuthService.class.getName());

    /** the OAuth scopes: Xbox Live sign-in plus a refresh token */
    public static final String SCOPE = "XboxLive.signin offline_access";
    static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    static final String MINECRAFT_RELYING_PARTY = "rp://api.minecraftservices.com/";
    static final String XBOX_RELYING_PARTY = "http://xboxlive.com";

    /** Xbox XSTS error numbers ("XErr") */
    static final long XERR_BANNED = 2148916227L;
    static final long XERR_PARENTAL_CONTROLS = 2148916229L;
    static final long XERR_NO_XBOX_ACCOUNT = 2148916233L;
    static final long XERR_TERMS_NOT_ACCEPTED = 2148916234L;
    static final long XERR_REGION_BLOCKED = 2148916235L;
    static final long XERR_ADULT_VERIFICATION = 2148916236L;
    static final long XERR_ADULT_VERIFICATION_2 = 2148916237L;
    static final long XERR_CHILD_ACCOUNT = 2148916238L;

    /** network failures tolerated in a row while waiting for the player to finish signing in */
    static final int MAX_POLL_NETWORK_FAILURES = 3;

    private static final Pattern UUID_HEX = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern XUID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern USER_CODE = Pattern.compile("[A-Za-z0-9 -]{1,64}");

    private final HttpService http;
    private final Endpoints endpoints;
    private final Supplier<String> clientId;
    private final Clock clock;
    private final Sleeper sleeper;

    /**
     * @param clientId supplies the Azure application (client) id, read on every call so a changed setting
     *                 takes effect immediately; blank means sign-in isn't configured
     */
    public MicrosoftAuthService(HttpService http, Endpoints endpoints, Supplier<String> clientId) {
        this(http, endpoints, clientId, Clock.systemUTC(), Sleeper.SYSTEM);
    }

    /** with an explicit clock and sleeper (tests) */
    public MicrosoftAuthService(HttpService http, Endpoints endpoints, Supplier<String> clientId, Clock clock, Sleeper sleeper) {
        this.http = Objects.requireNonNull(http, "http");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** true when a client id is configured (sign-in can be attempted) */
    public boolean isConfigured() {
        return !clientId().isEmpty();
    }

    /** the configured client id, trimmed; empty when none */
    public String clientId() {
        try {
            String id = clientId.get();
            return id == null ? "" : id.trim();
        } catch (RuntimeException e) {
            LOG.warning("Couldn't read the Microsoft client id setting: " + e);
            return "";
        }
    }

    // ---------------------------------------------------------------- Microsoft OAuth

    /** ask Microsoft for a device code the player enters at {@link DeviceCode#verificationUri()} */
    public DeviceCode startDeviceCode(CancellationToken cancel) throws LauncherException {
        String id = requireClientId();
        cancel.check();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", id);
        form.put("scope", SCOPE);
        HttpService.Response r = http.postForm(msaUri("devicecode"), form, cancel);
        JsonObject o = tryParseObject(r);
        if (!r.ok()) {
            throw oauthError(r, o);
        }
        if (o == null) {
            throw badResponse("Microsoft");
        }
        String deviceCode = requireString(o, "device_code", "Microsoft");
        Redactor.register(deviceCode);
        String userCode = requireString(o, "user_code", "Microsoft").trim();
        if (!USER_CODE.matcher(userCode).matches()) {
            throw badResponse("Microsoft");
        }
        String link = string(o, "verification_uri");
        if (link == null) {
            link = string(o, "verification_url"); // older endpoint spelling
        }
        URI verification = httpsUri(link);
        int expiresIn = (int) clamp(longOr(o, "expires_in", 900), 1, 7200);
        int interval = (int) clamp(longOr(o, "interval", 5), 1, 60);
        String message = printable(string(o, "message"), 500);
        LOG.info("Microsoft device-code sign-in started (code valid for " + expiresIn / 60 + " min)");
        return new DeviceCode(userCode, verification, expiresIn, interval, deviceCode, message);
    }

    /**
     * Wait until the player finishes signing in on Microsoft's page, polling every
     * {@link DeviceCode#intervalSeconds()} (5 seconds longer each time Microsoft says {@code slow_down}).
     * Throws {@link ErrorCode#AUTH_FAILED} when the player declines or the code expires, and
     * {@link ErrorCode#CANCELLED} when {@code cancel} fires.
     */
    public MsaTokens pollDeviceCode(DeviceCode code, CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(code, "code");
        String id = requireClientId();
        Instant deadline = clock.instant().plusSeconds(Math.max(1, code.expiresInSeconds()));
        long interval = Math.max(1, code.intervalSeconds());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", DEVICE_CODE_GRANT);
        form.put("client_id", id);
        form.put("device_code", code.deviceCode());
        int transientFailures = 0;
        while (true) {
            sleeper.sleep(Duration.ofSeconds(interval), cancel);
            cancel.check();
            if (clock.instant().isAfter(deadline)) {
                throw codeExpired();
            }
            HttpService.Response r;
            try {
                r = http.postForm(msaUri("token"), form, cancel);
            } catch (LauncherException e) {
                if (e.code() == ErrorCode.NETWORK && ++transientFailures <= MAX_POLL_NETWORK_FAILURES) {
                    LOG.fine("Network error while waiting for sign-in; trying again");
                    continue;
                }
                throw e;
            }
            if (r.ok()) {
                MsaTokens tokens = parseTokens(r, null);
                LOG.info("Microsoft sign-in completed");
                return tokens;
            }
            JsonObject o = tryParseObject(r);
            String error = o == null ? null : string(o, "error");
            if (error == null) {
                if (r.status() >= 500 && ++transientFailures <= MAX_POLL_NETWORK_FAILURES) {
                    continue;
                }
                throw oauthError(r, o);
            }
            transientFailures = 0;
            switch (error) {
                case "authorization_pending" -> {
                    // the player hasn't finished yet
                }
                case "slow_down" -> interval += 5;
                case "authorization_declined", "access_denied" ->
                    throw new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.declined");
                case "expired_token", "bad_verification_code", "code_expired" -> throw codeExpired();
                default -> throw oauthError(r, o);
            }
        }
    }

    /**
     * Renew the Microsoft tokens with a stored refresh token. Microsoft may return a new refresh token
     * (store it); when it doesn't, the old one is kept in the result. Throws {@link ErrorCode#AUTH_EXPIRED}
     * when the refresh token was revoked or expired (the player must sign in again).
     */
    public MsaTokens refresh(String refreshToken, CancellationToken cancel) throws LauncherException {
        String id = requireClientId();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new LauncherException(ErrorCode.AUTH_EXPIRED, "auth.error.sessionExpired");
        }
        Redactor.register(refreshToken);
        cancel.check();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", id);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("scope", SCOPE);
        HttpService.Response r = http.postForm(msaUri("token"), form, cancel);
        if (r.ok()) {
            return parseTokens(r, refreshToken);
        }
        JsonObject o = tryParseObject(r);
        String error = o == null ? null : string(o, "error");
        if (error != null) {
            switch (error) {
                case "invalid_grant", "interaction_required", "consent_required", "login_required" -> {
                    LOG.info("The saved Microsoft sign-in is no longer valid (" + safeCode(error) + ")");
                    throw new LauncherException(ErrorCode.AUTH_EXPIRED, "auth.error.sessionExpired");
                }
                default -> {
                    // fall through to the generic mapping
                }
            }
        }
        throw oauthError(r, o);
    }

    // ---------------------------------------------------------------- Xbox Live and Minecraft services

    /**
     * Turn Microsoft tokens into a Minecraft login: Xbox Live user token, XSTS token for Minecraft
     * services (Xbox account problems map to {@link ErrorCode#AUTH_NO_XBOX_ACCOUNT},
     * {@link ErrorCode#AUTH_REGION_BLOCKED}, {@link ErrorCode#AUTH_ADULT_VERIFICATION},
     * {@link ErrorCode#AUTH_CHILD_ACCOUNT}), the xuid (optional), the Minecraft token, then the
     * entitlement and profile. An account with a profile may play (Game Pass accounts can lack a store
     * entitlement); otherwise {@link ErrorCode#AUTH_NOT_OWNED} explains whether the game or only the
     * profile name is missing.
     */
    public MinecraftLogin loginToMinecraft(MsaTokens msa, CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(msa, "msa");
        requireClientId();
        XboxToken user = xboxUserToken(msa.accessToken(), cancel);
        XboxToken xsts = xsts(user.token(), MINECRAFT_RELYING_PARTY, cancel);
        String xuid = readXuid(user.token(), cancel);
        McToken mc = loginWithXbox(xsts, cancel);

        boolean entitled = false;
        LauncherException entitlementError = null;
        try {
            entitled = hasJavaEntitlement(mc.token(), cancel);
        } catch (LauncherException e) {
            if (e.isCancelled() || e.code() == ErrorCode.NETWORK) {
                throw e;
            }
            entitlementError = e;
            LOG.warning("Couldn't check Minecraft entitlements: " + e.getMessage());
        }
        Profile profile = profile(mc.token(), cancel);
        if (profile == null) {
            if (entitlementError != null) {
                throw entitlementError;
            }
            if (entitled) {
                throw new LauncherException(ErrorCode.AUTH_NOT_OWNED, "auth.error.noProfile");
            }
            throw new LauncherException(ErrorCode.AUTH_NOT_OWNED, "auth.error.notOwned");
        }
        if (!entitled) {
            LOG.info("No Minecraft store entitlement, but a profile exists (for example Xbox Game Pass)");
        }
        LOG.info("Signed in to Minecraft services as " + profile.name());
        return new MinecraftLogin(profile.uuid(), profile.name(), mc.token(), mc.expiresAt(), xuid, true,
            profile.skinUrl(), profile.skinVariant(), msa);
    }

    record XboxToken(String token, String uhs, String xid) {
        @Override
        public String toString() {
            return "XboxToken[<redacted>]";
        }
    }

    record McToken(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "McToken[<redacted>, " + expiresAt + "]";
        }
    }

    record Profile(String uuid, String name, String skinUrl, String skinVariant) {
    }

    private XboxToken xboxUserToken(String msaAccessToken, CancellationToken cancel) throws LauncherException {
        if (msaAccessToken == null || msaAccessToken.isBlank()) {
            throw new LauncherException(ErrorCode.AUTH_EXPIRED, "auth.error.sessionExpired");
        }
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msaAccessToken);
        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");
        HttpService.Response r = http.postJson(endpoints.xboxUserAuth(), Json.COMPACT.toJson(body), xboxHeaders(), true, cancel);
        JsonObject o = tryParseObject(r);
        if (!r.ok()) {
            throw xboxError(r, o);
        }
        XboxToken t = parseXboxToken(o);
        LOG.fine("Xbox Live user token received");
        return t;
    }

    private XboxToken xsts(String userToken, String relyingParty, CancellationToken cancel) throws LauncherException {
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(userToken);
        props.add("UserTokens", tokens);
        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", relyingParty);
        body.addProperty("TokenType", "JWT");
        HttpService.Response r = http.postJson(endpoints.xboxXsts(), Json.COMPACT.toJson(body), xboxHeaders(), true, cancel);
        JsonObject o = tryParseObject(r);
        if (!r.ok()) {
            throw xboxError(r, o);
        }
        XboxToken t = parseXboxToken(o);
        LOG.fine("XSTS token received for " + relyingParty);
        return t;
    }

    /** the Xbox user id from a second XSTS call; failures are logged, not fatal */
    private String readXuid(String userToken, CancellationToken cancel) throws LauncherException {
        try {
            String xid = xsts(userToken, XBOX_RELYING_PARTY, cancel).xid();
            if (xid != null && XUID.matcher(xid).matches()) {
                return xid;
            }
            LOG.fine("The Xbox profile has no readable xuid");
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                throw e;
            }
            LOG.info("Couldn't read the Xbox user id (continuing without it): " + e.code());
        }
        return null;
    }

    private McToken loginWithXbox(XboxToken xsts, CancellationToken cancel) throws LauncherException {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + xsts.uhs() + ";" + xsts.token());
        URI uri = HttpService.uri(endpoints.minecraftServices(), "authentication/login_with_xbox", null);
        HttpService.Response r = http.postJson(uri, Json.COMPACT.toJson(body), Map.of(), true, cancel);
        JsonObject o = tryParseObject(r);
        if (!r.ok()) {
            String text = r.text();
            if (r.status() == 403 && (text.contains("Invalid app registration") || text.contains("AppRegInfo"))) {
                throw new LauncherException(ErrorCode.AUTH_NOT_CONFIGURED, "auth.error.appNotApproved");
            }
            if (r.status() == 429 || r.status() >= 500) {
                r.requireOk();
            }
            throw new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.minecraftLogin", r.status());
        }
        if (o == null) {
            throw badResponse("Minecraft");
        }
        String token = requireString(o, "access_token", "Minecraft");
        Redactor.register(token);
        long expiresIn = clamp(longOr(o, "expires_in", 86400), 60, 7 * 86400L);
        LOG.fine("Minecraft services token received");
        return new McToken(token, clock.instant().plusSeconds(expiresIn));
    }

    /**
     * True when the account's licences list Minecraft: Java Edition ({@code product_minecraft} or
     * {@code game_minecraft}). Asks {@code entitlements/license}, the endpoint Mojang's own launcher uses, and
     * falls back to the older {@code entitlements/mcstore} when that one isn't answered.
     */
    private boolean hasJavaEntitlement(String mcToken, CancellationToken cancel) throws LauncherException {
        URI license = HttpService.uri(endpoints.minecraftServices(), "entitlements/license",
            Map.of("requestId", java.util.UUID.randomUUID().toString()));
        HttpService.Response r = http.execute(HttpService.Request.get(license).withHeaders(bearer(mcToken)), cancel);
        if (!r.ok() && r.status() != 429 && r.status() < 500) {
            LOG.fine("entitlements/license answered HTTP " + r.status() + "; asking entitlements/mcstore");
            URI mcstore = HttpService.uri(endpoints.minecraftServices(), "entitlements/mcstore", null);
            r = http.execute(HttpService.Request.get(mcstore).withHeaders(bearer(mcToken)), cancel);
        }
        r.requireOk();
        JsonObject o = tryParseObject(r);
        if (o == null) {
            throw badResponse("Minecraft");
        }
        JsonElement items = o.get("items");
        if (items == null || !items.isJsonArray()) {
            return false;
        }
        for (JsonElement item : items.getAsJsonArray()) {
            if (item.isJsonObject()) {
                String name = string(item.getAsJsonObject(), "name");
                if ("product_minecraft".equals(name) || "game_minecraft".equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** the Minecraft profile, or null when the account has none (HTTP 404) */
    private Profile profile(String mcToken, CancellationToken cancel) throws LauncherException {
        URI uri = HttpService.uri(endpoints.minecraftServices(), "minecraft/profile", null);
        HttpService.Response r = http.execute(HttpService.Request.get(uri).withHeaders(bearer(mcToken)), cancel);
        if (r.status() == 404) {
            return null;
        }
        if (r.status() == 401 || r.status() == 403) {
            throw new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.minecraftLogin", r.status());
        }
        r.requireOk();
        JsonObject o = tryParseObject(r);
        if (o == null) {
            throw badResponse("Minecraft");
        }
        String uuid = normalizeUuid(string(o, "id"));
        String name = string(o, "name");
        if (uuid == null || name == null || !isSafeName(name)) {
            throw badResponse("Minecraft");
        }
        String skinUrl = null;
        String variant = null;
        JsonElement skins = o.get("skins");
        if (skins != null && skins.isJsonArray()) {
            for (JsonElement s : skins.getAsJsonArray()) {
                if (!s.isJsonObject()) {
                    continue;
                }
                JsonObject skin = s.getAsJsonObject();
                if ("ACTIVE".equalsIgnoreCase(string(skin, "state"))) {
                    skinUrl = webUrlOrNull(string(skin, "url"));
                    String v = string(skin, "variant");
                    variant = v == null ? null : switch (v.toUpperCase(Locale.ROOT)) {
                        case "CLASSIC" -> "classic";
                        case "SLIM" -> "slim";
                        default -> null;
                    };
                    break;
                }
            }
        }
        return new Profile(uuid, name, skinUrl, variant);
    }

    // ---------------------------------------------------------------- helpers

    private String requireClientId() throws LauncherException {
        String id = clientId();
        if (id.isEmpty()) {
            throw new LauncherException(ErrorCode.AUTH_NOT_CONFIGURED, "auth.error.notConfigured");
        }
        return id;
    }

    private URI msaUri(String path) {
        return HttpService.uri(endpoints.microsoftLogin(), path, null);
    }

    private static Map<String, String> xboxHeaders() {
        return Map.of("x-xbl-contract-version", "1");
    }

    private static Map<String, String> bearer(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        return h;
    }

    private MsaTokens parseTokens(HttpService.Response r, String previousRefresh) throws LauncherException {
        JsonObject o = tryParseObject(r);
        if (o == null) {
            throw badResponse("Microsoft");
        }
        String access = requireString(o, "access_token", "Microsoft");
        Redactor.register(access);
        String refresh = string(o, "refresh_token");
        if (refresh == null || refresh.isBlank()) {
            refresh = previousRefresh;
        }
        Redactor.register(refresh);
        long expiresIn = clamp(longOr(o, "expires_in", 3600), 60, 86400);
        return new MsaTokens(access, refresh, clock.instant().plusSeconds(expiresIn));
    }

    private static XboxToken parseXboxToken(JsonObject o) throws LauncherException {
        if (o == null) {
            throw badResponse("Xbox Live");
        }
        String token = requireString(o, "Token", "Xbox Live");
        Redactor.register(token);
        String uhs = null;
        String xid = null;
        JsonElement claims = o.get("DisplayClaims");
        if (claims != null && claims.isJsonObject()) {
            JsonElement xui = claims.getAsJsonObject().get("xui");
            if (xui != null && xui.isJsonArray() && !xui.getAsJsonArray().isEmpty() && xui.getAsJsonArray().get(0).isJsonObject()) {
                JsonObject first = xui.getAsJsonArray().get(0).getAsJsonObject();
                uhs = string(first, "uhs");
                xid = string(first, "xid");
            }
        }
        if (uhs == null || uhs.isBlank()) {
            throw badResponse("Xbox Live");
        }
        Redactor.register(uhs);
        return new XboxToken(token, uhs, xid);
    }

    /** map an Xbox Live error answer (with or without an XErr number) to a helpful error */
    static LauncherException xboxError(HttpService.Response r, JsonObject o) {
        Long xerr = null;
        if (o != null && o.has("XErr")) {
            try {
                xerr = o.get("XErr").getAsLong();
            } catch (RuntimeException e) {
                try {
                    xerr = Long.parseLong(o.get("XErr").getAsString().trim());
                } catch (RuntimeException ignored) {
                    // not a number
                }
            }
        }
        if (xerr == null) {
            if (r.status() == 429 || r.status() >= 500) {
                try {
                    r.requireOk();
                } catch (LauncherException e) {
                    return e;
                }
            }
            return new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.xboxAuth", r.status());
        }
        LOG.info("Xbox Live refused the sign-in: XErr " + xerr);
        return xboxError(xerr);
    }

    /** the error for one XErr number */
    static LauncherException xboxError(long xerr) {
        if (xerr == XERR_NO_XBOX_ACCOUNT) {
            return new LauncherException(ErrorCode.AUTH_NO_XBOX_ACCOUNT, "auth.error.xbox.noAccount");
        }
        if (xerr == XERR_TERMS_NOT_ACCEPTED) {
            return new LauncherException(ErrorCode.AUTH_NO_XBOX_ACCOUNT, "auth.error.xbox.terms");
        }
        if (xerr == XERR_REGION_BLOCKED) {
            return new LauncherException(ErrorCode.AUTH_REGION_BLOCKED, "auth.error.xbox.region");
        }
        if (xerr == XERR_ADULT_VERIFICATION || xerr == XERR_ADULT_VERIFICATION_2) {
            return new LauncherException(ErrorCode.AUTH_ADULT_VERIFICATION, "auth.error.xbox.adultVerification");
        }
        if (xerr == XERR_CHILD_ACCOUNT) {
            return new LauncherException(ErrorCode.AUTH_CHILD_ACCOUNT, "auth.error.xbox.child");
        }
        if (xerr == XERR_PARENTAL_CONTROLS) {
            return new LauncherException(ErrorCode.AUTH_CHILD_ACCOUNT, "auth.error.xbox.parentalControls");
        }
        if (xerr == XERR_BANNED) {
            return new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.xbox.banned");
        }
        return new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.xbox.other", Long.toString(xerr));
    }

    /** map an OAuth error answer from Microsoft's token or device-code endpoint */
    private static LauncherException oauthError(HttpService.Response r, JsonObject o) {
        String error = o == null ? null : string(o, "error");
        if (error == null) {
            if (r.status() == 429 || r.status() >= 500) {
                try {
                    r.requireOk();
                } catch (LauncherException e) {
                    return e;
                }
            }
            return new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.microsoft", "HTTP " + r.status());
        }
        String code = safeCode(error);
        LOG.info("Microsoft sign-in error: " + code);
        return switch (error) {
            case "invalid_client", "unauthorized_client" ->
                new LauncherException(ErrorCode.AUTH_NOT_CONFIGURED, "auth.error.clientRejected", code);
            case "authorization_declined", "access_denied" -> new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.declined");
            case "expired_token", "bad_verification_code", "code_expired" -> codeExpired();
            default -> new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.microsoft", code);
        };
    }

    private static LauncherException codeExpired() {
        return new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.codeExpired");
    }

    private static LauncherException badResponse(String service) {
        return new LauncherException(ErrorCode.INVALID_DATA, "auth.error.badResponse", service);
    }

    static JsonObject tryParseObject(HttpService.Response r) {
        String text = r.text();
        if (text.isBlank()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(text);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    private static String requireString(JsonObject o, String field, String service) throws LauncherException {
        String s = string(o, field);
        if (s == null || s.isBlank()) {
            throw badResponse(service);
        }
        return s;
    }

    private static long longOr(JsonObject o, String field, long fallback) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return e.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException ex) {
            try {
                return Long.parseLong(e.getAsString().trim());
            } catch (NumberFormatException ex2) {
                return fallback;
            }
        }
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    /** OAuth error codes are short ASCII words; anything else is cut down so it can be shown safely */
    static String safeCode(String error) {
        String s = error.replaceAll("[^A-Za-z0-9_.-]", "");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** text from a server shown to the user: control characters removed, length capped */
    private static String printable(String s, int max) {
        if (s == null) {
            return "";
        }
        String clean = s.replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    /** the verification link must be an https URL with a host: the UI opens it in a browser */
    private static URI httpsUri(String link) throws LauncherException {
        if (link == null) {
            throw badResponse("Microsoft");
        }
        try {
            URI u = new URI(link.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getHost().isBlank()
                || u.getRawUserInfo() != null) {
                throw badResponse("Microsoft");
            }
            return u;
        } catch (URISyntaxException e) {
            throw badResponse("Microsoft");
        }
    }

    /** skin URLs are http(s) on Mojang's texture host; anything else is dropped */
    private static String webUrlOrNull(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("https") || scheme.equals("http")) && u.getHost() != null && u.getRawUserInfo() == null
                ? u.toString() : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** a profile UUID as 32 lowercase hex digits, accepting the dashed form; null when malformed */
    static String normalizeUuid(String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim().replace("-", "").toLowerCase(Locale.ROOT);
        return UUID_HEX.matcher(s).matches() ? s : null;
    }

    /** profile names are short and plain; refuse anything that could confuse a command line or a file name */
    static boolean isSafeName(String name) {
        if (name.isEmpty() || name.length() > 32) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }
}
