package dev.shardlight.client.skin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Small PNGs for the skin tests. */
final class SkinFixtures {
    private SkinFixtures() {
    }

    static BufferedImage image(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** a 64 x 64 skin that is clear except for an opaque head front (red) and body front (green) */
    static BufferedImage skin() {
        BufferedImage img = image(64, 64);
        fill(img, 8, 8, 8, 8, 0xFFFF0000);
        fill(img, 20, 20, 8, 12, 0xFF00FF00);
        return img;
    }

    static void fill(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                img.setRGB(x + i, y + j, argb);
            }
        }
    }
}
