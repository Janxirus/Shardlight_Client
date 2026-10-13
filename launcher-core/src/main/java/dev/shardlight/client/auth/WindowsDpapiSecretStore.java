package dev.shardlight.client.auth;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.OsInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Windows secret store: each value is encrypted with the Data Protection API for the current Windows user
 * ({@code CryptProtectData}, with an application entropy that also names the key) and saved as a base64
 * blob in {@code <data>/secrets/<sha256(key)>.bin}. Only the same Windows user on the same PC can decrypt
 * it. JNA is touched only when a value is actually encrypted or decrypted on Windows.
 */
public final class WindowsDpapiSecretStore implements SecretStore {
    private static final Logger LOG = Logger.getLogger(WindowsDpapiSecretStore.class.getName());
    /** mixed into every encryption so other programs using DPAPI can't read our blobs by accident */
    static final String ENTROPY_PREFIX = "dev.shardlight.client/secret-store/v1:";

    /** the encryption primitive; DPAPI on Windows, a fake in tests */
    interface Cipher {
        byte[] protect(byte[] plain, byte[] entropy);

        byte[] unprotect(byte[] blob, byte[] entropy);
    }

    private final Path dir;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Cipher cipher;

    /** the store under {@code <data>/secrets}, using DPAPI (Windows only) */
    public WindowsDpapiSecretStore(AppPaths paths) {
        this(paths.root().resolve("secrets"), null);
    }

    /** a store in {@code dir} with an explicit cipher (null = DPAPI, loaded on first use) */
    WindowsDpapiSecretStore(Path dir, Cipher cipher) {
        this.dir = dir.toAbsolutePath().normalize();
        this.cipher = cipher;
    }

    /** the folder holding the encrypted blobs */
    public Path directory() {
        return dir;
    }

    @Override
    public String name() {
        return I18n.tr("auth.store.windows");
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    /**
     * True when DPAPI works here: on Windows, a test value encrypts and decrypts. Never throws; false on
     * other systems (without loading JNA).
     */
    public boolean isUsable() {
        try {
            Cipher c = cipher();
            byte[] probe = "shardlight-probe".getBytes(StandardCharsets.UTF_8);
            byte[] entropy = entropy("probe");
            return Arrays.equals(probe, c.unprotect(c.protect(probe, entropy), entropy));
        } catch (RuntimeException | LinkageError e) {
            LOG.info("Windows Data Protection isn't usable: " + e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void put(String key, String value) {
        SecretStore.requireValidKey(key);
        if (value == null) {
            throw new IllegalArgumentException("null secret value");
        }
        Redactor.register(value);
        byte[] blob;
        try {
            blob = cipher().protect(value.getBytes(StandardCharsets.UTF_8), entropy(key));
        } catch (RuntimeException | LinkageError e) {
            throw new SecretStoreException(name(), "CryptProtectData failed: " + e.getClass().getSimpleName(), e);
        }
        byte[] text = Base64.getEncoder().encode(blob);
        Path file = fileFor(key);
        lock.lock();
        try {
            FileRetry.run("write " + file.getFileName(), () -> {
                AtomicFiles.writeBytes(file, text);
                return null;
            });
        } catch (IOException | LauncherException e) {
            throw new SecretStoreException(name(), "couldn't write " + file.getFileName() + ": " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> get(String key) {
        SecretStore.requireValidKey(key);
        Path file = fileFor(key);
        byte[] text;
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            text = FileRetry.run("read " + file.getFileName(), () -> Files.readAllBytes(file));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | LauncherException e) {
            throw new SecretStoreException(name(), "couldn't read " + file.getFileName() + ": " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
        try {
            byte[] blob = Base64.getMimeDecoder().decode(text);
            String value = new String(cipher().unprotect(blob, entropy(key)), StandardCharsets.UTF_8);
            Redactor.register(value);
            return Optional.of(value);
        } catch (IllegalArgumentException e) {
            LOG.warning("Ignoring a damaged saved secret (" + file.getFileName() + ")");
            return Optional.empty();
        } catch (RuntimeException | LinkageError e) {
            // a blob from another Windows user or PC (copied data folder) can't be decrypted here
            LOG.warning("A saved secret can't be decrypted for this Windows user (" + file.getFileName() + "): "
                + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        SecretStore.requireValidKey(key);
        Path file = fileFor(key);
        lock.lock();
        try {
            FileRetry.run("delete " + file.getFileName(), () -> Files.deleteIfExists(file));
        } catch (IOException | LauncherException e) {
            // still locked: move it out of the way so it is no longer found, then try to delete that
            try {
                Path aside = file.resolveSibling(file.getFileName() + ".deleted-" + UUID.randomUUID());
                Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE);
                Files.deleteIfExists(aside);
            } catch (IOException e2) {
                if (Files.exists(file)) {
                    throw new SecretStoreException(name(), "couldn't delete " + file.getFileName() + ": " + e.getMessage(), e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@code <dir>/<sha256(key)>.bin}: the key itself never appears on disk */
    Path fileFor(String key) {
        return dir.resolve(Hashing.hash(key.getBytes(StandardCharsets.UTF_8), HashAlgo.SHA256) + ".bin");
    }

    static byte[] entropy(String key) {
        return (ENTROPY_PREFIX + key).getBytes(StandardCharsets.UTF_8);
    }

    private Cipher cipher() {
        Cipher c = cipher;
        if (c == null) {
            if (!OsInfo.current().isWindows()) {
                throw new UnsupportedOperationException("DPAPI is only available on Windows");
            }
            c = JnaDpapi.create();
            cipher = c;
        }
        return c;
    }

    @Override
    public String toString() {
        return "WindowsDpapiSecretStore[" + dir + "]";
    }
}
