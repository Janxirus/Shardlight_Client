package dev.shardlight.client.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.shardlight.client.auth.AuthFixtures.Answer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftAuthServiceTest {
    AuthFixtures fx;
    MicrosoftAuthService auth;

    @BeforeEach
    void setUp() {
        fx = new AuthFixtures();
        auth = fx.service();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private TestHttpServer.Recorded lastRequest(String path) {
        List<TestHttpServer.Recorded> all = fx.server.requests().stream().filter(r -> r.path().equals(path)).toList();
        assertFalse(all.isEmpty(), "no request to " + path);
        return all.get(all.size() - 1);
    }

    private static JsonObject json(TestHttpServer.Recorded r) {
        return JsonParser.parseString(r.bodyText()).getAsJsonObject();
    }

    private static LauncherException fails(ErrorCode code, Executable e) {
        LauncherException ex = assertThrows(LauncherException.class, e::run);
        assertEquals(code, ex.code(), () -> "got " + ex);
        return ex;
    }

    @FunctionalInterface
    interface Executable {
        void run() throws Exception;
    }

    private DeviceCode code(int expiresIn, int interval) {
        return new DeviceCode(AuthFixtures.USER_CODE, URI.create("https://www.microsoft.com/link"), expiresIn, interval,
            AuthFixtures.DEVICE_CODE, "");
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void notConfiguredWithoutClientId() {
        for (MicrosoftAuthService s : List.of(fx.service(() -> ""), fx.service(() -> "   "), fx.service(() -> null),
            fx.service(() -> {
                throw new IllegalStateException("settings broken");
            }))) {
            assertFalse(s.isConfigured());
            assertEquals("", s.clientId());
            fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> s.startDeviceCode(CancellationToken.NONE));
            fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> s.pollDeviceCode(code(900, 5), CancellationToken.NONE));
            fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> s.refresh(AuthFixtures.MSA_REFRESH, CancellationToken.NONE));
            LauncherException e = fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> s.loginToMinecraft(
                new MsaTokens(AuthFixtures.MSA_ACCESS, AuthFixtures.MSA_REFRESH, Instant.now()), CancellationToken.NONE));
            assertEquals("auth.error.notConfigured", e.messageKey());
            assertTrue(I18n.has(e.messageKey()));
        }
        assertTrue(fx.server.requests().isEmpty(), "nothing may be sent without a client id");
        assertTrue(auth.isConfigured());
        assertEquals(AuthFixtures.CLIENT_ID, fx.service(() -> "  " + AuthFixtures.CLIENT_ID + "\n").clientId());
    }

    // ---------------------------------------------------------------- device code

    @Test
    void startsDeviceCode() throws Exception {
        DeviceCode dc = auth.startDeviceCode(CancellationToken.NONE);
        assertEquals(AuthFixtures.USER_CODE, dc.userCode());
        assertEquals(URI.create("https://www.microsoft.com/link"), dc.verificationUri());
        assertEquals(900, dc.expiresInSeconds());
        assertEquals(5, dc.intervalSeconds());
        assertEquals(AuthFixtures.DEVICE_CODE, dc.deviceCode());
        assertTrue(dc.message().contains(AuthFixtures.USER_CODE));
        assertFalse(dc.toString().contains(AuthFixtures.DEVICE_CODE), dc.toString());
        assertTrue(dc.toString().contains(AuthFixtures.USER_CODE));

        TestHttpServer.Recorded r = lastRequest("/msa/devicecode");
        assertEquals("POST", r.method());
        assertTrue(r.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
        assertEquals(Map.of("client_id", AuthFixtures.CLIENT_ID, "scope", "XboxLive.signin offline_access"),
            AuthFixtures.form(r.bodyText()));
        assertEquals(Redactor.MASK, Redactor.redact(AuthFixtures.DEVICE_CODE));
    }

    @Test
    void acceptsOlderVerificationUrlSpellingAndClampsNumbers() throws Exception {
        fx.server.json("/msa/devicecode", "{\"user_code\":\"ABCD-EFGH\",\"device_code\":\"" + AuthFixtures.DEVICE_CODE + "\","
            + "\"verification_url\":\"https://microsoft.com/devicelogin\",\"expires_in\":\"900\",\"interval\":0}");
        DeviceCode dc = auth.startDeviceCode(CancellationToken.NONE);
        assertEquals("https://microsoft.com/devicelogin", dc.verificationUri().toString());
        assertEquals(900, dc.expiresInSeconds());
        assertEquals(1, dc.intervalSeconds());
        assertEquals("", dc.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "http://www.microsoft.com/link", "file:///C:/Windows/System32/calc.exe",
        "https://user:pw@evil.example/", "not a uri at all", "https:///nohost"})
    void refusesUnsafeVerificationLinks(String link) {
        fx.server.json("/msa/devicecode", "{\"user_code\":\"ABCD\",\"device_code\":\"" + AuthFixtures.DEVICE_CODE
            + "\",\"verification_uri\":\"" + link + "\",\"expires_in\":900,\"interval\":5}");
        fails(ErrorCode.INVALID_DATA, () -> auth.startDeviceCode(CancellationToken.NONE));
    }

    @Test
    void refusesStrangeUserCodesAndBrokenAnswers() {
        fx.server.json("/msa/devicecode", "{\"user_code\":\"AB\\nCD\",\"device_code\":\"x\",\"verification_uri\":\"https://www.microsoft.com/link\"}");
        fails(ErrorCode.INVALID_DATA, () -> auth.startDeviceCode(CancellationToken.NONE));
        fx.server.json("/msa/devicecode", "[1,2,3]");
        fails(ErrorCode.INVALID_DATA, () -> auth.startDeviceCode(CancellationToken.NONE));
        fx.server.json("/msa/devicecode", "{\"user_code\":\"ABCD\"}");
        fails(ErrorCode.INVALID_DATA, () -> auth.startDeviceCode(CancellationToken.NONE));
    }

    @Test
    void rejectedClientIdIsAConfigurationError() {
        fx.server.handle("/msa/devicecode", ex -> {
            Answer a = AuthFixtures.oauthError("invalid_client", 700016);
            TestHttpServer.respond(ex, a.status(), "application/json", a.body().getBytes());
        });
        LauncherException e = fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> auth.startDeviceCode(CancellationToken.NONE));
        assertEquals("auth.error.clientRejected", e.messageKey());
        assertTrue(e.userMessage().contains("invalid_client"));
    }

    // ---------------------------------------------------------------- polling

    @Test
    void pendingThenSuccess() throws Exception {
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        Instant start = fx.clock.instant();
        MsaTokens t = auth.pollDeviceCode(code(900, 5), CancellationToken.NONE);
        assertEquals(AuthFixtures.MSA_ACCESS, t.accessToken());
        assertEquals(AuthFixtures.MSA_REFRESH, t.refreshToken());
        assertEquals(start.plusSeconds(15).plusSeconds(3600), t.expiresAt());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5)), fx.sleeps);
        assertEquals(3, fx.devicePollCount.get());
        assertEquals(Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", AuthFixtures.CLIENT_ID,
            "device_code", AuthFixtures.DEVICE_CODE), AuthFixtures.form(lastRequest("/msa/token").bodyText()));
        assertFalse(t.toString().contains(AuthFixtures.MSA_ACCESS));
        assertFalse(t.toString().contains(AuthFixtures.MSA_REFRESH));
        assertEquals(Redactor.MASK, Redactor.redact(AuthFixtures.MSA_ACCESS));
        assertEquals(Redactor.MASK, Redactor.redact(AuthFixtures.MSA_REFRESH));
    }

    @Test
    void slowDownAddsFiveSeconds() throws Exception {
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        fx.devicePolls.add(AuthFixtures.oauthError("slow_down", 70016));
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        fx.devicePolls.add(AuthFixtures.oauthError("slow_down", 70016));
        auth.pollDeviceCode(code(900, 5), CancellationToken.NONE);
        assertEquals(List.of(5L, 5L, 10L, 10L, 15L), fx.sleeps.stream().map(Duration::toSeconds).toList());
    }

    @Test
    void declined() {
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_declined", 70019));
        LauncherException e = fails(ErrorCode.AUTH_FAILED, () -> auth.pollDeviceCode(code(900, 5), CancellationToken.NONE));
        assertEquals("auth.error.declined", e.messageKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired_token", "bad_verification_code"})
    void expiredCode(String error) {
        fx.devicePolls.add(AuthFixtures.oauthError(error, 70020));
        LauncherException e = fails(ErrorCode.AUTH_FAILED, () -> auth.pollDeviceCode(code(900, 5), CancellationToken.NONE));
        assertEquals("auth.error.codeExpired", e.messageKey());
    }

    @Test
    void givesUpWhenTheCodeOutlivesItsLifetime() {
        for (int i = 0; i < 50; i++) {
            fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        }
        LauncherException e = fails(ErrorCode.AUTH_FAILED, () -> auth.pollDeviceCode(code(12, 5), CancellationToken.NONE));
        assertEquals("auth.error.codeExpired", e.messageKey());
        assertEquals(2, fx.devicePollCount.get(), "polls at 5 s and 10 s, then the 12 s code is over");
    }

    @Test
    void cancellationStopsPolling() {
        for (int i = 0; i < 50; i++) {
            fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        }
        CancellationToken cancel = new CancellationToken();
        MicrosoftAuthService s = new MicrosoftAuthService(dev.shardlight.client.net.HttpService.forTests(), fx.endpoints,
            () -> AuthFixtures.CLIENT_ID, fx.clock, (d, c) -> {
                c.check();
                if (fx.devicePollCount.get() >= 2) {
                    cancel.cancel();
                    c.check();
                }
            });
        LauncherException e = fails(ErrorCode.CANCELLED, () -> s.pollDeviceCode(code(900, 5), cancel));
        assertTrue(e.isCancelled());
        assertEquals(2, fx.devicePollCount.get());
    }

    @Test
    void realSleeperStopsOnCancel() {
        CancellationToken cancel = new CancellationToken();
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // test thread
            }
            cancel.cancel();
        }).start();
        long t0 = System.nanoTime();
        assertThrows(LauncherException.class, () -> Sleeper.SYSTEM.sleep(Duration.ofSeconds(30), cancel));
        assertTrue(System.nanoTime() - t0 < Duration.ofSeconds(5).toNanos());
    }

    @Test
    void toleratesBriefServerTroubleWhilePolling() throws Exception {
        fx.devicePolls.add(new Answer(503, "<html>Service Unavailable</html>"));
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        fx.devicePolls.add(new Answer(502, ""));
        MsaTokens t = auth.pollDeviceCode(code(900, 5), CancellationToken.NONE);
        assertEquals(AuthFixtures.MSA_ACCESS, t.accessToken());
    }

    @Test
    void unknownPollErrorFails() {
        fx.devicePolls.add(AuthFixtures.oauthError("invalid_request", 900144));
        LauncherException e = fails(ErrorCode.AUTH_FAILED, () -> auth.pollDeviceCode(code(900, 5), CancellationToken.NONE));
        assertEquals("auth.error.microsoft", e.messageKey());
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refreshRotatesTokens() throws Exception {
        MsaTokens t = auth.refresh(AuthFixtures.MSA_REFRESH, CancellationToken.NONE);
        assertEquals(AuthFixtures.MSA_ACCESS_2, t.accessToken());
        assertEquals(AuthFixtures.MSA_REFRESH_2, t.refreshToken());
        assertEquals(Map.of("client_id", AuthFixtures.CLIENT_ID, "grant_type", "refresh_token",
                "refresh_token", AuthFixtures.MSA_REFRESH, "scope", "XboxLive.signin offline_access"),
            AuthFixtures.form(lastRequest("/msa/token").bodyText()));
    }

    @Test
    void refreshWithoutNewRefreshTokenKeepsTheOldOne() throws Exception {
        fx.refreshAnswer = () -> Answer.ok(AuthFixtures.msaTokens(AuthFixtures.MSA_ACCESS_2, null));
        assertEquals(AuthFixtures.MSA_REFRESH, auth.refresh(AuthFixtures.MSA_REFRESH, CancellationToken.NONE).refreshToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_grant", "interaction_required"})
    void deadRefreshTokenMeansSignInAgain(String error) {
        fx.refreshAnswer = () -> AuthFixtures.oauthError(error, 70000);
        LauncherException e = fails(ErrorCode.AUTH_EXPIRED, () -> auth.refresh(AuthFixtures.MSA_REFRESH, CancellationToken.NONE));
        assertEquals("auth.error.sessionExpired", e.messageKey());
    }

    @Test
    void blankRefreshTokenNeedsNoRequest() {
        fails(ErrorCode.AUTH_EXPIRED, () -> auth.refresh("  ", CancellationToken.NONE));
        fails(ErrorCode.AUTH_EXPIRED, () -> auth.refresh(null, CancellationToken.NONE));
        assertEquals(0, fx.refreshCount.get());
    }

    @Test
    void refreshServerErrorIsNotAnExpiry() {
        fx.refreshAnswer = () -> new Answer(500, "oops");
        LauncherException e = assertThrows(LauncherException.class, () -> auth.refresh(AuthFixtures.MSA_REFRESH, CancellationToken.NONE));
        assertTrue(e.code() == ErrorCode.HTTP_STATUS, e.toString());
    }

    @Test
    void networkFailureIsNetwork() {
        MicrosoftAuthService s = fx.service();
        fx.close();
        fails(ErrorCode.NETWORK, () -> s.refresh(AuthFixtures.MSA_REFRESH, CancellationToken.NONE));
    }

    // ---------------------------------------------------------------- Xbox Live and Minecraft

    private MsaTokens msa() {
        return new MsaTokens(AuthFixtures.MSA_ACCESS, AuthFixtures.MSA_REFRESH, fx.clock.instant().plusSeconds(3600));
    }

    @Test
    void completeLogin() throws Exception {
        MinecraftLogin login = auth.loginToMinecraft(msa(), CancellationToken.NONE);
        assertEquals(AuthFixtures.UUID, login.uuid());
        assertEquals(AuthFixtures.NAME, login.username());
        assertEquals(AuthFixtures.MC_TOKEN, login.accessToken());
        assertEquals(fx.clock.instant().plusSeconds(86400), login.expiresAt());
        assertEquals(AuthFixtures.XUID, login.xuid());
        assertTrue(login.ownsGame());
        assertEquals(AuthFixtures.SKIN_URL, login.skinUrl());
        assertEquals("classic", login.skinVariant());
        for (String token : AuthFixtures.ALL_TOKENS) {
            assertFalse(login.toString().contains(token), login.toString());
        }

        // a) Xbox Live user authentication
        TestHttpServer.Recorded xbl = lastRequest("/xbl/user/authenticate");
        assertEquals("POST", xbl.method());
        assertTrue(xbl.header("Content-Type").startsWith("application/json"));
        assertEquals("application/json", xbl.header("Accept"));
        assertEquals("1", xbl.header("x-xbl-contract-version"));
        JsonObject b = json(xbl);
        assertEquals("RPS", b.getAsJsonObject("Properties").get("AuthMethod").getAsString());
        assertEquals("user.auth.xboxlive.com", b.getAsJsonObject("Properties").get("SiteName").getAsString());
        assertEquals("d=" + AuthFixtures.MSA_ACCESS, b.getAsJsonObject("Properties").get("RpsTicket").getAsString());
        assertEquals("http://auth.xboxlive.com", b.get("RelyingParty").getAsString());
        assertEquals("JWT", b.get("TokenType").getAsString());
        assertFalse(xbl.bodyText().contains("\\u003d"), "no HTML escaping in the JSON body");

        // b) + c) XSTS for Minecraft services, then for xboxlive.com (xuid)
        List<TestHttpServer.Recorded> xsts = fx.server.requests().stream().filter(r -> r.path().equals("/xsts/xsts/authorize")).toList();
        assertEquals(2, xsts.size());
        JsonObject x1 = json(xsts.get(0));
        assertEquals("RETAIL", x1.getAsJsonObject("Properties").get("SandboxId").getAsString());
        assertEquals(AuthFixtures.XBL_TOKEN, x1.getAsJsonObject("Properties").getAsJsonArray("UserTokens").get(0).getAsString());
        assertEquals("rp://api.minecraftservices.com/", x1.get("RelyingParty").getAsString());
        assertEquals("JWT", x1.get("TokenType").getAsString());
        assertEquals("http://xboxlive.com", json(xsts.get(1)).get("RelyingParty").getAsString());

        // d) Minecraft login
        TestHttpServer.Recorded mc = lastRequest("/mcs/authentication/login_with_xbox");
        assertEquals("XBL3.0 x=" + AuthFixtures.UHS + ";" + AuthFixtures.XSTS_MC_TOKEN, json(mc).get("identityToken").getAsString());

        // e) + f) entitlements and profile with the bearer token
        TestHttpServer.Recorded licence = lastRequest("/mcs/entitlements/license");
        assertEquals("Bearer " + AuthFixtures.MC_TOKEN, licence.header("Authorization"));
        assertTrue(licence.query().startsWith("requestId="), licence.query());
        assertEquals(0, fx.server.hits("/mcs/entitlements/mcstore"), "the older endpoint is only a fallback");
        assertEquals("GET", lastRequest("/mcs/minecraft/profile").method());
        assertEquals("Bearer " + AuthFixtures.MC_TOKEN, lastRequest("/mcs/minecraft/profile").header("Authorization"));

        for (String t : List.of(AuthFixtures.XBL_TOKEN, AuthFixtures.XSTS_MC_TOKEN, AuthFixtures.XSTS_XBOX_TOKEN, AuthFixtures.MC_TOKEN)) {
            assertFalse(Redactor.redact("value " + t).contains(t), "registered with the redactor: " + t);
        }
    }

    static Stream<Arguments> xerrs() {
        return Stream.of(
            Arguments.of(2148916233L, ErrorCode.AUTH_NO_XBOX_ACCOUNT, "auth.error.xbox.noAccount"),
            Arguments.of(2148916234L, ErrorCode.AUTH_NO_XBOX_ACCOUNT, "auth.error.xbox.terms"),
            Arguments.of(2148916235L, ErrorCode.AUTH_REGION_BLOCKED, "auth.error.xbox.region"),
            Arguments.of(2148916236L, ErrorCode.AUTH_ADULT_VERIFICATION, "auth.error.xbox.adultVerification"),
            Arguments.of(2148916237L, ErrorCode.AUTH_ADULT_VERIFICATION, "auth.error.xbox.adultVerification"),
            Arguments.of(2148916238L, ErrorCode.AUTH_CHILD_ACCOUNT, "auth.error.xbox.child"),
            Arguments.of(2148916229L, ErrorCode.AUTH_CHILD_ACCOUNT, "auth.error.xbox.parentalControls"),
            Arguments.of(2148916227L, ErrorCode.AUTH_FAILED, "auth.error.xbox.banned"),
            Arguments.of(2148916999L, ErrorCode.AUTH_FAILED, "auth.error.xbox.other"));
    }

    @ParameterizedTest
    @MethodSource("xerrs")
    void xstsErrorsAreExplained(long xerr, ErrorCode code, String key) {
        fx.xstsError = xerr;
        LauncherException e = fails(code, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        assertEquals(key, e.messageKey());
        assertTrue(I18n.has(key), key);
        assertFalse(e.userMessage().contains("''"));
        assertEquals(0, fx.loginCount.get(), "no Minecraft login after an XSTS refusal");
    }

    @Test
    void xerrAsStringIsUnderstood() {
        fx.server.handle("/xsts/xsts/authorize", ex -> TestHttpServer.respond(ex, 401, "application/json",
            "{\"Identity\":\"0\",\"XErr\":\"2148916238\",\"Message\":\"\",\"Redirect\":\"https://start.ui.xboxlive.com/AddChildToFamily\"}".getBytes()));
        fails(ErrorCode.AUTH_CHILD_ACCOUNT, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
    }

    @Test
    void xboxRefusalWithoutBody() {
        fx.server.handle("/xbl/user/authenticate", ex -> TestHttpServer.respond(ex, 400, ""));
        LauncherException e = fails(ErrorCode.AUTH_FAILED, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        assertEquals("auth.error.xboxAuth", e.messageKey());
    }

    @Test
    void brokenXboxAnswersAreInvalidData() {
        fx.server.json("/xbl/user/authenticate", "{\"Token\":\"" + AuthFixtures.XBL_TOKEN + "\",\"DisplayClaims\":{\"xui\":[]}}");
        fails(ErrorCode.INVALID_DATA, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        fx.server.json("/xbl/user/authenticate", "<html>");
        fails(ErrorCode.INVALID_DATA, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
    }

    @Test
    void xuidIsOptional() throws Exception {
        fx.server.handle("/xsts/xsts/authorize", ex -> {
            String body = fx.bodyOf(ex);
            if (body.contains("rp://api.minecraftservices.com/")) {
                TestHttpServer.respond(ex, 200, "application/json",
                    AuthFixtures.xboxToken(AuthFixtures.XSTS_MC_TOKEN, "{\"uhs\":\"" + AuthFixtures.UHS + "\"}").getBytes());
            } else {
                TestHttpServer.respond(ex, 401, "application/json", AuthFixtures.xerr(2148916233L).getBytes());
            }
        });
        MinecraftLogin login = auth.loginToMinecraft(msa(), CancellationToken.NONE);
        assertNull(login.xuid());
        assertEquals(AuthFixtures.UUID, login.uuid());
    }

    @Test
    void notOwned() {
        fx.entitlementsJson = AuthFixtures.entitlements(false);
        fx.profileJson = null;
        LauncherException e = fails(ErrorCode.AUTH_NOT_OWNED, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        assertEquals("auth.error.notOwned", e.messageKey());
        assertTrue(e.userMessage().contains("Java Edition"));
    }

    @Test
    void entitlementWithoutProfile() {
        fx.profileJson = null;
        LauncherException e = fails(ErrorCode.AUTH_NOT_OWNED, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        assertEquals("auth.error.noProfile", e.messageKey());
        assertTrue(e.userMessage().contains("minecraft.net"));
    }

    @Test
    void gamePassAccountWithProfileButNoItems() throws Exception {
        fx.entitlementsJson = AuthFixtures.entitlements(false);
        MinecraftLogin login = auth.loginToMinecraft(msa(), CancellationToken.NONE);
        assertTrue(login.ownsGame());
        assertEquals(AuthFixtures.NAME, login.username());
    }

    @Test
    void entitlementOutageDoesNotBlockAPlayerWithAProfile() throws Exception {
        fx.server.text("/mcs/entitlements/license", 503, "busy");
        assertTrue(auth.loginToMinecraft(msa(), CancellationToken.NONE).ownsGame());
        fx.profileJson = null;
        LauncherException e = assertThrows(LauncherException.class, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        assertEquals(ErrorCode.HTTP_STATUS, e.code(), "an outage isn't reported as 'not owned'");
    }

    @Test
    void olderEntitlementEndpointIsAskedWhenTheLicenceOneIsMissing() throws Exception {
        fx.server.text("/mcs/entitlements/license", 404, "");
        fx.entitlementsJson = AuthFixtures.entitlements(true);
        assertTrue(auth.loginToMinecraft(msa(), CancellationToken.NONE).ownsGame());
        assertEquals(1, fx.server.hits("/mcs/entitlements/license"));
        assertEquals(1, fx.server.hits("/mcs/entitlements/mcstore"));
        List<String> asked = fx.server.requests().stream().map(TestHttpServer.Recorded::path)
            .filter(path -> path.startsWith("/mcs/entitlements/")).toList();
        assertEquals(List.of("/mcs/entitlements/license", "/mcs/entitlements/mcstore"), asked, "the licence endpoint comes first");
        assertEquals("Bearer " + AuthFixtures.MC_TOKEN, lastRequest("/mcs/entitlements/mcstore").header("Authorization"));
    }

    @Test
    void unapprovedAppIsAConfigurationError() {
        fx.server.handle("/mcs/authentication/login_with_xbox", ex -> TestHttpServer.respond(ex, 403, "application/json",
            "{\"path\":\"/authentication/login_with_xbox\",\"errorMessage\":\"Invalid app registration, see https://aka.ms/AppRegInfo for more information\"}".getBytes()));
        LauncherException e = fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        assertEquals("auth.error.appNotApproved", e.messageKey());
        assertTrue(e.userMessage().contains("aka.ms/mce-reviewappid"));
    }

    @Test
    void minecraftLoginRefused() {
        fx.server.text("/mcs/authentication/login_with_xbox", 401, "");
        LauncherException e = fails(ErrorCode.AUTH_FAILED, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
        assertEquals("auth.error.minecraftLogin", e.messageKey());
    }

    @Test
    void minecraftRateLimit() {
        fx.server.handle("/mcs/authentication/login_with_xbox", ex -> {
            ex.getResponseHeaders().add("Retry-After", "0");
            TestHttpServer.respond(ex, 429, "");
        });
        fails(ErrorCode.RATE_LIMITED, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
    }

    @Test
    void profileWithDashedUuidAndSlimSkin() throws Exception {
        fx.profileJson = "{\"id\":\"069A79F4-44E9-4726-A5BE-FCA90E38AAF5\",\"name\":\"Notch\",\"skins\":["
            + "{\"id\":\"a\",\"state\":\"INACTIVE\",\"url\":\"http://textures.minecraft.net/texture/old\",\"variant\":\"CLASSIC\"},"
            + "{\"id\":\"b\",\"state\":\"ACTIVE\",\"url\":\"https://textures.minecraft.net/texture/new\",\"variant\":\"SLIM\"}],\"capes\":[]}";
        MinecraftLogin login = auth.loginToMinecraft(msa(), CancellationToken.NONE);
        assertEquals(AuthFixtures.UUID, login.uuid());
        assertEquals("https://textures.minecraft.net/texture/new", login.skinUrl());
        assertEquals("slim", login.skinVariant());
    }

    @Test
    void profileWithoutSkins() throws Exception {
        fx.profileJson = "{\"id\":\"" + AuthFixtures.UUID + "\",\"name\":\"Notch\"}";
        MinecraftLogin login = auth.loginToMinecraft(msa(), CancellationToken.NONE);
        assertNull(login.skinUrl());
        assertNull(login.skinVariant());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"id\":\"../../../etc/passwd\",\"name\":\"Notch\"}",
        "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch --accessToken x\"}",
        "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"a\\nb\"}",
        "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"\"}",
        "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}",
        "{\"name\":\"Notch\"}",
        "[]"})
    void refusesMaliciousProfiles(String profile) {
        fx.profileJson = profile;
        fails(ErrorCode.INVALID_DATA, () -> auth.loginToMinecraft(msa(), CancellationToken.NONE));
    }

    @Test
    void dropsNonWebSkinUrls() throws Exception {
        fx.profileJson = "{\"id\":\"" + AuthFixtures.UUID + "\",\"name\":\"Notch\",\"skins\":[{\"state\":\"ACTIVE\","
            + "\"url\":\"file:///C:/Windows/win.ini\",\"variant\":\"WEIRD\"}]}";
        MinecraftLogin login = auth.loginToMinecraft(msa(), CancellationToken.NONE);
        assertNull(login.skinUrl());
        assertNull(login.skinVariant());
    }

    @Test
    void cancelledBeforeLogin() {
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        fails(ErrorCode.CANCELLED, () -> auth.loginToMinecraft(msa(), cancel));
    }
}
