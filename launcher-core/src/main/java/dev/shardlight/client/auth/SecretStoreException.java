package dev.shardlight.client.auth;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.log.Redactor;

/**
 * A secret store operation failed (the keychain is locked, the Secret Service isn't running, DPAPI refused,
 * a file couldn't be written). Unchecked so {@link SecretStore} keeps a small interface; services convert
 * it with {@link #toLauncherException()} before it reaches the UI. The detail text never contains the
 * secret itself.
 */
public final class SecretStoreException extends RuntimeException {
    private final String storeName;
    private final String detail;

    /** {@code detail} is a short technical reason; it is passed through {@link Redactor} */
    public SecretStoreException(String storeName, String detail, Throwable cause) {
        super(storeName + ": " + Redactor.redact(detail), cause);
        this.storeName = storeName;
        this.detail = Redactor.redact(detail);
    }

    /** a failure without an underlying exception */
    public SecretStoreException(String storeName, String detail) {
        this(storeName, detail, null);
    }

    /** the user-facing name of the store that failed */
    public String storeName() {
        return storeName;
    }

    /** the short technical reason (already redacted) */
    public String detail() {
        return detail;
    }

    /** a {@link ErrorCode#SECRET_STORE_UNAVAILABLE} error for the UI */
    public LauncherException toLauncherException() {
        return new LauncherException(ErrorCode.SECRET_STORE_UNAVAILABLE, this, "auth.error.secretStore", storeName, detail);
    }
}
