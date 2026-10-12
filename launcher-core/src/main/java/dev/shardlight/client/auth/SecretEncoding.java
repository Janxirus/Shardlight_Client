package dev.shardlight.client.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * How values are written to the command-line stores (Keychain, Secret Service): base64 with a marker
 * prefix. That keeps every value plain ASCII without quotes, spaces or newlines, so it can't break the
 * {@code security -i} command syntax and the tools never hex-encode or trim it.
 */
final class SecretEncoding {
    static final String PREFIX = "shardlight-b64:";

    private SecretEncoding() {
    }

    static String encode(String value) {
        return PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The original value of {@code stored} (tool output, a trailing newline is ignored). Values without the
     * prefix (stored by hand) are returned as they are; a damaged base64 value gives empty.
     */
    static Optional<String> decode(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        String s = stripLineEnd(stored);
        if (!s.startsWith(PREFIX)) {
            return s.isEmpty() ? Optional.empty() : Optional.of(s);
        }
        try {
            return Optional.of(new String(Base64.getDecoder().decode(s.substring(PREFIX.length()).trim()), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String stripLineEnd(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
