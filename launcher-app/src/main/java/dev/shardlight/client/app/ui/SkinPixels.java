package dev.shardlight.client.app.ui;

import dev.shardlight.client.skin.SkinFlat;
import dev.shardlight.client.skin.SkinVariant;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayInputStream;

/** Pixel work on skin pictures: decoding, sharp enlarging and the flat player previews. */
public final class SkinPixels {
    private SkinPixels() {
    }

    /** a decoded picture, or null when the bytes are not an image */
    public static Image decode(byte[] png) {
        if (png == null || png.length == 0) {
            return null;
        }
        Image img = new Image(new ByteArrayInputStream(png));
        return img.isError() || img.getWidth() < 1 ? null : img;
    }

    public static int[] argb(Image img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        int[] px = new int[w * h];
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = r.getArgb(x, y);
            }
        }
        return px;
    }

    /** every pixel becomes a factor x factor block, so a texture stays sharp however close the camera comes */
    public static WritableImage enlarge(Image img, int factor) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        int[] src = argb(img);
        WritableImage out = new WritableImage(w * factor, h * factor);
        PixelWriter pw = out.getPixelWriter();
        int[] row = new int[w * factor];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int k = 0; k < factor; k++) {
                    row[x * factor + k] = src[y * w + x];
                }
            }
            for (int k = 0; k < factor; k++) {
                pw.setPixels(0, y * factor + k, w * factor, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w * factor);
            }
        }
        return out;
    }

    /** the whole player seen from the front or back, enlarged {@code scale} times without smoothing */
    public static WritableImage flatPlayer(Image skin, SkinVariant variant, boolean back, int scale) {
        int[] flat = SkinFlat.render(argb(skin), (int) skin.getWidth(), (int) skin.getHeight(), variant, back);
        WritableImage out = new WritableImage(SkinFlat.WIDTH * scale, SkinFlat.HEIGHT * scale);
        PixelWriter pw = out.getPixelWriter();
        int[] row = new int[SkinFlat.WIDTH * scale];
        for (int y = 0; y < SkinFlat.HEIGHT; y++) {
            for (int x = 0; x < SkinFlat.WIDTH; x++) {
                for (int k = 0; k < scale; k++) {
                    row[x * scale + k] = flat[y * SkinFlat.WIDTH + x];
                }
            }
            for (int k = 0; k < scale; k++) {
                pw.setPixels(0, y * scale + k, SkinFlat.WIDTH * scale, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0,
                    SkinFlat.WIDTH * scale);
            }
        }
        return out;
    }
}
