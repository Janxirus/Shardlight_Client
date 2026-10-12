package dev.shardlight.client.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.OsInfo;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Signed launcher update checks. The distributor publishes a manifest
 * <pre>{@code
 * {"schemaVersion": 1, "channel": "stable",
 *  "latest": {"version": "1.2.0", "releasedAt": "2026-11-02T10:00:00Z", "notesUrl": "https://...",
 *             "changelog": "...", "downloads": [{"platform": "windows-x64", "type": "msi",
 *             "url": "https://...", "sha256": "<64 hex>", "size": 123}]},
 *  "signature": "<base64 Ed25519 signature of the canonical JSON of latest>"}
 * }</pre>
 * and the launcher holds the matching Ed25519 public key. {@link #check} verifies the signature before it
 * reads anything else, then offers the release only when it is newer than the running launcher, wasn't
 * skipped, and (on the stable channel) isn't a pre-release. {@link #download} fetches the file with the
 * signed SHA-256 and size checked; nothing is ever installed or run by this class.
 *
 * <p>Without a manifest URL or a public key the service is disabled ({@link #isConfigured()} false,
 * {@link #disabledReason()} says why); see docs/RELEASING.md. Blocking and thread-safe.
 */
public final class UpdateService {
    /** the manifest format this launcher reads */
    public static final int MANIFEST_SCHEMA_VERSION = 1;
    /** the platform id of 64-bit Windows downloads */
    public static final String PLATFORM_WINDOWS_X64 = "windows-x64";
    /** the default channel */
    public static final String CHANNEL_STABLE = "stable";
    /** the pre-release channel */
    public static final String CHANNEL_BETA = "beta";
    /** replaced by the channel name in a configured manifest URL, for one manifest per channel */
    public static final String CHANNEL_PLACEHOLDER = "{channel}";
    /** the placeholder as it appears in a {@link URI} (braces percent-encoded) */
    public static final String CHANNEL_PLACEHOLDER_ENCODED = "%7Bchannel%7D";

    private static final Logger LOG = Logger.getLogger(UpdateService.class.getName());
    private static final int MAX_MANIFEST_BYTES = 1 << 20;
    private static final long MAX_DOWNLOAD_BYTES = 2L << 30;
    private static final int MAX_CHANGELOG_CHARS = 20_000;
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern PLATFORM = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Set<String> TYPES = Set.of(UpdateInfo.TYPE_MSI, UpdateInfo.TYPE_ZIP);

    private final HttpService http;
    private final URI manifestUrl;
    private final PublicKey publicKey;
    private final String currentVersion;
    private final SemVer current;
    private final DownloadManager downloads;
    private final String disabledReasonKey;
    private final Set<SemVer> skipped = ConcurrentHashMap.newKeySet();

    /**
     * @param http           the launcher's HTTP service
     * @param manifestUrl    https address of the manifest, or null; {@code %7Bchannel%7D} in it is replaced
     *                       by the channel
     * @param publicKey      the distributor's Ed25519 public key, or null
     * @param currentVersion the running launcher's version ({@code BuildInfo.VERSION})
     */
    public UpdateService(HttpService http, URI manifestUrl, PublicKey publicKey, String currentVersion) {
        this(http, manifestUrl, publicKey, currentVersion, null);
    }

    /**
     * Like {@link #UpdateService(HttpService, URI, PublicKey, String)}, downloading through a shared
     * {@link DownloadManager} so the downloads list shows the file; null makes a private one per download.
     */
    public UpdateService(HttpService http, URI manifestUrl, PublicKey publicKey, String currentVersion,
                         DownloadManager downloads) {
        this.http = Objects.requireNonNull(http, "http");
        this.manifestUrl = manifestUrl;
        this.publicKey = publicKey;
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
        this.current = SemVer.tryParse(this.currentVersion).orElse(null);
        this.downloads = downloads;
        this.disabledReasonKey = reason(http, manifestUrl, publicKey, current);
        if (disabledReasonKey != null) {
            LOG.info("Launcher updates disabled: " + I18n.english(disabledReasonKey));
        }
    }

    /**
     * The service configured with the manifest URL and public key baked into this build
     * ({@link UpdateConfig#bundled()}); a blank or invalid value leaves it disabled with the reason.
     */
    public static UpdateService fromBundledConfig(HttpService http, String currentVersion) {
        return fromBundledConfig(http, currentVersion, null);
    }

    /** {@link #fromBundledConfig(HttpService, String)} downloading through a shared {@link DownloadManager} */
    public static UpdateService fromBundledConfig(HttpService http, String currentVersion, DownloadManager downloads) {
        UpdateConfig c = UpdateConfig.bundled();
        return new UpdateService(http, c.manifestUrl().orElse(null), c.publicKey().orElse(null), currentVersion,
            downloads);
    }

    private static String reason(HttpService http, URI url, PublicKey key, SemVer current) {
        if (url == null || url.toString().isBlank()) {
            return "update.disabled.noManifestUrl";
        }
        if (key == null) {
            return "update.disabled.noPublicKey";
        }
        if (!UpdateKeys.isEd25519(key)) {
            return "update.disabled.badPublicKey";
        }
        if (current == null) {
            return "update.disabled.badCurrentVersion";
        }
        try {
            http.validate(withChannel(url, CHANNEL_STABLE));
        } catch (LauncherException | URISyntaxException e) {
            return "update.disabled.insecureUrl";
        }
        return null;
    }

    /** the manifest URL for a channel: the placeholder ({@code %7Bchannel%7D} in a URI) replaced */
    static URI withChannel(URI template, String channel) throws URISyntaxException {
        String enc = URLEncoder.encode(channel, StandardCharsets.UTF_8);
        String s = template.toString()
            .replace(CHANNEL_PLACEHOLDER_ENCODED, enc)
            .replace(CHANNEL_PLACEHOLDER_ENCODED.toLowerCase(Locale.ROOT), enc)
            .replace(CHANNEL_PLACEHOLDER, enc);
        return new URI(s);
    }

    // ---------------------------------------------------------------- state

    /** true when a manifest URL and a valid public key are configured, so updates can be checked */
    public boolean isConfigured() {
        return disabledReasonKey == null;
    }

    /** the i18n key saying why updates are off, empty when configured */
    public Optional<String> disabledReasonKey() {
        return Optional.ofNullable(disabledReasonKey);
    }

    /** the localized reason updates are off, empty when configured */
    public Optional<String> disabledReason() {
        return disabledReasonKey().map(I18n::tr);
    }

    /** the running launcher's version as given */
    public String currentVersion() {
        return currentVersion;
    }

    /** the configured manifest URL, possibly with the channel placeholder, or null */
    public URI manifestUrl() {
        return manifestUrl;
    }

    /** never offer this version again (the user's "skip this version"); invalid versions are ignored */
    public void skipVersion(String version) {
        SemVer.tryParse(version).ifPresent(skipped::add);
    }

    /** offer this version again */
    public void unskipVersion(String version) {
        SemVer.tryParse(version).ifPresent(skipped::remove);
    }

    /** replace the skipped versions (for example from {@code LauncherSettings.updates.skippedVersion}) */
    public void setSkippedVersions(Collection<String> versions) {
        skipped.clear();
        if (versions != null) {
            for (String v : versions) {
                skipVersion(v);
            }
        }
    }

    /** the skipped versions, sorted */
    public Set<String> skippedVersions() {
        Set<String> out = new TreeSet<>();
        for (SemVer v : skipped) {
            out.add(v.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------- check

    /**
     * Fetch the manifest for {@code channel} ({@code stable} or {@code beta}), verify its signature, and
     * return the release it offers when that is newer than the running launcher and not skipped. On the
     * stable channel pre-releases are never offered. Throws {@link ErrorCode#API_KEY_MISSING}
     * ({@code update.error.notConfigured}) when the service is disabled, {@link ErrorCode#SIGNATURE_INVALID}
     * when the signature doesn't verify, and {@link ErrorCode#INVALID_DATA} for a malformed manifest.
     */
    public Optional<UpdateInfo> check(String channel, CancellationToken cancel) throws LauncherException {
        if (!isConfigured()) {
            throw new LauncherException(ErrorCode.API_KEY_MISSING, "update.error.notConfigured", I18n.tr(disabledReasonKey));
        }
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        String ch = channel == null || channel.isBlank() ? CHANNEL_STABLE : channel.trim().toLowerCase(Locale.ROOT);
        if (!CHANNEL.matcher(ch).matches()) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "update.error.badChannel", String.valueOf(channel));
        }
        URI url;
        try {
            url = withChannel(manifestUrl, ch);
        } catch (URISyntaxException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "update.error.badManifest", e.getMessage());
        }
        HttpService.Response r = http.execute(HttpService.Request.get(url)
            .withHeaders(Map.of("Accept", "application/json", "Cache-Control", "no-cache"))
            .withTimeout(Duration.ofSeconds(20)), c);
        if (r.status() == 404) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "update.error.noManifest", url.getHost());
        }
        r.requireOk();
        if (r.body().length > MAX_MANIFEST_BYTES) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "update.error.badManifest", "too large");
        }
        UpdateInfo info = verifyManifest(new String(r.body(), StandardCharsets.UTF_8), publicKey);
        SemVer offered = info.semVer();
        if (!offered.isNewerThan(current)) {
            LOG.fine(() -> "No newer launcher: offered " + offered + ", running " + current);
            return Optional.empty();
        }
        if (offered.isPreRelease() && CHANNEL_STABLE.equals(ch)) {
            LOG.info("Ignoring pre-release " + offered + " on the stable channel");
            return Optional.empty();
        }
        if (skipped.contains(offered)) {
            LOG.info("Launcher " + offered + " is available but was skipped");
            return Optional.empty();
        }
        LOG.info("Launcher update available: " + offered + " (running " + current + ")");
        return Optional.of(info);
    }

    /**
     * Verify a manifest's signature with {@code key} and read the release it describes. The signature is
     * checked over the canonical JSON of {@code latest} before any field is used. Throws
     * {@link ErrorCode#SIGNATURE_INVALID} ({@code update.error.badSignature}) when it doesn't verify and
     * {@link ErrorCode#INVALID_DATA} / {@link ErrorCode#UNSUPPORTED_VERSION} for a malformed or newer manifest.
     */
    public static UpdateInfo verifyManifest(String manifestJson, PublicKey key) throws LauncherException {
        Objects.requireNonNull(key, "key");
        JsonObject root = parseStrict(manifestJson);
        JsonElement latest = root.get("latest");
        JsonElement sig = root.get("signature");
        if (latest == null || !latest.isJsonObject() || sig == null || !sig.isJsonPrimitive() || !sig.getAsJsonPrimitive().isString()) {
            throw new LauncherException(ErrorCode.SIGNATURE_INVALID, "update.error.badSignature");
        }
        byte[] signature;
        try {
            String s = sig.getAsString().replaceAll("\\s", "");
            signature = s.contains("-") || s.contains("_") ? Base64.getUrlDecoder().decode(s) : Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new LauncherException(ErrorCode.SIGNATURE_INVALID, e, "update.error.badSignature");
        }
        byte[] signed;
        try {
            signed = CanonicalJson.bytes(latest);
        } catch (IllegalArgumentException e) {
            throw new LauncherException(ErrorCode.SIGNATURE_INVALID, e, "update.error.badSignature");
        }
        if (signature.length != 64 || !verify(signed, signature, key)) {
            LOG.warning("Launcher update manifest signature did not verify; ignoring it");
            throw new LauncherException(ErrorCode.SIGNATURE_INVALID, "update.error.badSignature");
        }
        // trusted from here on (the channel and schemaVersion are outside the signed part)
        JsonElement schema = root.get("schemaVersion");
        if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()) {
            throw badManifest("schemaVersion");
        }
        if (!String.valueOf(MANIFEST_SCHEMA_VERSION).equals(schema.getAsString())) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "update.error.newerManifest", schema.getAsString());
        }
        String channel = optString(root, "channel").map(s -> s.toLowerCase(Locale.ROOT)).orElse(CHANNEL_STABLE);
        return readLatest(latest.getAsJsonObject(), channel);
    }

    /** true when {@code signature} is a valid Ed25519 signature of {@code data} by {@code key} */
    public static boolean verify(byte[] data, byte[] signature, PublicKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOG.fine(() -> "Signature check failed: " + e);
            return false;
        }
    }

    private static JsonObject parseStrict(String text) throws LauncherException {
        String t = text != null && text.startsWith("\uFEFF") ? text.substring(1) : text;
        if (t == null || t.isBlank()) {
            throw badManifest("empty");
        }
        try (JsonReader reader = new JsonReader(new StringReader(t))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement e = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw badManifest("trailing data");
            }
            if (!e.isJsonObject()) {
                throw badManifest("not an object");
            }
            return e.getAsJsonObject();
        } catch (JsonParseException | IOException | IllegalStateException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "update.error.badManifest", String.valueOf(e.getMessage()));
        }
    }

    private static UpdateInfo readLatest(JsonObject latest, String channel) throws LauncherException {
        String version = optString(latest, "version").orElseThrow(() -> badManifest("latest.version"));
        SemVer v = SemVer.tryParse(version).orElseThrow(() -> badManifest("latest.version"));
        Instant released = optString(latest, "releasedAt").map(UpdateService::instant).orElse(null);
        URI notes = null;
        Optional<String> notesText = optString(latest, "notesUrl");
        if (notesText.isPresent() && !notesText.get().isBlank()) {
            notes = httpsUri(notesText.get()).orElseThrow(() -> badManifest("latest.notesUrl"));
        }
        String changelog = optString(latest, "changelog").orElse("");
        if (changelog.length() > MAX_CHANGELOG_CHARS) {
            changelog = changelog.substring(0, MAX_CHANGELOG_CHARS) + "\u2026";
        }
        List<UpdateInfo.Download> list = new ArrayList<>();
        JsonElement dl = latest.get("downloads");
        if (dl == null || !dl.isJsonArray()) {
            throw badManifest("latest.downloads");
        }
        JsonArray arr = dl.getAsJsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                throw badManifest("latest.downloads");
            }
            JsonObject d = el.getAsJsonObject();
            String platform = optString(d, "platform").map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
            String type = optString(d, "type").map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
            if (!PLATFORM.matcher(platform).matches()) {
                throw badManifest("downloads.platform");
            }
            if (!TYPES.contains(type)) {
                LOG.info("Ignoring update download of unknown type " + type);
                continue;
            }
            URI url = optString(d, "url").flatMap(UpdateService::httpsUri).orElseThrow(() -> badManifest("downloads.url"));
            String sha = optString(d, "sha256").map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
            if (!HashAlgo.SHA256.isValidHex(sha)) {
                throw badManifest("downloads.sha256");
            }
            JsonElement sz = d.get("size");
            long size;
            try {
                size = sz != null && sz.isJsonPrimitive() && sz.getAsJsonPrimitive().isNumber() ? Long.parseLong(sz.getAsString()) : -1;
            } catch (NumberFormatException e) {
                size = -1;
            }
            if (size <= 0 || size > MAX_DOWNLOAD_BYTES) {
                throw badManifest("downloads.size");
            }
            list.add(new UpdateInfo.Download(platform, type, url, sha, size));
        }
        return new UpdateInfo(v.toString(), channel, released, notes, changelog, list);
    }

    private static Instant instant(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static Optional<URI> httpsUri(String s) {
        try {
            URI u = new URI(s.trim());
            if ("https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank() && u.getUserInfo() == null) {
                return Optional.of(u);
            }
        } catch (URISyntaxException e) {
            // not a URL
        }
        return Optional.empty();
    }

    private static Optional<String> optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? Optional.of(e.getAsString()) : Optional.empty();
    }

    private static LauncherException badManifest(String what) {
        return new LauncherException(ErrorCode.INVALID_DATA, "update.error.badManifest", what);
    }

    // ---------------------------------------------------------------- download

    /**
     * Download the release's file for {@code platform} (the msi installer when both are offered) into
     * {@code dir}, checking the signed SHA-256 and size; an already downloaded valid file is reused. Returns
     * the file. Never installs or runs it. Throws {@link ErrorCode#NOT_FOUND}
     * ({@code update.error.noDownload}) when the release has nothing for the platform.
     */
    public Path download(UpdateInfo info, String platform, Path dir, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        Objects.requireNonNull(info, "info");
        UpdateInfo.Download d = info.download(platform == null ? "" : platform)
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "update.error.noDownload", info.version(), String.valueOf(platform)));
        return download(info, d, dir, progress, cancel);
    }

    /** download one specific file of a release (for example the zip instead of the msi), as {@link #download} */
    public Path download(UpdateInfo info, UpdateInfo.Download d, Path dir, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(d, "download");
        Objects.requireNonNull(dir, "dir");
        if (!info.downloads().contains(d)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "update.error.noDownload", info.version(), d.platform());
        }
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        http.validate(d.url());
        // the name comes from signed, validated parts only, never from the URL
        String name = "ShardlightClient-" + SemVer.parse(info.version()) + "-" + d.platform() + "." + d.type();
        if (!PathSafety.isSafeFileName(name)) {
            name = PathSafety.sanitizeFileName(name);
        }
        Path target = PathSafety.resolveInside(dir, name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        DownloadRequest req = new DownloadRequest(d.url(), List.of(), target, HashAlgo.SHA256, d.sha256(), d.size(),
            name, false, false);
        if (DownloadManager.isValid(req)) {
            LOG.info("Launcher update already downloaded: " + target);
            return target;
        }
        p.stage("update.stage.download");
        if (downloads != null) {
            downloads.downloadAll(I18n.tr("update.download.group"), List.of(req), "update.stage.download", p, c);
        } else {
            try (DownloadManager own = new DownloadManager(http, null, 1, 3)) {
                own.downloadAll(I18n.tr("update.download.group"), List.of(req), "update.stage.download", p, c);
            }
        }
        if (!DownloadManager.isValid(req)) {
            throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "update.error.downloadDamaged", name);
        }
        LOG.info("Downloaded launcher " + info.version() + " to " + target);
        return target;
    }

    /** the platform id of this computer ({@code windows-x64}, {@code linux-x64}, {@code macos-arm64}...) */
    public static String currentPlatform() {
        return platformOf(OsInfo.current());
    }

    /** the platform id of an OS/architecture */
    public static String platformOf(OsInfo os) {
        String o = switch (os.os()) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case LINUX -> "linux";
            case OTHER -> "other";
        };
        String a = switch (os.arch()) {
            case X64 -> "x64";
            case X86 -> "x86";
            case ARM64 -> "arm64";
            case ARM32 -> "arm32";
            case OTHER -> "other";
        };
        return o + "-" + a;
    }
}
