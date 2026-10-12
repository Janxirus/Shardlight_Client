package dev.shardlight.client.log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from text before it reaches a log file, the console, a diagnostics export or the
 * clipboard. Known secrets (tokens the launcher handled) are registered explicitly; JWT-shaped strings,
 * bearer headers, {@code --accessToken} arguments and common token fields are masked by pattern.
 */
public final class Redactor {
    public static final String MASK = "<redacted>";
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final Pattern[] PATTERNS = {
        // JSON Web Tokens (Minecraft and Xbox tokens are JWTs)
        Pattern.compile("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}"),
        // Authorization: Bearer xyz / XBL3.0 x=...;token
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
        Pattern.compile("(?i)(XBL3\\.0 x=)[^\\s\"]+"),
        // --accessToken xyz on a command line
        Pattern.compile("(--accessToken\\s+)\\S+"),
        // "access_token": "xyz", refresh_token=xyz, "x-api-key": "..."
        Pattern.compile("(?i)(\"?(?:access_token|refresh_token|accessToken|refreshToken|id_token|device_code|x-api-key|apiKey|RpsTicket)\"?\\s*[:=]\\s*\"?)[^\"\\s,&}]+"),
        // Microsoft refresh tokens (M.C1xx_...) and RPS tickets
        Pattern.compile("M\\.[A-Z0-9]{2,4}_[A-Za-z0-9!*$._-]{20,}"),
    };

    private Redactor() {
    }

    /** remember a secret value so every later log line masks it */
    public static void register(String secret) {
        if (secret != null && secret.length() >= 8) {
            SECRETS.add(secret);
        }
    }

    public static void forget(String secret) {
        if (secret != null) {
            SECRETS.remove(secret);
        }
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String s : SECRETS) {
            if (out.contains(s)) {
                out = out.replace(s, MASK);
            }
        }
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(out);
            if (!m.find()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            m.reset();
            while (m.find()) {
                String prefix = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : "";
                m.appendReplacement(sb, Matcher.quoteReplacement(prefix + MASK));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }
}
