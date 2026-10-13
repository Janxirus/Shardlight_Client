package dev.shardlight.client.java;

import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLocationsTest {
    static final OsInfo WINDOWS = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X64, "10.0");
    static final OsInfo MAC = new OsInfo(OsInfo.Os.MACOS, OsInfo.Arch.ARM64, "14.5");
    static final OsInfo LINUX = new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.X64, "6.8.0");

    @TempDir
    Path dir;

    static Path file(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "fake");
        return p.toAbsolutePath().normalize();
    }

    @Test
    void windowsVendorFoldersAndMinecraftRuntimes() throws Exception {
        Path pf = dir.resolve("Program Files");
        Path pf86 = dir.resolve("Program Files (x86)");
        Path adoptium = file(pf.resolve("Eclipse Adoptium/jdk-21.0.5.11-hotspot/bin/java.exe"));
        file(pf.resolve("Eclipse Adoptium/jdk-21.0.5.11-hotspot/bin/javaw.exe"));
        Path microsoft = file(pf.resolve("Microsoft/jdk-17.0.12.7-hotspot/bin/java.exe"));
        Path zulu = file(pf.resolve("Zulu/zulu-8/jre/bin/java.exe"));
        Path corretto = file(pf.resolve("Amazon Corretto/jdk21.0.4_7/bin/java.exe"));
        Path semeru = file(pf.resolve("Semeru/jdk-17.0.11.9-openj9/bin/java.exe"));
        Path bellsoft = file(pf86.resolve("BellSoft/LibericaJDK-8/bin/java.exe"));
        Path oracle = file(pf86.resolve("Java/jre1.8.0_402/bin/java.exe"));
        Path appData = dir.resolve("AppData/Roaming");
        Path official = file(appData.resolve(".minecraft/runtime/java-runtime-delta/windows-x64/java-runtime-delta/bin/java.exe"));
        Path local = dir.resolve("AppData/Local");
        Path store = file(local.resolve("Packages/" + JavaLocations.STORE_LAUNCHER_PACKAGE
            + "/LocalCache/Local/runtime/java-runtime-gamma/windows-x64/java-runtime-gamma/bin/java.exe"));
        Path ours = dir.resolve("data/game/runtimes");
        Path managed = file(ours.resolve("java-runtime-epsilon/windows-x64/bin/java.exe"));
        Path javaHome = file(dir.resolve("custom jdk/bin/java.exe"));
        Path onPath = file(dir.resolve("tools/java/bin/java.exe"));
        file(pf.resolve("Unrelated Vendor/jdk/bin/java.exe"));
        Files.createDirectories(pf.resolve("Java/empty-folder"));

        JavaEnvironment env = new JavaEnvironment(WINDOWS, Map.of(
            "java_home", "\"" + dir.resolve("custom jdk") + "\"",
            "Path", dir.resolve("tools/java/bin") + ";;C:\\does\\not\\exist;" + dir.resolve("custom jdk/bin"),
            "ProgramFiles", pf.toString(),
            "ProgramW6432", pf.toString(),
            "ProgramFiles(x86)", pf86.toString(),
            "APPDATA", appData.toString(),
            "LOCALAPPDATA", local.toString()), dir.resolve("home"), List.of());
        List<Path> found = JavaLocations.candidates(env, ours);
        assertEquals(javaHome, found.get(0), "JAVA_HOME first (variable names are case-insensitive on Windows)");
        assertEquals(onPath, found.get(1), "then PATH");
        assertEquals(managed, found.get(2), "then the launcher's own runtimes");
        assertTrue(found.containsAll(List.of(adoptium, microsoft, zulu, corretto, semeru, bellsoft, oracle, official, store)),
            found.toString());
        assertEquals(found.size(), found.stream().distinct().count(), "no duplicates");
        assertTrue(found.stream().noneMatch(p -> p.toString().contains("Unrelated Vendor")));
        assertTrue(found.stream().allMatch(p -> p.getFileName().toString().equals("java.exe")), "java.exe is probed");
    }

    @Test
    void macLayouts() throws Exception {
        Path system = dir.resolve("Library/Java/JavaVirtualMachines");
        Path temurin = file(system.resolve("temurin-21.jdk/Contents/Home/bin/java"));
        Path home = dir.resolve("home");
        Path user = file(home.resolve("Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/java"));
        Path official = file(home.resolve("Library/Application Support/minecraft/runtime/java-runtime-delta/mac-os-arm64/"
            + "java-runtime-delta/jre.bundle/Contents/Home/bin/java"));
        Path ours = dir.resolve("data/game/runtimes");
        Path managed = file(ours.resolve("java-runtime-delta/mac-os-arm64/jre.bundle/Contents/Home/bin/java"));
        List<Path> found = JavaLocations.candidates(new JavaEnvironment(MAC, Map.of(), home, List.of(system)), ours);
        assertTrue(found.containsAll(List.of(temurin, user, official, managed)), found.toString());
    }

    @Test
    void linuxLayouts() throws Exception {
        Path jvm = dir.resolve("usr/lib/jvm");
        Path openjdk = file(jvm.resolve("java-21-openjdk-amd64/bin/java"));
        Path jre8 = file(jvm.resolve("java-8-openjdk-amd64/jre/bin/java"));
        Path home = dir.resolve("home");
        Path sdkman = file(home.resolve(".sdkman/candidates/java/21.0.5-tem/bin/java"));
        Path official = file(home.resolve(".minecraft/runtime/jre-legacy/linux/jre-legacy/bin/java"));
        Path jdks = file(home.resolve(".jdks/corretto-17/bin/java"));
        List<Path> found = JavaLocations.candidates(new JavaEnvironment(LINUX,
            Map.of("PATH", "/nonexistent:" + jvm.resolve("java-21-openjdk-amd64/bin")), home, List.of(jvm)), null);
        assertEquals(openjdk, found.get(0), "PATH entry first");
        assertTrue(found.containsAll(List.of(jre8, sdkman, official, jdks)), found.toString());
        assertEquals(found.size(), found.stream().distinct().count());
    }

    @Test
    void missingFoldersAndBadValuesAreHarmless() {
        JavaEnvironment env = new JavaEnvironment(WINDOWS, Map.of("JAVA_HOME", "\u0000bad", "PATH", ";;;",
            "ProgramFiles", dir.resolve("nope").toString()), dir.resolve("nohome"), List.of());
        assertTrue(JavaLocations.candidates(env, dir.resolve("none")).isEmpty());
        assertFalse(JavaLocations.children(dir.resolve("none")).iterator().hasNext());
        assertEquals(null, env.get("MISSING"));
    }
}
