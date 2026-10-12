package dev.shardlight.client.minecraft.model;

import com.google.gson.JsonParseException;
import dev.shardlight.client.minecraft.MinecraftFixtures;
import dev.shardlight.client.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionJsonTest {
    @Test
    void parsesModernVanilla() {
        VersionJson v = VersionJson.parse(MinecraftFixtures.template("1.21.4.json"));
        assertEquals("1.21.4", v.id());
        assertEquals("release", v.type());
        assertEquals("net.minecraft.client.main.Main", v.mainClass());
        assertNull(v.minecraftArguments());
        assertNull(v.inheritsFrom());
        assertTrue(v.hasArguments());

        List<Argument> game = v.gameArguments();
        assertEquals(Argument.of("--username"), game.get(0));
        Argument demo = game.stream().filter(a -> a.values().equals(List.of("--demo"))).findFirst().orElseThrow();
        assertEquals(1, demo.rules().size());
        assertEquals("allow", demo.rules().get(0).action());
        assertEquals(Map.of("is_demo_user", true), demo.rules().get(0).features());
        Argument res = game.stream().filter(a -> a.values().contains("--width")).findFirst().orElseThrow();
        assertEquals(List.of("--width", "${resolution_width}", "--height", "${resolution_height}"), res.values());

        List<Argument> jvm = v.jvmArguments();
        assertEquals(List.of("-XstartOnFirstThread"), jvm.get(0).values());
        assertEquals("osx", jvm.get(0).rules().get(0).os().name());
        assertEquals(1, jvm.get(1).values().size(), "a plain string value");
        assertEquals("x86", jvm.get(2).rules().get(0).os().arch());
        assertEquals(Argument.of("${classpath}"), jvm.get(jvm.size() - 1));

        Library natives = v.libraries().stream().filter(l -> l.name().equals("org.lwjgl:lwjgl:3.3.3:natives-windows"))
            .findFirst().orElseThrow();
        assertEquals("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar", natives.downloads().artifact().path());
        assertEquals("windows", natives.rules().get(0).os().name());
        assertEquals(165442L, natives.downloads().artifact().size());
        assertFalse(natives.hasNatives());

        assertEquals("19", v.assetIndex().id());
        assertEquals(811666929L, v.assetIndex().totalSize());
        assertEquals("java-runtime-delta", v.javaVersion().component());
        assertEquals(21, v.javaVersion().majorVersion());
        assertEquals("-Dlog4j.configurationFile=${path}", v.logging().client().argument());
        assertEquals("client-1.21.2.xml", v.logging().client().file().id());
        assertNotNull(v.downloads().get("client"));
        assertEquals(1, v.complianceLevel());
    }

    @Test
    void parsesLegacyNativesAndFabricProfiles() {
        VersionJson legacy = VersionJson.parse(MinecraftFixtures.template("1.8.9.json"));
        assertNotNull(legacy.minecraftArguments());
        assertFalse(legacy.hasArguments());
        Library twitch = legacy.libraries().stream().filter(l -> l.name().startsWith("tv.twitch:twitch-platform"))
            .findFirst().orElseThrow();
        assertEquals("natives-windows-${arch}", twitch.natives().get("windows"));
        assertEquals(List.of("META-INF/"), twitch.extractExcludes());
        assertNull(twitch.downloads().artifact());
        assertEquals(3, twitch.downloads().classifiers().size());

        VersionJson fabric = VersionJson.parse(MinecraftFixtures.template("fabric-loader-0.16.10-1.21.4.json"));
        assertEquals("1.21.4", fabric.inheritsFrom());
        Library asm = fabric.libraries().get(0);
        assertEquals("https://maven.fabricmc.net/", asm.url());
        assertEquals("f0ed132a49244b042cd0e15702ab9f2ce3cc8436", asm.sha1());
        assertEquals(126093L, asm.size());
        assertNotNull(asm.sha512());
        assertNull(asm.downloads());
        assertEquals(List.of("-DFabricMcEmu= net.minecraft.client.main.Main "), fabric.jvmArguments().get(0).values());
        assertTrue(fabric.gameArguments().isEmpty());
    }

    @Test
    void argumentAdapterRoundTrips() {
        Argument plain = Argument.of("--demo");
        Argument ruled = new Argument(List.of("--width", "${resolution_width}"),
            List.of(new Rule("allow", null, Map.of("has_custom_resolution", true))));
        Argument single = new Argument(List.of("-Xss1M"), List.of(new Rule("allow", new OsRule(null, null, "x86"), null)));
        assertEquals("\"--demo\"", Json.COMPACT.toJson(plain));
        for (Argument a : List.of(plain, ruled, single)) {
            assertEquals(a, Json.read(Json.write(a), Argument.class));
        }
        assertTrue(Json.write(single).contains("\"value\": \"-Xss1M\""));
    }

    @Test
    void toleratesOddArgumentsAndNullEntries() {
        String json = """
            {"id":"x","arguments":{"game":[42, true, null, {"rules":[{"action":"allow"}]}, {"value":["a", null, 1]}],
             "jvm":null},"libraries":[null, {"name":"a:b:1"}], "unknownField": {"nested": [1,2]}}""";
        VersionJson v = VersionJson.parse(json);
        assertEquals(List.of("42"), v.gameArguments().get(0).values());
        assertEquals(List.of("true"), v.gameArguments().get(1).values());
        assertEquals(4, v.gameArguments().size(), "the null entry is dropped");
        assertTrue(v.gameArguments().get(2).values().isEmpty());
        assertEquals(List.of("a", "1"), v.gameArguments().get(3).values());
        assertTrue(v.jvmArguments().isEmpty());
        assertEquals(1, v.libraries().size());
        assertTrue(v.libraries().get(0).rules().isEmpty());
        assertTrue(v.downloads().isEmpty());
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(JsonParseException.class, () -> VersionJson.parse("{\"arguments\":{\"game\":[[1,2]]}}"));
        assertThrows(JsonParseException.class, () -> VersionJson.parse("not json at all {"));
        assertThrows(JsonParseException.class, () -> VersionJson.parse(""));
        assertThrows(JsonParseException.class, () -> VersionJson.parse("{\"libraries\": \"oops\"}"));
    }

    @Test
    void assetIndexFlags() {
        AssetIndex legacy = Json.read("{\"virtual\":true,\"objects\":{\"a/b.png\":{\"hash\":\"ab\",\"size\":3}}}", AssetIndex.class);
        assertTrue(legacy.isVirtual());
        assertFalse(legacy.isMapToResources());
        AssetIndex pre16 = Json.read("{\"map_to_resources\":true,\"objects\":{}}", AssetIndex.class);
        assertTrue(pre16.isMapToResources());
        assertEquals(3, legacy.objects().get("a/b.png").sizeOrUnknown());
    }
}
