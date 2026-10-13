package dev.shardlight.client.skin;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.account.AccountService;
import dev.shardlight.client.auth.AuthFixtures;
import dev.shardlight.client.auth.MemorySecretStore;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinServiceTest {
    static final String TEX = "http://textures.minecraft.net/texture/";

    @TempDir
    Path dir;
    AuthFixtures fx;
    AccountService accounts;
    SkinService skins;
    Account account;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AuthFixtures();
        accounts = new AccountService(new AppPaths(dir), new MemorySecretStore(), fx.service(), fx.clock);
        account = accounts.signIn(c -> { }, CancellationToken.NONE);
        skins = new SkinService(HttpService.forTests(), fx.endpoints, accounts,
            url -> SkinService.textureUri(url) == null ? null : fx.server.uri("/tex" + URI.create(url).getPath()));
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private static String wardrobeJson(String variant, String activeCape) {
        return "{\"id\":\"" + AuthFixtures.UUID + "\",\"name\":\"Notch\",\"skins\":["
            + "{\"id\":\"s1\",\"state\":\"ACTIVE\",\"url\":\"" + TEX + "aaa\",\"variant\":\"" + variant + "\"},"
            + "{\"id\":\"s2\",\"state\":\"INACTIVE\",\"url\":\"" + TEX + "bbb\",\"variant\":\"CLASSIC\"},"
            + "{\"id\":\"s3\",\"state\":\"INACTIVE\",\"url\":\"http://evil.example/x\",\"variant\":\"CLASSIC\"}],"
            + "\"capes\":[{\"id\":\"c1\",\"state\":\"" + ("c1".equals(activeCape) ? "ACTIVE" : "INACTIVE")
            + "\",\"url\":\"" + TEX + "ccc\",\"alias\":\"Migrator\"},"
            + "{\"id\":\"c2\",\"state\":\"" + ("c2".equals(activeCape) ? "ACTIVE" : "INACTIVE")
            + "\",\"url\":\"" + TEX + "ddd\",\"alias\":\"Vanilla\"}]}";
    }

    @Test
    void readsWhatTheAccountWearsAndOwns() throws Exception {
        fx.profileJson = wardrobeJson("SLIM", "c2");
        SkinService.Wardrobe w = skins.wardrobe(account.id, CancellationToken.NONE);
        assertEquals(2, w.skins().size(), "a skin on another host is dropped");
        assertEquals(SkinVariant.SLIM, w.activeSkin().orElseThrow().variant());
        assertEquals(2, w.capes().size());
        assertEquals("Vanilla", w.activeCape().orElseThrow().alias());
        String auth = fx.server.requests().stream().filter(r -> r.path().equals("/mcs/minecraft/profile"))
            .reduce((a, b) -> b).orElseThrow().header("Authorization");
        assertTrue(auth.startsWith("Bearer "));
    }

    @Test
    void noCapeActiveGivesEmpty() throws Exception {
        fx.profileJson = wardrobeJson("CLASSIC", null);
        assertTrue(skins.wardrobe(account.id, CancellationToken.NONE).activeCape().isEmpty());
    }

    @Test
    void uploadsASkinAsMultipartAndRemembersTheNewHead() throws Exception {
        fx.server.handle("/mcs/minecraft/profile/skins", ex -> TestHttpServer.respond(ex, 200, "application/json",
            wardrobeJson("SLIM", null).getBytes(StandardCharsets.UTF_8)));
        byte[] png = SkinFixtures.png(SkinFixtures.skin());
        SkinService.Wardrobe w = skins.uploadSkin(account.id, png, SkinVariant.SLIM, CancellationToken.NONE);
        assertEquals(SkinVariant.SLIM, w.activeSkin().orElseThrow().variant());

        TestHttpServer.Recorded r = fx.server.requests().stream().filter(x -> x.path().equals("/mcs/minecraft/profile/skins"))
            .findFirst().orElseThrow();
        assertEquals("POST", r.method());
        assertTrue(r.header("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = new String(r.body(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"variant\"\r\n\r\nslim\r\n"), body.substring(0, 200));
        assertTrue(body.contains("name=\"file\"; filename=\"skin.png\""));
        assertTrue(body.contains("Content-Type: image/png"));
        assertTrue(body.contains("PNG"), "the picture is in the body");

        Account updated = accounts.account(account.id).orElseThrow();
        assertEquals(TEX + "aaa", updated.skinUrl);
        assertEquals("slim", updated.skinVariant);
    }

    @Test
    void refusesAFileThatIsNotASkinWithoutAskingTheServer() {
        LauncherException e = assertThrows(LauncherException.class,
            () -> skins.uploadSkin(account.id, "nope".getBytes(), SkinVariant.CLASSIC, CancellationToken.NONE));
        assertEquals("skin.error.notPng", e.messageKey());
        assertTrue(fx.server.requests().stream().noneMatch(r -> r.path().endsWith("/skins")));
    }

    @Test
    void explainsWhenMinecraftRejectsTheSkin() {
        fx.server.handle("/mcs/minecraft/profile/skins", ex -> TestHttpServer.respond(ex, 400, "bad"));
        LauncherException e = assertThrows(LauncherException.class, () -> skins.uploadSkin(account.id,
            SkinFixtures.png(SkinFixtures.skin()), SkinVariant.CLASSIC, CancellationToken.NONE));
        assertEquals("skin.error.rejected", e.messageKey());
    }

    @Test
    void mapsDeniedMissingAndBusyAnswers() {
        for (Object[] c : new Object[][] {{401, "skin.error.denied"}, {403, "skin.error.denied"},
            {404, "skin.error.noProfile"}, {429, "skin.error.rateLimited"}, {503, "skin.error.status"}}) {
            int status = (Integer) c[0];
            fx.server.handle("/mcs/minecraft/profile/capes/active", ex -> TestHttpServer.respond(ex, status, "no"));
            LauncherException e = assertThrows(LauncherException.class,
                () -> skins.setCape(account.id, "c1", CancellationToken.NONE), "status " + status);
            assertEquals(c[1], e.messageKey(), "status " + status);
        }
    }

    @Test
    void choosesAndRemovesACape() throws Exception {
        fx.server.handle("/mcs/minecraft/profile/capes/active", ex -> {
            String active = ex.getRequestMethod().equals("PUT") ? "c1" : null;
            TestHttpServer.respond(ex, 200, "application/json", wardrobeJson("CLASSIC", active).getBytes(StandardCharsets.UTF_8));
        });
        SkinService.Wardrobe on = skins.setCape(account.id, "c1", CancellationToken.NONE);
        assertEquals("c1", on.activeCape().orElseThrow().id());
        TestHttpServer.Recorded put = fx.server.requests().stream().filter(x -> x.method().equals("PUT")).findFirst().orElseThrow();
        assertEquals("{\"capeId\":\"c1\"}", put.bodyText());
        assertEquals("application/json", put.header("Content-Type"));

        SkinService.Wardrobe off = skins.setCape(account.id, null, CancellationToken.NONE);
        assertTrue(off.activeCape().isEmpty());
        assertTrue(fx.server.requests().stream().anyMatch(x -> x.method().equals("DELETE")));
    }

    @Test
    void fetchesPicturesOnlyFromTheTextureHost() throws Exception {
        byte[] png = SkinFixtures.png(SkinFixtures.skin());
        fx.server.bytes("/tex/texture/aaa", png);
        assertEquals(png.length, skins.texture(TEX + "aaa", CancellationToken.NONE).length);
        LauncherException e = assertThrows(LauncherException.class,
            () -> skins.texture("http://evil.example/texture/aaa", CancellationToken.NONE));
        assertEquals("skin.error.badTexture", e.messageKey());
        fx.server.bytes("/tex/texture/big", new byte[300 * 1024]);
        assertEquals("skin.error.badTexture", assertThrows(LauncherException.class,
            () -> skins.texture(TEX + "big", CancellationToken.NONE)).messageKey());
    }

    @Test
    void textureUriKeepsOnlyMojangsHostOverHttps() {
        assertEquals("https://textures.minecraft.net/texture/abc", SkinService.textureUri(TEX + "abc").toString());
        assertEquals(null, SkinService.textureUri("https://textures.minecraft.net.evil.example/texture/abc"));
        assertEquals(null, SkinService.textureUri("https://user@textures.minecraft.net/texture/abc"));
        assertEquals(null, SkinService.textureUri(null));
        assertEquals(null, SkinService.textureUri("not a url"));
    }

    @Test
    void failsPlainlyWhenNoOneIsSignedIn() throws Exception {
        accounts.signOut(account.id);
        LauncherException e = assertThrows(LauncherException.class, () -> skins.wardrobe(account.id, CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        assertNotNull(e.userMessage());
        assertFalse(e.userMessage().isBlank());
    }

    @Test
    void theAccessTokenNeverAppearsInErrors() {
        fx.server.handle("/mcs/minecraft/profile/skins", ex -> TestHttpServer.respond(ex, 401, "no"));
        LauncherException e = assertThrows(LauncherException.class, () -> skins.uploadSkin(account.id,
            SkinFixtures.png(SkinFixtures.skin()), SkinVariant.CLASSIC, CancellationToken.NONE));
        for (String t : AuthFixtures.ALL_TOKENS) {
            assertFalse(e.userMessage().contains(t));
            assertFalse(String.valueOf(e).contains(t));
        }
    }
}
