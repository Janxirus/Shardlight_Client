package dev.shardlight.client.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Plain HTTP for the live tests, which talk to the real services (run with -PliveTests, never in the normal build). */
final class LiveHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20)).build();

    private LiveHttp() {
    }

    static JsonElement json(String url) throws IOException, InterruptedException {
        return JsonParser.parseString(new String(bytes(url), java.nio.charset.StandardCharsets.UTF_8));
    }

    static byte[] bytes(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
            .header("User-Agent", "Janxirus/Shardlight_Client live tests (github.com/Janxirus/Shardlight_Client)")
            .header("Accept", "application/json, */*").GET().build();
        HttpResponse<byte[]> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost() + URI.create(url).getPath());
        }
        return r.body();
    }
}
