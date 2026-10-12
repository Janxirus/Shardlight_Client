package dev.shardlight.client.auth;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

/**
 * DPAPI through JNA. Kept in its own class so the JNA Windows bindings are loaded only when
 * {@link WindowsDpapiSecretStore} actually encrypts or decrypts on Windows.
 */
final class JnaDpapi implements WindowsDpapiSecretStore.Cipher {
    private static final String DESCRIPTION = "Shardlight Client";

    private JnaDpapi() {
    }

    static WindowsDpapiSecretStore.Cipher create() {
        return new JnaDpapi();
    }

    @Override
    public byte[] protect(byte[] plain, byte[] entropy) {
        // UI_FORBIDDEN: never show a Windows prompt; fail instead
        return Crypt32Util.cryptProtectData(plain, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, DESCRIPTION, null);
    }

    @Override
    public byte[] unprotect(byte[] blob, byte[] entropy) {
        return Crypt32Util.cryptUnprotectData(blob, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
    }
}
