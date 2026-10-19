package dev.shardlight.client.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.diagnostics.CrashAnalyzer;
import dev.shardlight.client.diagnostics.Finding;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.loader.LoaderService;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.MinecraftFixtures;
import dev.shardlight.client.minecraft.VersionManifestService;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.testutil.TestHttpServer;
import dev.shardlight.client.testutil.TestProjects;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installs a version whose "game" is a tiny Java program and launches it for real: the command, the redacted
 * output log, play time, crash detection and analysis, stopping, and one game per project.
 */
class LaunchServiceTest {
    static final String VERSION = "fake-1";
    static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsYXVuY2gtdGVzdCJ9.c2lnbmF0dXJlLXZhbHVlLXRlc3Q";
    static final MinecraftSession SESSION = new MinecraftSession("Steve", "0123456789abcdef0123456789abcdef", TOKEN,
        "2535400000000000", "msa", "client-id");
    static byte[] fakeGame;

    @TempDir
    static Path compileDir;
    @TempDir
    Path dir;
    TestHttpServer server;
    MinecraftFixtures fx;
    AppPaths paths;
    DownloadManager downloads;
    TestProjects projects;
    LaunchService launches;

    @BeforeAll
    static void compileFakeGame() throws Exception {
        String source = """
            package net.minecraft.client.main;

            import java.nio.file.*;
            import java.util.*;

            public class Main {
                public static void main(String[] args) throws Exception {
                    List<String> a = Arrays.asList(args);
                    Path gameDir = Paths.get(a.get(a.indexOf("--gameDir") + 1));
                    Files.write(gameDir.resolve("args.txt"), a);
                    System.out.println("Setting user: " + a.get(a.indexOf("--username") + 1));
                    System.out.println("Checking session " + a.get(a.indexOf("--accessToken") + 1));
                    System.out.println("Connecting to play.example.net, 25565 (192.168.1.20)");
                    String mode = System.getProperty("fake.mode", "ok");
                    if (mode.equals("crash")) {
                        Path dir = gameDir.resolve("crash-reports");
                        Files.createDirectories(dir);
                        Files.write(dir.resolve("crash-2026-10-12_10.00.00-client.txt"), Arrays.asList(
                            "---- Minecraft Crash Report ----", "", "Description: Initializing game", "",
                            "java.lang.OutOfMemoryError: Java heap space", "\\tat net.minecraft.Fake.run(Fake.java:1)"));
                        System.out.println("#@!@# Game crashed! Crash report saved to: #@!@# " + dir);
                        System.exit(-1);
                    } else if (mode.equals("fabric")) {
                        System.out.println("Incompatible mods found!");
                        System.out.println(" - Mod 'Sodium' (sodium) 0.6.0 requires version 0.100.0 or later of mod 'Fabric API' (fabric-api), which is missing!");
                        System.exit(1);
                    } else if (mode.equals("wait")) {
                        System.out.println("Waiting to be closed");
                        Thread.sleep(60000);
                    }
                    System.out.println("Stopping!");
                }
            }
            """;
        Path src = compileDir.resolve("src/net/minecraft/client/main/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = compileDir.resolve("classes");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, "--release", "17", "-d", classes.toString(), src.toString());
        assertEquals(0, rc, err.toString(StandardCharsets.UTF_8));
        fakeGame = MinecraftFixtures.zip(Map.of("net/minecraft/client/main/Main.class",
            Files.readAllBytes(classes.resolve("net/minecraft/client/main/Main.class"))));
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new TestHttpServer();
        fx = new MinecraftFixtures(server);
        paths = new AppPaths(dir.resolve("data"));
        paths.createDirectories();
        HttpService http = HttpService.forTests();
        downloads = new DownloadManager(http, paths.files(), 4, 1);
        downloads.setBackoffMillis(5);
        VersionManifestService manifest = new VersionManifestService(http, fx.endpoints, paths);
        manifest.setFailureQuietMillis(0);
        GameInstaller installer = new GameInstaller(http, fx.endpoints, paths, downloads, manifest, OsInfo.current());
        JavaRuntimeService java = new JavaRuntimeService(http, fx.endpoints, paths, downloads);
        projects = new TestProjects(paths);
        launches = new LaunchService(paths, projects, new LoaderService(List.of(), installer), installer, java,
            () -> false, new CrashAnalyzer(), OsInfo.current());
        publishFakeVersion();
    }

    @AfterEach
    void tearDown() {
        launches.runningAll().forEach(GameProcess::stop);
        downloads.close();
        server.close();
    }

    /** the 1.21.4 template with no libraries, Java 17 and the fake game as its client jar */
    void publishFakeVersion() {
        JsonObject o = JsonParser.parseString(MinecraftFixtures.template("1.21.4.json")).getAsJsonObject();
        o.addProperty("id", VERSION);
        o.add("libraries", new JsonArray());
        o.getAsJsonObject("javaVersion").addProperty("majorVersion", 17);
        JsonObject prepared = JsonParser.parseString(fx.prepareJson(Json.write(o))).getAsJsonObject();
        fx.serve(prepared.getAsJsonObject("downloads").getAsJsonObject("client"), fakeGame);
        fx.publish(VERSION, "release", Json.write(prepared));
    }

    Project project(String id, String mode) throws LauncherException {
        Project p = projects.create(id, VERSION, LoaderType.VANILLA, null);
        p.java.javaPath = Path.of(System.getProperty("java.home"), "bin",
            OsInfo.current().isWindows() ? "java.exe" : "java").toString();
        p.java.minMemoryMb = 32;
        p.java.maxMemoryMb = 256;
        p.java.extraJvmArgs = "-Dfake.mode=" + mode;
        return projects.save(p);
    }

    GameExit run(Project p, LaunchOptions options) throws Exception {
        LaunchPlan plan = launches.prepare(p, SESSION, options, null, CancellationToken.NONE);
        GameProcess game = launches.start(plan);
        return game.onExit().get(60, TimeUnit.SECONDS);
    }

    @Test
    void theSessionIsAskedForAfterTheDownloads() throws Exception {
        Project p = project("late-session", "ok");
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        LaunchPlan plan = launches.prepare(p, c -> {
            asked.incrementAndGet();
            assertTrue(Files.isDirectory(paths.versions().resolve(VERSION)), "the game is installed before the session is read");
            return SESSION;
        }, LaunchOptions.NONE, null, CancellationToken.NONE);
        assertEquals(1, asked.get());
        List<String> cmd = plan.command().command();
        assertEquals(TOKEN, cmd.get(cmd.indexOf("--accessToken") + 1));
    }

    @Test
    void launchesTheGameAndRecordsHowItWent() throws Exception {
        Project p = project("vanilla", "ok");
        LaunchPlan plan = launches.prepare(p, SESSION, LaunchOptions.NONE, null, CancellationToken.NONE);

        List<String> cmd = plan.command().command();
        assertEquals(TOKEN, cmd.get(cmd.indexOf("--accessToken") + 1));
        assertTrue(cmd.contains("net.minecraft.client.main.Main"));
        assertTrue(cmd.contains("-Xmx256M") || cmd.contains("-Xmx256m"), cmd.toString());
        assertFalse(plan.command().redacted().contains(TOKEN));
        assertEquals(VERSION, projects.get("vanilla").orElseThrow().installedVersionId);

        GameProcess game = launches.start(plan);
        assertTrue(Files.exists(paths.project("vanilla").resolve(LaunchService.PID_FILE)));
        GameExit exit = game.onExit().get(60, TimeUnit.SECONDS);

        assertEquals(0, exit.exitCode());
        assertFalse(exit.crashed());
        assertTrue(exit.findings().isEmpty());
        assertTrue(exit.crashReport().isEmpty());
        assertTrue(launches.running("vanilla").isEmpty());
        assertFalse(launches.isRunning("vanilla"));
        assertFalse(Files.exists(paths.project("vanilla").resolve(LaunchService.PID_FILE)));

        Path gameDir = projects.gameDir(p);
        List<String> args = Files.readAllLines(gameDir.resolve("args.txt"));
        assertEquals(gameDir.toAbsolutePath().normalize(), Path.of(args.get(args.indexOf("--gameDir") + 1)).toAbsolutePath().normalize());
        assertEquals("Steve", args.get(args.indexOf("--username") + 1));

        String log = Files.readString(gameDir.resolve("logs").resolve(LaunchService.LOG_FILE));
        assertTrue(log.contains("Setting user: Steve"), log);
        assertTrue(log.contains("Stopping!"), log);
        assertFalse(log.contains(TOKEN), "the access token never reaches the log file");
        assertFalse(game.recentLines().stream().anyMatch(l -> l.contains(TOKEN)));

        Project after = projects.get("vanilla").orElseThrow();
        assertTrue(after.lastPlayedAt > 0);
        assertTrue(after.totalPlayMillis >= 0);
    }

    @Test
    void aCrashIsDetectedAndExplained() throws Exception {
        GameExit exit = run(project("crashy", "crash"), LaunchOptions.NONE);

        assertTrue(exit.crashed());
        assertFalse(exit.stopRequested());
        assertTrue(exit.crashReport().isPresent());
        assertTrue(exit.crashReport().get().getFileName().toString().startsWith("crash-"));
        List<String> titles = exit.findings().stream().map(Finding::titleKey).toList();
        assertEquals("diagnostics.memory.title", titles.get(0), titles.toString());
        assertTrue(titles.contains("diagnostics.report.title"), titles.toString());
    }

    @Test
    void aFailedStartWithoutCrashReportIsAnalysedFromTheOutput() throws Exception {
        GameExit exit = run(project("modded", "fabric"), LaunchOptions.NONE);

        assertTrue(exit.crashed());
        assertEquals(1, exit.exitCode());
        assertTrue(exit.crashReport().isEmpty());
        Finding f = exit.findings().get(0);
        assertEquals("diagnostics.missing.title", f.titleKey());
        assertEquals(List.of("Sodium", "Fabric API", "version 0.100.0 or later"), f.args());
        assertEquals("fabric-api", f.modId());
    }

    @Test
    void oneGamePerProjectAndStoppingIsNotACrash() throws Exception {
        Project p = project("waiting", "wait");
        LaunchPlan plan = launches.prepare(p, SESSION, LaunchOptions.NONE, null, CancellationToken.NONE);
        GameProcess game = launches.start(plan);
        assertTrue(launches.running("waiting").isPresent());
        assertEquals(1, launches.runningAll().size());

        LauncherException twice = assertThrows(LauncherException.class, () -> launches.start(plan));
        assertEquals(ErrorCode.ALREADY_RUNNING, twice.code());
        LauncherException again = assertThrows(LauncherException.class,
            () -> launches.prepare(p, SESSION, LaunchOptions.NONE, null, CancellationToken.NONE));
        assertEquals(ErrorCode.ALREADY_RUNNING, again.code());

        game.stop();
        GameExit exit = game.onExit().get(30, TimeUnit.SECONDS);
        assertTrue(exit.stopRequested());
        assertFalse(exit.crashed());
        assertTrue(exit.findings().isEmpty());
        assertFalse(launches.isRunning("waiting"));
    }

    @Test
    void aGameLeftRunningByAnEarlierLauncherBlocksASecondCopy() throws Exception {
        Project p = project("leftover", "ok");
        Path pid = paths.project("leftover").resolve(LaunchService.PID_FILE);
        ProcessHandle self = ProcessHandle.current();
        long started = self.info().startInstant().map(i -> i.toEpochMilli()).orElse(0L);
        Files.writeString(pid, self.pid() + "\n" + started + "\n");
        assertTrue(launches.isRunning("leftover"), "this test's own Java process stands in for the game");
        LauncherException e = assertThrows(LauncherException.class,
            () -> launches.prepare(p, SESSION, LaunchOptions.NONE, null, CancellationToken.NONE));
        assertEquals(ErrorCode.ALREADY_RUNNING, e.code());

        Files.writeString(pid, "999999999\n0\n");
        assertFalse(launches.isRunning("leftover"), "a pid that no longer runs is cleared");
        assertFalse(Files.exists(pid));
    }

    @Test
    void quickPlayOpensAWorldThatExists() throws Exception {
        Project p = project("quick", "ok");
        LauncherException missing = assertThrows(LauncherException.class,
            () -> launches.prepare(p, SESSION, new LaunchOptions("Missing World", null, false), null, CancellationToken.NONE));
        assertEquals(ErrorCode.NOT_FOUND, missing.code());
        assertThrows(LauncherException.class,
            () -> launches.prepare(p, SESSION, new LaunchOptions("../escape", null, false), null, CancellationToken.NONE));

        Files.createDirectories(projects.gameDir(p).resolve("saves/My World"));
        LaunchPlan plan = launches.prepare(p, SESSION, new LaunchOptions("My World", null, false), null, CancellationToken.NONE);
        List<String> cmd = plan.command().command();
        assertEquals("My World", cmd.get(cmd.indexOf("--quickPlaySingleplayer") + 1));
    }

    @Test
    void preparingNeedsASignedInPlayer() throws Exception {
        Project p = project("nosession", "ok");
        LauncherException e = assertThrows(LauncherException.class,
            () -> launches.prepare(p, (MinecraftSession) null, LaunchOptions.NONE, null, CancellationToken.NONE));
        assertNotNull(e.userMessage());
    }
}
