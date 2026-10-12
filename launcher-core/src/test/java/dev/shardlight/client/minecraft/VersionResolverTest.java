package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.model.Argument;
import dev.shardlight.client.minecraft.model.Library;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionResolverTest {
    @TempDir
    Path versions;

    VersionResolver resolver() {
        return new VersionResolver(versions);
    }

    void write(String id, String json) throws Exception {
        MinecraftFixtures.writeVersion(versions, id, json);
    }

    @Test
    void resolvesVanillaAlone() throws Exception {
        write("1.21.4", MinecraftFixtures.template("1.21.4.json"));
        ResolvedVersion v = resolver().resolve("1.21.4");
        assertEquals(List.of("1.21.4"), v.chain());
        assertEquals("1.21.4", v.jarId());
        assertEquals("1.21.4", v.clientOwnerId());
        assertEquals("19", v.assetIndexId());
        assertEquals(21, v.javaMajorVersion());
        assertEquals("java-runtime-delta", v.javaComponent());
        assertTrue(v.supportsQuickPlay());
        assertTrue(v.hasModernArguments());
        assertFalse(v.isInherited());
    }

    @Test
    void mergesFabricOverVanilla() throws Exception {
        write("1.21.4", MinecraftFixtures.template("1.21.4.json"));
        write("fabric-loader-0.16.10-1.21.4", MinecraftFixtures.template("fabric-loader-0.16.10-1.21.4.json"));
        ResolvedVersion v = resolver().resolve("fabric-loader-0.16.10-1.21.4");
        assertEquals(List.of("fabric-loader-0.16.10-1.21.4", "1.21.4"), v.chain());
        assertEquals("1.21.4", v.rootId());
        assertTrue(v.isInherited());
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", v.mainClass());
        // inherited
        assertEquals("19", v.assetIndexId());
        assertEquals(21, v.javaMajorVersion());
        assertEquals("client-1.21.2.xml", v.logging().client().file().id());
        assertEquals("1.21.4", v.clientOwnerId());
        assertEquals("fabric-loader-0.16.10-1.21.4", v.jarId(), "the child gets its own copy of the parent's jar");
        // parent's arguments first, then the child's
        assertEquals(Argument.of("${classpath}"), v.jvmArguments().get(v.jvmArguments().size() - 2));
        assertEquals(List.of("-DFabricMcEmu= net.minecraft.client.main.Main "),
            v.jvmArguments().get(v.jvmArguments().size() - 1).values());
        assertEquals(Argument.of("--username"), v.gameArguments().get(0));
        // the child's libraries first
        assertEquals("org.ow2.asm:asm:9.7.1", v.libraries().get(0).name());
        assertEquals("net.fabricmc:fabric-loader:0.16.10", v.libraries().get(4).name());
        assertEquals("ca.weblite:java-objc-bridge:1.1", v.libraries().get(5).name());
        assertEquals("release", v.type());
    }

    @Test
    void childLibrariesReplaceParentsByGroupArtifactClassifier() throws Exception {
        write("base", """
            {"id":"base","mainClass":"a.Main","downloads":{"client":{"url":"https://x/c.jar","sha1":null}},
             "libraries":[{"name":"org.ow2.asm:asm:9.3"},{"name":"org.lwjgl:lwjgl:3.3.1"},
                          {"name":"org.lwjgl:lwjgl:3.3.1:natives-linux"},{"name":"com.example:dup:1.0","rules":[{"action":"allow","os":{"name":"osx"}}]},
                          {"name":"com.example:dup:2.0","rules":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]}]}""");
        write("child", """
            {"id":"child","inheritsFrom":"base","libraries":[{"name":"org.ow2.asm:asm:9.7.1"},{"name":"org.lwjgl:lwjgl:3.3.3"}]}""");
        ResolvedVersion v = resolver().resolve("child");
        List<String> names = v.libraries().stream().map(Library::name).toList();
        assertEquals(List.of("org.ow2.asm:asm:9.7.1", "org.lwjgl:lwjgl:3.3.3", "org.lwjgl:lwjgl:3.3.1:natives-linux",
            "com.example:dup:1.0", "com.example:dup:2.0"), names, "same-version duplicates are OS alternatives and stay");
        assertEquals("a.Main", v.mainClass());
        assertEquals(ResolvedVersion.DEFAULT_ASSETS, v.assetIndexId());
        assertEquals(8, v.javaMajorVersion());
        assertNull(v.javaComponent());
    }

    @Test
    void jarOverrideAndLegacyArguments() throws Exception {
        write("1.12.2", """
            {"id":"1.12.2","mainClass":"net.minecraft.client.main.Main","minecraftArguments":"--username ${auth_player_name}",
             "downloads":{"client":{"url":"https://x/client.jar"}},"assets":"1.12","type":"release"}""");
        write("1.12.2-forge-14.23.5.2860", """
            {"id":"1.12.2-forge-14.23.5.2860","inheritsFrom":"1.12.2","jar":"1.12.2","type":"release",
             "mainClass":"net.minecraft.launchwrapper.Launch",
             "minecraftArguments":"--username ${auth_player_name} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker"}""");
        ResolvedVersion v = resolver().resolve("1.12.2-forge-14.23.5.2860");
        assertEquals("1.12.2", v.jarId());
        assertEquals("net.minecraft.launchwrapper.Launch", v.mainClass());
        assertTrue(v.minecraftArguments().contains("FMLTweaker"));
        assertEquals("1.12", v.assetIndexId());
        assertFalse(v.supportsQuickPlay());
        assertFalse(v.hasModernArguments());
    }

    @Test
    void missingVersionAndParent() throws Exception {
        LauncherException e = assertThrows(LauncherException.class, () -> resolver().resolve("1.99"));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        assertEquals("install.error.versionNotInstalled", e.messageKey());
        write("child", "{\"id\":\"child\",\"inheritsFrom\":\"gone\"}");
        LauncherException p = assertThrows(LauncherException.class, () -> resolver().resolve("child"));
        assertEquals(ErrorCode.NOT_FOUND, p.code());
        assertEquals("install.error.parentMissing", p.messageKey());
        assertFalse(resolver().exists("1.99"));
        assertTrue(resolver().exists("child"));
    }

    @Test
    void inheritanceLoopsAreDetected() throws Exception {
        write("a", "{\"id\":\"a\",\"inheritsFrom\":\"b\"}");
        write("b", "{\"id\":\"b\",\"inheritsFrom\":\"a\"}");
        write("self", "{\"id\":\"self\",\"inheritsFrom\":\"self\"}");
        assertEquals("install.error.inheritanceLoop",
            assertThrows(LauncherException.class, () -> resolver().resolve("a")).messageKey());
        assertEquals("install.error.inheritanceLoop",
            assertThrows(LauncherException.class, () -> resolver().resolve("self")).messageKey());
        for (int i = 0; i < 20; i++) {
            write("d" + i, "{\"id\":\"d" + i + "\",\"inheritsFrom\":\"d" + (i + 1) + "\"}");
        }
        write("d20", "{\"id\":\"d20\"}");
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> resolver().resolve("d0")).code());
    }

    @Test
    void corruptJsonAndBom() throws Exception {
        write("broken", "{\"id\": \"broken\", \"libraries\": [");
        LauncherException e = assertThrows(LauncherException.class, () -> resolver().resolve("broken"));
        assertEquals(ErrorCode.INVALID_DATA, e.code());
        assertEquals("install.error.versionJsonInvalid", e.messageKey());
        write("bom", "﻿{\"id\":\"bom\",\"mainClass\":\"x.Y\"}");
        assertEquals("x.Y", resolver().resolve("bom").mainClass());
    }

    @Test
    void unsafeIdsAreRejected() throws Exception {
        Files.writeString(versions.resolve("evil.json"), "{}");
        for (String id : new String[] {"../evil", "..", "a/b", "a\\b", "C:evil", "con", "", " x "}) {
            LauncherException e = assertThrows(LauncherException.class, () -> resolver().resolve(id), id);
            assertEquals(ErrorCode.UNSAFE_PATH, e.code(), id);
        }
        write("child", "{\"id\":\"child\",\"inheritsFrom\":\"../../evil\"}");
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class, () -> resolver().resolve("child")).code());
    }
}
