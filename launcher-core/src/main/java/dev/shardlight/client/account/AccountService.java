package dev.shardlight.client.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.auth.DeviceCode;
import dev.shardlight.client.auth.MemorySecretStore;
import dev.shardlight.client.auth.MicrosoftAuthService;
import dev.shardlight.client.auth.MinecraftLogin;
import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.auth.MsaTokens;
import dev.shardlight.client.auth.SecretStore;
import dev.shardlight.client.auth.SecretStoreException;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.storage.JsonMigrator;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.Json;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The signed-in Microsoft accounts. {@code accounts.json} lists them (name, UUID, xuid, skin, ownership,
 * timestamps, the selected one) and never holds a token; tokens live in the {@link SecretStore}:
 * {@code msa.refresh.<uuid>} (the Microsoft refresh token) and {@code mc.session.<uuid>} (the Minecraft
 * access token with its expiry, as JSON).
 *
 * <p>All methods are safe to call from several threads; the blocking ones ({@link #signIn},
 * {@link #session}) must run off the UI thread. Listeners added with {@link #addListener} run after every
 * change, on the thread that made it.
 */
public final class AccountService {
    private static final Logger LOG = Logger.getLogger(AccountService.class.getName());
    /** the accounts.json schema this launcher writes */
    public static final int SCHEMA_VERSION = 1;
    /** a cached Minecraft token is reused only while it has more than this left */
    public static final Duration REUSE_MARGIN = Duration.ofMinutes(5);
    static final JsonMigrator MIGRATOR = new JsonMigrator("accounts.json", SCHEMA_VERSION)
        .step(0, AccountService::migrate0to1);
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;
    private final SecretStore secrets;
    /** holds secrets the OS store refused to save, so this run still works */
    private final MemorySecretStore unsaved = new MemorySecretStore();
    private final MicrosoftAuthService auth;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private AccountsFile data = new AccountsFile();
    private boolean readOnly;
    private boolean readOnlyWarned;
    private Path recoveredFrom;

    /** load {@code accounts.json} from {@code paths} */
    public AccountService(AppPaths paths, SecretStore secrets, MicrosoftAuthService auth) throws LauncherException {
        this(paths, secrets, auth, Clock.systemUTC());
    }

    /** with an explicit clock (tests) */
    public AccountService(AppPaths paths, SecretStore secrets, MicrosoftAuthService auth, Clock clock) throws LauncherException {
        this.file = paths.accountsFile();
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.clock = Objects.requireNonNull(clock, "clock");
        load();
    }

    /** the key of an account's Microsoft refresh token in the secret store */
    public static String refreshTokenKey(String accountId) {
        return "msa.refresh." + accountId;
    }

    /** the key of an account's cached Minecraft session in the secret store */
    public static String sessionKey(String accountId) {
        return "mc.session." + accountId;
    }

    // ---------------------------------------------------------------- reading

    /** every account, in the order they were added (copies; changing them changes nothing) */
    public List<Account> accounts() {
        synchronized (lock) {
            List<Account> out = new ArrayList<>(data.accounts.size());
            for (Account a : data.accounts) {
                out.add(Json.copy(a, Account.class));
            }
            return out;
        }
    }

    /** one account by id */
    public Optional<Account> account(String accountId) {
        String id = normalizeId(accountId);
        synchronized (lock) {
            return find(data, id).map(a -> Json.copy(a, Account.class));
        }
    }

    /** the account used for launching, if any */
    public Optional<Account> selected() {
        synchronized (lock) {
            return data.selectedId == null ? Optional.empty() : find(data, data.selectedId).map(a -> Json.copy(a, Account.class));
        }
    }

    /** true when a Microsoft client id is configured, so sign-in can be offered */
    public boolean isSignInConfigured() {
        return auth.isConfigured();
    }

    /** true when sign-ins survive a restart (the OS secret store works); false = memory only, warn the user */
    public boolean remembersSignIn() {
        return secrets.isPersistent();
    }

    /**
     * True when {@code accountId} still has a usable saved sign-in (a refresh token, or a Minecraft token
     * that hasn't expired), so {@link #session} can work without asking the player to sign in again. False
     * for unknown accounts, after {@link ErrorCode#AUTH_EXPIRED}, or when the secret store can't be read.
     * May block briefly (macOS and Linux stores run a small command); call it off the UI thread.
     */
    public boolean hasSavedSignIn(String accountId) {
        String id = normalizeId(accountId);
        if (!ID.matcher(id).matches() || account(id).isEmpty()) {
            return false;
        }
        try {
            if (readSecret(refreshTokenKey(id)).isPresent()) {
                return true;
            }
            return readSecret(sessionKey(id)).flatMap(StoredSession::parse)
                .map(s -> s.expiresAt().isAfter(clock.instant()))
                .orElse(false);
        } catch (LauncherException e) {
            LOG.fine("Couldn't check the saved sign-in: " + e.code());
            return false;
        }
    }

    /** the user-facing name of the secret store in use */
    public String secretStoreName() {
        return secrets.name();
    }

    /** true when accounts.json came from a newer launcher; changes then aren't saved to it */
    public boolean isReadOnly() {
        synchronized (lock) {
            return readOnly;
        }
    }

    /** where an unreadable accounts.json was moved to, or null */
    public Path recoveredFrom() {
        synchronized (lock) {
            return recoveredFrom;
        }
    }

    // ---------------------------------------------------------------- changing

    /** make {@code accountId} the account used for launching; {@link ErrorCode#NOT_FOUND} when unknown */
    public void select(String accountId) throws LauncherException {
        String id = normalizeId(accountId);
        mutate(f -> {
            if (find(f, id).isEmpty()) {
                throw unknownAccount();
            }
            f.selectedId = id;
            return null;
        });
    }

    /**
     * Sign in a Microsoft account with the device-code flow and add (or update) it. {@code showCode} is
     * called once, on this thread, with the code and link to show; then this method waits until the player
     * has signed in on Microsoft's page (or the code expires, or {@code cancel} fires), checks Xbox Live,
     * game ownership and the profile, and stores the tokens. The first account is selected automatically.
     */
    public Account signIn(Consumer<DeviceCode> showCode, CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(showCode, "showCode");
        Objects.requireNonNull(cancel, "cancel");
        DeviceCode code = auth.startDeviceCode(cancel);
        try {
            showCode.accept(code);
        } catch (RuntimeException e) {
            throw new LauncherException(ErrorCode.INTERNAL, e, "error.internal", "showing the sign-in code: " + e.getClass().getSimpleName());
        }
        MsaTokens msa = auth.pollDeviceCode(code, cancel);
        MinecraftLogin login = auth.loginToMinecraft(msa, cancel);
        cancel.check();
        String id = login.uuid();
        ReentrantLock sessionLock = sessionLock(id);
        lockFor(sessionLock, cancel);
        try {
            storeTokens(id, login);
            Account saved = mutate(f -> upsert(f, login, true));
            LOG.info("Signed in " + login.username());
            return saved;
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * What the game needs to start as {@code accountId}. Reuses the stored Minecraft token while it has more
     * than {@link #REUSE_MARGIN} left; otherwise refreshes the Microsoft sign-in and redoes Xbox Live,
     * XSTS, Minecraft login and the profile check, storing the new tokens (Microsoft may rotate the
     * refresh token). Errors: {@link ErrorCode#AUTH_EXPIRED} when the saved sign-in can't be renewed (the
     * account stays listed; the player signs in again), {@link ErrorCode#NETWORK} when offline (account
     * kept), {@link ErrorCode#AUTH_NOT_OWNED} when the game is no longer owned, {@link ErrorCode#NOT_FOUND}
     * for an unknown account.
     */
    public MinecraftSession session(String accountId, CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(cancel, "cancel");
        String id = normalizeId(accountId);
        account(id).orElseThrow(AccountService::unknownAccount);
        ReentrantLock sessionLock = sessionLock(id);
        lockFor(sessionLock, cancel);
        try {
            // read again under the lock: another thread may have renewed or signed it out meanwhile
            Account account = account(id).orElseThrow(AccountService::unknownAccount);
            Optional<StoredSession> cached = readSecret(sessionKey(id)).flatMap(StoredSession::parse);
            // after a restart the token comes from the secret store, not from Microsoft: mask it from now on
            cached.ifPresent(c -> Redactor.register(c.accessToken()));
            Instant now = clock.instant();
            if (cached.isPresent() && cached.get().expiresAt().isAfter(now.plus(REUSE_MARGIN))) {
                touch(id);
                LOG.fine("Reusing the stored Minecraft session for " + account.username);
                return new MinecraftSession(account.username, id, cached.get().accessToken(), account.xuid, "msa", auth.clientId());
            }
            return refreshSession(account, cancel);
        } finally {
            sessionLock.unlock();
        }
    }

    /** sign out: forget the account and delete its tokens; another account is selected if any is left */
    public void signOut(String accountId) throws LauncherException {
        String id = normalizeId(accountId);
        if (!ID.matcher(id).matches()) {
            return;
        }
        ReentrantLock sessionLock = sessionLock(id);
        sessionLock.lock();
        try {
            SecretStoreException failure = null;
            for (String key : List.of(refreshTokenKey(id), sessionKey(id))) {
                try {
                    deleteSecret(key);
                } catch (SecretStoreException e) {
                    LOG.warning("Couldn't delete a saved sign-in: " + e.getMessage());
                    failure = failure == null ? e : failure;
                }
            }
            String name = mutate(f -> {
                Optional<Account> a = find(f, id);
                f.accounts.removeIf(x -> id.equals(x.id));
                if (id.equals(f.selectedId) || (f.selectedId != null && find(f, f.selectedId).isEmpty())) {
                    f.selectedId = f.accounts.isEmpty() ? null : f.accounts.get(0).id;
                }
                return a.map(x -> x.username).orElse(null);
            });
            if (name != null) {
                LOG.info("Signed out " + name);
            }
            if (failure != null) {
                throw new LauncherException(ErrorCode.SECRET_STORE_UNAVAILABLE, failure, "auth.error.signOutSecrets", failure.storeName());
            }
        } finally {
            sessionLock.unlock();
        }
    }

    /** run {@code listener} after every change to the account list or selection */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** stop calling a listener added with {@link #addListener} */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // ---------------------------------------------------------------- session refresh

    private MinecraftSession refreshSession(Account account, CancellationToken cancel) throws LauncherException {
        String id = account.id;
        Optional<String> refreshToken = readSecret(refreshTokenKey(id));
        refreshToken.ifPresent(Redactor::register);
        if (refreshToken.isEmpty()) {
            throw new LauncherException(ErrorCode.AUTH_EXPIRED, "auth.error.signInAgain", account.username);
        }
        MsaTokens msa;
        try {
            msa = auth.refresh(refreshToken.get(), cancel);
        } catch (LauncherException e) {
            if (e.code() == ErrorCode.AUTH_EXPIRED) {
                // the refresh token is dead: drop it so the next attempt asks to sign in without a network round trip
                deleteQuietly(refreshTokenKey(id));
                deleteQuietly(sessionKey(id));
                throw new LauncherException(ErrorCode.AUTH_EXPIRED, e, "auth.error.signInAgain", account.username);
            }
            throw e;
        }
        if (msa.refreshToken() != null && !msa.refreshToken().equals(refreshToken.get())) {
            // Microsoft rotated the refresh token: keep the new one right away
            putSecret(refreshTokenKey(id), msa.refreshToken());
        }
        MinecraftLogin login;
        try {
            login = auth.loginToMinecraft(msa, cancel);
        } catch (LauncherException e) {
            if (e.code() == ErrorCode.AUTH_NOT_OWNED) {
                markNotOwned(id);
            }
            throw e;
        }
        if (!id.equals(login.uuid())) {
            throw new LauncherException(ErrorCode.AUTH_FAILED, "auth.error.profileChanged", account.username);
        }
        putSecret(sessionKey(id), new StoredSession(login.accessToken(), login.expiresAt()).toJson());
        Account updated = mutate(f -> upsert(f, login, false));
        if (updated == null) {
            // signed out while we were refreshing
            deleteQuietly(refreshTokenKey(id));
            deleteQuietly(sessionKey(id));
            throw unknownAccount();
        }
        LOG.info("Renewed the Minecraft session for " + login.username());
        return new MinecraftSession(login.username(), id, login.accessToken(),
            login.xuid() != null ? login.xuid() : account.xuid, "msa", auth.clientId());
    }

    private void storeTokens(String id, MinecraftLogin login) {
        if (login.msa() != null && login.msa().refreshToken() != null) {
            putSecret(refreshTokenKey(id), login.msa().refreshToken());
        } else {
            deleteQuietly(refreshTokenKey(id));
        }
        putSecret(sessionKey(id), new StoredSession(login.accessToken(), login.expiresAt()).toJson());
    }

    /** add or update the account from a login; returns a copy, or null when {@code allowAdd} is false and it's gone */
    private Account upsert(AccountsFile f, MinecraftLogin login, boolean allowAdd) {
        long now = clock.millis();
        Account a = find(f, login.uuid()).orElse(null);
        if (a == null) {
            if (!allowAdd) {
                return null;
            }
            a = new Account();
            a.id = login.uuid();
            a.addedAt = now;
            f.accounts.add(a);
        }
        a.username = login.username();
        if (login.xuid() != null) {
            a.xuid = login.xuid();
        }
        a.type = "msa";
        a.lastUsedAt = now;
        a.skinUrl = login.skinUrl();
        a.skinVariant = login.skinVariant();
        a.ownsGame = login.ownsGame();
        a.entitlementCheckedAt = now;
        if (f.selectedId == null || find(f, f.selectedId).isEmpty()) {
            f.selectedId = a.id;
        }
        return Json.copy(a, Account.class);
    }

    private void markNotOwned(String id) {
        try {
            mutate(f -> {
                find(f, id).ifPresent(a -> {
                    a.ownsGame = false;
                    a.entitlementCheckedAt = clock.millis();
                });
                return null;
            });
        } catch (LauncherException e) {
            LOG.warning("Couldn't save the ownership check: " + e.getMessage());
        }
    }

    private void touch(String id) {
        try {
            mutate(f -> {
                find(f, id).ifPresent(a -> a.lastUsedAt = clock.millis());
                return null;
            });
        } catch (LauncherException e) {
            LOG.warning("Couldn't save the account list: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- secrets

    private void putSecret(String key, String value) {
        try {
            secrets.put(key, value);
            unsaved.delete(key);
        } catch (SecretStoreException e) {
            // keep it for this run so the player can still play; it won't survive a restart
            LOG.warning("Couldn't save a sign-in token, keeping it in memory only: " + e.getMessage());
            unsaved.put(key, value);
        }
    }

    private Optional<String> readSecret(String key) throws LauncherException {
        Optional<String> v = unsaved.get(key);
        if (v.isPresent()) {
            return v;
        }
        try {
            return secrets.get(key);
        } catch (SecretStoreException e) {
            throw e.toLauncherException();
        }
    }

    private void deleteSecret(String key) {
        unsaved.delete(key);
        secrets.delete(key);
    }

    private void deleteQuietly(String key) {
        try {
            deleteSecret(key);
        } catch (SecretStoreException e) {
            LOG.warning("Couldn't delete a saved sign-in: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- state and file

    @FunctionalInterface
    private interface Change<T> {
        T apply(AccountsFile f) throws LauncherException;
    }

    /** apply {@code change} to a copy, save it, swap it in, then notify listeners */
    private <T> T mutate(Change<T> change) throws LauncherException {
        T result;
        synchronized (lock) {
            AccountsFile copy = Json.copy(data, AccountsFile.class);
            result = change.apply(copy);
            save(copy);
            data = copy;
        }
        fireChanged();
        return result;
    }

    private void fireChanged() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                LOG.warning("An account listener failed: " + e);
            }
        }
    }

    /** caller holds {@link #lock} */
    private void save(AccountsFile f) throws LauncherException {
        if (readOnly) {
            if (!readOnlyWarned) {
                readOnlyWarned = true;
                LOG.warning("accounts.json is from a newer Shardlight Client; account changes won't be saved");
            }
            return;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                AtomicFiles.writeJson(file, f);
                return;
            } catch (LauncherException e) {
                // Windows: a scanner may briefly hold the file
                boolean locked = e.getCause() instanceof AccessDeniedException
                    || (e.getCause() instanceof FileSystemException && !(e.getCause() instanceof NoSuchFileException));
                if (!locked || attempt >= 5) {
                    throw e;
                }
                sleepQuietly(25L << attempt);
            }
        }
    }

    private void load() throws LauncherException {
        synchronized (lock) {
            data = new AccountsFile();
            if (!Files.exists(file)) {
                return;
            }
            String text;
            try {
                text = readWithRetry();
            } catch (CharacterCodingException e) {
                setAside();
                return;
            } catch (IOException e) {
                throw LauncherException.io(e, file.toString());
            }
            JsonObject o;
            try {
                JsonElement e = JsonParser.parseString(text);
                if (e == null || !e.isJsonObject()) {
                    throw new JsonParseException("not a JSON object");
                }
                o = e.getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                setAside();
                return;
            }
            boolean migrated;
            try {
                readOnly = MIGRATOR.isNewer(o);
                migrated = MIGRATOR.migrate(o);
            } catch (JsonParseException | NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
                // a schemaVersion that isn't a whole number
                readOnly = false;
                setAside();
                return;
            }
            AccountsFile f;
            try {
                f = Json.GSON.fromJson(o, AccountsFile.class);
            } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
                setAside();
                return;
            }
            boolean cleaned = sanitize(f);
            data = f;
            if ((migrated || cleaned) && !readOnly) {
                // no pre-migration copy: an old file could hold token fields that must not survive anywhere
                save(f);
            }
        }
    }

    private String readWithRetry() throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (CharacterCodingException | NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                if (attempt >= 5) {
                    throw e;
                }
                sleepQuietly(25L << attempt);
            }
        }
    }

    private void setAside() throws LauncherException {
        Path aside = file.resolveSibling("accounts.json.corrupt-" + LocalDateTime.now(clock).format(STAMP));
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
        recoveredFrom = aside;
        data = new AccountsFile();
        LOG.warning("accounts.json was unreadable and was moved to " + aside.getFileName() + "; please sign in again");
    }

    /** drop malformed or duplicate entries and fix the selection; returns true when anything changed */
    static boolean sanitize(AccountsFile f) {
        boolean changed = false;
        if (f.accounts == null) {
            f.accounts = new ArrayList<>();
            changed = true;
        }
        Map<String, Account> byId = new LinkedHashMap<>();
        for (Account a : f.accounts) {
            String id = a == null || a.id == null ? null : a.id.trim().replace("-", "").toLowerCase(Locale.ROOT);
            if (id == null || !ID.matcher(id).matches() || byId.containsKey(id)) {
                changed = true;
                continue;
            }
            if (!id.equals(a.id)) {
                a.id = id;
                changed = true;
            }
            if (a.type == null || a.type.isBlank()) {
                a.type = "msa";
                changed = true;
            }
            byId.put(id, a);
        }
        f.accounts = new ArrayList<>(byId.values());
        if (f.selectedId != null && !byId.containsKey(f.selectedId)) {
            f.selectedId = f.accounts.isEmpty() ? null : f.accounts.get(0).id;
            changed = true;
        }
        f.schemaVersion = Math.max(f.schemaVersion, SCHEMA_VERSION);
        return changed;
    }

    /** 0 -> 1: the first versioned schema. Renames "selected" and strips any token-like fields. */
    static void migrate0to1(JsonObject o) {
        if (!o.has("selectedId") && o.has("selected") && o.get("selected").isJsonPrimitive()) {
            o.add("selectedId", o.get("selected"));
        }
        o.remove("selected");
        JsonElement list = o.get("accounts");
        if (list != null && list.isJsonArray()) {
            JsonArray accounts = list.getAsJsonArray();
            for (JsonElement e : accounts) {
                if (e.isJsonObject()) {
                    for (String k : List.of("accessToken", "refreshToken", "access_token", "refresh_token", "token", "clientToken")) {
                        e.getAsJsonObject().remove(k);
                    }
                }
            }
        }
    }

    private static Optional<Account> find(AccountsFile f, String id) {
        for (Account a : f.accounts) {
            if (a.id.equals(id)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private ReentrantLock sessionLock(String id) {
        return sessionLocks.computeIfAbsent(id, k -> new ReentrantLock());
    }

    private static void lockFor(ReentrantLock l, CancellationToken cancel) throws LauncherException {
        try {
            while (!l.tryLock(100, TimeUnit.MILLISECONDS)) {
                cancel.check();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LauncherException.cancelled();
        }
    }

    private static String normalizeId(String accountId) {
        return accountId == null ? "" : accountId.trim().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static LauncherException unknownAccount() {
        return new LauncherException(ErrorCode.NOT_FOUND, "auth.error.unknownAccount");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
