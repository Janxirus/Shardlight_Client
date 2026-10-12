package dev.shardlight.client.account;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StoredSessionTest {
    static final String TOKEN = "eyJraWQiOiJhYzg0YSJ9.eyJzdWIiOiJ0ZXN0In0.storedSessionTestSig";

    @Test
    void roundTrips() {
        StoredSession s = new StoredSession(TOKEN, Instant.parse("2026-10-13T10:00:00Z"));
        assertEquals("{\"accessToken\":\"" + TOKEN + "\",\"expiresAt\":\"2026-10-13T10:00:00Z\"}", s.toJson());
        assertEquals(Optional.of(s), StoredSession.parse(s.toJson()));
        assertFalse(s.toString().contains(TOKEN));
    }

    @Test
    void acceptsEpochMillis() {
        assertEquals(Instant.ofEpochMilli(1_791_000_000_000L),
            StoredSession.parse("{\"accessToken\":\"t\",\"expiresAt\":1791000000000}").orElseThrow().expiresAt());
    }

    @Test
    void damagedValuesAreIgnored() {
        for (String bad : new String[] {null, "", "  ", "{", "[]", "\"x\"", "{\"accessToken\":\"t\"}",
            "{\"accessToken\":\"\",\"expiresAt\":\"2026-10-13T10:00:00Z\"}", "{\"accessToken\":\"t\",\"expiresAt\":\"tomorrow\"}",
            "{\"accessToken\":{},\"expiresAt\":\"2026-10-13T10:00:00Z\"}", "{\"accessToken\":\"t\",\"expiresAt\":[1]}"}) {
            assertEquals(Optional.empty(), StoredSession.parse(bad), bad);
        }
    }
}
