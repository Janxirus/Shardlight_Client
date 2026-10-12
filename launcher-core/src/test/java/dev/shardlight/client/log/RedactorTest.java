package dev.shardlight.client.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {
    private static final String SECRET = "s3cr3t-value-only-the-launcher-knows";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    @AfterEach
    void forget() {
        Redactor.forget(SECRET);
    }

    @Test
    void ordinaryTextIsUntouched() {
        String line = "Downloading sodium-0.6.0.jar (412 KB) for project Survival 1.21.4 from cdn.modrinth.com";
        assertEquals(line, Redactor.redact(line));
        assertNull(Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }

    @Test
    void registeredSecretsAreMaskedEverywhereUntilForgotten() {
        Redactor.register(SECRET);
        assertEquals("key " + Redactor.MASK + " and again " + Redactor.MASK,
            Redactor.redact("key " + SECRET + " and again " + SECRET));
        assertFalse(Redactor.redact("in the middle:" + SECRET + ".").contains(SECRET));
        Redactor.forget(SECRET);
        assertTrue(Redactor.redact("value " + SECRET).contains(SECRET));
    }

    @Test
    void tooShortSecretsAreNotRegistered() {
        Redactor.register("abc");
        Redactor.register(null);
        assertEquals("abc is a word", Redactor.redact("abc is a word"));
    }

    @Test
    void tokensAreMaskedByShape() {
        assertEquals("token " + Redactor.MASK + " end", Redactor.redact("token " + JWT + " end"));
        assertEquals("Authorization: Bearer " + Redactor.MASK,
            Redactor.redact("Authorization: Bearer abcdefgh1234567890"));
        assertEquals("Authorization: XBL3.0 x=" + Redactor.MASK,
            Redactor.redact("Authorization: XBL3.0 x=1234567890;eyJtokenpart"));
        assertEquals("java -cp x net.minecraft.Main --username Steve --accessToken " + Redactor.MASK + " --version 1.21.4",
            Redactor.redact("java -cp x net.minecraft.Main --username Steve --accessToken abc.def-ghi --version 1.21.4"));
        assertEquals("refresh " + Redactor.MASK,
            Redactor.redact("refresh M.C123_BAY.-CU!c4nB*sm8DB3MOZ7xgTZf7Tz5cLz!cKy5mq8gFgFSURg9fQ$$"));
    }

    @Test
    void tokenFieldsInJsonAndFormsAreMasked() {
        assertEquals("{\"access_token\": \"" + Redactor.MASK + "\", \"expires_in\": 3600}",
            Redactor.redact("{\"access_token\": \"abc123def456\", \"expires_in\": 3600}"));
        assertEquals("grant_type=refresh_token&refresh_token=" + Redactor.MASK + "&client_id=x",
            Redactor.redact("grant_type=refresh_token&refresh_token=0.AAAA-bbbb&client_id=x"));
        assertEquals("\"x-api-key\": \"" + Redactor.MASK + "\"", Redactor.redact("\"x-api-key\": \"$2a$10$abcdef\""));
        assertEquals("{\"device_code\":\"" + Redactor.MASK + "\",\"user_code\":\"ABCD-EFGH\"}",
            Redactor.redact("{\"device_code\":\"DAQABAAEAAAD\",\"user_code\":\"ABCD-EFGH\"}"));
        assertEquals("accessToken=" + Redactor.MASK, Redactor.redact("accessToken=abcdefgh"));
    }
}
