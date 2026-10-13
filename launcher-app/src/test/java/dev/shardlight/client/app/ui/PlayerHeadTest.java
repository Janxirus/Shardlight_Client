package dev.shardlight.client.app.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerHeadTest {
    @Test
    void onlyMojangTextureHostsAreLoadedAndAlwaysOverHttps() {
        assertEquals("https://textures.minecraft.net/texture/abc",
            PlayerHead.skinUri("http://textures.minecraft.net/texture/abc").toString());
        assertEquals("https://textures.minecraft.net/texture/abc",
            PlayerHead.skinUri("https://textures.minecraft.net/texture/abc?x=1").toString());
        assertNull(PlayerHead.skinUri("https://evil.example/texture/abc"));
        assertNull(PlayerHead.skinUri("https://textures.minecraft.net.evil.example/texture/abc"));
        assertNull(PlayerHead.skinUri("file:///etc/passwd"));
        assertNull(PlayerHead.skinUri(""));
        assertNull(PlayerHead.skinUri(null));
    }

    @Test
    void theFaceIsTheEightByEightSquareWithTheHatOverIt() {
        int w = 64;
        int[] skin = new int[w * 64];
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                skin[y * w + x] = 0xFF112233;
            }
        }
        // one pixel of hat, fully opaque; one clear hat pixel that must not hide the face
        skin[8 * w + 40] = 0xFFAABBCC;
        skin[8 * w + 41] = 0x00FFFFFF;
        int[] face = PlayerHead.faceArgb(skin, w);
        assertEquals(64, face.length);
        assertEquals(0xFFAABBCC, face[0]);
        assertEquals(0xFF112233, face[1]);
        assertEquals(0xFF112233, face[63]);
    }
}
