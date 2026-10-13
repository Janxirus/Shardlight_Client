package dev.shardlight.client.app.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Bundled theme screenshots (docs/THEME_PREVIEWS.md) are optional, but any that exist must be the right shape. */
class ThemePreviewTest {
    private static final List<String> THEMES = List.of("ember", "blood_moon", "soul_fire", "ashen", "dark_green",
        "purple_flame", "pink_blossom", "dark_fantasy", "medieval", "cyberpunk", "steampunk", "celestial", "frostbound",
        "voidborn", "arcane_academy");

    @Test
    void everyBundledPreviewIs640By360() throws IOException {
        for (String id : THEMES) {
            try (InputStream in = getClass().getResourceAsStream("/app/themes/preview/" + id + ".png")) {
                if (in == null) {
                    continue; // no screenshot yet: the tile shows the pack logo
                }
                BufferedImage img = ImageIO.read(in);
                assertNotNull(img, id);
                assertEquals(640, img.getWidth(), id);
                assertEquals(360, img.getHeight(), id);
            }
        }
    }

    @Test
    void everyThemeHasALogoForTheFallbackTile() {
        for (String id : THEMES) {
            assertNotNull(getClass().getResource("/app/themes/" + id + ".png"), id);
        }
    }
}
