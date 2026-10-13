package dev.shardlight.client.app;

import dev.shardlight.client.skin.SkinUv;
import dev.shardlight.client.skin.SkinVariant;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** --capture only: small skins and a cape drawn here, so the screenshots have something to show. Original art, no game files. */
final class SampleSkins {
    private SampleSkins() {
    }

    /** colours of one sample character, as 0xRRGGBB */
    record Look(int skin, int hair, int shirt, int shirtDark, int pants, int shoes, int cap, int eyes) {
    }

    static final Look WANDERER = new Look(0xE0AC7E, 0x3B2A1E, 0x2FA8A0, 0x23807A, 0x39477A, 0x2B2B33, -1, 0x2A3A6A);
    static final Look EMBER = new Look(0xC98A64, 0x7A2418, 0xB5412F, 0x8A2F22, 0x40343A, 0x251D20, 0xD9783C, 0x2A1A14);
    static final Look RANGER = new Look(0xF0C8A0, 0x7A5A2E, 0x4C8B4A, 0x376B37, 0x6B5638, 0x2F2A22, 0x3E6E3C, 0x2E4A2E);

    static byte[] skin(Look look, SkinVariant variant) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (SkinUv.Box b : SkinUv.boxes(64, variant)) {
            if (b.overlay()) {
                continue;
            }
            for (SkinUv.Face f : SkinUv.Face.values()) {
                SkinUv.Rect r = b.face(f);
                int base = switch (b.name()) {
                    case "head" -> look.skin;
                    case "body" -> look.shirt;
                    case "rightArm", "leftArm" -> look.shirt;
                    default -> look.pants;
                };
                fill(img, r.x(), r.y(), r.w(), r.h(), base);
            }
            switch (b.name()) {
                case "head" -> head(img, b, look);
                case "body" -> {
                    // a darker belt and a lighter chest line
                    SkinUv.Rect front = b.face(SkinUv.Face.FRONT);
                    fill(img, front.x(), front.y() + front.h() - 2, front.w(), 2, look.shirtDark);
                    fill(img, front.x() + 3, front.y() + 1, 2, 5, lighter(look.shirt));
                }
                case "rightArm", "leftArm" -> {
                    for (SkinUv.Face f : new SkinUv.Face[] {SkinUv.Face.FRONT, SkinUv.Face.BACK, SkinUv.Face.LEFT, SkinUv.Face.RIGHT}) {
                        SkinUv.Rect r = b.face(f);
                        fill(img, r.x(), r.y() + r.h() - 3, r.w(), 3, look.skin);
                    }
                }
                case "rightLeg", "leftLeg" -> {
                    for (SkinUv.Face f : new SkinUv.Face[] {SkinUv.Face.FRONT, SkinUv.Face.BACK, SkinUv.Face.LEFT, SkinUv.Face.RIGHT}) {
                        SkinUv.Rect r = b.face(f);
                        fill(img, r.x(), r.y() + r.h() - 3, r.w(), 3, look.shoes);
                    }
                }
                default -> { }
            }
        }
        if (look.cap >= 0) {
            // an outer layer: a cap over the head and a collar on the jacket layer
            for (SkinUv.Box b : SkinUv.boxes(64, variant)) {
                if (b.name().equals("hat")) {
                    for (SkinUv.Face f : SkinUv.Face.values()) {
                        SkinUv.Rect r = b.face(f);
                        int rows = f == SkinUv.Face.TOP ? r.h() : f == SkinUv.Face.BOTTOM ? 0 : 3;
                        fill(img, r.x(), r.y(), r.w(), rows, look.cap);
                    }
                    SkinUv.Rect front = b.face(SkinUv.Face.FRONT);
                    fill(img, front.x(), front.y() + 3, front.w(), 1, darker(look.cap));
                }
                if (b.name().equals("jacket")) {
                    SkinUv.Rect front = b.face(SkinUv.Face.FRONT);
                    fill(img, front.x(), front.y(), front.w(), 2, look.cap);
                }
            }
        }
        return png(img);
    }

    private static void head(BufferedImage img, SkinUv.Box head, Look look) {
        SkinUv.Rect top = head.face(SkinUv.Face.TOP);
        fill(img, top.x(), top.y(), top.w(), top.h(), look.hair);
        for (SkinUv.Face f : new SkinUv.Face[] {SkinUv.Face.LEFT, SkinUv.Face.RIGHT, SkinUv.Face.BACK}) {
            SkinUv.Rect r = head.face(f);
            fill(img, r.x(), r.y(), r.w(), f == SkinUv.Face.BACK ? 6 : 3, look.hair);
        }
        SkinUv.Rect face = head.face(SkinUv.Face.FRONT);
        fill(img, face.x(), face.y(), face.w(), 2, look.hair);
        fill(img, face.x(), face.y() + 2, 1, 1, look.hair);
        fill(img, face.x() + face.w() - 1, face.y() + 2, 1, 1, look.hair);
        // eyes and a mouth
        fill(img, face.x() + 1, face.y() + 4, 2, 1, 0xFFFFFF);
        fill(img, face.x() + 5, face.y() + 4, 2, 1, 0xFFFFFF);
        fill(img, face.x() + 2, face.y() + 4, 1, 1, look.eyes);
        fill(img, face.x() + 5, face.y() + 4, 1, 1, look.eyes);
        fill(img, face.x() + 3, face.y() + 6, 2, 1, darker(look.skin));
    }

    /** a 64 x 32 cape: a teal field with a pale shard on the outside, a dark lining on the inside */
    static byte[] cape() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        fill(img, 1, 1, 10, 16, 0x24706C);
        fill(img, 1, 1, 10, 1, 0x3BB5AD);
        fill(img, 1, 16, 10, 1, 0x1B4F4C);
        int[][] shard = {{5, 4, 2, 1}, {4, 5, 4, 2}, {4, 7, 4, 2}, {5, 9, 2, 2}, {5, 11, 2, 1}};
        for (int[] s : shard) {
            fill(img, s[0], s[1], s[2], s[3], 0xDFFFF9);
        }
        fill(img, 12, 1, 10, 16, 0x15302F);
        fill(img, 0, 1, 1, 16, 0x1B4F4C);
        fill(img, 11, 1, 1, 16, 0x1B4F4C);
        fill(img, 1, 0, 10, 1, 0x1B4F4C);
        fill(img, 11, 0, 10, 1, 0x1B4F4C);
        return png(img);
    }

    private static void fill(BufferedImage img, int x, int y, int w, int h, int rgb) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if (x + i >= 0 && y + j >= 0 && x + i < img.getWidth() && y + j < img.getHeight()) {
                    img.setRGB(x + i, y + j, 0xFF000000 | rgb);
                }
            }
        }
    }

    private static int lighter(int rgb) {
        return mix(rgb, 0xFFFFFF, 0.25);
    }

    private static int darker(int rgb) {
        return mix(rgb, 0x000000, 0.25);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
