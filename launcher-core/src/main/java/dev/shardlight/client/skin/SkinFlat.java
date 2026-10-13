package dev.shardlight.client.skin;

/**
 * A flat picture of a skin, cut from its texture: the whole player seen from the front or the back, 16 x 32
 * pixels with the outer layers drawn over the inner ones. Used for the library thumbnails and as the preview
 * when 3D is unavailable.
 */
public final class SkinFlat {
    public static final int WIDTH = 16;
    public static final int HEIGHT = 32;

    private SkinFlat() {
    }

    /**
     * @param skin   ARGB pixels of the skin, row by row
     * @param width  skin width in pixels (64, or a multiple of 64 for high-resolution skins)
     * @param height skin height in pixels
     * @param back   draw the back instead of the front
     * @return 16 x 32 ARGB pixels; the empty space around the player is fully transparent
     */
    public static int[] render(int[] skin, int width, int height, SkinVariant variant, boolean back) {
        int[] out = new int[WIDTH * HEIGHT];
        if (width < 64 || height < 32 || skin.length < width * height) {
            return out;
        }
        int scale = width / 64;
        // the texture is addressed in 64-pixel units; a bigger texture is the same layout scaled
        int unitsHigh = height / scale;
        for (SkinUv.Box b : SkinUv.boxes(unitsHigh, variant)) {
            SkinUv.Face face = back ? SkinUv.Face.BACK : SkinUv.Face.FRONT;
            SkinUv.Rect r = b.face(face);
            // the player seen from the front shows its left on the viewer's right; from the back it is reversed
            int originX = back ? (int) Math.round(-(b.x() + b.w()) + 8) : (int) Math.round(b.x() + 8);
            int originY = (int) Math.round(b.y() + 32);
            for (int py = 0; py < b.h(); py++) {
                for (int px = 0; px < b.w(); px++) {
                    int sx = b.mirror() ? b.w() - 1 - px : px;
                    int argb = sample(skin, width, height, scale, r.x() + sx, r.y() + py);
                    int a = argb >>> 24;
                    int dx = originX + px;
                    int dy = originY + py;
                    if (dx < 0 || dx >= WIDTH || dy < 0 || dy >= HEIGHT) {
                        continue;
                    }
                    int idx = dy * WIDTH + dx;
                    // the outer layers (drawn after the inner ones) only where they are not clear; the inner
                    // layer is always opaque in the game, so any transparency there shows as black
                    if (!b.overlay() || a > 0) {
                        out[idx] = argb | 0xFF000000;
                    }
                }
            }
        }
        return out;
    }

    /** the middle pixel of a texel, so a high-resolution skin gives the same picture as a normal one */
    private static int sample(int[] skin, int width, int height, int scale, int ux, int uy) {
        int x = Math.min(width - 1, ux * scale + scale / 2);
        int y = Math.min(height - 1, uy * scale + scale / 2);
        return skin[y * width + x];
    }
}
