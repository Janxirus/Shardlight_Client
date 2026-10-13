package dev.shardlight.client.skin;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each piece of a Minecraft skin sits on the 64 x 64 texture, and how big it is in the world. Pure
 * numbers, so the 3D viewer and the flat previews share one source and a test can check them.
 *
 * <p>Model space: x to the player's left (the viewer's right when looking at the front), y down, feet at 0,
 * the top of the head at -32, z from -2 at the front to +2 at the back. Sizes are in texture pixels.
 */
public final class SkinUv {
    private SkinUv() {
    }

    /** the six sides of a box, named from the player's point of view */
    public enum Face { TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK }

    /** a rectangle of the texture, in pixels of a 64-wide skin */
    public record Rect(int x, int y, int w, int h) {
    }

    /**
     * One box of the player model.
     *
     * @param name    stable name (head, hat, body, jacket, rightArm, rightSleeve ...)
     * @param u       texture x of the box's net
     * @param v       texture y of the box's net
     * @param w       width in pixels (x)
     * @param h       height in pixels (y)
     * @param d       depth in pixels (z)
     * @param x       left edge in model space
     * @param y       top edge in model space
     * @param z       front edge in model space
     * @param grow    how far the box is inflated on every side (the outer layer)
     * @param mirror  the texture is read mirrored (the left limbs of an old 64 x 32 skin)
     * @param overlay true for the outer layer (hat, jacket, sleeves, pants)
     */
    public record Box(String name, int u, int v, int w, int h, int d, double x, double y, double z, double grow,
                      boolean mirror, boolean overlay) {
        /** the texture rectangle of {@code f}, before any mirroring */
        public Rect face(Face f) {
            return switch (f) {
                case TOP -> new Rect(u + d, v, w, d);
                case BOTTOM -> new Rect(u + d + w, v, w, d);
                case RIGHT -> new Rect(u, v + d, d, h);
                case FRONT -> new Rect(u + d, v + d, w, h);
                case LEFT -> new Rect(u + d + w, v + d, d, h);
                case BACK -> new Rect(u + 2 * d + w, v + d, w, h);
            };
        }
    }

    /**
     * Every box of the player for a skin of the given pixel height and arm shape. A 64 x 32 skin (the old
     * format) has no outer layers except the hat, and its left limbs are the right ones mirrored.
     */
    public static List<Box> boxes(int skinHeight, SkinVariant variant) {
        boolean modern = skinHeight >= 64;
        int arm = variant.armWidth();
        List<Box> out = new ArrayList<>();
        // inner layer
        out.add(new Box("head", 0, 0, 8, 8, 8, -4, -32, -4, 0, false, false));
        out.add(new Box("body", 16, 16, 8, 12, 4, -4, -24, -2, 0, false, false));
        out.add(new Box("rightArm", 40, 16, arm, 12, 4, -4 - arm, -24, -2, 0, false, false));
        out.add(new Box("leftArm", modern ? 32 : 40, modern ? 48 : 16, arm, 12, 4, 4, -24, -2, 0, !modern, false));
        out.add(new Box("rightLeg", 0, 16, 4, 12, 4, -4, -12, -2, 0, false, false));
        out.add(new Box("leftLeg", modern ? 16 : 0, modern ? 48 : 16, 4, 12, 4, 0, -12, -2, 0, !modern, false));
        // outer layer
        out.add(new Box("hat", 32, 0, 8, 8, 8, -4, -32, -4, 0.5, false, true));
        if (modern) {
            out.add(new Box("jacket", 16, 32, 8, 12, 4, -4, -24, -2, 0.25, false, true));
            out.add(new Box("rightSleeve", 40, 32, arm, 12, 4, -4 - arm, -24, -2, 0.25, false, true));
            out.add(new Box("leftSleeve", 48, 48, arm, 12, 4, 4, -24, -2, 0.25, false, true));
            out.add(new Box("rightPants", 0, 32, 4, 12, 4, -4, -12, -2, 0.25, false, true));
            out.add(new Box("leftPants", 0, 48, 4, 12, 4, 0, -12, -2, 0.25, false, true));
        }
        return out;
    }

    /**
     * The cape: 10 x 16 x 1, hanging from the shoulders on the back; its net starts at (0, 0) of a 64 x 32
     * texture. Minecraft turns the cape model half a circle, so its "front" (the large picture at 1, 1) is the
     * side that faces away from the player: read its faces through {@link #turned(Face)}.
     */
    public static Box cape() {
        return new Box("cape", 0, 0, 10, 16, 1, -5, -24, 2, 0, false, false);
    }

    /** the face of a box that has been turned half a circle about the vertical axis, as the cape is */
    public static Face turned(Face f) {
        return switch (f) {
            case FRONT -> Face.BACK;
            case BACK -> Face.FRONT;
            case LEFT -> Face.RIGHT;
            case RIGHT -> Face.LEFT;
            default -> f;
        };
    }
}
