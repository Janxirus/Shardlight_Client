package dev.shardlight.client.auth;

import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.log.Redactor;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps secrets in memory only: they are gone when the launcher closes, so the player signs in again next
 * time. Used when no OS secret store works; secrets are never written to plain files instead.
 */
public final class MemorySecretStore implements SecretStore {
    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return I18n.tr("auth.store.memory");
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public void put(String key, String value) {
        SecretStore.requireValidKey(key);
        if (value == null) {
            throw new IllegalArgumentException("null secret value");
        }
        Redactor.register(value);
        values.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(SecretStore.requireValidKey(key)));
    }

    @Override
    public void delete(String key) {
        values.remove(SecretStore.requireValidKey(key));
    }

    /** how many secrets are held (for tests and diagnostics; never the values) */
    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return "MemorySecretStore[" + values.size() + " secrets]";
    }
}
