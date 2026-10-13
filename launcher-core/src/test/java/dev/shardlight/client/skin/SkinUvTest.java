package dev.shardlight.client.skin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinUvTest {
    @Test
    void headNetMatchesTheKnownLayout() {
        SkinUv.Box head = SkinUv.boxes(64, SkinVariant.CLASSIC).get(0);
        assertEquals("head", head.name());
        assertEquals(new SkinUv.Rect(8, 0, 8, 8), head.face(SkinUv.Face.TOP));
        assertEquals(new SkinUv.Rect(16, 0, 8, 8), head.face(SkinUv.Face.BOTTOM));
        assertEquals(new SkinUv.Rect(0, 8, 8, 8), head.face(SkinUv.Face.RIGHT));
        assertEquals(new SkinUv.Rect(8, 8, 8, 8), head.face(SkinUv.Face.FRONT));
        assertEquals(new SkinUv.Rect(16, 8, 8, 8), head.face(SkinUv.Face.LEFT));
        assertEquals(new SkinUv.Rect(24, 8, 8, 8), head.face(SkinUv.Face.BACK));
    }

    @Test
    void everyFaceStaysInsideTheTexture() {
        for (SkinVariant v : SkinVariant.values()) {
            for (int height : new int[] {32, 64}) {
                for (SkinUv.Box b : SkinUv.boxes(height, v)) {
                    for (SkinUv.Face f : SkinUv.Face.values()) {
                        SkinUv.Rect r = b.face(f);
                        assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= 64 && r.y() + r.h() <= height,
                            b.name() + " " + f + " " + r + " in 64 x " + height);
                    }
                }
            }
        }
    }

    @Test
    void slimArmsAreThreePixelsAndKeepTheOuterEdgeInPlace() {
        SkinUv.Box classic = box(SkinUv.boxes(64, SkinVariant.CLASSIC), "leftArm");
        SkinUv.Box slim = box(SkinUv.boxes(64, SkinVariant.SLIM), "leftArm");
        assertEquals(4, classic.w());
        assertEquals(3, slim.w());
        assertEquals(classic.x(), slim.x(), "the arm starts at the body in both");
        SkinUv.Box slimRight = box(SkinUv.boxes(64, SkinVariant.SLIM), "rightArm");
        assertEquals(-7, slimRight.x());
    }

    @Test
    void anOldSkinHasOnlyAHatLayerAndMirroredLeftLimbs() {
        List<SkinUv.Box> old = SkinUv.boxes(32, SkinVariant.CLASSIC);
        assertEquals(7, old.size());
        assertEquals(1, old.stream().filter(SkinUv.Box::overlay).count());
        assertTrue(box(old, "leftArm").mirror());
        assertTrue(box(old, "leftLeg").mirror());
        assertEquals(box(old, "rightArm").u(), box(old, "leftArm").u());
        assertFalse(box(SkinUv.boxes(64, SkinVariant.CLASSIC), "leftArm").mirror());
    }

    @Test
    void theCapesOutsideIsTheLargePictureAtTheCorner() {
        SkinUv.Box cape = SkinUv.cape();
        // the face that looks away from the player (the back plane) reads the net's front
        assertEquals(new SkinUv.Rect(1, 1, 10, 16), cape.face(SkinUv.turned(SkinUv.Face.BACK)));
        assertEquals(new SkinUv.Rect(12, 1, 10, 16), cape.face(SkinUv.turned(SkinUv.Face.FRONT)));
    }

    private static SkinUv.Box box(List<SkinUv.Box> boxes, String name) {
        return boxes.stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }
}
