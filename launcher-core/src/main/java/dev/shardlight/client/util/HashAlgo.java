package dev.shardlight.client.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hash algorithms used by Mojang (SHA-1), Modrinth (SHA-1/SHA-512) and our own manifests (SHA-256). */
public enum HashAlgo {
    SHA1("SHA-1", 40),
    SHA256("SHA-256", 64),
    SHA512("SHA-512", 128),
    /** CurseForge also lists MD5; only used as a secondary check, never alone */
    MD5("MD5", 32);

    private final String jcaName;
    private final int hexLength;

    HashAlgo(String jcaName, int hexLength) {
        this.jcaName = jcaName;
        this.hexLength = hexLength;
    }

    public MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(jcaName);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public int hexLength() {
        return hexLength;
    }

    /** true when {@code hex} is a well-formed hash for this algorithm */
    public boolean isValidHex(String hex) {
        return hex != null && hex.length() == hexLength && hex.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}
