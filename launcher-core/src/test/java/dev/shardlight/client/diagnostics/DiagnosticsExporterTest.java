package dev.shardlight.client.diagnostics;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsExporterTest {
    static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkaWFnbm9zdGljcyJ9.ZGlhZ25vc3RpY3Mtc2lnbmF0dXJl";
    @TempDir
    Path dir;

    Map<String, String> read(Path zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    Project project() {
        Project p = new Project();
        p.id = "survival";
        p.name = "Survival";
        p.minecraftVersion = "1.21.4";
        p.notes = "my private notes about the base at 100 64 -200";
        p.java.javaPath = System.getProperty("user.home") + "/jdks/21/bin/java";
        ContentEntry mod = new ContentEntry();
        mod.title = "Sodium";
        mod.fileName = "sodium-0.6.jar";
        mod.versionNumber = "0.6.0";
        p.content.add(mod);
        return p;
    }

    Path gameDir() throws IOException {
        Path game = dir.resolve("game");
        Files.createDirectories(game.resolve("logs"));
        Files.createDirectories(game.resolve("crash-reports"));
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("logs/latest.log"), String.join("\n",
            "[12:00] Setting user: Steve",
            "[12:00] Session " + TOKEN,
            "[12:01] Connecting to play.secret-server.net, 25565",
            "[12:01] Resolved 203.0.113.42:25565 and [2001:db8:85a3:0:0:8a2e:370:7334]",
            "[12:02] Loaded mods from " + System.getProperty("user.home") + "/.minecraft/mods",
            "[12:02] LWJGL 3.3.3 and Windows 10.0.19045.1234"));
        Files.writeString(game.resolve("logs/shardlight-launch.log"),
            "Command: java -cp x Main --username Steve --uuid 0123 --accessToken <redacted> --quickPlayMultiplayer mc.example.org:25565");
        for (int i = 0; i < 5; i++) {
            Path report = game.resolve("crash-reports/crash-" + i + "-client.txt");
            Files.writeString(report, "Description: crash " + i);
            Files.setLastModifiedTime(report, FileTime.fromMillis(1_000_000L * (i + 1)));
        }
        Files.writeString(game.resolve("hs_err_pid42.log"), String.join("\n",
            "# A fatal error has been detected by the Java Runtime Environment:",
            "java_command: net.minecraft.client.main.Main --accessToken " + TOKEN,
            "",
            "Environment Variables:",
            "PATH=/secret/path",
            "USERNAME=someone",
            "",
            "---------------  S Y S T E M  ---------------"));
        Files.writeString(game.resolve("options.txt"), "lastServer:play.secret-server.net");
        Files.write(game.resolve("servers.dat"), new byte[] {1, 2, 3});
        Files.write(game.resolve("mods/sodium-0.6.jar"), new byte[2048]);
        return game;
    }

    @Test
    void exportsAShareableZip() throws Exception {
        Path logs = dir.resolve("launcher-logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("launcher.log"), "Signed in, refresh_token=M.C123_BAY.0.U.-abcdefghijklmnopqrstuvwxyz0123");
        Files.writeString(logs.resolve("diagnostics-old.zip"), "not a log");

        Path out = dir.resolve("out/diagnostics.zip");
        Path written = new DiagnosticsExporter().export(project(), gameDir(), logs, out, false);
        assertEquals(out, written);
        assertFalse(Files.exists(out.resolveSibling("diagnostics.zip.part")));

        Map<String, String> zip = read(out);
        assertTrue(zip.containsKey("README.txt"));
        assertTrue(zip.containsKey("system.txt"));
        assertTrue(zip.containsKey("project/project.json"));
        assertTrue(zip.containsKey("project/content.txt"));
        assertTrue(zip.containsKey("launcher/launcher.log"));
        assertTrue(zip.containsKey("game/latest.log"));
        assertTrue(zip.containsKey("game/shardlight-launch.log"));
        assertTrue(zip.containsKey("game/hs_err_pid42.log"));
        assertFalse(zip.keySet().stream().anyMatch(k -> k.contains("options") || k.contains("servers.dat")
            || k.endsWith(".zip")), zip.keySet().toString());
        assertEquals(DiagnosticsExporter.NEWEST_FILES, zip.keySet().stream().filter(k -> k.startsWith("game/crash-reports/")).count());
        assertTrue(zip.containsKey("game/crash-reports/crash-4-client.txt"), "newest first");
        assertFalse(zip.containsKey("game/crash-reports/crash-0-client.txt"));

        String all = String.join("\n", zip.values());
        assertFalse(all.contains(TOKEN), "tokens are masked");
        assertFalse(all.contains("M.C123_BAY"), "refresh tokens are masked");
        assertFalse(all.contains("my private notes"), "project notes are left out");
        assertFalse(all.contains("play.secret-server.net"), "server names are masked");
        assertFalse(all.contains("mc.example.org"), "quick play servers are masked");
        assertFalse(all.contains("203.0.113.42"), "IPv4 addresses are masked");
        assertFalse(all.contains("2001:db8:85a3"), "IPv6 addresses are masked");
        assertFalse(all.contains("Steve"), "the player name is replaced");
        assertFalse(all.contains("/secret/path"), "environment variables are left out");
        assertFalse(all.contains(System.getProperty("user.home")), "the home folder becomes ~");
        assertTrue(zip.get("game/latest.log").contains("Windows 10.0.19045.1234"), "version numbers are not IP addresses");
        assertTrue(zip.get("game/latest.log").contains("~/.minecraft/mods"));
        assertTrue(zip.get("project/content.txt").contains("Sodium"));
        assertTrue(zip.get("project/content.txt").contains("sodium-0.6.jar (2 KB)"));
        assertTrue(zip.get("README.txt").contains("Nothing was uploaded"));
    }

    @Test
    void serverAddressesStayWhenTheUserAsks() throws Exception {
        Path out = dir.resolve("with-servers.zip");
        new DiagnosticsExporter().export(project(), gameDir(), null, out, true);
        String latest = read(out).get("game/latest.log");
        assertTrue(latest.contains("play.secret-server.net"));
        assertTrue(latest.contains("203.0.113.42"));
        assertFalse(latest.contains(TOKEN), "tokens are masked either way");
    }

    @Test
    void launcherOnly() throws Exception {
        Path out = dir.resolve("launcher.zip");
        new DiagnosticsExporter().export(null, null, null, out, false);
        Map<String, String> zip = read(out);
        assertEquals(java.util.Set.of("README.txt", "system.txt"), zip.keySet());
        assertTrue(zip.get("README.txt").contains("Launcher only"));
    }

    @Test
    void registeredSecretsAreMaskedEverywhere() {
        Redactor.register("my-curseforge-key-1234567890");
        try {
            assertEquals("key <redacted>", DiagnosticsExporter.sanitize("key my-curseforge-key-1234567890", true));
        } finally {
            Redactor.forget("my-curseforge-key-1234567890");
        }
    }

    @Test
    void javaFatalErrorLogsLoseTheirEnvironment() {
        String out = DiagnosticsExporter.withoutEnvironment("a\nEnvironment Variables:\nJAVA_HOME=/x\nPATH=/y\n\nb\n");
        assertTrue(out.contains("Environment Variables: <left out>"));
        assertFalse(out.contains("JAVA_HOME"));
        assertTrue(out.contains("b"));
    }
}
