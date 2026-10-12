package dev.shardlight.client.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {
    @TempDir
    Path dir;
    TestHttpServer server;
    HttpService http;
    KeyPair keys;
    KeyPair otherKeys;
    byte[] installer;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestHttpServer();
        http = HttpService.forTests();
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        keys = g.generateKeyPair();
        otherKeys = g.generateKeyPair();
        installer = new byte[50_000];
        new Random(7).nextBytes(installer);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // ------------------------------------------------------------------ helpers

    private JsonObject latest(String version, String url, String sha256, long size) {
        JsonObject l = new JsonObject();
        l.addProperty("version", version);
        l.addProperty("releasedAt", "2026-11-02T10:00:00Z");
        l.addProperty("notesUrl", "https://example.org/shardlight-client/releases/" + version);
        l.addProperty("changelog", "Faster start-up.\nNew \"Ember\" look.");
        JsonArray downloads = new JsonArray();
        JsonObject zip = new JsonObject();
        zip.addProperty("platform", "windows-x64");
        zip.addProperty("type", "zip");
        zip.addProperty("url", url.replace(".msi", ".zip"));
        zip.addProperty("sha256", sha256);
        zip.addProperty("size", size);
        downloads.add(zip);
        JsonObject msi = new JsonObject();
        msi.addProperty("platform", "windows-x64");
        msi.addProperty("type", "msi");
        msi.addProperty("url", url);
        msi.addProperty("sha256", sha256);
        msi.addProperty("size", size);
        downloads.add(msi);
        l.add("downloads", downloads);
        return l;
    }

    private JsonObject latest(String version) {
        return latest(version, "https://example.org/dl/ShardlightClient-" + version + ".msi",
            Hashing.hash(installer, HashAlgo.SHA256), installer.length);
    }

    private static String sign(JsonObject latest, PrivateKey key) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(CanonicalJson.bytes(latest));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private String manifest(JsonObject latest, PrivateKey signer) throws Exception {
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", 1);
        m.addProperty("channel", "stable");
        m.add("latest", latest);
        m.addProperty("signature", sign(latest, signer));
        // pretty-printed with other key order: canonical form must not depend on the served layout
        return dev.shardlight.client.util.Json.GSON.toJson(m);
    }

    private UpdateService service(String current) {
        return new UpdateService(http, server.uri("/updates/stable.json"), keys.getPublic(), current);
    }

    private void serve(String json) {
        server.json("/updates/stable.json", json);
    }

    // ------------------------------------------------------------------ check

    @Test
    void offersNewerSignedRelease() throws Exception {
        serve(manifest(latest("1.2.0"), keys.getPrivate()));
        UpdateService s = service("1.1.3");
        assertTrue(s.isConfigured());
        Optional<UpdateInfo> info = s.check("stable", CancellationToken.NONE);
        assertTrue(info.isPresent());
        UpdateInfo u = info.get();
        assertEquals("1.2.0", u.version());
        assertEquals("stable", u.channel());
        assertEquals(Instant.parse("2026-11-02T10:00:00Z"), u.releasedAt());
        assertEquals(URI.create("https://example.org/shardlight-client/releases/1.2.0"), u.notesUrl());
        assertEquals("Faster start-up.\nNew \"Ember\" look.", u.changelog());
        assertEquals(2, u.downloads().size());
        assertEquals("msi", u.download("windows-x64").orElseThrow().type(), "msi preferred");
        assertEquals("zip", u.download("WINDOWS-X64", "zip").orElseThrow().type());
        assertTrue(u.download("linux-x64").isEmpty());
        assertFalse(u.isPreRelease());
    }

    @Test
    void verifiesManifestSignedWithOpensslAndPython() throws Exception {
        // made with the exact recipe in docs/RELEASING.md (openssl genpkey/pkeyutl, Python json.dumps)
        String pem;
        String json;
        try (InputStream a = getClass().getResourceAsStream("/update/openssl-public.pem");
             InputStream b = getClass().getResourceAsStream("/update/openssl-signed-manifest.json")) {
            assertNotNull(a);
            assertNotNull(b);
            pem = new String(a.readAllBytes(), StandardCharsets.UTF_8);
            json = new String(b.readAllBytes(), StandardCharsets.UTF_8);
        }
        PublicKey key = UpdateKeys.parsePublicKey(pem);
        UpdateInfo u = UpdateService.verifyManifest(json, key);
        assertEquals("1.2.0", u.version());
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", u.download("windows-x64").orElseThrow().sha256());
        // and a one-character change breaks it
        LauncherException e = assertThrows(LauncherException.class,
            () -> UpdateService.verifyManifest(json.replace("\"size\": 4", "\"size\": 5"), key));
        assertEquals(ErrorCode.SIGNATURE_INVALID, e.code());
        // a different key doesn't verify it
        assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(json, keys.getPublic()));
    }

    @Test
    void tamperedVersionIsRejected() throws Exception {
        String good = manifest(latest("1.2.0"), keys.getPrivate());
        serve(good.replace("\"version\": \"1.2.0\"", "\"version\": \"9.9.9\""));
        LauncherException e = assertThrows(LauncherException.class, () -> service("1.0.0").check("stable", CancellationToken.NONE));
        assertEquals(ErrorCode.SIGNATURE_INVALID, e.code());
        assertEquals("update.error.badSignature", e.messageKey());
    }

    @Test
    void tamperedDownloadIsRejected() throws Exception {
        JsonObject l = latest("1.2.0");
        String good = manifest(l, keys.getPrivate());
        String evilUrl = good.replace("https://example.org/dl/ShardlightClient-1.2.0.msi", "https://evil.example/x.msi");
        String evilHash = good.replace(Hashing.hash(installer, HashAlgo.SHA256), "0".repeat(64));
        for (String bad : List.of(evilUrl, evilHash)) {
            assertFalse(bad.equals(good));
            LauncherException e = assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(bad, keys.getPublic()));
            assertEquals(ErrorCode.SIGNATURE_INVALID, e.code());
        }
        // an extra field added to latest after signing is tampering too
        JsonObject m = dev.shardlight.client.util.Json.parse(good).getAsJsonObject();
        m.getAsJsonObject("latest").addProperty("extra", "x");
        assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(m.toString(), keys.getPublic()));
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        serve(manifest(latest("1.2.0"), otherKeys.getPrivate()));
        LauncherException e = assertThrows(LauncherException.class, () -> service("1.0.0").check("stable", CancellationToken.NONE));
        assertEquals(ErrorCode.SIGNATURE_INVALID, e.code());
    }

    @Test
    void missingOrBrokenSignatureIsRejected() throws Exception {
        JsonObject l = latest("1.2.0");
        JsonObject noSig = new JsonObject();
        noSig.addProperty("schemaVersion", 1);
        noSig.add("latest", l);
        assertEquals(ErrorCode.SIGNATURE_INVALID,
            assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(noSig.toString(), keys.getPublic())).code());
        for (String sig : List.of("", "not base64!!", Base64.getEncoder().encodeToString(new byte[64]),
            Base64.getEncoder().encodeToString(new byte[10]))) {
            JsonObject m = noSig.deepCopy();
            m.addProperty("signature", sig);
            assertEquals(ErrorCode.SIGNATURE_INVALID,
                assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(m.toString(), keys.getPublic())).code(), sig);
        }
        JsonObject numberSig = noSig.deepCopy();
        numberSig.addProperty("signature", 5);
        assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(numberSig.toString(), keys.getPublic()));
    }

    @Test
    void malformedJsonIsInvalidData() {
        for (String bad : List.of("", "[]", "{", "{\"latest\":{}} trailing", "{latest: {}}", "{'a':1}")) {
            LauncherException e = assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(bad, keys.getPublic()), bad);
            assertTrue(e.code() == ErrorCode.INVALID_DATA || e.code() == ErrorCode.SIGNATURE_INVALID, bad);
        }
    }

    @Test
    void fractionalNumbersInSignedDataAreRefused() throws Exception {
        JsonObject l = latest("1.2.0");
        l.addProperty("weight", 1.5);
        // even correctly signed by the distributor, a fraction has no single canonical spelling
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(keys.getPrivate());
        s.update(l.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", 1);
        m.add("latest", l);
        m.addProperty("signature", Base64.getEncoder().encodeToString(s.sign()));
        assertEquals(ErrorCode.SIGNATURE_INVALID,
            assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(m.toString(), keys.getPublic())).code());
    }

    @Test
    void downgradeAndSameVersionAreNotOffered() throws Exception {
        serve(manifest(latest("1.2.0"), keys.getPrivate()));
        assertTrue(service("1.2.0").check("stable", CancellationToken.NONE).isEmpty(), "same version");
        assertTrue(service("1.3.0").check("stable", CancellationToken.NONE).isEmpty(), "downgrade");
        assertTrue(service("1.2.0+build.7").check("stable", CancellationToken.NONE).isEmpty(), "build metadata ignored");
        assertTrue(service("v1.1.9").check("stable", CancellationToken.NONE).isPresent());
    }

    @Test
    void preReleaseOrderingFollowsSemver() {
        List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0-0", "1.1.0", "2.0.0", "10.0.0");
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = 0; j < ordered.size(); j++) {
                SemVer a = SemVer.parse(ordered.get(i));
                SemVer b = SemVer.parse(ordered.get(j));
                assertEquals(Integer.signum(Integer.compare(i, j)), Integer.signum(a.compareTo(b)), ordered.get(i) + " vs " + ordered.get(j));
            }
        }
        assertEquals(SemVer.parse("1.0.0+a"), SemVer.parse("v1.0.0+b"));
        assertEquals("1.0.0-rc.1+x.7", SemVer.parse("v1.0.0-rc.1+x.7").toString());
        // numbers larger than a long compare numerically
        assertTrue(SemVer.parse("99999999999999999999999.0.0").isNewerThan(SemVer.parse("9999999999999999999999.0.0")));
        for (String bad : List.of("1", "1.2", "01.2.3", "1.2.3-", "1.2.3-01", "1.2.3+", "1.2.3.4", "a.b.c", "", "1.2.3-be$ta")) {
            assertTrue(SemVer.tryParse(bad).isEmpty(), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("x"));
        assertTrue(SemVer.tryParse(null).isEmpty());
        assertTrue(SemVer.tryParse("1.0.0-" + "a".repeat(300)).isEmpty());
    }

    @Test
    void preReleasesOnlyOnBetaChannel() throws Exception {
        server.json("/updates/beta.json", manifest(latest("1.3.0-beta.2"), keys.getPrivate()));
        server.json("/updates/stable.json", manifest(latest("1.3.0-beta.2"), keys.getPrivate()));
        URI template = new UpdateConfig(server.base() + "updates/{channel}.json", "").manifestUrl().orElseThrow();
        UpdateService s = new UpdateService(http, template, keys.getPublic(), "1.3.0-beta.1");
        Optional<UpdateInfo> beta = s.check("beta", CancellationToken.NONE);
        assertTrue(beta.isPresent());
        assertTrue(beta.get().isPreRelease());
        assertEquals(1, server.hits("/updates/beta.json"));
        assertTrue(s.check("stable", CancellationToken.NONE).isEmpty(), "stable never offers a pre-release");
        assertEquals(1, server.hits("/updates/stable.json"));
        // the release beats its pre-releases
        server.json("/updates/beta.json", manifest(latest("1.3.0"), keys.getPrivate()));
        assertEquals("1.3.0", s.check("BETA", CancellationToken.NONE).orElseThrow().version());
        // an older pre-release is not offered
        server.json("/updates/beta.json", manifest(latest("1.3.0-alpha.9"), keys.getPrivate()));
        assertTrue(s.check("beta", CancellationToken.NONE).isEmpty());
        // channel names can't inject into the URL
        LauncherException e = assertThrows(LauncherException.class, () -> s.check("../stable", CancellationToken.NONE));
        assertEquals("update.error.badChannel", e.messageKey());
    }

    @Test
    void skippedVersionIsIgnored() throws Exception {
        serve(manifest(latest("1.2.0"), keys.getPrivate()));
        UpdateService s = service("1.0.0");
        s.skipVersion("v1.2.0");
        assertEquals(java.util.Set.of("1.2.0"), s.skippedVersions());
        assertTrue(s.check("stable", CancellationToken.NONE).isEmpty());
        s.unskipVersion("1.2.0");
        assertTrue(s.check("stable", CancellationToken.NONE).isPresent());
        s.setSkippedVersions(List.of("1.2.0", "garbage"));
        assertEquals(java.util.Set.of("1.2.0"), s.skippedVersions());
        assertTrue(s.check("stable", CancellationToken.NONE).isEmpty());
        // a newer one than the skipped one is offered again
        serve(manifest(latest("1.2.1"), keys.getPrivate()));
        assertEquals("1.2.1", s.check("stable", CancellationToken.NONE).orElseThrow().version());
    }

    @Test
    void disabledWithoutUrlOrKey() {
        UpdateService noUrl = new UpdateService(http, null, keys.getPublic(), "1.0.0");
        assertFalse(noUrl.isConfigured());
        assertEquals("update.disabled.noManifestUrl", noUrl.disabledReasonKey().orElseThrow());
        assertTrue(I18n.has(noUrl.disabledReasonKey().orElseThrow()));
        LauncherException e = assertThrows(LauncherException.class, () -> noUrl.check("stable", CancellationToken.NONE));
        assertEquals(ErrorCode.API_KEY_MISSING, e.code());
        assertEquals("update.error.notConfigured", e.messageKey());

        UpdateService noKey = new UpdateService(http, server.uri("/updates/stable.json"), null, "1.0.0");
        assertFalse(noKey.isConfigured());
        assertEquals("update.disabled.noPublicKey", noKey.disabledReasonKey().orElseThrow());
        assertTrue(noKey.disabledReason().orElseThrow().contains("signing key"));

        UpdateService insecure = new UpdateService(http, URI.create("http://example.org/m.json"), keys.getPublic(), "1.0.0");
        assertEquals("update.disabled.insecureUrl", insecure.disabledReasonKey().orElseThrow());

        UpdateService badVersion = new UpdateService(http, server.uri("/updates/stable.json"), keys.getPublic(), "dev");
        assertEquals("update.disabled.badCurrentVersion", badVersion.disabledReasonKey().orElseThrow());

        assertTrue(service("1.0.0").disabledReasonKey().isEmpty());
        assertTrue(service("1.0.0").disabledReason().isEmpty());
    }

    @Test
    void bundledConfigComesFromTheBuild() throws Exception {
        // a developer build has no values; a distributor's CI build may bake them in (docs/RELEASING.md)
        UpdateConfig bundled = UpdateConfig.bundled();
        assertEquals(BuildInfo.updateManifestUrl().orElse(""), bundled.manifestUrlText());
        assertEquals(BuildInfo.updatePublicKey().orElse(""), bundled.publicKeyText());
        if (bundled.manifestUrl().isEmpty() || bundled.publicKey().isEmpty()) {
            assertFalse(UpdateService.fromBundledConfig(http, "0.1.0").isConfigured());
        }
        assertFalse(new UpdateService(http, null, null, "0.1.0").isConfigured());
        // a configured one is read from properties
        String text = "manifestUrl=https://example.org/u/{channel}.json\npublicKey=" + UpdateKeys.toBase64(keys.getPublic()) + "\n";
        UpdateConfig c = UpdateConfig.read(new StringReader(text));
        assertEquals("https://example.org/u/%7Bchannel%7D.json", c.manifestUrl().orElseThrow().toString());
        assertEquals(keys.getPublic(), c.publicKey().orElseThrow());
        assertEquals(URI.create("https://example.org/u/beta.json"), UpdateService.withChannel(c.manifestUrl().orElseThrow(), "beta"));
        assertTrue(new UpdateConfig("not a url ::", "garbage").manifestUrl().isEmpty());
        assertTrue(new UpdateConfig("", "garbage").publicKey().isEmpty());
    }

    @Test
    void missingManifestIsNotFound() {
        LauncherException e = assertThrows(LauncherException.class, () -> service("1.0.0").check("stable", CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        assertEquals("update.error.noManifest", e.messageKey());
    }

    @Test
    void newerManifestFormatIsReportedAfterTheSignature() throws Exception {
        JsonObject l = latest("1.2.0");
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", 2);
        m.add("latest", l);
        m.addProperty("signature", sign(l, keys.getPrivate()));
        LauncherException e = assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(m.toString(), keys.getPublic()));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, e.code());
        m.addProperty("signature", sign(l, otherKeys.getPrivate()));
        assertEquals(ErrorCode.SIGNATURE_INVALID,
            assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(m.toString(), keys.getPublic())).code());
    }

    @Test
    void signedButMalformedReleasesAreRejected() throws Exception {
        String sha = Hashing.hash(installer, HashAlgo.SHA256);
        List<JsonObject> bad = List.of(
            latest("not-semver"),
            latest("1.2.0", "http://example.org/plain-http.msi", sha, 10),
            latest("1.2.0", "https://example.org/x.msi", "xyz", 10),
            latest("1.2.0", "https://example.org/x.msi", sha, 0),
            latest("1.2.0", "https://example.org/x.msi", sha, -5));
        for (JsonObject l : bad) {
            JsonObject m = new JsonObject();
            m.addProperty("schemaVersion", 1);
            m.add("latest", l);
            m.addProperty("signature", sign(l, keys.getPrivate()));
            LauncherException e = assertThrows(LauncherException.class, () -> UpdateService.verifyManifest(m.toString(), keys.getPublic()), l.toString());
            assertEquals(ErrorCode.INVALID_DATA, e.code(), l.toString());
        }
        // unknown download types are skipped (newer installers), the rest is kept
        JsonObject l = latest("1.2.0");
        JsonObject exe = l.getAsJsonArray("downloads").get(0).getAsJsonObject().deepCopy();
        exe.addProperty("type", "exe");
        l.getAsJsonArray("downloads").add(exe);
        JsonObject m = new JsonObject();
        m.addProperty("schemaVersion", 1);
        m.add("latest", l);
        m.addProperty("signature", sign(l, keys.getPrivate()));
        assertEquals(2, UpdateService.verifyManifest(m.toString(), keys.getPublic()).downloads().size());
    }

    // ------------------------------------------------------------------ download

    @Test
    void downloadsVerifiedInstallerWithoutRunningIt() throws Exception {
        server.bytes("/dl/ShardlightClient-1.2.0.msi", installer);
        serve(manifest(latest("1.2.0", server.uri("/dl/ShardlightClient-1.2.0.msi").toString(),
            Hashing.hash(installer, HashAlgo.SHA256), installer.length), keys.getPrivate()));
        // the manifest only allows https downloads; verify with a loopback-https-free check of the logic
        LauncherException e = assertThrows(LauncherException.class, () -> service("1.0.0").check("stable", CancellationToken.NONE));
        assertEquals(ErrorCode.INVALID_DATA, e.code(), "plain http download URLs are refused");

        // build the info directly (as check() would) pointing at the loopback test server
        UpdateInfo info = new UpdateInfo("1.2.0", "stable", null, null, "", List.of(
            new UpdateInfo.Download("windows-x64", "msi", server.uri("/dl/ShardlightClient-1.2.0.msi"),
                Hashing.hash(installer, HashAlgo.SHA256), installer.length)));
        Path out = service("1.0.0").download(info, "windows-x64", dir.resolve("updates"), ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(dir.resolve("updates").resolve("ShardlightClient-1.2.0-windows-x64.msi"), out);
        assertArrayEquals(installer, Files.readAllBytes(out));
        // a second call reuses the verified file
        int hits = server.hits("/dl/ShardlightClient-1.2.0.msi");
        assertEquals(out, service("1.0.0").download(info, "windows-x64", dir.resolve("updates"), null, null));
        assertEquals(hits, server.hits("/dl/ShardlightClient-1.2.0.msi"));
    }

    @Test
    void damagedDownloadIsDeleted() throws Exception {
        byte[] other = installer.clone();
        other[100] ^= 1;
        server.bytes("/dl/bad.msi", other);
        UpdateInfo info = new UpdateInfo("1.2.0", "stable", null, null, "", List.of(
            new UpdateInfo.Download("windows-x64", "msi", server.uri("/dl/bad.msi"),
                Hashing.hash(installer, HashAlgo.SHA256), installer.length)));
        LauncherException e = assertThrows(LauncherException.class,
            () -> service("1.0.0").download(info, "windows-x64", dir.resolve("updates"), ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code());
        assertFalse(Files.exists(dir.resolve("updates").resolve("ShardlightClient-1.2.0-windows-x64.msi")));

        UpdateInfo wrongSize = new UpdateInfo("1.2.0", "stable", null, null, "", List.of(
            new UpdateInfo.Download("windows-x64", "zip", server.uri("/dl/bad.msi"),
                Hashing.hash(other, HashAlgo.SHA256), other.length + 1)));
        assertThrows(LauncherException.class,
            () -> service("1.0.0").download(wrongSize, "windows-x64", dir.resolve("updates"), ProgressListener.NONE, CancellationToken.NONE));
        assertFalse(Files.exists(dir.resolve("updates").resolve("ShardlightClient-1.2.0-windows-x64.zip")));
    }

    @Test
    void downloadForMissingPlatformIsNotFound() {
        UpdateInfo info = new UpdateInfo("1.2.0", "stable", null, null, "", List.of());
        LauncherException e = assertThrows(LauncherException.class,
            () -> service("1.0.0").download(info, "linux-x64", dir, ProgressListener.NONE, CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        UpdateInfo.Download foreign = new UpdateInfo.Download("windows-x64", "msi", URI.create("https://example.org/a.msi"), "0".repeat(64), 1);
        assertThrows(LauncherException.class,
            () -> service("1.0.0").download(info, foreign, dir, ProgressListener.NONE, CancellationToken.NONE));
    }

    @Test
    void platformIds() {
        assertEquals("windows-x64", UpdateService.platformOf(OsInfo.detect("Windows 11", "amd64", "10.0")));
        assertEquals("macos-arm64", UpdateService.platformOf(OsInfo.detect("Mac OS X", "aarch64", "14.0")));
        assertEquals("linux-x64", UpdateService.platformOf(OsInfo.detect("Linux", "x86_64", "6.1")));
        assertFalse(UpdateService.currentPlatform().isBlank());
    }

    // ------------------------------------------------------------------ keys and canonical JSON

    @Test
    void parsesPublicKeyForms() throws Exception {
        PublicKey k = keys.getPublic();
        String b64 = UpdateKeys.toBase64(k);
        assertEquals(k, UpdateKeys.parsePublicKey(b64));
        String pem = "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
        assertEquals(k, UpdateKeys.parsePublicKey(pem));
        byte[] der = k.getEncoded();
        byte[] raw = java.util.Arrays.copyOfRange(der, der.length - 32, der.length);
        assertEquals(k, UpdateKeys.parsePublicKey(Base64.getEncoder().encodeToString(raw)));
        assertEquals(k, UpdateKeys.parsePublicKey(java.util.HexFormat.of().formatHex(raw)));
        KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        for (String bad : List.of("", "   ", "garbage!", Base64.getEncoder().encodeToString(new byte[31]),
            Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded()),
            "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----")) {
            LauncherException e = assertThrows(LauncherException.class, () -> UpdateKeys.parsePublicKey(bad), bad);
            assertEquals(ErrorCode.SIGNATURE_INVALID, e.code());
        }
        assertFalse(UpdateKeys.isEd25519(rsa.getPublic()));
        UpdateService rsaService = new UpdateService(http, server.uri("/m.json"), rsa.getPublic(), "1.0.0");
        assertEquals("update.disabled.badPublicKey", rsaService.disabledReasonKey().orElseThrow());
    }

    @Test
    void canonicalJsonMatchesPython() {
        // expected text from: json.dumps(latest, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        String input = "{\"version\":\"1.2.0\",\"releasedAt\":\"2026-11-02T10:00:00Z\",\"notesUrl\":\"https://example.org/notes/1.2.0\","
            + "\"changelog\":\"Faster start.\\nNew \\\"Ember\\\" look \\u00e9\\u4e2d\\ud83d\\udd25 tab\\there \\u0001\","
            + "\"downloads\":[{\"platform\":\"windows-x64\",\"type\":\"msi\",\"url\":\"https://example.org/dl/ShardlightClient-1.2.0.msi\","
            + "\"sha256\":\"" + "ab".repeat(32) + "\",\"size\":123456789}],\"zeta\":{\"b\":[1,-2,0],\"a\":null,\"A\":true}}";
        String expected = "{\"changelog\":\"Faster start.\\nNew \\\"Ember\\\" look \u00e9\u4e2d\ud83d\udd25 tab\\there \\u0001\","
            + "\"downloads\":[{\"platform\":\"windows-x64\",\"sha256\":\"" + "ab".repeat(32) + "\",\"size\":123456789,\"type\":\"msi\","
            + "\"url\":\"https://example.org/dl/ShardlightClient-1.2.0.msi\"}],\"notesUrl\":\"https://example.org/notes/1.2.0\","
            + "\"releasedAt\":\"2026-11-02T10:00:00Z\",\"version\":\"1.2.0\",\"zeta\":{\"A\":true,\"a\":null,\"b\":[1,-2,0]}}";
        assertEquals(expected, CanonicalJson.canonicalize(dev.shardlight.client.util.Json.parse(input)));
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), CanonicalJson.bytes(dev.shardlight.client.util.Json.parse(input)));
        // whitespace and key order of the input don't matter
        assertEquals("{\"a\":1,\"b\":[2,3]}", CanonicalJson.canonicalize(dev.shardlight.client.util.Json.parse("{ \"b\" : [ 2 , 3 ] ,\n \"a\" : 1 }")));
        assertEquals("{\"n\":0}", CanonicalJson.canonicalize(dev.shardlight.client.util.Json.parse("{\"n\":-0}")));
        for (String bad : List.of("{\"x\":1.5}", "{\"x\":1e3}", "{\"x\":1.0}")) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(dev.shardlight.client.util.Json.parse(bad)), bad);
        }
        JsonObject lone = new JsonObject();
        lone.addProperty("s", "a\ud800b");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(lone));
    }
}
