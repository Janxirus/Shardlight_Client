package dev.shardlight.client.preset;

import dev.shardlight.client.preset.SettingDef.Target;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Minecraft video options the presets write are exactly the 26.3 key list that CI checks against the real client. */
class VanillaOptionsTest {
    @Test
    void presetsWriteExactlyTheKeysPinnedForMinecraft263() {
        Set<String> used = PresetCatalog.settings().stream().filter(d -> d.target() == Target.VANILLA)
            .map(SettingDef::jsonKey).collect(Collectors.toSet());
        assertEquals(VanillaOptions.keys(), used,
            "preset/vanilla-options-26.3.txt and the VANILLA part of PresetCatalog must list the same keys");
        assertEquals(12, used.size());
    }

    @Test
    void theKeyListIsThePinned26_3Set() {
        assertEquals(Set.of("graphicsMode", "renderDistance", "simulationDistance", "particles", "entityShadows", "ao",
            "biomeBlendRadius", "maxFps", "enableVsync", "mipmapLevels", "renderClouds", "entityDistanceScaling"),
            VanillaOptions.keys());
    }

    @Test
    void onlyChecked26_3VersionsAreTrusted() {
        assertTrue(VanillaOptions.verifiedFor("26.3"));
        assertTrue(VanillaOptions.verifiedFor("26.3.1"));
        assertFalse(VanillaOptions.verifiedFor("26.30"), "a different version that starts with the same digits");
        assertFalse(VanillaOptions.verifiedFor("26.2"));
        assertFalse(VanillaOptions.verifiedFor("26.3-pre1"));
        assertFalse(VanillaOptions.verifiedFor("1.21.4"));
        assertFalse(VanillaOptions.verifiedFor(null));
    }
}
