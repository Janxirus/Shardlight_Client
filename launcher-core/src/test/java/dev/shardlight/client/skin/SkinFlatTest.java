package dev.shardlight.client.skin;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkinFlatTest {
    private static int[] px(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static int at(int[] out, int x, int y) {
        return out[y * SkinFlat.WIDTH + x];
    }

    @Test
    void theFrontShowsTheHeadOnTopAndTheBodyBelow() {
        int[] out = SkinFlat.render(px(SkinFixtures.skin()), 64, 64, SkinVariant.CLASSIC, false);
        assertEquals(0xFFFF0000, at(out, 4, 0), "head's top-left pixel");
        assertEquals(0xFFFF0000, at(out, 11, 7), "head's bottom-right pixel");
        assertEquals(0xFF00FF00, at(out, 4, 8), "body below the head");
        assertEquals(0xFF00FF00, at(out, 11, 19));
        assertEquals(0, at(out, 0, 0), "the corner outside the player stays clear");
    }

    @Test
    void theOuterLayerCoversTheInnerOneOnlyWhereItIsNotClear() {
        BufferedImage img = SkinFixtures.skin();
        img.setRGB(40 + 1, 8 + 1, 0xFF0000FF); // hat front pixel (1, 1)
        int[] out = SkinFlat.render(px(img), 64, 64, SkinVariant.CLASSIC, false);
        assertEquals(0xFF0000FF, at(out, 4 + 1, 1));
        assertEquals(0xFFFF0000, at(out, 4 + 2, 1), "a clear hat pixel shows the head");
    }

    @Test
    void theBackIsReadFromTheBackFacesAndMirroredOnTheScreen() {
        BufferedImage img = SkinFixtures.skin();
        // the head's back is at (24, 8); put one blue pixel in its left column, which is the player's left side
        img.setRGB(24, 8, 0xFF0000FF);
        int[] out = SkinFlat.render(px(img), 64, 64, SkinVariant.CLASSIC, true);
        // seen from behind the player's left is on the viewer's left
        assertEquals(0xFF0000FF, at(out, 4, 0));
    }

    @Test
    void slimArmsAreNarrowerOnTheScreen() {
        BufferedImage img = SkinFixtures.image(64, 64);
        SkinFixtures.fill(img, 44, 20, 3, 12, 0xFFFFFF00); // three columns of the right arm's front
        int[] classic = SkinFlat.render(px(img), 64, 64, SkinVariant.CLASSIC, false);
        int[] slim = SkinFlat.render(px(img), 64, 64, SkinVariant.SLIM, false);
        assertEquals(0xFFFFFF00, at(classic, 0, 8));
        assertEquals(0xFF000000, at(classic, 3, 8), "the fourth column of a classic arm is part of the arm");
        assertEquals(0, at(slim, 0, 8), "a slim arm leaves the outer column empty");
        assertEquals(0xFFFFFF00, at(slim, 1, 8));
        assertEquals(0xFFFFFF00, at(slim, 3, 8));
    }

    @Test
    void aTooSmallPictureGivesAnEmptyPlayer() {
        int[] out = SkinFlat.render(new int[10], 4, 4, SkinVariant.CLASSIC, false);
        for (int v : out) {
            assertEquals(0, v);
        }
    }

    @Test
    void aHighResolutionSkinGivesTheSamePicture() {
        BufferedImage big = SkinFixtures.image(128, 128);
        SkinFixtures.fill(big, 16, 16, 16, 16, 0xFFFF0000); // the head front at twice the size
        int[] out = SkinFlat.render(px(big), 128, 128, SkinVariant.CLASSIC, false);
        assertEquals(0xFFFF0000, at(out, 4, 0));
        assertEquals(0xFFFF0000, at(out, 11, 7));
    }
}
