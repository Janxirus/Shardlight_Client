package dev.shardlight.client.skin;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Checks a skin or cape file before it is saved or sent anywhere, and reads its pixels. */
public final class SkinImage {
    /** a skin file is a few kilobytes; anything near this is not one */
    public static final int MAX_BYTES = 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private SkinImage() {
    }

    /** a decoded skin: its size and ARGB pixels, plus the arm shape its pixels suggest */
    public record Info(int width, int height, int[] pixels, SkinVariant guessedVariant) {
    }

    /** a skin: a PNG of exactly 64 x 64 or 64 x 32 pixels, the only sizes Minecraft accepts */
    public static Info readSkin(byte[] data) throws LauncherException {
        BufferedImage img = decode(data);
        int w = img.getWidth();
        int h = img.getHeight();
        if (w != 64 || (h != 64 && h != 32)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.size", w, h);
        }
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        return new Info(w, h, px, guessVariant(px, w, h));
    }

    /** a cape: a PNG whose width is a multiple of 64 and whose height is half of that (64 x 32 and its bigger copies) */
    public static Info readCape(byte[] data) throws LauncherException {
        BufferedImage img = decode(data);
        int w = img.getWidth();
        int h = img.getHeight();
        if (w < 64 || w % 64 != 0 || h * 2 != w) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.capeSize", w, h);
        }
        return new Info(w, h, img.getRGB(0, 0, w, h, null, 0, w), SkinVariant.CLASSIC);
    }

    /**
     * Slim skins leave the arm's fourth column clear. On a 64 x 64 skin the right arm's front is columns 44 to
     * 47; when the last column (47) is clear all the way down, the arms are 3 pixels wide.
     */
    static SkinVariant guessVariant(int[] px, int w, int h) {
        if (h < 64) {
            return SkinVariant.CLASSIC;
        }
        for (int y = 20; y < 32; y++) {
            if ((px[y * w + 47] >>> 24) != 0) {
                return SkinVariant.CLASSIC;
            }
        }
        return SkinVariant.SLIM;
    }

    private static BufferedImage decode(byte[] data) throws LauncherException {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.notPng");
        }
        for (int i = 0; i < PNG.length; i++) {
            if (data.length <= i || data[i] != PNG[i]) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.notPng");
            }
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "skin.error.notPng");
            }
            return img;
        } catch (IOException | RuntimeException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "skin.error.notPng");
        }
    }
}
