package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchPreparationTest {
    @TempDir
    Path dir;

    InstalledVersion withNatives(List<Path> jars, List<String> excludes) {
        ResolvedVersion v = VersionResolver.merge(List.of("x"), List.of(
            dev.shardlight.client.minecraft.model.VersionJson.parse("{\"id\":\"x\",\"mainClass\":\"a.B\"}")));
        return new InstalledVersion("x", v, dir.resolve("x.jar"), List.of(dir.resolve("x.jar")), jars, excludes, "legacy",
            dir.resolve("assets"), false, false, 8, null, null);
    }

    Path jar(String name, Map<String, byte[]> entries) throws Exception {
        Path p = dir.resolve("libs").resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, MinecraftFixtures.zip(entries));
        return p;
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void extractsNativesSkippingMetaInfAndExcludes() throws Exception {
        Map<String, byte[]> legacy = new LinkedHashMap<>();
        legacy.put("META-INF/MANIFEST.MF", b("Manifest-Version: 1.0\n"));
        legacy.put("META-INF/MOJANG_C.SF", b("signature"));
        legacy.put("lwjgl64.dll", b("dll bytes"));
        legacy.put("OpenAL64.dll", b("openal"));
        legacy.put("debug/skip-me.txt", b("excluded by extract.exclude"));
        Map<String, byte[]> modern = new LinkedHashMap<>();
        modern.put("META-INF/MANIFEST.MF", b("Manifest-Version: 1.0\n"));
        modern.put("windows/x64/org/lwjgl/lwjgl.dll", b("modern dll"));
        InstalledVersion iv = withNatives(List.of(jar("lwjgl-platform-natives-windows.jar", legacy),
            jar("lwjgl-3.3.3-natives-windows.jar", modern)), List.of("META-INF/", "debug/"));
        Path natives = dir.resolve("natives/x");
        assertEquals(3, LaunchPreparation.extractNatives(iv, natives, CancellationToken.NONE));
        assertEquals("dll bytes", Files.readString(natives.resolve("lwjgl64.dll")));
        assertTrue(Files.isRegularFile(natives.resolve("OpenAL64.dll")));
        assertTrue(Files.isRegularFile(natives.resolve("windows/x64/org/lwjgl/lwjgl.dll")));
        assertFalse(Files.exists(natives.resolve("META-INF")));
        assertFalse(Files.exists(natives.resolve("debug")));

        // up-to-date files are left alone (a running game may hold them open); changed ones are replaced
        assertEquals(0, LaunchPreparation.extractNatives(iv, natives, CancellationToken.NONE));
        Files.writeString(natives.resolve("OpenAL64.dll"), "tampered");
        assertEquals(1, LaunchPreparation.extractNatives(iv, natives, CancellationToken.NONE));
        assertEquals("openal", Files.readString(natives.resolve("OpenAL64.dll")));
    }

    @Test
    void missingOrBrokenNativeJars() throws Exception {
        InstalledVersion missing = withNatives(List.of(dir.resolve("gone.jar")), List.of("META-INF/"));
        LauncherException e = assertThrows(LauncherException.class,
            () -> LaunchPreparation.extractNatives(missing, dir.resolve("n"), CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        assertEquals("install.error.nativeMissing", e.messageKey());

        Path broken = dir.resolve("broken.jar");
        Files.writeString(broken, "this is not a zip");
        assertEquals(ErrorCode.INVALID_ARCHIVE, assertThrows(LauncherException.class, () -> LaunchPreparation.extractNatives(
            withNatives(List.of(broken), List.of()), dir.resolve("n"), CancellationToken.NONE)).code());

        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        assertTrue(assertThrows(LauncherException.class, () -> LaunchPreparation.extractNatives(
            withNatives(List.of(broken), List.of()), dir.resolve("n"), cancel)).isCancelled());
    }

    @Test
    void maliciousNativeJarsAreRejected() throws Exception {
        for (String evil : List.of("../../escape.dll", "/abs.dll", "C:/windows/evil.dll", "con.dll", "a/../../b.dll")) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("ok.dll", b("fine"));
            entries.put(evil, b("evil"));
            InstalledVersion iv = withNatives(List.of(jar("evil.jar", entries)), List.of("META-INF/"));
            Path natives = dir.resolve("natives-" + Math.abs(evil.hashCode()));
            LauncherException e = assertThrows(LauncherException.class,
                () -> LaunchPreparation.extractNatives(iv, natives, CancellationToken.NONE), evil);
            assertEquals(ErrorCode.UNSAFE_PATH, e.code(), evil);
            assertFalse(Files.exists(natives.resolve("ok.dll")), "nothing is written from an unsafe jar");
        }
        assertFalse(Files.exists(dir.resolve("escape.dll")));
    }

    @Test
    void excludeMatching() {
        assertTrue(LaunchPreparation.excluded("META-INF/MANIFEST.MF", List.of("META-INF/")));
        assertTrue(LaunchPreparation.excluded("META-INF\\x.SF", List.of("META-INF/")));
        assertFalse(LaunchPreparation.excluded("lwjgl.dll", List.of("META-INF/", "")));
        assertFalse(LaunchPreparation.excluded("lwjgl.dll", List.of()));
    }
}
