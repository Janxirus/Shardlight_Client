package dev.shardlight.client.skin;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkinImageTest {
    @Test
    void readsA64By64Skin() throws Exception {
        SkinImage.Info info = SkinImage.readSkin(SkinFixtures.png(SkinFixtures.skin()));
        assertEquals(64, info.width());
        assertEquals(64, info.height());
        assertEquals(0xFFFF0000, info.pixels()[8 * 64 + 8]);
    }

    @Test
    void readsTheOld64By32Format() throws Exception {
        SkinImage.Info info = SkinImage.readSkin(SkinFixtures.png(SkinFixtures.image(64, 32)));
        assertEquals(32, info.height());
        assertEquals(SkinVariant.CLASSIC, info.guessedVariant());
    }

    @Test
    void refusesOtherSizes() {
        LauncherException e = assertThrows(LauncherException.class,
            () -> SkinImage.readSkin(SkinFixtures.png(SkinFixtures.image(128, 128))));
        assertEquals("skin.error.size", e.messageKey());
        assertEquals(ErrorCode.INVALID_DATA, e.code());
    }

    @Test
    void refusesFilesThatAreNotPng() {
        assertEquals("skin.error.notPng", assertThrows(LauncherException.class,
            () -> SkinImage.readSkin("hello".getBytes())).messageKey());
        assertEquals("skin.error.notPng", assertThrows(LauncherException.class,
            () -> SkinImage.readSkin(new byte[0])).messageKey());
        assertEquals("skin.error.notPng", assertThrows(LauncherException.class,
            () -> SkinImage.readSkin(new byte[SkinImage.MAX_BYTES + 1])).messageKey());
        // a PNG signature followed by nothing useful
        byte[] fake = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0};
        assertEquals("skin.error.notPng", assertThrows(LauncherException.class, () -> SkinImage.readSkin(fake)).messageKey());
    }

    @Test
    void aClearFourthArmColumnMeansSlim() throws Exception {
        BufferedImage slim = SkinFixtures.image(64, 64);
        SkinFixtures.fill(slim, 44, 20, 3, 12, 0xFF123456);
        assertEquals(SkinVariant.SLIM, SkinImage.readSkin(SkinFixtures.png(slim)).guessedVariant());
        BufferedImage classic = SkinFixtures.image(64, 64);
        SkinFixtures.fill(classic, 44, 20, 4, 12, 0xFF123456);
        assertEquals(SkinVariant.CLASSIC, SkinImage.readSkin(SkinFixtures.png(classic)).guessedVariant());
    }

    @Test
    void capesAreTwiceAsWideAsTheyAreHigh() throws Exception {
        assertEquals(64, SkinImage.readCape(SkinFixtures.png(SkinFixtures.image(64, 32))).width());
        assertEquals(128, SkinImage.readCape(SkinFixtures.png(SkinFixtures.image(128, 64))).width());
        assertEquals("skin.error.capeSize", assertThrows(LauncherException.class,
            () -> SkinImage.readCape(SkinFixtures.png(SkinFixtures.image(64, 64)))).messageKey());
    }
}
