package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCoordinateTest {
    @Test
    void plainCoordinate() throws Exception {
        MavenCoordinate m = MavenCoordinate.parse("org.ow2.asm:asm:9.7.1");
        assertEquals("org/ow2/asm/asm/9.7.1/asm-9.7.1.jar", m.path());
        assertEquals("org.ow2.asm:asm", m.key());
        assertNull(m.classifier());
        assertEquals("org.ow2.asm:asm:9.7.1", m.toString());
    }

    @Test
    void classifierAndExtension() throws Exception {
        MavenCoordinate n = MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.3:natives-windows");
        assertEquals("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar", n.path());
        assertEquals("org.lwjgl:lwjgl:natives-windows", n.key());
        assertTrue(n.isNativesClassifier());
        MavenCoordinate z = MavenCoordinate.parse("de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip");
        assertEquals("de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip", z.path());
        MavenCoordinate c = MavenCoordinate.parse("net.neoforged:neoforge:21.1.77:client@jar");
        assertEquals("net/neoforged/neoforge/21.1.77/neoforge-21.1.77-client.jar", c.path());
        assertFalse(c.isNativesClassifier());
        assertEquals("tv/twitch/twitch-platform/6.5/twitch-platform-6.5-natives-windows-64.jar",
            MavenCoordinate.parse("tv.twitch:twitch-platform:6.5").withClassifier("natives-windows-64").path());
        assertEquals("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7",
            MavenCoordinate.parse("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7").toString());
    }

    @Test
    void malformedNamesAreRejected() {
        for (String bad : new String[] {null, "", "  ", "a:b", "a:b:c:d:e", "a::1", ":b:1", "a:b:1:", "a:b:1@"}) {
            LauncherException e = assertThrows(LauncherException.class, () -> MavenCoordinate.parse(bad), String.valueOf(bad));
            assertEquals(ErrorCode.INVALID_DATA, e.code(), String.valueOf(bad));
        }
    }

    @Test
    void traversalIsRejected() {
        for (String evil : new String[] {"..:b:1", "a:..:1", "a:b:..", "a/b:c:1", "a:b\\c:1", "a:b:1:../../x",
            "a..b:c:1", "a:b:1@../../exe", "a:con:1", "a:b:1:x\u0000"}) {
            LauncherException e = assertThrows(LauncherException.class, () -> MavenCoordinate.parse(evil), evil);
            assertEquals(ErrorCode.UNSAFE_PATH, e.code(), evil);
        }
    }
}
