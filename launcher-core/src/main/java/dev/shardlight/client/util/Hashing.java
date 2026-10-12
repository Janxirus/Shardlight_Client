package dev.shardlight.client.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** File and byte hashing. */
public final class Hashing {
    private Hashing() {
    }

    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    public static String hash(byte[] data, HashAlgo algo) {
        return hex(algo.newDigest().digest(data));
    }

    public static String hash(Path file, HashAlgo algo) throws IOException {
        MessageDigest md = algo.newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    /** true when the file exists and its hash equals {@code expected} (case-insensitive) */
    public static boolean matches(Path file, HashAlgo algo, String expected) {
        if (expected == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            return hash(file, algo).equalsIgnoreCase(expected.trim());
        } catch (IOException e) {
            return false;
        }
    }

    public static String normalize(String hex) {
        return hex == null ? null : hex.trim().toLowerCase(Locale.ROOT);
    }
}
