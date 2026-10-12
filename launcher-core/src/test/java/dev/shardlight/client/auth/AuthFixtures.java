package dev.shardlight.client.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.testutil.TestHttpServer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A fake Microsoft identity platform, Xbox Live and Minecraft services on {@link TestHttpServer}, answering
 * with the same JSON shapes the real services use. Every handler can be replaced per test with
 * {@code server.handle(path, ...)}; the token endpoint is scripted through {@link #devicePolls} and
 * {@link #refreshAnswer}.
 */
public final class AuthFixtures implements AutoCloseable {
    public static final String CLIENT_ID = "00000000-1111-2222-3333-444444444444";
    public static final String DEVICE_CODE = "DAQABAAEAAAD-fake-device-code-0123456789abcdefABCDEF";
    public static final String USER_CODE = "FQK5HW3UF";
    public static final String MSA_ACCESS = "EwBwA8l6BAAUfakeMsaAccessToken0123456789abcdefABCDEF";
    public static final String MSA_ACCESS_2 = "EwBwA8l6BAAUfakeMsaAccessTokenSecond9876543210zyxw";
    public static final String MSA_REFRESH = "M.C507_BAY.0.U.-CfakeRefreshTokenOne0123456789abcdefghij";
    public static final String MSA_REFRESH_2 = "M.C507_BAY.0.U.-CfakeRefreshTokenTwo9876543210zyxwvutsr";
    public static final String XBL_TOKEN = "EwAIA+pvBAAUKods63Ys1fGlwiccIFJ+9u-fakeXblUserToken-0123456789";
    public static final String XSTS_MC_TOKEN = "eyJlbmMiOiJBMTI4Q0JDK0hTMjU2fakeXstsMinecraftToken0123456789";
    public static final String XSTS_XBOX_TOKEN = "eyJlbmMiOiJBMTI4Q0JDK0hTMjU2fakeXstsXboxliveToken0123456789";
    public static final String UHS = "12345678901234567890";
    public static final String XUID = "2535416790837978";
    public static final String MC_TOKEN = "eyJraWQiOiJhYzg0YSIsImFsZyI6IkhTMjU2In0.eyJ4dWlkIjoiMjUzNTQxNjc5MDgzNzk3OCJ9.fakeSignatureOne_abc";
    public static final String MC_TOKEN_2 = "eyJraWQiOiJhYzg0YSIsImFsZyI6IkhTMjU2In0.eyJ4dWlkIjoiMjUzNTQxNjc5MDgzNzk3OSJ9.fakeSignatureTwo_xyz";
    public static final String UUID = "069a79f444e94726a5befca90e38aaf5";
    public static final String NAME = "Notch";
    public static final String SKIN_URL = "http://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680";

    /** every secret value the fake services hand out */
    public static final List<String> ALL_TOKENS = List.of(DEVICE_CODE, MSA_ACCESS, MSA_ACCESS_2, MSA_REFRESH, MSA_REFRESH_2,
        XBL_TOKEN, XSTS_MC_TOKEN, XSTS_XBOX_TOKEN, MC_TOKEN, MC_TOKEN_2);

    /** a scripted HTTP answer */
    public record Answer(int status, String body) {
        public static Answer ok(String body) {
            return new Answer(200, body);
        }
    }

    public final TestHttpServer server = new TestHttpServer();
    public final Endpoints endpoints = Endpoints.local(server.base());
    public final FakeClock clock = new FakeClock(Instant.parse("2026-10-12T10:00:00Z"));
    /** every wait the service asked for */
    public final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    /** answers to device-code polls, in order; when empty a poll succeeds */
    public final Deque<Answer> devicePolls = new ArrayDeque<>();
    /** the answer to a refresh_token grant */
    public volatile Supplier<Answer> refreshAnswer = () -> Answer.ok(msaTokens(MSA_ACCESS_2, MSA_REFRESH_2));
    /** the Minecraft token login_with_xbox hands out next */
    public volatile String nextMcToken = MC_TOKEN;
    /** an XErr for the Minecraft XSTS call, or null */
    public volatile Long xstsError;
    public volatile String profileJson = profile(UUID, NAME);
    public volatile String entitlementsJson = entitlements(true);
    public final AtomicInteger devicePollCount = new AtomicInteger();
    public final AtomicInteger refreshCount = new AtomicInteger();
    public final AtomicInteger loginCount = new AtomicInteger();

    public AuthFixtures() {
        server.handle("/msa/devicecode", ex -> TestHttpServer.respond(ex, 200, "application/json", bytes(deviceCodeJson(900, 5))));
        server.handle("/msa/token", ex -> {
            Map<String, String> form = form(bodyOf(ex));
            Answer a;
            if ("refresh_token".equals(form.get("grant_type"))) {
                refreshCount.incrementAndGet();
                a = refreshAnswer.get();
            } else {
                devicePollCount.incrementAndGet();
                synchronized (devicePolls) {
                    a = devicePolls.isEmpty() ? Answer.ok(msaTokens(MSA_ACCESS, MSA_REFRESH)) : devicePolls.poll();
                }
            }
            TestHttpServer.respond(ex, a.status(), "application/json; charset=utf-8", bytes(a.body()));
        });
        server.handle("/xbl/user/authenticate", ex -> TestHttpServer.respond(ex, 200, "application/json",
            bytes(xboxToken(XBL_TOKEN, "{\"uhs\":\"" + UHS + "\"}"))));
        server.handle("/xsts/xsts/authorize", ex -> {
            JsonObject body = JsonParser.parseString(bodyOf(ex)).getAsJsonObject();
            String rp = body.get("RelyingParty").getAsString();
            if (rp.equals("rp://api.minecraftservices.com/")) {
                Long err = xstsError;
                if (err != null) {
                    TestHttpServer.respond(ex, 401, "application/json", bytes(xerr(err)));
                } else {
                    TestHttpServer.respond(ex, 200, "application/json", bytes(xboxToken(XSTS_MC_TOKEN, "{\"uhs\":\"" + UHS + "\"}")));
                }
            } else if (rp.equals("http://xboxlive.com")) {
                TestHttpServer.respond(ex, 200, "application/json", bytes(xboxToken(XSTS_XBOX_TOKEN,
                    "{\"gtg\":\"" + NAME + "\",\"xid\":\"" + XUID + "\",\"uhs\":\"" + UHS + "\",\"agg\":\"Adult\",\"usr\":\"234\",\"utr\":\"190\",\"prv\":\"185 186 187 188 191 192 193 195 196 198 199 200 201 203 204 211 214 215 216 217 220 224 227 228 235 238 245 247 249 252 254 255\"}")));
            } else {
                TestHttpServer.respond(ex, 400, "");
            }
        });
        server.handle("/mcs/authentication/login_with_xbox", ex -> {
            loginCount.incrementAndGet();
            TestHttpServer.respond(ex, 200, "application/json", bytes("{\"username\":\"b1a7c3f2-1234-4cde-9abc-1234567890ab\","
                + "\"roles\":[],\"metadata\":{},\"access_token\":\"" + nextMcToken + "\",\"expires_in\":86400,\"token_type\":\"Bearer\"}"));
        });
        server.handle("/mcs/entitlements/mcstore", ex -> TestHttpServer.respond(ex, 200, "application/json", bytes(entitlementsJson)));
        server.handle("/mcs/minecraft/profile", ex -> {
            String p = profileJson;
            if (p == null) {
                TestHttpServer.respond(ex, 404, "application/json", bytes("{\"path\":\"/minecraft/profile\",\"errorType\":\"NOT_FOUND\","
                    + "\"error\":\"NOT_FOUND\",\"errorMessage\":\"The server has not found anything matching the request URI\","
                    + "\"developerMessage\":\"The server has not found anything matching the request URI\"}"));
            } else {
                TestHttpServer.respond(ex, 200, "application/json", bytes(p));
            }
        });
    }

    /**
     * The body of the request being handled. {@link TestHttpServer} reads and records every body before
     * calling a handler, so it is taken from the newest recorded request to the same path.
     */
    public String bodyOf(com.sun.net.httpserver.HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        List<TestHttpServer.Recorded> all = server.requests();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).path().equals(path)) {
                return all.get(i).bodyText();
            }
        }
        return "";
    }

    /** a sleeper that records the wait and moves the fake clock instead of waiting */
    public Sleeper sleeper() {
        return (d, cancel) -> {
            cancel.check();
            sleeps.add(d);
            clock.advance(d);
        };
    }

    public MicrosoftAuthService service() {
        return service(() -> CLIENT_ID);
    }

    public MicrosoftAuthService service(Supplier<String> clientId) {
        return new MicrosoftAuthService(HttpService.forTests(), endpoints, clientId, clock, sleeper());
    }

    // ---------------------------------------------------------------- JSON in the real services' shapes

    public static String deviceCodeJson(int expiresIn, int interval) {
        return "{\"user_code\":\"" + USER_CODE + "\",\"device_code\":\"" + DEVICE_CODE + "\","
            + "\"verification_uri\":\"https://www.microsoft.com/link\",\"expires_in\":" + expiresIn + ",\"interval\":" + interval + ","
            + "\"message\":\"To sign in, use a web browser to open the page https://www.microsoft.com/link and enter the code "
            + USER_CODE + " to authenticate.\"}";
    }

    public static String msaTokens(String access, String refresh) {
        return "{\"token_type\":\"Bearer\",\"scope\":\"XboxLive.signin XboxLive.offline_access\",\"expires_in\":3600,"
            + "\"ext_expires_in\":3600,\"access_token\":\"" + access + "\""
            + (refresh == null ? "" : ",\"refresh_token\":\"" + refresh + "\"") + "}";
    }

    /** an OAuth error answer as Microsoft sends it (HTTP 400) */
    public static Answer oauthError(String error, int aadsts) {
        return new Answer(400, "{\"error\":\"" + error + "\",\"error_description\":\"AADSTS" + aadsts + ": " + error
            + ". Trace ID: 0b5c8a5e-0000-4000-8000-000000000000 Correlation ID: 3a1d2c4b-0000-4000-8000-000000000000"
            + " Timestamp: 2026-10-12 10:00:00Z\",\"error_codes\":[" + aadsts + "],\"timestamp\":\"2026-10-12 10:00:00Z\","
            + "\"trace_id\":\"0b5c8a5e-0000-4000-8000-000000000000\",\"correlation_id\":\"3a1d2c4b-0000-4000-8000-000000000000\","
            + "\"error_uri\":\"https://login.microsoftonline.com/error?code=" + aadsts + "\"}");
    }

    public static String xboxToken(String token, String xuiClaim) {
        return "{\"IssueInstant\":\"2026-10-12T10:00:00.0000000Z\",\"NotAfter\":\"2026-10-26T10:00:00.0000000Z\","
            + "\"Token\":\"" + token + "\",\"DisplayClaims\":{\"xui\":[" + xuiClaim + "]}}";
    }

    public static String xerr(long xerr) {
        return "{\"Identity\":\"0\",\"XErr\":" + xerr + ",\"Message\":\"\",\"Redirect\":\"https://start.ui.xboxlive.com/CreateAccount\"}";
    }

    public static String entitlements(boolean owns) {
        String items = owns
            ? "{\"name\":\"product_minecraft\",\"signature\":\"eyJhbGciOiJSUzI1NiJ9.eyJzaWduZXIiOiJtb2phbmcifQ.c2lnbmF0dXJl\"},"
            + "{\"name\":\"game_minecraft\",\"signature\":\"eyJhbGciOiJSUzI1NiJ9.eyJzaWduZXIiOiJtb2phbmcifQ.c2lnbmF0dXJl\"}"
            : "";
        return "{\"items\":[" + items + "],\"signature\":\"eyJhbGciOiJSUzI1NiJ9.eyJpdGVtcyI6W119.c2ln\",\"keyId\":\"1\","
            + "\"requestId\":\"2f6c0d2c-0000-4000-8000-000000000000\"}";
    }

    public static String profile(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"skins\":[{\"id\":\"6a6e65e5-76dd-4c3c-a625-162924514568\","
            + "\"state\":\"ACTIVE\",\"url\":\"" + SKIN_URL + "\","
            + "\"textureKey\":\"292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680\",\"variant\":\"CLASSIC\"}],"
            + "\"capes\":[{\"id\":\"1981aad3-73f0-4ad1-a2e5-1e4d1a3b0f57\",\"state\":\"INACTIVE\","
            + "\"url\":\"http://textures.minecraft.net/texture/2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933\","
            + "\"alias\":\"Migrator\"}],\"profileActions\":{}}";
    }

    /** decode an application/x-www-form-urlencoded body */
    public static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.close();
        }
    }
}
