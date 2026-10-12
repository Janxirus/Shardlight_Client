package dev.shardlight.client.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.util.Json;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;

/**
 * A cached Minecraft access token as stored in the secret store under {@code mc.session.<uuid>}:
 * {@code {"accessToken": "...", "expiresAt": "2026-01-01T12:00:00Z"}}.
 */
record StoredSession(String accessToken, Instant expiresAt) {
    String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("accessToken", accessToken);
        o.addProperty("expiresAt", expiresAt.toString());
        return Json.COMPACT.toJson(o);
    }

    /** the parsed value, or empty when it is missing or damaged */
    static Optional<StoredSession> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject o = e.getAsJsonObject();
            JsonElement token = o.get("accessToken");
            JsonElement expires = o.get("expiresAt");
            if (token == null || !token.isJsonPrimitive() || expires == null || !expires.isJsonPrimitive()) {
                return Optional.empty();
            }
            String t = token.getAsString();
            if (t.isBlank()) {
                return Optional.empty();
            }
            Instant at = expires.getAsJsonPrimitive().isNumber()
                ? Instant.ofEpochMilli(expires.getAsLong())
                : Instant.parse(expires.getAsString());
            return Optional.of(new StoredSession(t, at));
        } catch (JsonParseException | IllegalStateException | NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "StoredSession[<redacted>, " + expiresAt + "]";
    }
}
