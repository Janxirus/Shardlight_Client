package dev.shardlight.client.net;

import com.google.gson.JsonParseException;
import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * One HTTP client for the whole launcher: a proper User-Agent, HTTPS only (plain HTTP is allowed for
 * loopback test servers when enabled), per-host rate limits, and retries with exponential backoff for
 * network errors, HTTP 429 and 5xx answers (honouring {@code Retry-After}).
 */
public final class HttpService {
    private static final Logger LOG = Logger.getLogger(HttpService.class.getName());

    /** a finished response */
    public record Response(int status, Map<String, List<String>> headers, byte[] body, URI uri) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public Optional<String> header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return Optional.of(e.getValue().get(0));
                }
            }
            return Optional.empty();
        }

        public <T> T json(Type type) throws LauncherException {
            try {
                T v = Json.read(text(), type);
                if (v == null) {
                    throw new JsonParseException("empty body");
                }
                return v;
            } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
                throw new LauncherException(ErrorCode.INVALID_DATA, e, "error.invalidResponse", uri.getHost());
            }
        }

        /** throw a {@link LauncherException} that matches the status unless it is 2xx */
        public Response requireOk() throws LauncherException {
            if (ok()) {
                return this;
            }
            throw statusError(status, uri);
        }
    }

    /** a request; {@code body} null for GET */
    public record Request(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout,
                          boolean retryable) {
        public static Request get(URI uri) {
            return new Request("GET", uri, Map.of(), null, Duration.ofSeconds(30), true);
        }

        public Request withHeaders(Map<String, String> extra) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.putAll(extra);
            return new Request(method, uri, h, body, timeout, retryable);
        }

        public Request withTimeout(Duration t) {
            return new Request(method, uri, headers, body, t, retryable);
        }

        public Request retryable(boolean r) {
            return new Request(method, uri, headers, body, timeout, r);
        }
    }

    private final HttpClient client;
    private final String userAgent;
    private final boolean allowLoopbackHttp;
    private final int maxAttempts;
    private final long baseBackoffMillis;
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public HttpService(HttpClient client, String userAgent, boolean allowLoopbackHttp, int maxAttempts, long baseBackoffMillis) {
        this.client = client;
        this.userAgent = userAgent;
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMillis = Math.max(0, baseBackoffMillis);
    }

    /** the production client: system proxy, redirects followed, 15 s connect timeout */
    public static HttpService create() {
        return new HttpService(defaultClient(), BuildInfo.userAgent(), false, 4, 1000);
    }

    /** a client for tests that may talk plain HTTP to 127.0.0.1 and retries quickly */
    public static HttpService forTests() {
        return new HttpService(defaultClient(), BuildInfo.userAgent(), true, 3, 10);
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(ProxySelector.getDefault())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    public HttpClient client() {
        return client;
    }

    public String userAgent() {
        return userAgent;
    }

    /** limit requests to {@code host} (for example Modrinth: 300 per minute) */
    public void setRateLimit(String host, RateLimiter limiter) {
        limiters.put(host.toLowerCase(Locale.ROOT), limiter);
    }

    public Optional<RateLimiter> rateLimiter(String host) {
        return Optional.ofNullable(host == null ? null : limiters.get(host.toLowerCase(Locale.ROOT)));
    }

    /** throws {@link ErrorCode#UNSAFE_PATH} for anything but https (or loopback http in tests) */
    public void validate(URI uri) throws LauncherException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.badUrl", uri.toString());
        }
        if (scheme.equals("https")) {
            return;
        }
        if (scheme.equals("http") && allowLoopbackHttp && isLoopback(uri.getHost())) {
            return;
        }
        throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.insecureUrl", uri.toString());
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
    }

    /**
     * Send with retries. Returns the final response whatever its status (except that 429/5xx are retried
     * first), so callers that need error bodies (Xbox sign-in) can read them.
     */
    public Response execute(Request req, CancellationToken cancel) throws LauncherException {
        validate(req.uri());
        LauncherException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            cancel.check();
            Optional<RateLimiter> rl = rateLimiter(req.uri().getHost());
            if (rl.isPresent()) {
                rl.get().acquire(cancel);
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(req.uri())
                .timeout(req.timeout() == null ? Duration.ofSeconds(30) : req.timeout())
                .header("User-Agent", userAgent);
            req.headers().forEach(b::header);
            if (req.body() == null) {
                b.method(req.method(), HttpRequest.BodyPublishers.noBody());
            } else {
                b.method(req.method(), HttpRequest.BodyPublishers.ofByteArray(req.body()));
            }
            try {
                HttpResponse<byte[]> r = sendInterruptibly(b.build(), HttpResponse.BodyHandlers.ofByteArray(), cancel);
                Response resp = new Response(r.statusCode(), r.headers().map(), r.body() == null ? new byte[0] : r.body(), req.uri());
                noteRateHeaders(resp);
                boolean retry = req.retryable() && (resp.status() == 429 || resp.status() == 500 || resp.status() == 502
                    || resp.status() == 503 || resp.status() == 504);
                if (!retry || attempt == maxAttempts) {
                    return resp;
                }
                last = statusError(resp.status(), req.uri());
                sleep(backoff(attempt, resp), cancel);
            } catch (IOException e) {
                last = new LauncherException(ErrorCode.NETWORK, e, "error.network", req.uri().getHost(), String.valueOf(e.getMessage()));
                if (!req.retryable() || attempt == maxAttempts) {
                    throw last;
                }
                LOG.fine(() -> "Retrying " + req.uri().getHost() + " after " + e);
                sleep(backoff(attempt, null), cancel);
            }
        }
        throw last == null ? LauncherException.internal("no attempt made") : last;
    }

    private <T> HttpResponse<T> sendInterruptibly(HttpRequest r, HttpResponse.BodyHandler<T> h, CancellationToken cancel)
        throws IOException, LauncherException {
        var future = client.sendAsync(r, h);
        cancel.onCancel(() -> future.cancel(true));
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw LauncherException.cancelled();
        } catch (java.util.concurrent.CancellationException e) {
            throw LauncherException.cancelled();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw new IOException(c);
        }
    }

    /** open a streaming GET (downloads); the caller closes the body. No retries here. */
    public HttpResponse<InputStream> openStream(URI uri, Map<String, String> headers, Duration timeout, CancellationToken cancel)
        throws LauncherException {
        validate(uri);
        Optional<RateLimiter> rl = rateLimiter(uri.getHost());
        if (rl.isPresent()) {
            rl.get().acquire(cancel);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", userAgent).GET();
        headers.forEach(b::header);
        try {
            return sendInterruptibly(b.build(), HttpResponse.BodyHandlers.ofInputStream(), cancel);
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.NETWORK, e, "error.network", uri.getHost(), String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- convenience

    public String getText(URI uri, CancellationToken cancel) throws LauncherException {
        return execute(Request.get(uri), cancel).requireOk().text();
    }

    public <T> T getJson(URI uri, Type type, Map<String, String> headers, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.putIfAbsent("Accept", "application/json");
        return execute(Request.get(uri).withHeaders(h), cancel).requireOk().json(type);
    }

    public <T> T getJson(URI uri, Type type, CancellationToken cancel) throws LauncherException {
        return getJson(uri, type, Map.of(), cancel);
    }

    /** POST a JSON body; not retried unless {@code retryable} */
    public Response postJson(URI uri, Object body, Map<String, String> headers, boolean retryable, CancellationToken cancel)
        throws LauncherException {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Content-Type", "application/json");
        h.putIfAbsent("Accept", "application/json");
        byte[] bytes = (body instanceof String s ? s : Json.COMPACT.toJson(body)).getBytes(StandardCharsets.UTF_8);
        return execute(new Request("POST", uri, h, bytes, Duration.ofSeconds(30), retryable), cancel);
    }

    /** POST an application/x-www-form-urlencoded body (OAuth endpoints) */
    public Response postForm(URI uri, Map<String, String> form, CancellationToken cancel) throws LauncherException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        return execute(new Request("POST", uri, h, formEncode(form).getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30), false), cancel);
    }

    public static String formEncode(Map<String, String> form) {
        StringJoiner j = new StringJoiner("&");
        form.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    /** {@code base} + path segment + query, encoding the query values */
    public static URI uri(URI base, String path, Map<String, String> query) {
        String b = base.toString();
        String p = path == null ? "" : path;
        if (b.endsWith("/") && p.startsWith("/")) {
            p = p.substring(1);
        } else if (!b.endsWith("/") && !p.isEmpty() && !p.startsWith("/")) {
            p = "/" + p;
        }
        StringBuilder s = new StringBuilder(b).append(p);
        if (query != null && !query.isEmpty()) {
            s.append(s.indexOf("?") >= 0 ? '&' : '?').append(formEncode(query));
        }
        return URI.create(s.toString());
    }

    public static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---------------------------------------------------------------- internals

    private void noteRateHeaders(Response r) {
        Optional<RateLimiter> rl = rateLimiter(r.uri().getHost());
        if (rl.isEmpty()) {
            return;
        }
        Optional<String> remaining = r.header("X-Ratelimit-Remaining");
        Optional<String> reset = r.header("X-Ratelimit-Reset");
        if (remaining.isPresent() && reset.isPresent()) {
            try {
                if (Integer.parseInt(remaining.get().trim()) <= 0) {
                    rl.get().pauseFor(Math.min(60_000, Long.parseLong(reset.get().trim()) * 1000));
                }
            } catch (NumberFormatException ignored) {
                // malformed header: ignore it
            }
        }
    }

    private long backoff(int attempt, Response r) {
        if (r != null) {
            Optional<String> ra = r.header("Retry-After");
            if (ra.isPresent()) {
                long ms = parseRetryAfter(ra.get());
                if (ms >= 0) {
                    return Math.min(ms, 60_000);
                }
            }
        }
        long exp = baseBackoffMillis * (1L << Math.min(6, attempt - 1));
        long jitter = (long) (exp * 0.2 * Math.random());
        return Math.min(30_000, exp + jitter);
    }

    static long parseRetryAfter(String v) {
        String s = v.trim();
        try {
            return Long.parseLong(s) * 1000;
        } catch (NumberFormatException e) {
            try {
                ZonedDateTime t = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(0, t.toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (DateTimeParseException e2) {
                return -1;
            }
        }
    }

    private static void sleep(long millis, CancellationToken cancel) throws LauncherException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            cancel.check();
            try {
                Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LauncherException.cancelled();
            }
        }
    }

    static LauncherException statusError(int status, URI uri) {
        String host = uri.getHost();
        if (status == 404) {
            return new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", host, uri.getPath());
        }
        if (status == 429) {
            return new LauncherException(ErrorCode.RATE_LIMITED, "error.http.rateLimited", host);
        }
        if (status == 401 || status == 403) {
            return new LauncherException(ErrorCode.HTTP_STATUS, "error.http.denied", host, status);
        }
        return new LauncherException(ErrorCode.HTTP_STATUS, "error.http.status", host, status);
    }
}
