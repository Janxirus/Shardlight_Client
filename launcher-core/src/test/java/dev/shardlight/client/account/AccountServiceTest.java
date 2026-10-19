package dev.shardlight.client.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.auth.AuthFixtures;
import dev.shardlight.client.auth.DeviceCode;
import dev.shardlight.client.auth.MemorySecretStore;
import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.auth.SecretStore;
import dev.shardlight.client.auth.SecretStoreException;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {
    static final String UUID_2 = "853c80ef3c3749fdaa49938b674adae6";

    @TempDir
    Path dir;
    AuthFixtures fx;
    AppPaths paths;
    MemorySecretStore secrets;
    AccountService accounts;
    final List<DeviceCode> shown = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        fx = new AuthFixtures();
        paths = new AppPaths(dir);
        secrets = new MemorySecretStore();
        accounts = new AccountService(paths, secrets, fx.service(), fx.clock);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private Account signIn() throws LauncherException {
        return accounts.signIn(shown::add, CancellationToken.NONE);
    }

    private String accountsJson() throws Exception {
        return Files.readString(paths.accountsFile(), StandardCharsets.UTF_8);
    }

    private void assertNoTokensOnDisk() throws Exception {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(f, StandardCharsets.ISO_8859_1);
                for (String token : AuthFixtures.ALL_TOKENS) {
                    assertFalse(text.contains(token), "token in " + dir.relativize(f));
                }
                assertFalse(text.toLowerCase().contains("token"), "token-like field in " + dir.relativize(f) + ": " + text);
            }
        }
    }

    private static LauncherException fails(ErrorCode code, Exec e) {
        LauncherException ex = assertThrows(LauncherException.class, e::run);
        assertEquals(code, ex.code(), () -> "got " + ex);
        return ex;
    }

    @FunctionalInterface
    interface Exec {
        void run() throws Exception;
    }

    private TestHttpServer.Recorded lastTokenRequest() {
        List<TestHttpServer.Recorded> all = fx.server.requests().stream().filter(r -> r.path().equals("/msa/token")).toList();
        return all.get(all.size() - 1);
    }

    // ---------------------------------------------------------------- sign-in

    @Test
    void completeSignIn() throws Exception {
        assertTrue(accounts.accounts().isEmpty());
        assertTrue(accounts.selected().isEmpty());
        assertTrue(accounts.isSignInConfigured());
        assertFalse(accounts.remembersSignIn(), "memory store");

        fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        Account a = signIn();

        assertEquals(1, shown.size(), "the code is shown once");
        assertEquals(AuthFixtures.USER_CODE, shown.get(0).userCode());
        assertEquals("https://www.microsoft.com/link", shown.get(0).verificationUri().toString());

        assertEquals(AuthFixtures.UUID, a.id);
        assertEquals(AuthFixtures.NAME, a.username);
        assertEquals(AuthFixtures.XUID, a.xuid);
        assertEquals("msa", a.type);
        assertTrue(a.ownsGame);
        assertEquals(AuthFixtures.SKIN_URL, a.skinUrl);
        assertEquals("classic", a.skinVariant);
        long now = fx.clock.millis();
        assertEquals(now, a.addedAt);
        assertEquals(now, a.lastUsedAt);
        assertEquals(now, a.entitlementCheckedAt);

        assertEquals(List.of(AuthFixtures.UUID), accounts.accounts().stream().map(x -> x.id).toList());
        assertEquals(AuthFixtures.UUID, accounts.selected().orElseThrow().id, "the first account is selected");

        assertEquals(Optional.of(AuthFixtures.MSA_REFRESH), secrets.get("msa.refresh." + AuthFixtures.UUID));
        JsonObject session = JsonParser.parseString(secrets.get("mc.session." + AuthFixtures.UUID).orElseThrow()).getAsJsonObject();
        assertEquals(AuthFixtures.MC_TOKEN, session.get("accessToken").getAsString());
        assertEquals(fx.clock.instant().plusSeconds(86400).toString(), session.get("expiresAt").getAsString());
        assertEquals(2, secrets.size());

        JsonObject file = JsonParser.parseString(accountsJson()).getAsJsonObject();
        assertEquals(1, file.get("schemaVersion").getAsInt());
        assertEquals(AuthFixtures.UUID, file.get("selectedId").getAsString());
        assertEquals(AuthFixtures.NAME, file.getAsJsonArray("accounts").get(0).getAsJsonObject().get("username").getAsString());
        assertNoTokensOnDisk();
    }

    @Test
    void signingInAgainUpdatesInsteadOfDuplicating() throws Exception {
        Account first = signIn();
        fx.clock.advance(Duration.ofHours(2));
        fx.profileJson = AuthFixtures.profile(AuthFixtures.UUID, "Notch_Renamed");
        Account again = signIn();
        assertEquals(1, accounts.accounts().size());
        assertEquals("Notch_Renamed", again.username);
        assertEquals(first.addedAt, again.addedAt, "added time is kept");
        assertEquals(fx.clock.millis(), again.lastUsedAt);
    }

    @Test
    void secondAccountIsAddedButNotSelected() throws Exception {
        signIn();
        fx.profileJson = AuthFixtures.profile(UUID_2, "Jeb_");
        Account second = signIn();
        assertEquals(UUID_2, second.id);
        assertEquals(List.of(AuthFixtures.UUID, UUID_2), accounts.accounts().stream().map(x -> x.id).toList());
        assertEquals(AuthFixtures.UUID, accounts.selected().orElseThrow().id);
        accounts.select(UUID_2);
        assertEquals(UUID_2, accounts.selected().orElseThrow().id);
        assertEquals(UUID_2, new AccountService(paths, secrets, fx.service(), fx.clock).selected().orElseThrow().id,
            "the selection is saved");
        fails(ErrorCode.NOT_FOUND, () -> accounts.select("ffffffffffffffffffffffffffffffff"));
        fails(ErrorCode.NOT_FOUND, () -> accounts.select("../../etc/passwd"));
        fails(ErrorCode.NOT_FOUND, () -> accounts.select(null));
    }

    @Test
    void failedSignInsAddNothing() throws Exception {
        fx.devicePolls.add(AuthFixtures.oauthError("authorization_declined", 70019));
        fails(ErrorCode.AUTH_FAILED, this::signIn);
        fx.devicePolls.clear();
        fx.profileJson = null;
        fx.entitlementsJson = AuthFixtures.entitlements(false);
        fails(ErrorCode.AUTH_NOT_OWNED, this::signIn);
        fx.profileJson = AuthFixtures.profile(AuthFixtures.UUID, AuthFixtures.NAME);
        fx.xstsError = 2148916233L;
        fails(ErrorCode.AUTH_NO_XBOX_ACCOUNT, this::signIn);
        assertTrue(accounts.accounts().isEmpty());
        assertEquals(0, secrets.size());
        assertFalse(Files.exists(paths.accountsFile()));
    }

    @Test
    void uiCallbackFailureAbortsSignIn() {
        LauncherException e = fails(ErrorCode.INTERNAL, () -> accounts.signIn(c -> {
            throw new IllegalStateException("dialog closed");
        }, CancellationToken.NONE));
        assertNotNull(e.getCause());
        assertEquals(0, fx.devicePollCount.get());
        assertTrue(accounts.accounts().isEmpty());
    }

    @Test
    void cancelledSignIn() {
        CancellationToken cancel = new CancellationToken();
        for (int i = 0; i < 10; i++) {
            fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
        }
        fails(ErrorCode.CANCELLED, () -> accounts.signIn(c -> cancel.cancel(), cancel));
        assertTrue(accounts.accounts().isEmpty());
        assertEquals(0, secrets.size());
    }

    @Test
    void notConfiguredWithoutClientId() throws Exception {
        AccountService unconfigured = new AccountService(paths, secrets, fx.service(() -> ""), fx.clock);
        assertFalse(unconfigured.isSignInConfigured());
        LauncherException e = fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> unconfigured.signIn(shown::add, CancellationToken.NONE));
        assertEquals("auth.error.notConfigured", e.messageKey());
        assertTrue(shown.isEmpty());
        assertTrue(fx.server.requests().isEmpty());
    }

    // ---------------------------------------------------------------- sessions

    @Test
    void cachedSessionIsReused() throws Exception {
        signIn();
        int tokenCalls = fx.server.hits("/msa/token");
        int logins = fx.loginCount.get();

        MinecraftSession s = accounts.session(AuthFixtures.UUID, CancellationToken.NONE);
        assertEquals(AuthFixtures.NAME, s.username());
        assertEquals(AuthFixtures.UUID, s.uuid());
        assertEquals(AuthFixtures.MC_TOKEN, s.accessToken());
        assertEquals(AuthFixtures.XUID, s.xuid());
        assertEquals("msa", s.userType());
        assertEquals(AuthFixtures.CLIENT_ID, s.clientId());
        assertFalse(s.toString().contains(AuthFixtures.MC_TOKEN));

        fx.clock.advance(Duration.ofHours(19).plusMinutes(54)); // 4 h 6 min left
        assertEquals(AuthFixtures.MC_TOKEN, accounts.session(AuthFixtures.UUID, CancellationToken.NONE).accessToken());
        assertEquals(tokenCalls, fx.server.hits("/msa/token"), "no refresh while the token has more than 4 hours");
        assertEquals(logins, fx.loginCount.get());
        assertEquals(fx.clock.millis(), accounts.account(AuthFixtures.UUID).orElseThrow().lastUsedAt);

        fx.clock.advance(Duration.ofMinutes(12)); // 3 h 54 min left: too little for a long play session, renew
        fx.nextMcToken = AuthFixtures.MC_TOKEN_2;
        assertEquals(AuthFixtures.MC_TOKEN_2, accounts.session(AuthFixtures.UUID, CancellationToken.NONE).accessToken());
        assertEquals(1, fx.refreshCount.get());
    }

    @Test
    void refreshWithRotatedRefreshToken() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.nextMcToken = AuthFixtures.MC_TOKEN_2;
        fx.profileJson = AuthFixtures.profile(AuthFixtures.UUID, "NotchNewName");

        MinecraftSession s = accounts.session(AuthFixtures.UUID, CancellationToken.NONE);
        assertEquals(AuthFixtures.MC_TOKEN_2, s.accessToken());
        assertEquals("NotchNewName", s.username(), "a name change is picked up");
        assertEquals("NotchNewName", accounts.account(AuthFixtures.UUID).orElseThrow().username);
        assertEquals(AuthFixtures.MSA_REFRESH, AuthFixtures.form(lastTokenRequest().bodyText()).get("refresh_token"));
        assertEquals("refresh_token", AuthFixtures.form(lastTokenRequest().bodyText()).get("grant_type"));
        assertEquals(Optional.of(AuthFixtures.MSA_REFRESH_2), secrets.get("msa.refresh." + AuthFixtures.UUID), "rotated token stored");
        assertTrue(secrets.get("mc.session." + AuthFixtures.UUID).orElseThrow().contains(AuthFixtures.MC_TOKEN_2));

        // the next renewal uses the rotated token
        fx.clock.advance(Duration.ofHours(25));
        fx.refreshAnswer = () -> AuthFixtures.Answer.ok(AuthFixtures.msaTokens(AuthFixtures.MSA_ACCESS, AuthFixtures.MSA_REFRESH));
        accounts.session(AuthFixtures.UUID, CancellationToken.NONE);
        assertEquals(AuthFixtures.MSA_REFRESH_2, AuthFixtures.form(lastTokenRequest().bodyText()).get("refresh_token"));
        assertEquals(2, fx.refreshCount.get());
        assertNoTokensOnDisk();
    }

    @Test
    void expiredRefreshKeepsTheAccount() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.refreshAnswer = () -> AuthFixtures.oauthError("invalid_grant", 70000);
        LauncherException e = fails(ErrorCode.AUTH_EXPIRED, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals("auth.error.signInAgain", e.messageKey());
        assertTrue(e.userMessage().contains(AuthFixtures.NAME));
        assertEquals(1, accounts.accounts().size(), "the account stays listed");
        assertEquals(AuthFixtures.UUID, accounts.selected().orElseThrow().id);
        assertEquals(Optional.empty(), secrets.get("msa.refresh." + AuthFixtures.UUID), "the dead token is dropped");

        // no network round trip for the next attempt
        fails(ErrorCode.AUTH_EXPIRED, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals(1, fx.refreshCount.get());

        // signing in again fixes it
        signIn();
        assertEquals(AuthFixtures.MC_TOKEN, accounts.session(AuthFixtures.UUID, CancellationToken.NONE).accessToken());
    }

    @Test
    void savedSignInStatusForTheAccountsPage() throws Exception {
        assertFalse(accounts.hasSavedSignIn(AuthFixtures.UUID), "unknown account");
        signIn();
        assertTrue(accounts.hasSavedSignIn(AuthFixtures.UUID));
        assertFalse(accounts.hasSavedSignIn("../../x"));
        fx.clock.advance(Duration.ofHours(25));
        fx.refreshAnswer = () -> AuthFixtures.oauthError("invalid_grant", 70000);
        assertThrows(LauncherException.class, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertFalse(accounts.hasSavedSignIn(AuthFixtures.UUID), "needs a new sign-in");
        assertEquals(1, fx.refreshCount.get(), "checking the status never contacts Microsoft");
    }

    @Test
    void missingRefreshTokenMeansSignInAgain() throws Exception {
        signIn();
        secrets.delete("msa.refresh." + AuthFixtures.UUID);
        secrets.delete("mc.session." + AuthFixtures.UUID);
        fails(ErrorCode.AUTH_EXPIRED, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals(0, fx.refreshCount.get());
        assertEquals(1, accounts.accounts().size());
    }

    @Test
    void damagedCachedSessionIsRenewed() throws Exception {
        signIn();
        secrets.put("mc.session." + AuthFixtures.UUID, "{not json");
        fx.nextMcToken = AuthFixtures.MC_TOKEN_2;
        assertEquals(AuthFixtures.MC_TOKEN_2, accounts.session(AuthFixtures.UUID, CancellationToken.NONE).accessToken());
    }

    @Test
    void networkErrorKeepsTheAccountAndTokens() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.close();
        fails(ErrorCode.NETWORK, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals(1, accounts.accounts().size());
        assertEquals(Optional.of(AuthFixtures.MSA_REFRESH), secrets.get("msa.refresh." + AuthFixtures.UUID));
    }

    @Test
    void lostOwnershipIsRecorded() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.profileJson = null;
        fx.entitlementsJson = AuthFixtures.entitlements(false);
        fails(ErrorCode.AUTH_NOT_OWNED, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        Account a = accounts.account(AuthFixtures.UUID).orElseThrow();
        assertFalse(a.ownsGame);
        assertEquals(fx.clock.millis(), a.entitlementCheckedAt);
    }

    @Test
    void refreshThatReturnsAnotherProfileIsRefused() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.profileJson = AuthFixtures.profile(UUID_2, "Someone");
        LauncherException e = fails(ErrorCode.AUTH_FAILED, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals("auth.error.profileChanged", e.messageKey());
        assertEquals(List.of(AuthFixtures.UUID), accounts.accounts().stream().map(x -> x.id).toList());
    }

    @Test
    void cachedSessionWorksEvenWithoutClientIdButRenewalDoesNot() throws Exception {
        signIn();
        AccountService unconfigured = new AccountService(paths, secrets, fx.service(() -> ""), fx.clock);
        MinecraftSession s = unconfigured.session(AuthFixtures.UUID, CancellationToken.NONE);
        assertEquals(AuthFixtures.MC_TOKEN, s.accessToken());
        assertEquals("", s.clientId());
        fx.clock.advance(Duration.ofHours(25));
        fails(ErrorCode.AUTH_NOT_CONFIGURED, () -> unconfigured.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals(1, unconfigured.accounts().size());
    }

    @Test
    void unknownAccount() {
        fails(ErrorCode.NOT_FOUND, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        fails(ErrorCode.NOT_FOUND, () -> accounts.session("bad id with spaces", CancellationToken.NONE));
        fails(ErrorCode.NOT_FOUND, () -> accounts.session(null, CancellationToken.NONE));
    }

    @Test
    void concurrentSessionRequestsRefreshOnce() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.nextMcToken = AuthFixtures.MC_TOKEN_2;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MinecraftSession>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return accounts.session(AuthFixtures.UUID, CancellationToken.NONE);
            }));
        }
        go.countDown();
        for (Future<MinecraftSession> f : results) {
            assertEquals(AuthFixtures.MC_TOKEN_2, f.get(30, TimeUnit.SECONDS).accessToken());
        }
        pool.shutdown();
        assertEquals(1, fx.refreshCount.get(), "one renewal shared by every caller");
    }

    // ---------------------------------------------------------------- sign-out

    @Test
    void signOutDeletesSecretsAndSelectsAnother() throws Exception {
        signIn();
        fx.profileJson = AuthFixtures.profile(UUID_2, "Jeb_");
        signIn();
        assertEquals(4, secrets.size());

        accounts.signOut(AuthFixtures.UUID);
        assertEquals(Optional.empty(), secrets.get("msa.refresh." + AuthFixtures.UUID));
        assertEquals(Optional.empty(), secrets.get("mc.session." + AuthFixtures.UUID));
        assertEquals(2, secrets.size(), "the other account's tokens stay");
        assertEquals(List.of(UUID_2), accounts.accounts().stream().map(x -> x.id).toList());
        assertEquals(UUID_2, accounts.selected().orElseThrow().id);
        fails(ErrorCode.NOT_FOUND, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));

        accounts.signOut(UUID_2);
        assertEquals(0, secrets.size());
        assertTrue(accounts.accounts().isEmpty());
        assertTrue(accounts.selected().isEmpty());
        accounts.signOut(UUID_2); // already gone: nothing happens
        accounts.signOut("not an id");
        AccountService reloaded = new AccountService(paths, secrets, fx.service(), fx.clock);
        assertTrue(reloaded.accounts().isEmpty());
        assertTrue(reloaded.selected().isEmpty());
        assertNoTokensOnDisk();
    }

    @Test
    void signOutReportsASecretStoreThatWontDelete() throws Exception {
        FlakyStore store = new FlakyStore();
        AccountService s = new AccountService(paths, store, fx.service(), fx.clock);
        s.signIn(shown::add, CancellationToken.NONE);
        store.failDeletes = true;
        LauncherException e = fails(ErrorCode.SECRET_STORE_UNAVAILABLE, () -> s.signOut(AuthFixtures.UUID));
        assertEquals("auth.error.signOutSecrets", e.messageKey());
        assertTrue(s.accounts().isEmpty(), "the account is removed anyway");
    }

    @Test
    void failedSignOutDeletesAreRetriedLater() throws Exception {
        FlakyStore store = new FlakyStore();
        AccountService s = new AccountService(paths, store, fx.service(), fx.clock);
        s.signIn(shown::add, CancellationToken.NONE);
        store.failDeletes = true;
        assertThrows(LauncherException.class, () -> s.signOut(AuthFixtures.UUID));
        assertTrue(store.inner.get("msa.refresh." + AuthFixtures.UUID).isPresent(), "left behind for now");
        assertTrue(accountsJson().contains("msa.refresh." + AuthFixtures.UUID), "remembered by key name");

        AccountService restarted = new AccountService(paths, store, fx.service(), fx.clock);
        restarted.maintain(CancellationToken.NONE);
        assertTrue(store.inner.get("msa.refresh." + AuthFixtures.UUID).isPresent(), "still failing: kept for later");

        store.failDeletes = false;
        restarted.maintain(CancellationToken.NONE);
        assertTrue(store.inner.get("msa.refresh." + AuthFixtures.UUID).isEmpty());
        assertTrue(store.inner.get("mc.session." + AuthFixtures.UUID).isEmpty());
        assertFalse(accountsJson().contains("msa.refresh."), "nothing pending any more");
    }

    @Test
    void pendingDeletesSkipAnAccountThatSignedInAgain() throws Exception {
        FlakyStore store = new FlakyStore();
        AccountService s = new AccountService(paths, store, fx.service(), fx.clock);
        s.signIn(shown::add, CancellationToken.NONE);
        store.failDeletes = true;
        assertThrows(LauncherException.class, () -> s.signOut(AuthFixtures.UUID));
        store.failDeletes = false;
        s.signIn(shown::add, CancellationToken.NONE);
        s.maintain(CancellationToken.NONE);
        assertTrue(store.inner.get("msa.refresh." + AuthFixtures.UUID).isPresent(), "the new sign-in's token survives");
        assertFalse(accountsJson().contains("msa.refresh."));
    }

    // ---------------------------------------------------------------- app id, offline, background renewal

    @Test
    void changedAppIdAsksToSignInAgain() throws Exception {
        signIn();
        assertEquals(AuthFixtures.CLIENT_ID, accounts.account(AuthFixtures.UUID).orElseThrow().clientId);
        AccountService other = new AccountService(paths, secrets, fx.service(() -> "99999999-1111-2222-3333-444444444444"), fx.clock);
        fx.clock.advance(Duration.ofHours(25));
        LauncherException e = fails(ErrorCode.AUTH_EXPIRED, () -> other.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals("auth.error.appChanged", e.messageKey());
        assertEquals(0, fx.refreshCount.get(), "Microsoft would refuse it anyway");
        assertFalse(other.hasSavedSignIn(AuthFixtures.UUID));
        assertEquals(1, other.accounts().size(), "the account stays listed");
    }

    @Test
    void refusedAppIdOnRenewalMeansSignInAgain() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.refreshAnswer = () -> AuthFixtures.oauthError("unauthorized_client", 700016);
        LauncherException e = fails(ErrorCode.AUTH_EXPIRED, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertEquals("auth.error.appChanged", e.messageKey());
        assertFalse(accounts.hasSavedSignIn(AuthFixtures.UUID));
    }

    @Test
    void offlineStartNeedsARecentOwnershipCheck() throws Exception {
        assertFalse(accounts.canPlayOffline(AuthFixtures.UUID), "unknown account");
        signIn();
        assertTrue(accounts.canPlayOffline(AuthFixtures.UUID));
        MinecraftSession s = accounts.offlineSession(AuthFixtures.UUID);
        assertEquals(AuthFixtures.NAME, s.username());
        assertEquals(AuthFixtures.UUID, s.uuid());
        assertEquals(AccountService.OFFLINE_TOKEN, s.accessToken());
        assertEquals("msa", s.userType());

        fx.clock.advance(AccountService.OFFLINE_GRACE.plusHours(1));
        assertFalse(accounts.canPlayOffline(AuthFixtures.UUID));
        LauncherException e = fails(ErrorCode.AUTH_EXPIRED, () -> accounts.offlineSession(AuthFixtures.UUID));
        assertEquals("auth.error.offlineNotAllowed", e.messageKey());
    }

    @Test
    void offlineStartIsRefusedWhenTheGameIsNotOwned() throws Exception {
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.profileJson = null;
        fx.entitlementsJson = AuthFixtures.entitlements(false);
        fails(ErrorCode.AUTH_NOT_OWNED, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertFalse(accounts.canPlayOffline(AuthFixtures.UUID));
    }

    @Test
    void maintainRenewsASessionThatIsRunningLow() throws Exception {
        signIn();
        accounts.maintain(CancellationToken.NONE);
        assertEquals(0, fx.refreshCount.get(), "plenty left: nothing to do");
        fx.clock.advance(Duration.ofHours(21));
        fx.nextMcToken = AuthFixtures.MC_TOKEN_2;
        accounts.maintain(CancellationToken.NONE);
        assertEquals(1, fx.refreshCount.get());
        int logins = fx.loginCount.get();
        assertEquals(AuthFixtures.MC_TOKEN_2, accounts.session(AuthFixtures.UUID, CancellationToken.NONE).accessToken());
        assertEquals(logins, fx.loginCount.get(), "Play reuses the renewed token");
    }

    @Test
    void maintainNeverThrows() throws Exception {
        accounts.maintain(CancellationToken.NONE); // no accounts
        signIn();
        fx.clock.advance(Duration.ofHours(25));
        fx.refreshAnswer = () -> new AuthFixtures.Answer(503, "");
        accounts.maintain(CancellationToken.NONE);
        assertEquals(1, accounts.accounts().size());
    }

    // ---------------------------------------------------------------- secret store trouble

    /** a store whose writes or deletes can be made to fail */
    static final class FlakyStore implements SecretStore {
        final MemorySecretStore inner = new MemorySecretStore();
        volatile boolean failPuts;
        volatile boolean failDeletes;

        @Override
        public String name() {
            return "Flaky store";
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void put(String key, String value) {
            if (failPuts) {
                throw new SecretStoreException(name(), "keychain locked");
            }
            inner.put(key, value);
        }

        @Override
        public Optional<String> get(String key) {
            return inner.get(key);
        }

        @Override
        public void delete(String key) {
            if (failDeletes) {
                throw new SecretStoreException(name(), "keychain locked");
            }
            inner.delete(key);
        }
    }

    @Test
    void storeThatRefusesWritesStillLetsThePlayerPlayThisRun() throws Exception {
        FlakyStore store = new FlakyStore();
        store.failPuts = true;
        AccountService s = new AccountService(paths, store, fx.service(), fx.clock);
        assertTrue(s.remembersSignIn());
        assertEquals("Flaky store", s.secretStoreName());
        s.signIn(shown::add, CancellationToken.NONE);
        assertEquals(0, store.inner.size());
        assertEquals(AuthFixtures.MC_TOKEN, s.session(AuthFixtures.UUID, CancellationToken.NONE).accessToken());

        // when the store works again, renewed tokens go there
        store.failPuts = false;
        fx.clock.advance(Duration.ofHours(25));
        s.session(AuthFixtures.UUID, CancellationToken.NONE);
        assertEquals(Optional.of(AuthFixtures.MSA_REFRESH_2), store.inner.get("msa.refresh." + AuthFixtures.UUID));
    }

    @Test
    void storeThatFailsToReadIsReported() throws Exception {
        signIn();
        SecretStore broken = new SecretStore() {
            @Override
            public String name() {
                return "Broken store";
            }

            @Override
            public boolean isPersistent() {
                return true;
            }

            @Override
            public void put(String key, String value) {
            }

            @Override
            public Optional<String> get(String key) {
                throw new SecretStoreException(name(), "service not running");
            }

            @Override
            public void delete(String key) {
            }
        };
        AccountService s = new AccountService(paths, broken, fx.service(), fx.clock);
        LauncherException e = fails(ErrorCode.SECRET_STORE_UNAVAILABLE, () -> s.session(AuthFixtures.UUID, CancellationToken.NONE));
        assertTrue(e.userMessage().contains("Broken store"));
        assertEquals(1, s.accounts().size());
    }

    // ---------------------------------------------------------------- privacy

    @Test
    void logsNeverSeeATokenAndTheRedactorMasksThem() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                StringBuilder b = new StringBuilder(formatter.formatMessage(r));
                if (r.getThrown() != null) {
                    StringWriter sw = new StringWriter();
                    r.getThrown().printStackTrace(new PrintWriter(sw));
                    b.append('\n').append(sw);
                }
                lines.add(b.toString());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Logger logger = Logger.getLogger("dev.shardlight");
        Level before = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
        List<LauncherException> errors = new ArrayList<>();
        try {
            fx.devicePolls.add(AuthFixtures.oauthError("authorization_pending", 70016));
            fx.devicePolls.add(AuthFixtures.oauthError("slow_down", 70016));
            signIn();
            accounts.session(AuthFixtures.UUID, CancellationToken.NONE);
            fx.clock.advance(Duration.ofHours(25));
            fx.nextMcToken = AuthFixtures.MC_TOKEN_2;
            accounts.session(AuthFixtures.UUID, CancellationToken.NONE);
            fx.clock.advance(Duration.ofHours(25));
            fx.refreshAnswer = () -> AuthFixtures.oauthError("invalid_grant", 70000);
            errors.add(assertThrows(LauncherException.class, () -> accounts.session(AuthFixtures.UUID, CancellationToken.NONE)));
            fx.xstsError = 2148916235L;
            errors.add(assertThrows(LauncherException.class, this::signIn));
            accounts.signOut(AuthFixtures.UUID);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(before);
        }
        assertFalse(lines.isEmpty(), "the flow logs what it does");
        assertTrue(lines.stream().anyMatch(l -> l.contains("Signed in " + AuthFixtures.NAME)));
        StringBuilder everything = new StringBuilder();
        lines.forEach(l -> everything.append(l).append('\n'));
        for (LauncherException e : errors) {
            everything.append(e.getMessage()).append('\n').append(e.userMessage()).append('\n').append(e).append('\n');
        }
        for (String token : AuthFixtures.ALL_TOKENS) {
            assertFalse(everything.toString().contains(token), "a log line or error contains a token");
        }

        // every token the flow handled is registered with the redactor
        for (String token : AuthFixtures.ALL_TOKENS) {
            String redacted = Redactor.redact("Authorization header was " + token + " (" + token + ")");
            assertFalse(redacted.contains(token), "redactor didn't mask a token");
            assertTrue(redacted.contains(Redactor.MASK));
        }
    }

    // ---------------------------------------------------------------- accounts.json

    @Test
    void reloadsAfterRestart() throws Exception {
        signIn();
        AccountService next = new AccountService(paths, secrets, fx.service(), fx.clock);
        assertEquals(AuthFixtures.NAME, next.selected().orElseThrow().username);
        assertEquals(AuthFixtures.MC_TOKEN, next.session(AuthFixtures.UUID, CancellationToken.NONE).accessToken());
        assertNull(next.recoveredFrom());
        assertFalse(next.isReadOnly());
    }

    @Test
    void corruptFileIsSetAside() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(paths.accountsFile(), "{\"accounts\": [ {\"id\": ");
        AccountService s = new AccountService(paths, secrets, fx.service(), fx.clock);
        assertTrue(s.accounts().isEmpty());
        assertNotNull(s.recoveredFrom());
        assertTrue(Files.exists(s.recoveredFrom()));
        assertTrue(s.recoveredFrom().getFileName().toString().startsWith("accounts.json.corrupt-"));
        assertFalse(Files.exists(paths.accountsFile()));

        for (String bad : List.of("[1,2]", "\"text\"", "{\"schemaVersion\":\"one\"}", "{\"accounts\": {\"id\": 3}}")) {
            Files.writeString(paths.accountsFile(), bad);
            AccountService s2 = new AccountService(paths, secrets, fx.service(), fx.clock);
            assertTrue(s2.accounts().isEmpty(), bad);
            assertNotNull(s2.recoveredFrom(), bad);
        }
        Files.write(paths.accountsFile(), new byte[] {(byte) 0xC3, (byte) 0x28, '{', '}'});
        assertNotNull(new AccountService(paths, secrets, fx.service(), fx.clock).recoveredFrom(), "invalid UTF-8");
    }

    @Test
    void migratesAnUnversionedFileAndDropsTokenFields() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(paths.accountsFile(), "{\"selected\":\"" + AuthFixtures.UUID + "\",\"accounts\":[{\"id\":\""
            + AuthFixtures.UUID + "\",\"username\":\"Notch\",\"accessToken\":\"" + AuthFixtures.MC_TOKEN
            + "\",\"refreshToken\":\"" + AuthFixtures.MSA_REFRESH + "\"}]}");
        AccountService s = new AccountService(paths, secrets, fx.service(), fx.clock);
        assertEquals(AuthFixtures.UUID, s.selected().orElseThrow().id);
        assertEquals("msa", s.selected().orElseThrow().type);
        JsonObject file = JsonParser.parseString(accountsJson()).getAsJsonObject();
        assertEquals(1, file.get("schemaVersion").getAsInt());
        assertEquals(AuthFixtures.UUID, file.get("selectedId").getAsString());
        assertNoTokensOnDisk();
    }

    @Test
    void cleansUpMalformedEntries() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(paths.accountsFile(), "{\"schemaVersion\":1,\"selectedId\":\"gone0000000000000000000000000000\",\"accounts\":["
            + "null,{\"id\":\"../../evil\",\"username\":\"x\"},{\"username\":\"no id\"},"
            + "{\"id\":\"069A79F4-44E9-4726-A5BE-FCA90E38AAF5\",\"username\":\"Notch\"},"
            + "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"username\":\"Duplicate\"}]}");
        AccountService s = new AccountService(paths, secrets, fx.service(), fx.clock);
        assertEquals(1, s.accounts().size());
        assertEquals(AuthFixtures.UUID, s.accounts().get(0).id);
        assertEquals("Notch", s.accounts().get(0).username);
        assertEquals(AuthFixtures.UUID, s.selected().orElseThrow().id, "a dangling selection moves to an existing account");
        assertFalse(accountsJson().contains("evil"), "the cleaned list was saved");
    }

    @Test
    void newerFileIsNotOverwritten() throws Exception {
        Files.createDirectories(dir);
        String newer = "{\"schemaVersion\":99,\"selectedId\":null,\"accounts\":[],\"futureField\":true}";
        Files.writeString(paths.accountsFile(), newer);
        AccountService s = new AccountService(paths, secrets, fx.service(), fx.clock);
        assertTrue(s.isReadOnly());
        s.signIn(shown::add, CancellationToken.NONE);
        assertEquals(1, s.accounts().size(), "works for this run");
        assertEquals(newer, accountsJson(), "but the newer file is left alone");
    }

    @Test
    void returnedAccountsAreCopies() throws Exception {
        signIn();
        accounts.accounts().get(0).username = "Hacked";
        accounts.selected().orElseThrow().username = "Hacked";
        assertEquals(AuthFixtures.NAME, accounts.account(AuthFixtures.UUID).orElseThrow().username);
    }

    @Test
    void listenersHearEveryChange() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Runnable l = calls::incrementAndGet;
        accounts.addListener(l);
        accounts.addListener(() -> {
            throw new IllegalStateException("a broken listener doesn't stop the others");
        });
        signIn();
        assertEquals(1, calls.get());
        fx.profileJson = AuthFixtures.profile(UUID_2, "Jeb_");
        signIn();
        accounts.select(UUID_2);
        accounts.signOut(UUID_2);
        assertEquals(4, calls.get());
        accounts.removeListener(l);
        accounts.select(AuthFixtures.UUID);
        assertEquals(4, calls.get());
    }
}
