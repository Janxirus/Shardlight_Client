package dev.shardlight.client.update;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Reading the distributor's Ed25519 update-signing public key. Accepted forms: a PEM block
 * ({@code -----BEGIN PUBLIC KEY-----}, as written by {@code openssl pkey -pubout}), the base64 of the same
 * X.509 SubjectPublicKeyInfo DER (44 bytes for Ed25519, as written by {@code keytool -exportcert} after
 * extracting the key, or Java's {@code PublicKey.getEncoded()}), or the bare 32-byte key in base64 or hex.
 */
public final class UpdateKeys {
    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the 32 key bytes follow */
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private UpdateKeys() {
    }

    /**
     * The public key in {@code text}; throws {@link ErrorCode#SIGNATURE_INVALID}
     * ({@code update.error.badKey}) when it isn't an Ed25519 public key in a form listed above.
     */
    public static PublicKey parsePublicKey(String text) throws LauncherException {
        if (text == null || text.isBlank() || text.length() > 8192) {
            throw bad(null);
        }
        String s = text.trim();
        try {
            byte[] raw;
            if (s.startsWith("-----BEGIN")) {
                if (!s.startsWith("-----BEGIN PUBLIC KEY-----") || !s.contains("-----END PUBLIC KEY-----")) {
                    throw bad(null);
                }
                String body = s.substring("-----BEGIN PUBLIC KEY-----".length(), s.indexOf("-----END PUBLIC KEY-----"));
                raw = Base64.getMimeDecoder().decode(body.replaceAll("\\s", ""));
            } else if (s.length() == 64 && s.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
                raw = HexFormat.of().parseHex(s.toLowerCase(Locale.ROOT));
            } else {
                raw = Base64.getDecoder().decode(s.replaceAll("\\s", ""));
            }
            byte[] der;
            if (raw.length == 32) {
                der = new byte[ED25519_SPKI_PREFIX.length + 32];
                System.arraycopy(ED25519_SPKI_PREFIX, 0, der, 0, ED25519_SPKI_PREFIX.length);
                System.arraycopy(raw, 0, der, ED25519_SPKI_PREFIX.length, 32);
            } else {
                der = raw;
            }
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            if (!isEd25519(key)) {
                throw bad(null);
            }
            return key;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw bad(e);
        }
    }

    /** true for an Ed25519 key (Java reports its algorithm as "EdDSA" or "Ed25519") */
    public static boolean isEd25519(PublicKey key) {
        if (key == null) {
            return false;
        }
        String alg = key.getAlgorithm();
        if (!"EdDSA".equalsIgnoreCase(alg) && !"Ed25519".equalsIgnoreCase(alg)) {
            return false;
        }
        byte[] enc = key.getEncoded();
        if (enc == null || enc.length != ED25519_SPKI_PREFIX.length + 32) {
            return false;
        }
        for (int i = 0; i < ED25519_SPKI_PREFIX.length; i++) {
            if (enc[i] != ED25519_SPKI_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /** the key as one line of base64 (X.509 DER), the form docs/RELEASING.md asks distributors to configure */
    public static String toBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static LauncherException bad(Throwable cause) {
        return cause == null
            ? new LauncherException(ErrorCode.SIGNATURE_INVALID, "update.error.badKey")
            : new LauncherException(ErrorCode.SIGNATURE_INVALID, cause, "update.error.badKey");
    }
}
