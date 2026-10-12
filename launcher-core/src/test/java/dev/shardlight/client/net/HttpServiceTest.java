package dev.shardlight.client.net;

import com.google.gson.JsonObject;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpServiceTest {
    @Test
    void sendsUserAgentAndParsesJson() throws Exception {
        try (TestHttpServer s = new TestHttpServer()) {
            s.json("/x", "{\"a\": 3}");
            JsonObject o = HttpService.forTests().getJson(s.uri("/x"), JsonObject.class, CancellationToken.NONE);
            assertEquals(3, o.get("a").getAsInt());
            assertTrue(s.requests().get(0).header("User-Agent").startsWith("Janxirus/ShardlightClient/"));
        }
    }

    @Test
    void retriesRateLimitAndHonoursRetryAfter() throws Exception {
        try (TestHttpServer s = new TestHttpServer()) {
            AtomicInteger n = new AtomicInteger();
            s.handle("/r", ex -> {
                if (n.incrementAndGet() == 1) {
                    ex.getResponseHeaders().add("Retry-After", "0");
                    TestHttpServer.respond(ex, 429, "slow down");
                } else {
                    TestHttpServer.respond(ex, 200, "application/json", "{}".getBytes());
                }
            });
            HttpService.forTests().getJson(s.uri("/r"), JsonObject.class, CancellationToken.NONE);
            assertEquals(2, n.get());
        }
    }

    @Test
    void mapsStatusCodes() {
        try (TestHttpServer s = new TestHttpServer()) {
            LauncherException e = assertThrows(LauncherException.class,
                () -> HttpService.forTests().getText(s.uri("/nothing"), CancellationToken.NONE));
            assertEquals(ErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    void rejectsPlainHttpOutsideLoopback() {
        LauncherException e = assertThrows(LauncherException.class,
            () -> HttpService.forTests().getText(URI.create("http://example.com/"), CancellationToken.NONE));
        assertEquals(ErrorCode.UNSAFE_PATH, e.code());
    }

    @Test
    void buildsUris() {
        URI u = HttpService.uri(URI.create("https://api.modrinth.com/v2/"), "/search", Map.of("query", "a b"));
        assertEquals("https://api.modrinth.com/v2/search?query=a+b", u.toString());
    }
}
