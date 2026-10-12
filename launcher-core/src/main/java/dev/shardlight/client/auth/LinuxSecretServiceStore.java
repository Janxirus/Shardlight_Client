package dev.shardlight.client.auth;

import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.log.Redactor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Linux secret store: the freedesktop Secret Service (GNOME Keyring, KWallet, KeePassXC) through
 * libsecret's {@code secret-tool}. Items carry the attributes {@code service=shardlight-client} and
 * {@code account=<key>}; the value is written to {@code secret-tool store} on standard input, never on the
 * command line. Values are stored base64-encoded (see {@link SecretEncoding}).
 */
public final class LinuxSecretServiceStore implements SecretStore {
    private static final Logger LOG = Logger.getLogger(LinuxSecretServiceStore.class.getName());
    /** the label shown in the keyring manager */
    public static final String LABEL = "Shardlight Client";
    /** the {@code service} attribute of every item */
    public static final String SERVICE = "shardlight-client";
    static final String PROBE_KEY = "availability-probe";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ProcessRunner runner;
    private final Path tool;
    private final Object lock = new Object();

    /** a store using {@code tool} (the {@code secret-tool} executable) */
    public LinuxSecretServiceStore(Path tool) {
        this(ProcessRunner.system(), tool);
    }

    LinuxSecretServiceStore(ProcessRunner runner, Path tool) {
        this.runner = runner;
        this.tool = tool;
    }

    /** {@code secret-tool} on the {@code PATH}, if installed */
    public static Optional<Path> findTool() {
        return findOnPath("secret-tool", System.getenv("PATH"));
    }

    /** the first executable {@code name} in a {@code PATH}-style list of folders */
    static Optional<Path> findOnPath(String name, String pathEnv) {
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(dir).resolve(name);
                if (p.isAbsolute() && Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return Optional.of(p);
                }
            } catch (InvalidPathException ignored) {
                // a malformed PATH entry
            }
        }
        return Optional.empty();
    }

    @Override
    public String name() {
        return I18n.tr("auth.store.linux");
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    /**
     * True when a test value can be stored, looked up and cleared (the Secret Service is running and the
     * keyring is unlocked or can be unlocked). Never throws.
     */
    public boolean isUsable() {
        try {
            String probe = UUID.randomUUID().toString();
            put(PROBE_KEY, probe);
            boolean ok = get(PROBE_KEY).map(probe::equals).orElse(false);
            delete(PROBE_KEY);
            return ok;
        } catch (SecretStoreException | IllegalArgumentException e) {
            LOG.info("The Secret Service isn't usable: " + e.getMessage());
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
        String encoded = SecretEncoding.encode(value);
        Redactor.register(encoded);
        synchronized (lock) {
            ProcessRunner.Result r = run(storeCommand(key), encoded);
            if (!r.ok()) {
                throw new SecretStoreException(name(), "secret-tool store failed (exit " + r.exitCode() + "): " + shortError(r));
            }
        }
    }

    @Override
    public Optional<String> get(String key) {
        SecretStore.requireValidKey(key);
        ProcessRunner.Result r = run(lookupCommand(key), null);
        if (!r.ok()) {
            // "nothing found" is exit 1 with no message; anything on stderr is a real failure
            if (r.exitCode() == 1 && r.stderr().isBlank()) {
                return Optional.empty();
            }
            throw new SecretStoreException(name(), "secret-tool lookup failed (exit " + r.exitCode() + "): " + shortError(r));
        }
        Optional<String> value = SecretEncoding.decode(r.stdout());
        value.ifPresent(Redactor::register);
        return value;
    }

    @Override
    public void delete(String key) {
        SecretStore.requireValidKey(key);
        synchronized (lock) {
            ProcessRunner.Result r = run(clearCommand(key), null);
            if (!r.ok() && !(r.exitCode() == 1 && r.stderr().isBlank())) {
                throw new SecretStoreException(name(), "secret-tool clear failed (exit " + r.exitCode() + "): " + shortError(r));
            }
        }
    }

    // ---------------------------------------------------------------- command construction

    List<String> storeCommand(String key) {
        return List.of(tool.toString(), "store", "--label=" + LABEL, "service", SERVICE, "account", key);
    }

    List<String> lookupCommand(String key) {
        return List.of(tool.toString(), "lookup", "service", SERVICE, "account", key);
    }

    List<String> clearCommand(String key) {
        return List.of(tool.toString(), "clear", "service", SERVICE, "account", key);
    }

    private ProcessRunner.Result run(List<String> command, String stdin) {
        try {
            return runner.run(command, stdin, TIMEOUT);
        } catch (IOException e) {
            throw new SecretStoreException(name(), "couldn't run secret-tool: " + e.getMessage(), e);
        }
    }

    private static String shortError(ProcessRunner.Result r) {
        String s = Redactor.redact(r.stderr().strip());
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    @Override
    public String toString() {
        return "LinuxSecretServiceStore[" + tool + "]";
    }
}
