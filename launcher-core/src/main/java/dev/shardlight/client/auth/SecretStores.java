package dev.shardlight.client.auth;

import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.util.OsInfo;

import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Picks the secret store for this computer: Windows DPAPI, the macOS Keychain or the Linux Secret
 * Service when it works, otherwise {@link MemorySecretStore} (the UI then warns that sign-in won't be
 * remembered; see {@link SecretStore#isPersistent()}). Secrets are never written to plain files.
 */
public final class SecretStores {
    private static final Logger LOG = Logger.getLogger(SecretStores.class.getName());

    private SecretStores() {
    }

    /**
     * The best store available on this OS. Blocks briefly (Linux checks that the Secret Service answers);
     * call it off the UI thread, once at startup.
     */
    public static SecretStore forCurrentOs(AppPaths paths) {
        return forOs(OsInfo.current(), paths, ProcessRunner.system(), null, System.getenv("PATH"),
            MacKeychainSecretStore.SECURITY_TOOL);
    }

    /** the selection logic with every OS dependency injectable (tests) */
    static SecretStore forOs(OsInfo os, AppPaths paths, ProcessRunner runner, WindowsDpapiSecretStore.Cipher dpapi,
                             String pathEnv, Path securityTool) {
        SecretStore chosen = switch (os.os()) {
            case WINDOWS -> {
                WindowsDpapiSecretStore s = new WindowsDpapiSecretStore(paths.root().resolve("secrets"), dpapi);
                yield s.isUsable() ? s : null;
            }
            case MACOS -> {
                MacKeychainSecretStore s = new MacKeychainSecretStore(runner, securityTool);
                yield s.isUsable() ? s : null;
            }
            default -> {
                Optional<Path> tool = LinuxSecretServiceStore.findOnPath("secret-tool", pathEnv);
                if (tool.isEmpty()) {
                    LOG.info("secret-tool (libsecret) isn't installed");
                    yield null;
                }
                LinuxSecretServiceStore s = new LinuxSecretServiceStore(runner, tool.get());
                yield s.isUsable() ? s : null;
            }
        };
        if (chosen == null) {
            LOG.warning("No OS secret store is available; sign-in will be kept in memory only");
            return new MemorySecretStore();
        }
        LOG.info("Using secret store: " + chosen.getClass().getSimpleName());
        return chosen;
    }
}
