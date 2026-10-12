package dev.shardlight.client.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny HTTP server on 127.0.0.1 for tests. Register fixed responses or handlers per path; every request
 * is recorded. Pair it with {@code HttpService.forTests()} and {@code Endpoints.local(server.base())}.
 *
 * <pre>
 * try (TestHttpServer s = new TestHttpServer()) {
 *     s.json("/mojang/version_manifest_v2.json", "{...}");
 *     s.bytes("/files/a.jar", data);
 *     s.handle("/msa/token", ex -> s.respond(ex, 400, "{\"error\":\"authorization_pending\"}"));
 * }
 * </pre>
 */
public final class TestHttpServer implements AutoCloseable {
    /** a request as the server saw it */
    public record Recorded(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    private final HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public TestHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            requests.add(new Recorded(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(),
                Map.copyOf(ex.getRequestHeaders()), body));
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            Handler h = handlers.get(path);
            try {
                if (h == null) {
                    respond(ex, 404, "not found: " + path);
                } else {
                    h.handle(ex);
                }
            } catch (IOException | RuntimeException e) {
                try {
                    respond(ex, 500, String.valueOf(e));
                } catch (IOException ignored) {
                    // client went away
                }
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    /** base URI, ending with "/" */
    public URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public URI uri(String path) {
        return base().resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    public TestHttpServer handle(String path, Handler h) {
        handlers.put(path, h);
        return this;
    }

    public TestHttpServer json(String path, String json) {
        return handle(path, ex -> respond(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8)));
    }

    public TestHttpServer text(String path, int status, String text) {
        return handle(path, ex -> respond(ex, status, text));
    }

    /** serves bytes and honours Range requests (for resume tests) */
    public TestHttpServer bytes(String path, byte[] data) {
        return handle(path, ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                long from = Long.parseLong(range.substring(6, range.indexOf('-')));
                if (from >= data.length) {
                    respond(ex, 416, "range not satisfiable");
                    return;
                }
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (data.length - 1) + "/" + data.length);
                respond(ex, 206, "application/octet-stream", java.util.Arrays.copyOfRange(data, (int) from, data.length));
            } else {
                respond(ex, 200, "application/octet-stream", data);
            }
        });
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    public static void respond(HttpExchange ex, int status, String text) throws IOException {
        respond(ex, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    public static void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
