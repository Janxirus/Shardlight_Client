package dev.shardlight.client.minecraft;

import com.google.gson.JsonParseException;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Mojang's list of every Minecraft version. Fetched from {@link Endpoints#versionManifest()}, cached to
 * {@code cache/version_manifest_v2.json} and kept in memory; when the network fails the cached copy is used,
 * so installed versions keep working offline. Thread-safe.
 */
public final class VersionManifestService {
    private static final Logger LOG = Logger.getLogger(VersionManifestService.class.getName());
    static final String CACHE_FILE = "version_manifest_v2.json";

    private final HttpService http;
    private final Endpoints endpoints;
    private final Path cacheFile;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Manifest memory;
    private volatile LauncherException lastFailure;
    private volatile long lastFailureAt;
    private volatile long failureQuietMillis = 30_000;

    public VersionManifestService(HttpService http, Endpoints endpoints, AppPaths paths) {
        this.http = http;
        this.endpoints = endpoints;
        this.cacheFile = paths.cache().resolve(CACHE_FILE);
    }

    /** the parsed manifest: every version (newest first, as Mojang lists them) plus the latest ids */
    record Manifest(List<VersionSummary> versions, String latestRelease, String latestSnapshot, boolean fromNetwork) {
    }

    // the JSON shapes
    record LatestJson(String release, String snapshot) {
    }

    record EntryJson(String id, String type, String url, String time, String releaseTime, String sha1,
                     Integer complianceLevel) {
    }

    record ManifestJson(LatestJson latest, List<EntryJson> versions) {
    }

    /**
     * Every version, newest first. {@code refresh} forces a network fetch; otherwise the in-memory copy is used
     * when there is one. Falls back to the disk cache when the network fails and throws only when neither
     * works.
     */
    public List<VersionSummary> versions(boolean refresh, CancellationToken cancel) throws LauncherException {
        return manifest(refresh, cancel).versions();
    }

    /** the entry for {@code id} (exact, case-sensitive match), if Mojang lists it */
    public Optional<VersionSummary> find(String id, CancellationToken cancel) throws LauncherException {
        if (id == null) {
            return Optional.empty();
        }
        for (VersionSummary v : manifest(false, cancel).versions()) {
            if (v.id().equals(id)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /** the newest release id */
    public String latestRelease(CancellationToken cancel) throws LauncherException {
        Manifest m = manifest(false, cancel);
        if (m.latestRelease() != null) {
            return m.latestRelease();
        }
        return m.versions().stream().filter(VersionSummary::isRelease).findFirst().map(VersionSummary::id)
            .orElseThrow(() -> new LauncherException(ErrorCode.INVALID_DATA, "install.error.manifestInvalid"));
    }

    /** the newest snapshot id */
    public String latestSnapshot(CancellationToken cancel) throws LauncherException {
        Manifest m = manifest(false, cancel);
        if (m.latestSnapshot() != null) {
            return m.latestSnapshot();
        }
        return m.versions().stream().findFirst().map(VersionSummary::id)
            .orElseThrow(() -> new LauncherException(ErrorCode.INVALID_DATA, "install.error.manifestInvalid"));
    }

    /** forget the in-memory copy (the next call fetches again) */
    public void invalidate() {
        memory = null;
        lastFailure = null;
    }

    /**
     * After a failure (no network and no cache), calls without {@code refresh} fail at once for this long
     * instead of waiting for the network again; tests set it to 0.
     */
    public void setFailureQuietMillis(long millis) {
        failureQuietMillis = Math.max(0, millis);
    }

    Manifest manifest(boolean refresh, CancellationToken cancel) throws LauncherException {
        Manifest m = memory;
        if (m != null && !refresh) {
            return m;
        }
        lockInterruptibly(cancel);
        try {
            m = memory;
            if (m != null && !refresh) {
                return m;
            }
            LauncherException recent = lastFailure;
            if (!refresh && recent != null && System.currentTimeMillis() - lastFailureAt < failureQuietMillis) {
                throw recent;
            }
            try {
                m = load(cancel);
            } catch (LauncherException e) {
                if (!e.isCancelled()) {
                    lastFailure = e;
                    lastFailureAt = System.currentTimeMillis();
                }
                throw e;
            }
            lastFailure = null;
            memory = m;
            return m;
        } finally {
            lock.unlock();
        }
    }

    private Manifest load(CancellationToken cancel) throws LauncherException {
        LauncherException networkError;
        try {
            String text = http.getText(endpoints.versionManifest(), cancel);
            Manifest m = parse(text, true);
            try {
                AtomicFiles.writeString(cacheFile, text);
            } catch (LauncherException e) {
                LOG.warning("Couldn't cache the version list: " + e.getMessage());
            }
            return m;
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                throw e;
            }
            networkError = e;
        }
        Optional<Manifest> cached = readCache();
        if (cached.isPresent()) {
            LOG.info("Using the cached version list (" + networkError.getMessage() + ")");
            return cached.get();
        }
        throw new LauncherException(networkError.code() == ErrorCode.INVALID_DATA ? ErrorCode.INVALID_DATA : ErrorCode.NETWORK,
            networkError, "install.error.manifestUnavailable");
    }

    private Optional<Manifest> readCache() {
        if (!Files.isRegularFile(cacheFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(Files.readString(cacheFile, StandardCharsets.UTF_8), false));
        } catch (IOException | LauncherException e) {
            LOG.warning("The cached version list is unreadable: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** parse {@code version_manifest_v2.json}; entries with unusable ids or URLs are skipped */
    static Manifest parse(String text, boolean fromNetwork) throws LauncherException {
        ManifestJson json;
        try {
            json = Json.read(text, ManifestJson.class);
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "install.error.manifestInvalid");
        }
        if (json == null || json.versions() == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "install.error.manifestInvalid");
        }
        List<VersionSummary> out = new ArrayList<>();
        for (EntryJson e : json.versions()) {
            if (e == null || e.id() == null || !PathSafety.isSafeSegment(e.id()) || e.url() == null) {
                continue;
            }
            URI url;
            try {
                url = URI.create(e.url().trim());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String sha1 = HashAlgo.SHA1.isValidHex(e.sha1()) ? e.sha1().toLowerCase(java.util.Locale.ROOT) : null;
            out.add(new VersionSummary(e.id(), e.type() == null ? "release" : e.type(), url, sha1,
                parseTime(e.releaseTime() != null ? e.releaseTime() : e.time()),
                e.complianceLevel() == null ? 0 : e.complianceLevel()));
        }
        String latestRelease = json.latest() == null ? null : json.latest().release();
        String latestSnapshot = json.latest() == null ? null : json.latest().snapshot();
        return new Manifest(Collections.unmodifiableList(out), latestRelease, latestSnapshot, fromNetwork);
    }

    static Instant parseTime(String s) {
        if (s == null || s.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(s.trim());
            } catch (DateTimeParseException e2) {
                return Instant.EPOCH;
            }
        }
    }

    private void lockInterruptibly(CancellationToken cancel) throws LauncherException {
        try {
            while (!lock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                cancel.check();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LauncherException.cancelled();
        }
    }
}
