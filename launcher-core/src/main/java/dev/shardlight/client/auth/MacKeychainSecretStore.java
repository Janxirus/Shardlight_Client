package dev.shardlight.client.auth;

import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.log.Redactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * macOS secret store: generic passwords in the user's login Keychain (service "Shardlight Client",
 * account = the key), through Apple's {@code /usr/bin/security} tool. Secrets never appear on a command
 * line: writing starts {@code security -i} and sends the {@code add-generic-password} command on its
 * standard input. Values are stored base64-encoded (see {@link SecretEncoding}) so they are always safe
 * inside the quoted command and {@code security} never hex-encodes them on the way back.
 */
public final class MacKeychainSecretStore implements SecretStore {
    private static final Logger LOG = Logger.getLogger(MacKeychainSecretStore.class.getName());
    /** Apple's keychain tool */
    public static final Path SECURITY_TOOL = Path.of("/usr/bin/security");
    /** the Keychain service name every item is stored under */
    public static final String SERVICE = "Shardlight Client";
    /** {@code security}'s exit code for "The specified item could not be found in the keychain." */
    static final int ERR_NOT_FOUND = 44;
    /** {@code security -i} reads commands into a fixed buffer; longer lines would be cut */
    static final int MAX_COMMAND_LENGTH = 4000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ProcessRunner runner;
    private final Path tool;
    private final Object lock = new Object();

    /** the store using the real {@code /usr/bin/security} */
    public MacKeychainSecretStore() {
        this(ProcessRunner.system(), SECURITY_TOOL);
    }

    MacKeychainSecretStore(ProcessRunner runner, Path tool) {
        this.runner = runner;
        this.tool = tool;
    }

    @Override
    public String name() {
        return I18n.tr("auth.store.mac");
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    /** true when the {@code security} tool exists and a default keychain is set up; never throws */
    public boolean isUsable() {
        try {
            if (!Files.isExecutable(tool)) {
                return false;
            }
            return runner.run(List.of(tool.toString(), "default-keychain"), null, TIMEOUT).ok();
        } catch (IOException | RuntimeException e) {
            LOG.info("The macOS Keychain isn't usable: " + e.getClass().getSimpleName());
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
        String command = addCommand(key, encoded);
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new SecretStoreException(name(), "value too long for the Keychain (" + value.length() + " characters)");
        }
        synchronized (lock) {
            ProcessRunner.Result r = run(List.of(tool.toString(), "-i"), command);
            if (!r.ok()) {
                throw new SecretStoreException(name(), "add-generic-password failed (exit " + r.exitCode() + "): " + shortError(r));
            }
            // "security -i" may report a failed command only on stderr: read the value back to be sure
            Optional<String> back = get(key);
            if (back.isEmpty() || !back.get().equals(value)) {
                throw new SecretStoreException(name(), "the Keychain didn't keep the value: " + shortError(r));
            }
        }
    }

    @Override
    public Optional<String> get(String key) {
        SecretStore.requireValidKey(key);
        ProcessRunner.Result r = run(findCommand(key), null);
        if (r.exitCode() == ERR_NOT_FOUND) {
            return Optional.empty();
        }
        if (!r.ok()) {
            throw new SecretStoreException(name(), "find-generic-password failed (exit " + r.exitCode() + "): " + shortError(r));
        }
        Optional<String> value = SecretEncoding.decode(r.stdout());
        value.ifPresent(Redactor::register);
        return value;
    }

    @Override
    public void delete(String key) {
        SecretStore.requireValidKey(key);
        synchronized (lock) {
            ProcessRunner.Result r = run(deleteCommand(key), null);
            if (!r.ok() && r.exitCode() != ERR_NOT_FOUND) {
                throw new SecretStoreException(name(), "delete-generic-password failed (exit " + r.exitCode() + "): " + shortError(r));
            }
        }
    }

    // ---------------------------------------------------------------- command construction

    /** the line sent to {@code security -i}; {@code encodedValue} is already base64 with its prefix */
    static String addCommand(String key, String encodedValue) {
        return "add-generic-password -U -s " + quote(SERVICE) + " -a " + quote(SecretStore.requireValidKey(key))
            + " -w " + quote(encodedValue) + "\n";
    }

    List<String> findCommand(String key) {
        return List.of(tool.toString(), "find-generic-password", "-s", SERVICE, "-a", key, "-w");
    }

    List<String> deleteCommand(String key) {
        return List.of(tool.toString(), "delete-generic-password", "-s", SERVICE, "-a", key);
    }

    /**
     * Double-quote one argument for {@code security -i}'s command parser. Only characters that need no
     * escaping are accepted (keys are restricted and values are base64), so a quote, backslash, newline
     * or control character can never change the command; anything else is refused.
     */
    static String quote(String arg) {
        if (arg == null || arg.isEmpty()) {
            throw new IllegalArgumentException("empty Keychain argument");
        }
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '"' || c == '\\' || c == '\'') {
                throw new IllegalArgumentException("character not allowed in a Keychain command at index " + i);
            }
        }
        return "\"" + arg + "\"";
    }

    private ProcessRunner.Result run(List<String> command, String stdin) {
        try {
            return runner.run(command, stdin, TIMEOUT);
        } catch (IOException e) {
            throw new SecretStoreException(name(), "couldn't run " + tool.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static String shortError(ProcessRunner.Result r) {
        String s = Redactor.redact(r.stderr().strip());
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    @Override
    public String toString() {
        return "MacKeychainSecretStore[" + tool + "]";
    }
}
