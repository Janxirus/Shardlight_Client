package dev.shardlight.client.content;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionPredicatesTest {
    private static void yes(String pred, String v) {
        assertEquals(Optional.of(true), VersionPredicates.test(pred, v), pred + " vs " + v);
    }

    private static void no(String pred, String v) {
        assertEquals(Optional.of(false), VersionPredicates.test(pred, v), pred + " vs " + v);
    }

    @Test
    void fabricPredicates() {
        yes("*", "26.3");
        yes("", "26.3");
        yes("~26.3", "26.3");
        yes("~26.3", "26.3.2");
        no("~26.3", "26.4");
        no("~26.3", "26.2");
        yes(">=1.21", "26.3");
        yes(">=1.21", "1.21");
        no(">=1.21", "1.20.6");
        yes("1.21.x", "1.21");
        yes("1.21.x", "1.21.4");
        no("1.21.x", "1.22");
        yes("1.21.*", "1.21.1");
        no("1.21.x", "1.20.9");
        yes("26.3", "26.3");
        yes("26.3", "26.3.0");
        no("26.3", "26.3.1");
        yes("=1.20.1", "1.20.1");
        yes(">=1.20 <1.21", "1.20.4");
        no(">=1.20 <1.21", "1.21");
        yes("<=1.21", "1.21");
        no(">1.21", "1.21");
        yes("^1.20", "1.21.4");
        no("^1.20", "2.0");
        yes("~1.20 || ~26.3", "26.3.1");
        no("~1.20 || ~26.3", "26.4");
        yes(">1.21.x", "1.22");
        no(">1.21.x", "1.21.9");
    }

    @Test
    void prereleasesSortBeforeReleases() {
        no(">=26.3", "26.3-rc1");
        yes(">=26.3-rc1", "26.3-rc2");
        yes(">=26.3-rc2", "26.3");
        assertTrue(VersionPredicates.compare("26.3-rc10", "26.3-rc2").orElseThrow() > 0);
        assertTrue(VersionPredicates.compare("1.21-pre1", "1.21-rc1").orElseThrow() < 0);
        assertEquals(0, VersionPredicates.compare("1.21", "1.21.0").orElseThrow());
    }

    @Test
    void mavenRanges() {
        yes("[1.20.1,1.21)", "1.20.4");
        no("[1.20.1,1.21)", "1.21");
        yes("[1.20.1,1.21]", "1.21");
        no("(1.20.1,1.21]", "1.20.1");
        yes("[1.21,)", "26.3");
        yes("(,1.21]", "1.20");
        yes("[1.20.1]", "1.20.1");
        no("[1.20.1]", "1.20.2");
        yes("[1.19,1.20),[1.21,)", "26.3");
        no("[1.19,1.20),[1.21,)", "1.20.5");
    }

    @Test
    void unknownWhenUnparseable() {
        assertEquals(Optional.empty(), VersionPredicates.test(">=1.21", "26w14a"));
        assertEquals(Optional.empty(), VersionPredicates.test(">=banana", "26.3"));
        assertEquals(Optional.empty(), VersionPredicates.test("[1.2,", "26.3"));
        assertEquals(Optional.empty(), VersionPredicates.test(null, "26.3"));
        assertEquals(Optional.empty(), VersionPredicates.test(">=1.21", null));
        // one alternative unknown, the other false: unknown overall
        assertEquals(Optional.empty(), VersionPredicates.test(">=1.21 junk||<1.0", "26.3"));
    }

    @Test
    void bareMavenVersionIsOnlyARecommendationInForge() {
        // Forge treats "1.20.1" in versionRange as a soft requirement, but Fabric as exact; this class is
        // Fabric-style for strings that don't start with a bracket
        no("1.20.1", "26.3");
    }
}
