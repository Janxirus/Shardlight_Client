package dev.shardlight.client.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A place to keep small secrets (refresh tokens, session tokens, API keys) outside plain files: Windows
 * DPAPI, the macOS Keychain or the Linux Secret Service, or memory only when none of those works (see
 * {@link SecretStores#forCurrentOs}). Implementations are safe to call from several threads.
 *
 * <p>Keys are short ASCII names such as {@code msa.refresh.<uuid>}: letters, digits, {@code . _ -}, at most
 * 128 characters, starting with a letter or digit ({@link #requireValidKey}). Values are any text.
 *
 * <p>Operations block (some run a short OS command) and throw the unchecked {@link SecretStoreException}
 * when the OS store refuses or fails; {@link SecretStoreException#toLauncherException()} turns that into
 * a user-facing error.
 */
public interface SecretStore {
    /** what a key may look like */
    Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** the store's user-facing name, for example "Windows Data Protection" */
    String name();

    /** true when secrets survive a restart of the launcher (false for the memory-only store) */
    boolean isPersistent();

    /** store {@code value} under {@code key}, replacing any previous value */
    void put(String key, String value);

    /** the value stored under {@code key}, or empty when there is none (or it can no longer be read) */
    Optional<String> get(String key);

    /** remove {@code key}; does nothing when it isn't stored */
    void delete(String key);

    /** returns {@code key} when it is a valid key, otherwise throws {@link IllegalArgumentException} */
    static String requireValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid secret key: " + (key == null ? "null" : "\"" + key + "\""));
        }
        return key;
    }
}
