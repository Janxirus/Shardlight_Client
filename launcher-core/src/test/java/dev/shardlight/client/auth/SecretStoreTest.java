package dev.shardlight.client.auth;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretStoreTest {
    static final String TOKEN = "M.C507_BAY.0.U.-CsecretStoreTestToken0123456789abcdef";
    /** values that would break naive quoting or encoding */
    static final List<String> NASTY = List.of(
        "plain",
        "with space and \"double\" and 'single' quotes",
        "back\\slash\\\\ and $HOME and `id` and ; rm -rf / &&",
        "new\nline\r\nand\ttab",
        "unicode: Grüße 日本語 🎮",
        "-w --label=evil",
        "",
        "x".repeat(2000));

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- keys

    @ParameterizedTest
    @ValueSource(strings = {"msa.refresh.069a79f444e94726a5befca90e38aaf5", "mc.session.0", "curseforge-api_key", "a"})
    void validKeys(String key) {
        assertEquals(key, SecretStore.requireValidKey(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".hidden", "-flag", "a b", "a\"b", "a'b", "../x", "a/b", "a\\b", "a\nb", "é", "x;y"})
    void invalidKeys(String key) {
        assertThrows(IllegalArgumentException.class, () -> SecretStore.requireValidKey(key));
        assertThrows(IllegalArgumentException.class, () -> new MemorySecretStore().put(key, "v"));
    }

    @Test
    void tooLongKey() {
        assertThrows(IllegalArgumentException.class, () -> SecretStore.requireValidKey("k".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> SecretStore.requireValidKey(null));
    }

    // ---------------------------------------------------------------- memory

    @Test
    void memoryStore() throws Exception {
        MemorySecretStore s = new MemorySecretStore();
        assertFalse(s.isPersistent());
        assertFalse(s.name().isBlank());
        assertEquals(Optional.empty(), s.get("a"));
        for (String v : NASTY) {
            s.put("a", v);
            assertEquals(Optional.of(v), s.get("a"));
        }
        s.put("b", TOKEN);
        assertEquals(2, s.size());
        assertFalse(s.toString().contains(TOKEN));
        assertEquals(Redactor.MASK, Redactor.redact(TOKEN), "stored secrets are registered with the redactor");
        s.delete("a");
        s.delete("a");
        s.delete("never-stored");
        assertEquals(Optional.empty(), s.get("a"));
        assertEquals(Optional.of(TOKEN), s.get("b"));
        assertThrows(IllegalArgumentException.class, () -> s.put("c", null));

        // many threads at once
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int n = t;
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 200; i++) {
                    s.put("k" + n, "v" + i);
                    s.get("k" + n);
                }
                s.delete("k" + n);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, s.size());
    }

    @Test
    void secretStoreExceptionBecomesAUserError() {
        Redactor.register(TOKEN);
        SecretStoreException e = new SecretStoreException("macOS Keychain", "failed near " + TOKEN);
        assertFalse(e.getMessage().contains(TOKEN));
        assertFalse(e.detail().contains(TOKEN));
        LauncherException le = e.toLauncherException();
        assertEquals(ErrorCode.SECRET_STORE_UNAVAILABLE, le.code());
        assertEquals("auth.error.secretStore", le.messageKey());
        assertTrue(le.userMessage().startsWith("Couldn't use macOS Keychain"), le.userMessage());
    }

    // ---------------------------------------------------------------- shared encoding

    @Test
    void encodingRoundTrips() {
        for (String v : NASTY) {
            String enc = SecretEncoding.encode(v);
            assertTrue(enc.matches("shardlight-b64:[A-Za-z0-9+/=]*"), enc);
            assertEquals(Optional.of(v), SecretEncoding.decode(enc));
            assertEquals(Optional.of(v), SecretEncoding.decode(enc + "\n"));
            assertEquals(Optional.of(v), SecretEncoding.decode(enc + "\r\n"));
        }
        assertEquals(Optional.of("hand-made"), SecretEncoding.decode("hand-made\n"));
        assertEquals(Optional.empty(), SecretEncoding.decode("shardlight-b64:!!!not base64"));
        assertEquals(Optional.empty(), SecretEncoding.decode(""));
        assertEquals(Optional.empty(), SecretEncoding.decode(null));
    }

    // ---------------------------------------------------------------- macOS Keychain

    @Test
    void keychainCommandsKeepSecretsOffTheCommandLine() {
        FakeTools.Security tool = new FakeTools.Security();
        MacKeychainSecretStore s = new MacKeychainSecretStore(tool, Path.of("/usr/bin/security"));
        s.put("msa.refresh.abc", TOKEN);
        assertEquals(Optional.of(TOKEN), s.get("msa.refresh.abc"));

        FakeTools.Call add = tool.calls.get(0);
        assertEquals(List.of("/usr/bin/security", "-i"), add.command());
        String expectedLine = "add-generic-password -U -s \"Shardlight Client\" -a \"msa.refresh.abc\" -w \""
            + SecretEncoding.encode(TOKEN) + "\"\n";
        assertEquals(expectedLine, add.stdin());
        assertEquals(List.of("/usr/bin/security", "find-generic-password", "-s", "Shardlight Client", "-a", "msa.refresh.abc", "-w"),
            tool.calls.get(1).command());
        for (FakeTools.Call c : tool.calls) {
            for (String arg : c.command()) {
                assertFalse(arg.contains(TOKEN), "secret on a command line: " + c.command());
                assertFalse(arg.contains(SecretEncoding.encode(TOKEN)), "encoded secret on a command line");
            }
            if (c.stdin() != null) {
                assertFalse(c.stdin().contains(TOKEN), "the raw secret is never written, only its encoding");
            }
        }

        s.delete("msa.refresh.abc");
        assertEquals(List.of("/usr/bin/security", "delete-generic-password", "-s", "Shardlight Client", "-a", "msa.refresh.abc"),
            tool.calls.get(tool.calls.size() - 1).command());
        assertEquals(Optional.empty(), s.get("msa.refresh.abc"));
        s.delete("msa.refresh.abc"); // not found is fine
        assertTrue(s.isPersistent());
    }

    @Test
    void keychainRoundTripsAwkwardValues() {
        FakeTools.Security tool = new FakeTools.Security();
        MacKeychainSecretStore s = new MacKeychainSecretStore(tool, Path.of("/usr/bin/security"));
        for (String v : NASTY) {
            s.put("k", v);
            assertEquals(Optional.of(v), s.get("k"), v);
            // the interactive line parses into exactly the intended arguments
            FakeTools.Call add = tool.calls.stream().filter(c -> c.command().contains("-i")).reduce((a, b) -> b).orElseThrow();
            List<String> words = FakeTools.Security.tokenize(add.stdin().strip());
            assertEquals(List.of("add-generic-password", "-U", "-s", "Shardlight Client", "-a", "k", "-w", SecretEncoding.encode(v)), words);
            assertEquals(1, add.stdin().chars().filter(c -> c == '\n').count(), "exactly one command line");
        }
    }

    @Test
    void keychainQuotingRefusesAnythingThatCouldBreakTheCommand() {
        assertEquals("\"Shardlight Client\"", MacKeychainSecretStore.quote("Shardlight Client"));
        for (String bad : List.of("a\"b", "a\\b", "a'b", "a\nb", "a\rb", "\u0000", "é", "")) {
            assertThrows(IllegalArgumentException.class, () -> MacKeychainSecretStore.quote(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> MacKeychainSecretStore.addCommand("bad key", "v"));
    }

    @Test
    void keychainFailuresAreReported() {
        FakeTools.Security tool = new FakeTools.Security();
        MacKeychainSecretStore s = new MacKeychainSecretStore(tool, Path.of("/usr/bin/security"));
        tool.locked = true;
        SecretStoreException e = assertThrows(SecretStoreException.class, () -> s.put("k", TOKEN));
        assertFalse(e.getMessage().contains(TOKEN));
        assertThrows(SecretStoreException.class, () -> s.get("k"));
        assertThrows(SecretStoreException.class, () -> s.delete("k"));
        tool.locked = false;
        tool.dropWrites = true;
        assertThrows(SecretStoreException.class, () -> s.put("k", TOKEN), "a write that didn't stick is detected");
        MacKeychainSecretStore missing = new MacKeychainSecretStore((c, in, t) -> {
            throw new java.io.IOException("No such file");
        }, Path.of("/usr/bin/security"));
        assertThrows(SecretStoreException.class, () -> missing.get("k"));
    }

    @Test
    void keychainRefusesValuesTooLongForInteractiveMode() {
        MacKeychainSecretStore s = new MacKeychainSecretStore(new FakeTools.Security(), Path.of("/usr/bin/security"));
        assertThrows(SecretStoreException.class, () -> s.put("k", "x".repeat(4000)));
    }

    @Test
    void keychainUsability() throws Exception {
        Path fakeTool = Files.createFile(dir.resolve("security"));
        assertTrue(fakeTool.toFile().setExecutable(true));
        assertTrue(new MacKeychainSecretStore(new FakeTools.Security(), fakeTool).isUsable());
        assertFalse(new MacKeychainSecretStore(new FakeTools.Security(), dir.resolve("missing")).isUsable());
        assertFalse(new MacKeychainSecretStore((c, in, t) -> new ProcessRunner.Result(1, "", "no default keychain"), fakeTool).isUsable());
    }

    // ---------------------------------------------------------------- Linux Secret Service

    @Test
    void secretToolCommandsKeepSecretsOffTheCommandLine() {
        FakeTools.SecretTool tool = new FakeTools.SecretTool();
        LinuxSecretServiceStore s = new LinuxSecretServiceStore(tool, Path.of("/usr/bin/secret-tool"));
        s.put("mc.session.abc", TOKEN);
        assertEquals(Optional.of(TOKEN), s.get("mc.session.abc"));
        assertEquals(List.of("/usr/bin/secret-tool", "store", "--label=Shardlight Client", "service", "shardlight-client",
            "account", "mc.session.abc"), tool.calls.get(0).command());
        assertEquals(SecretEncoding.encode(TOKEN), tool.calls.get(0).stdin(), "the value goes to stdin, without a newline");
        assertEquals(List.of("/usr/bin/secret-tool", "lookup", "service", "shardlight-client", "account", "mc.session.abc"),
            tool.calls.get(1).command());
        s.delete("mc.session.abc");
        assertEquals(List.of("/usr/bin/secret-tool", "clear", "service", "shardlight-client", "account", "mc.session.abc"),
            tool.calls.get(2).command());
        assertEquals(Optional.empty(), s.get("mc.session.abc"));
        for (FakeTools.Call c : tool.calls) {
            assertTrue(c.command().stream().noneMatch(a -> a.contains(TOKEN)));
        }
        for (String v : NASTY) {
            s.put("k", v);
            assertEquals(Optional.of(v), s.get("k"));
        }
    }

    @Test
    void secretToolUsability() {
        FakeTools.SecretTool ok = new FakeTools.SecretTool();
        assertTrue(new LinuxSecretServiceStore(ok, Path.of("/usr/bin/secret-tool")).isUsable());
        assertTrue(ok.items.isEmpty(), "the probe cleans up after itself");

        FakeTools.SecretTool noDbus = new FakeTools.SecretTool();
        noDbus.noDbus = true;
        LinuxSecretServiceStore broken = new LinuxSecretServiceStore(noDbus, Path.of("/usr/bin/secret-tool"));
        assertFalse(broken.isUsable());
        assertThrows(SecretStoreException.class, () -> broken.get("k"), "an error on stderr isn't 'not found'");

        FakeTools.SecretTool forgetful = new FakeTools.SecretTool();
        forgetful.forgetful = true;
        assertFalse(new LinuxSecretServiceStore(forgetful, Path.of("/usr/bin/secret-tool")).isUsable(),
            "a lookup round trip must work");
    }

    @Test
    void findsToolsOnPath() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path tool = Files.createFile(bin.resolve("secret-tool"));
        assertTrue(tool.toFile().setExecutable(true));
        String path = String.join(java.io.File.pathSeparator, "", dir.resolve("nothing").toString(), "relative/dir", bin.toString());
        assertEquals(Optional.of(tool), LinuxSecretServiceStore.findOnPath("secret-tool", path));
        assertEquals(Optional.empty(), LinuxSecretServiceStore.findOnPath("secret-tool", dir.resolve("nothing").toString()));
        assertEquals(Optional.empty(), LinuxSecretServiceStore.findOnPath("secret-tool", null));
        assertEquals(Optional.empty(), LinuxSecretServiceStore.findOnPath("secret-tool", ""));
    }

    // ---------------------------------------------------------------- Windows DPAPI (fake cipher)

    /** stands in for DPAPI: reversible scrambling bound to the entropy, failing like DPAPI on a mismatch */
    static final class FakeDpapi implements WindowsDpapiSecretStore.Cipher {
        final List<byte[]> entropies = new ArrayList<>();
        volatile boolean broken;

        @Override
        public byte[] protect(byte[] plain, byte[] entropy) {
            if (broken) {
                throw new IllegalStateException("CryptProtectData failed");
            }
            entropies.add(entropy.clone());
            byte[] e = Hashing.hash(entropy, HashAlgo.SHA256).getBytes(StandardCharsets.US_ASCII);
            byte[] out = new byte[plain.length + e.length];
            System.arraycopy(e, 0, out, 0, e.length);
            for (int i = 0; i < plain.length; i++) {
                out[e.length + i] = (byte) (plain[i] ^ 0x5A);
            }
            return out;
        }

        @Override
        public byte[] unprotect(byte[] blob, byte[] entropy) {
            if (broken) {
                throw new IllegalStateException("CryptUnprotectData failed");
            }
            byte[] e = Hashing.hash(entropy, HashAlgo.SHA256).getBytes(StandardCharsets.US_ASCII);
            if (blob.length < e.length || !Arrays.equals(Arrays.copyOf(blob, e.length), e)) {
                throw new IllegalStateException("The parameter is incorrect.");
            }
            byte[] out = new byte[blob.length - e.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (blob[e.length + i] ^ 0x5A);
            }
            return out;
        }
    }

    @Test
    void dpapiStoreWritesBase64BlobsNamedByKeyHash() throws Exception {
        FakeDpapi dpapi = new FakeDpapi();
        Path secrets = dir.resolve("secrets");
        WindowsDpapiSecretStore s = new WindowsDpapiSecretStore(secrets, dpapi);
        assertTrue(s.isPersistent());
        assertEquals(Optional.empty(), s.get("msa.refresh.abc"));
        s.put("msa.refresh.abc", TOKEN);

        Path file = secrets.resolve(Hashing.hash("msa.refresh.abc".getBytes(StandardCharsets.UTF_8), HashAlgo.SHA256) + ".bin");
        assertEquals(file, s.fileFor("msa.refresh.abc"));
        assertTrue(Files.isRegularFile(file));
        String content = Files.readString(file);
        assertTrue(content.matches("[A-Za-z0-9+/=]+"), "a base64 blob: " + content);
        assertFalse(content.contains(TOKEN));
        try (Stream<Path> files = Files.list(secrets)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains("msa")), "the key name never appears on disk");
        }
        assertArrayEquals(WindowsDpapiSecretStore.entropy("msa.refresh.abc"), dpapi.entropies.get(0));
        assertTrue(new String(dpapi.entropies.get(0), StandardCharsets.UTF_8).startsWith(WindowsDpapiSecretStore.ENTROPY_PREFIX));

        assertEquals(Optional.of(TOKEN), s.get("msa.refresh.abc"));
        for (String v : NASTY) {
            s.put("k", v);
            assertEquals(Optional.of(v), s.get("k"));
        }

        // a new store over the same folder (next launch) reads the same secrets
        assertEquals(Optional.of(TOKEN), new WindowsDpapiSecretStore(secrets, dpapi).get("msa.refresh.abc"));

        s.delete("msa.refresh.abc");
        assertFalse(Files.exists(file));
        assertEquals(Optional.empty(), s.get("msa.refresh.abc"));
        s.delete("msa.refresh.abc");
    }

    @Test
    void dpapiBlobsCantBeSwappedBetweenKeysOrUsersAndDamageIsSurvivable() throws Exception {
        FakeDpapi dpapi = new FakeDpapi();
        WindowsDpapiSecretStore s = new WindowsDpapiSecretStore(dir, dpapi);
        s.put("a", "value-a-1234567");
        s.put("b", "value-b-1234567");
        // copying a's blob over b's file: the entropy names the key, so it doesn't decrypt as b
        Files.copy(s.fileFor("a"), s.fileFor("b"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(Optional.empty(), s.get("b"));
        // garbage in the file: treated as missing (the player signs in again), not a crash
        Files.writeString(s.fileFor("a"), "%%% not base64 %%%");
        assertEquals(Optional.empty(), s.get("a"));
        // a later put repairs it
        s.put("a", "value-a-2345678");
        assertEquals(Optional.of("value-a-2345678"), s.get("a"));
    }

    @Test
    void dpapiFailuresAreReported() {
        FakeDpapi dpapi = new FakeDpapi();
        dpapi.broken = true;
        WindowsDpapiSecretStore s = new WindowsDpapiSecretStore(dir, dpapi);
        assertFalse(s.isUsable());
        SecretStoreException e = assertThrows(SecretStoreException.class, () -> s.put("k", TOKEN));
        assertFalse(e.getMessage().contains(TOKEN));
    }

    @Test
    void dpapiIsNotUsableOutsideWindows() {
        WindowsDpapiSecretStore s = new WindowsDpapiSecretStore(new AppPaths(dir));
        assertEquals(dir.toAbsolutePath().normalize().resolve("secrets"), s.directory());
        if (!OsInfo.current().isWindows()) {
            assertFalse(s.isUsable());
            assertThrows(SecretStoreException.class, () -> s.put("k", "v"));
            assertFalse(Files.exists(dir.resolve("secrets")), "nothing is written when encryption isn't possible");
        }
    }

    // ---------------------------------------------------------------- choosing a store

    private static final OsInfo LINUX = new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.X64, "6.8");
    private static final OsInfo MAC = new OsInfo(OsInfo.Os.MACOS, OsInfo.Arch.ARM64, "15.0");
    private static final OsInfo WINDOWS = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X64, "10.0");

    @Test
    void picksTheOsStoreWhenItWorks() throws Exception {
        AppPaths paths = new AppPaths(dir);
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path secretTool = Files.createFile(bin.resolve("secret-tool"));
        assertTrue(secretTool.toFile().setExecutable(true));
        Path security = Files.createFile(bin.resolve("security"));
        assertTrue(security.toFile().setExecutable(true));

        assertInstanceOf(LinuxSecretServiceStore.class,
            SecretStores.forOs(LINUX, paths, new FakeTools.SecretTool(), null, bin.toString(), security));
        assertInstanceOf(MacKeychainSecretStore.class,
            SecretStores.forOs(MAC, paths, new FakeTools.Security(), null, "", security));
        SecretStore win = SecretStores.forOs(WINDOWS, paths, new FakeTools.Security(), new FakeDpapi(), "", security);
        assertInstanceOf(WindowsDpapiSecretStore.class, win);
        assertEquals(dir.toAbsolutePath().normalize().resolve("secrets"), ((WindowsDpapiSecretStore) win).directory());
    }

    @Test
    void fallsBackToMemoryNeverToPlainFiles() throws Exception {
        AppPaths paths = new AppPaths(dir);
        FakeTools.SecretTool noDbus = new FakeTools.SecretTool();
        noDbus.noDbus = true;
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path secretTool = Files.createFile(bin.resolve("secret-tool"));
        assertTrue(secretTool.toFile().setExecutable(true));
        FakeDpapi broken = new FakeDpapi();
        broken.broken = true;

        List<SecretStore> stores = List.of(
            SecretStores.forOs(LINUX, paths, noDbus, null, bin.toString(), dir.resolve("none")),
            SecretStores.forOs(LINUX, paths, new FakeTools.SecretTool(), null, dir.resolve("empty").toString(), dir.resolve("none")),
            SecretStores.forOs(MAC, paths, new FakeTools.Security(), null, "", dir.resolve("no-security")),
            SecretStores.forOs(WINDOWS, paths, new FakeTools.Security(), broken, "", dir.resolve("none")),
            SecretStores.forOs(new OsInfo(OsInfo.Os.OTHER, OsInfo.Arch.OTHER, ""), paths, noDbus, null, "", dir.resolve("none")));
        for (SecretStore s : stores) {
            assertInstanceOf(MemorySecretStore.class, s);
            assertFalse(s.isPersistent());
        }
        assertFalse(Files.exists(dir.resolve("secrets")));
    }
}
