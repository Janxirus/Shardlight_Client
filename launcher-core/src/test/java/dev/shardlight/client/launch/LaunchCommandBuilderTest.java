package dev.shardlight.client.launch;

import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.java.JavaInstall;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.minecraft.InstalledVersion;
import dev.shardlight.client.minecraft.MinecraftFixtures;
import dev.shardlight.client.minecraft.ResolvedVersion;
import dev.shardlight.client.minecraft.VersionResolver;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchCommandBuilderTest {
    static final OsInfo WINDOWS = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X64, "10.0");
    static final OsInfo WINDOWS_32 = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X86, "10.0");
    static final OsInfo MAC = new OsInfo(OsInfo.Os.MACOS, OsInfo.Arch.ARM64, "14.5");
    static final OsInfo LINUX = new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.X64, "6.8.0");
    /** a made-up JWT-shaped token; never sent anywhere */
    static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsYXVuY2gtdGVzdCJ9.ZmFrZS1zaWduYXR1cmUtZm9yLXRlc3Rz";
    static final MinecraftSession SESSION = new MinecraftSession("Steve_42", "0f9e8d7c6b5a49388271605f4e3d2c1b", TOKEN,
        "2535412345678901", "msa", "00000000-aaaa-bbbb-cccc-000000000000");

    @TempDir
    Path dir;
    Path versions;
    Path game;
    Path gameDir;
    Path natives;

    @BeforeEach
    void setUp() {
        versions = dir.resolve("data/game/versions");
        game = dir.resolve("data/game");
        gameDir = dir.resolve("data/projects/survival/minecraft");
        natives = dir.resolve("data/projects/survival/natives/1.21.4");
    }

    InstalledVersion installed(String id, int javaMajor, boolean virtual, boolean mapToResources, String loggingArgument,
                               String... templates) throws Exception {
        for (String t : templates) {
            String json = MinecraftFixtures.template(t);
            String vid = dev.shardlight.client.minecraft.model.VersionJson.parse(json).id();
            MinecraftFixtures.writeVersion(versions, vid, json);
        }
        ResolvedVersion v = new VersionResolver(versions).resolve(id);
        Path client = versions.resolve(id).resolve(id + ".jar");
        List<Path> cp = List.of(game.resolve("libraries/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"),
            game.resolve("libraries/com/mojang/authlib/6.0.57/authlib-6.0.57.jar"), client);
        return new InstalledVersion(id, v, client, cp, List.of(), List.of("META-INF/"), v.assetIndexId(),
            game.resolve("assets"), virtual, mapToResources, javaMajor, v.javaComponent(), loggingArgument);
    }

    InstalledVersion modern() throws Exception {
        return installed("1.21.4", 21, false, false,
            "-Dlog4j.configurationFile=" + game.resolve("assets/log_configs/client-1.21.2.xml"), "1.21.4.json");
    }

    static JavaInstall java(OsInfo os, int major) {
        return new JavaInstall(os.isWindows() ? Path.of("C:/Java/jdk-21/bin/javaw.exe") : Path.of("/opt/java/bin/java"),
            major, major + ".0.5", "Eclipse Adoptium", "amd64", false);
    }

    LaunchContext ctx(InstalledVersion iv, OsInfo os) {
        return new LaunchContext(iv, java(os, 21), gameDir, natives, SESSION, 512, 4096, "", "", 0, 0, false, null, null,
            false, "shardlight-client", "0.1.0", os);
    }

    static LaunchContext with(LaunchContext c, String extraJvm, String extraGame, int w, int h, boolean full, String sp,
                              String mp, boolean demo) {
        return new LaunchContext(c.installed(), c.java(), c.gameDir(), c.nativesDir(), c.session(), c.minMemoryMb(),
            c.maxMemoryMb(), extraJvm, extraGame, w, h, full, sp, mp, demo, c.launcherName(), c.launcherVersion(), c.os());
    }

    static String after(List<String> cmd, String flag) {
        int i = cmd.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < cmd.size(), flag + " missing in " + cmd);
        return cmd.get(i + 1);
    }

    static List<String> gameArgs(List<String> cmd, String mainClass) {
        return cmd.subList(cmd.indexOf(mainClass) + 1, cmd.size());
    }

    @Test
    void modernVanillaOnWindows() throws Exception {
        InstalledVersion iv = modern();
        LaunchCommand lc = LaunchCommandBuilder.build(with(ctx(iv, WINDOWS), "-XX:+UseG1GC -Dshardlight.test=\"a b\"", "", 0, 0,
            false, null, null, false));
        List<String> cmd = lc.command();
        assertEquals(Path.of("C:/Java/jdk-21/bin/javaw.exe").toAbsolutePath().toString(), cmd.get(0));
        assertEquals(gameDir.toAbsolutePath().normalize(), lc.workingDirectory());

        String heapDump = "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump";
        assertEquals(heapDump, cmd.get(1), "the version's own JVM arguments come first");
        assertFalse(cmd.contains("-XstartOnFirstThread"));
        assertFalse(cmd.contains("-Xss1M"));
        String nat = natives.toAbsolutePath().normalize().toString();
        assertTrue(cmd.contains("-Djava.library.path=" + nat));
        assertTrue(cmd.contains("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nat));
        assertTrue(cmd.contains("-Dminecraft.launcher.brand=shardlight-client"));
        assertTrue(cmd.contains("-Dminecraft.launcher.version=0.1.0"));
        String cp = after(cmd, "-cp");
        List<String> entries = List.of(cp.split(";"));
        assertEquals(3, entries.size());
        assertTrue(entries.get(2).endsWith("1.21.4.jar"), "client jar last");
        assertEquals(1, Collections.frequency(cmd, "-cp"), "no second classpath for modern versions");

        int cpIndex = cmd.indexOf(cp);
        int xms = cmd.indexOf("-Xms512M");
        int xmx = cmd.indexOf("-Xmx4096M");
        int log = cmd.indexOf(iv.loggingArgument());
        int g1 = cmd.indexOf("-XX:+UseG1GC");
        int main = cmd.indexOf("net.minecraft.client.main.Main");
        assertTrue(cpIndex < xms && xms < xmx && xmx < log && log < g1 && g1 < main, cmd.toString());
        assertEquals("-Dshardlight.test=a b", cmd.get(g1 + 1), "quotes group an extra argument");

        List<String> game = gameArgs(cmd, "net.minecraft.client.main.Main");
        assertEquals(List.of("--username", "Steve_42", "--version", "1.21.4",
            "--gameDir", gameDir.toAbsolutePath().normalize().toString(),
            "--assetsDir", game.get(7), "--assetIndex", "19", "--uuid", SESSION.uuid(), "--accessToken", TOKEN,
            "--clientId", SESSION.clientId(), "--xuid", SESSION.xuid(), "--userType", "msa", "--versionType", "release"), game);
        assertEquals(this.game.resolve("assets").toAbsolutePath().normalize().toString(), game.get(7));
        assertFalse(cmd.contains("--demo"));
        assertFalse(cmd.contains("--width"));
        assertTrue(cmd.stream().noneMatch(a -> a.startsWith("--quickPlay")));
        assertTrue(cmd.stream().noneMatch(a -> a.contains("${")), "every placeholder is filled: " + cmd);
        assertEquals(1, cmd.stream().filter(a -> a.contains(TOKEN)).count(), "the token appears only as its argument");
    }

    @Test
    void redactedCommandHidesTheToken() throws Exception {
        LaunchCommand lc = LaunchCommandBuilder.build(ctx(modern(), WINDOWS));
        String shown = lc.redacted();
        assertFalse(shown.contains(TOKEN));
        assertTrue(shown.contains("--accessToken " + Redactor.MASK), shown);
        assertTrue(shown.contains("--username Steve_42"));
        assertFalse(lc.toString().contains(TOKEN));
        // a token that doesn't look like a JWT is masked too: the builder registers it with the Redactor
        MinecraftSession plain = new MinecraftSession("Alex", "1234", "opaque-token-value-1234567890", "", "msa", "");
        LaunchContext c = ctx(modern(), LINUX);
        LaunchCommand other = LaunchCommandBuilder.build(new LaunchContext(c.installed(), c.java(), c.gameDir(), c.nativesDir(),
            plain, 0, 0, "", "", 0, 0, false, null, null, false, null, null, LINUX));
        assertFalse(other.redacted().contains("opaque-token-value-1234567890"));
        assertTrue(other.command().contains("opaque-token-value-1234567890"));
        assertFalse(other.command().stream().anyMatch(a -> a.startsWith("-Xm")), "0 MB leaves memory to the JVM");
        assertTrue(other.command().contains("-Dminecraft.launcher.brand=shardlight-client"), "default brand");
    }

    @Test
    void featuresResolutionDemoAndFullscreen() throws Exception {
        LaunchCommand lc = LaunchCommandBuilder.build(with(ctx(modern(), LINUX), "", "--extra \"two words\"", 1920, 1080,
            true, null, null, true));
        List<String> cmd = lc.command();
        assertEquals("1920", after(cmd, "--width"));
        assertEquals("1080", after(cmd, "--height"));
        assertTrue(cmd.contains("--demo"));
        assertTrue(cmd.contains("--fullscreen"));
        assertEquals(List.of("--extra", "two words"), cmd.subList(cmd.size() - 2, cmd.size()), "extra game arguments last");
        String cp = after(cmd, "-cp");
        assertEquals(3, unixClasspathEntries(cp), "':' separates the classpath on Linux");

        // only a width: no custom resolution feature
        List<String> half = LaunchCommandBuilder.build(with(ctx(modern(), LINUX), "", "", 1920, 0, false, null, null, false))
            .command();
        assertFalse(half.contains("--width"));
    }

    @Test
    void macAndThirtyTwoBitRules() throws Exception {
        List<String> mac = LaunchCommandBuilder.build(ctx(modern(), MAC)).command();
        assertEquals("-XstartOnFirstThread", mac.get(1));
        assertFalse(mac.stream().anyMatch(a -> a.startsWith("-XX:HeapDumpPath")));
        List<String> win32 = LaunchCommandBuilder.build(ctx(modern(), WINDOWS_32)).command();
        assertTrue(win32.contains("-Xss1M"));
        assertTrue(win32.stream().anyMatch(a -> a.startsWith("-XX:HeapDumpPath")));
    }

    @Test
    void quickPlaySingleplayerAndMultiplayer() throws Exception {
        InstalledVersion iv = modern();
        List<String> sp = LaunchCommandBuilder.build(with(ctx(iv, WINDOWS), "", "", 0, 0, false, " My World ", null, false))
            .command();
        assertEquals(gameDir.toAbsolutePath().normalize().resolve(LaunchCommandBuilder.QUICK_PLAY_LOG).toString(),
            after(sp, "--quickPlayPath"));
        assertEquals("My World", after(sp, "--quickPlaySingleplayer"));
        assertFalse(sp.contains("--quickPlayMultiplayer"));
        assertFalse(sp.contains("--quickPlayRealms"), "an empty realms placeholder drops the pair");
        assertFalse(sp.contains("--server"));

        List<String> mp = LaunchCommandBuilder.build(with(ctx(iv, WINDOWS), "", "", 0, 0, false, null, "play.example.net:25570",
            false)).command();
        assertEquals("play.example.net:25570", after(mp, "--quickPlayMultiplayer"));
        assertFalse(mp.contains("--quickPlaySingleplayer"));
        assertFalse(mp.contains("--server"), "modern versions use quick play, not --server");

        List<String> both = LaunchCommandBuilder.build(with(ctx(iv, WINDOWS), "", "", 0, 0, false, "World", "host", false))
            .command();
        assertTrue(both.contains("--quickPlaySingleplayer"));
        assertFalse(both.contains("--quickPlayMultiplayer"), "a world wins over a server");

        List<String> blank = LaunchCommandBuilder.build(with(ctx(iv, WINDOWS), "", "", 0, 0, false, "  ", "", false)).command();
        assertTrue(blank.stream().noneMatch(a -> a.startsWith("--quickPlay")));
    }

    @Test
    void placeholderValuesAreNeverExpandedAgain() throws Exception {
        // a world name that looks like a placeholder must reach the game literally, and never as the token
        List<String> cmd = LaunchCommandBuilder.build(with(ctx(modern(), WINDOWS), "-Dx=${auth_access_token}", "${classpath}",
            0, 0, false, "${auth_access_token}", null, false)).command();
        assertEquals("${auth_access_token}", after(cmd, "--quickPlaySingleplayer"));
        assertTrue(cmd.contains("-Dx=${auth_access_token}"), "extra arguments are passed literally");
        assertEquals("${classpath}", cmd.get(cmd.size() - 1));
        assertEquals(1, cmd.stream().filter(a -> a.contains(TOKEN)).count());
    }

    @Test
    void legacyVersionWithMinecraftArguments() throws Exception {
        InstalledVersion iv = installed("1.8.9", 8, false, false, null, "1.8.9.json");
        List<String> win = LaunchCommandBuilder.build(with(ctx(iv, WINDOWS), "", "", 1280, 720, true, "World", "mc.example.org",
            false)).command();
        String nat = natives.toAbsolutePath().normalize().toString();
        assertEquals(List.of("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
            "-Djava.library.path=" + nat, "-Dminecraft.launcher.brand=shardlight-client", "-Dminecraft.launcher.version=0.1.0",
            "-cp"), win.subList(1, 6), "the standard JVM arguments for versions without arguments.jvm");
        assertEquals(3, win.get(6).split(";").length);
        assertEquals("-Xms512M", win.get(7));
        assertEquals("net.minecraft.client.main.Main", win.get(9));
        List<String> game = gameArgs(win, "net.minecraft.client.main.Main");
        assertEquals(List.of("--username", "Steve_42", "--version", "1.8.9", "--gameDir",
            gameDir.toAbsolutePath().normalize().toString(), "--assetsDir", game.get(7), "--assetIndex", "1.8", "--uuid",
            SESSION.uuid(), "--accessToken", TOKEN, "--userProperties", "{}", "--userType", "msa",
            "--width", "1280", "--height", "720", "--server", "mc.example.org", "--port", "25565", "--fullscreen"), game);
        assertTrue(win.stream().noneMatch(a -> a.startsWith("--quickPlay")), "no quick play before 1.20");

        List<String> mac = LaunchCommandBuilder.build(ctx(iv, MAC)).command();
        assertFalse(mac.contains("-XstartOnFirstThread"), "LWJGL 2 must not run on the first thread");
        assertEquals(3, unixClasspathEntries(after(mac, "-cp")));
        assertTrue(LaunchCommandBuilder.build(with(ctx(iv, LINUX), "", "", 0, 0, false, null, null, true)).command()
            .contains("--demo"));
    }

    @Test
    void veryOldVersionsUseTheSessionAndVirtualAssets() throws Exception {
        MinecraftFixtures.writeVersion(versions, "1.5.2", """
            {"id":"1.5.2","type":"release","mainClass":"net.minecraft.launchwrapper.Launch","assets":"pre-1.6",
             "minecraftArguments":"${auth_player_name} ${auth_session} --gameDir ${game_directory} --assetsDir ${game_assets}",
             "libraries":[]}""");
        MinecraftFixtures.writeVersion(versions, "1.6.4", """
            {"id":"1.6.4","type":"release","mainClass":"net.minecraft.client.main.Main","assets":"legacy",
             "minecraftArguments":"--username ${auth_player_name} --session ${auth_session} --version ${version_name} --gameDir ${game_directory} --assetsDir ${game_assets}",
             "libraries":[]}""");
        ResolvedVersion pre = new VersionResolver(versions).resolve("1.5.2");
        InstalledVersion iv = new InstalledVersion("1.5.2", pre, versions.resolve("1.5.2/1.5.2.jar"),
            List.of(versions.resolve("1.5.2/1.5.2.jar")), List.of(), List.of("META-INF/"), "pre-1.6", game.resolve("assets"),
            false, true, 8, null, null);
        List<String> cmd = LaunchCommandBuilder.build(ctx(iv, LINUX)).command();
        List<String> args = gameArgs(cmd, "net.minecraft.launchwrapper.Launch");
        assertEquals("Steve_42", args.get(0));
        assertEquals("token:" + TOKEN + ":" + SESSION.uuid(), args.get(1));
        assertEquals(gameDir.toAbsolutePath().normalize().resolve("resources").toString(), after(cmd, "--assetsDir"),
            "map_to_resources versions read assets from the game folder");
        assertFalse(LaunchCommandBuilder.build(ctx(iv, LINUX)).redacted().contains(TOKEN));

        ResolvedVersion legacy = new VersionResolver(versions).resolve("1.6.4");
        InstalledVersion lv = new InstalledVersion("1.6.4", legacy, versions.resolve("1.6.4/1.6.4.jar"),
            List.of(versions.resolve("1.6.4/1.6.4.jar")), List.of(), List.of("META-INF/"), "legacy", game.resolve("assets"),
            true, false, 8, null, null);
        List<String> lcmd = LaunchCommandBuilder.build(ctx(lv, WINDOWS)).command();
        assertEquals(game.resolve("assets/virtual/legacy").toAbsolutePath().normalize().toString(), after(lcmd, "--assetsDir"));
        String shown = LaunchCommandBuilder.build(ctx(lv, WINDOWS)).redacted();
        assertTrue(shown.contains("--session " + Redactor.MASK), shown);
    }

    @Test
    void fabricProfileInheritingFromVanilla() throws Exception {
        String id = "fabric-loader-0.16.10-1.21.4";
        InstalledVersion iv = installed(id, 21, false, false, null, "1.21.4.json", id + ".json");
        List<String> cmd = LaunchCommandBuilder.build(ctx(iv, WINDOWS)).command();
        int main = cmd.indexOf("net.fabricmc.loader.impl.launch.knot.KnotClient");
        assertTrue(main > 0);
        assertFalse(cmd.contains("net.minecraft.client.main.Main"));
        int emu = cmd.indexOf("-DFabricMcEmu= net.minecraft.client.main.Main ");
        assertTrue(emu > cmd.indexOf("-cp") && emu < main, "the child's JVM arguments follow the parent's: " + cmd);
        assertEquals(id, after(cmd, "--version"));
        assertEquals("19", after(cmd, "--assetIndex"));
        assertEquals("release", after(cmd, "--versionType"));
        assertTrue(after(cmd, "-cp").endsWith(id + ".jar"));
        assertTrue(cmd.contains("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"));
    }

    @Test
    void fabricOnLegacyParentGetsTheStandardJvmArguments() throws Exception {
        MinecraftFixtures.writeVersion(versions, "1.8.9", MinecraftFixtures.template("1.8.9.json"));
        MinecraftFixtures.writeVersion(versions, "fabric-loader-0.16.10-1.8.9", """
            {"id":"fabric-loader-0.16.10-1.8.9","inheritsFrom":"1.8.9","type":"release",
             "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
             "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},"libraries":[]}""");
        ResolvedVersion v = new VersionResolver(versions).resolve("fabric-loader-0.16.10-1.8.9");
        InstalledVersion iv = new InstalledVersion(v.id(), v, versions.resolve("x.jar"), List.of(versions.resolve("x.jar")),
            List.of(), List.of(), v.assetIndexId(), game.resolve("assets"), false, false, 8, "jre-legacy", null);
        List<String> cmd = LaunchCommandBuilder.build(ctx(iv, WINDOWS)).command();
        assertTrue(cmd.contains("-cp"), "the inherited legacy version still needs a classpath: " + cmd);
        assertTrue(cmd.contains("-DFabricMcEmu= net.minecraft.client.main.Main "));
        assertEquals("1.8", after(cmd, "--assetIndex"));
        assertEquals("fabric-loader-0.16.10-1.8.9", after(cmd, "--version"));
    }

    @Test
    void newerVersionsNeedNewerJava() throws Exception {
        InstalledVersion iv = installed("26.1", 25, false, false, null, "26.1.json");
        LauncherException e = assertThrows(LauncherException.class, () -> LaunchCommandBuilder.build(ctx(iv, WINDOWS)));
        assertEquals(ErrorCode.JAVA_NOT_FOUND, e.code());
        assertEquals("install.launch.javaTooOld", e.messageKey());
        LaunchContext c = ctx(iv, WINDOWS);
        LaunchCommand ok = LaunchCommandBuilder.build(new LaunchContext(iv, java(WINDOWS, 25), c.gameDir(), c.nativesDir(),
            SESSION, 1024, 8192, "", "", 0, 0, false, null, null, false, "shardlight-client", "0.1.0", WINDOWS));
        assertTrue(ok.command().contains("-Xmx8192M"));
        assertEquals("26.1", after(ok.command(), "--version"));
    }

    @Test
    void missingInputsAreClearErrors() throws Exception {
        InstalledVersion iv = modern();
        LaunchContext c = ctx(iv, WINDOWS);
        LauncherException noSession = assertThrows(LauncherException.class, () -> LaunchCommandBuilder.build(new LaunchContext(
            iv, c.java(), gameDir, natives, null, 0, 0, "", "", 0, 0, false, null, null, false, "x", "1", WINDOWS)));
        assertEquals(ErrorCode.AUTH_FAILED, noSession.code());
        MinecraftSession blankToken = new MinecraftSession("Steve", "uuid", " ", "", "msa", "");
        assertEquals(ErrorCode.AUTH_FAILED, assertThrows(LauncherException.class, () -> LaunchCommandBuilder.build(
            new LaunchContext(iv, c.java(), gameDir, natives, blankToken, 0, 0, "", "", 0, 0, false, null, null, false, "x", "1",
                WINDOWS))).code());
        assertEquals("install.launch.noJava", assertThrows(LauncherException.class, () -> LaunchCommandBuilder.build(
            new LaunchContext(iv, null, gameDir, natives, SESSION, 0, 0, "", "", 0, 0, false, null, null, false, "x", "1",
                WINDOWS))).messageKey());
        assertEquals("install.launch.notInstalled", assertThrows(LauncherException.class, () -> LaunchCommandBuilder.build(
            new LaunchContext(null, c.java(), gameDir, natives, SESSION, 0, 0, "", "", 0, 0, false, null, null, false, "x", "1",
                WINDOWS))).messageKey());
    }

    @Test
    void memoryBoundsAreSane() throws Exception {
        LaunchContext c = ctx(modern(), LINUX);
        List<String> cmd = LaunchCommandBuilder.build(new LaunchContext(c.installed(), c.java(), gameDir, natives, SESSION,
            8192, 2048, "", "", 0, 0, false, null, null, false, "x", "1", LINUX)).command();
        assertTrue(cmd.contains("-Xms2048M"), "a minimum above the maximum is clamped");
        assertTrue(cmd.contains("-Xmx2048M"));
    }

    @Test
    void splitArgsRespectsQuotes() {
        assertEquals(List.of(), LaunchCommandBuilder.splitArgs(null));
        assertEquals(List.of(), LaunchCommandBuilder.splitArgs("   "));
        assertEquals(List.of("-Xss2M", "-Dname=a b", "-Dpath=C:\\Program Files\\Java", "", "x"),
            LaunchCommandBuilder.splitArgs("  -Xss2M -Dname=\"a b\"\t\"-Dpath=C:\\Program Files\\Java\" \"\" x "));
        assertEquals(List.of("unterminated quote"), LaunchCommandBuilder.splitArgs("\"unterminated quote"));
    }

    @Test
    void substituteAndHostParsing() {
        java.util.Map<String, String> values = java.util.Map.of("a", "1", "b", "${a}");
        assertEquals("x1y${a}z${unknown}", LaunchCommandBuilder.substitute("x${a}y${b}z${unknown}", values));
        assertEquals("${", LaunchCommandBuilder.substitute("${", values));
        assertArrayEquals(new String[] {"play.example.net", "25565"}, LaunchCommandBuilder.hostAndPort("play.example.net"));
        assertArrayEquals(new String[] {"host", "25570"}, LaunchCommandBuilder.hostAndPort(" host:25570 "));
        assertArrayEquals(new String[] {"::1", "25566"}, LaunchCommandBuilder.hostAndPort("[::1]:25566"));
        assertArrayEquals(new String[] {"host", "25565"}, LaunchCommandBuilder.hostAndPort("host:notaport"));
        assertArrayEquals(new String[] {"host", "25565"}, LaunchCommandBuilder.hostAndPort("host:70000"));
        assertArrayEquals(new String[] {"2001:db8::1", "25565"}, LaunchCommandBuilder.hostAndPort("2001:db8::1"));
    }

    @Test
    void launchCommandIsImmutableAndQuotesForDisplay() {
        List<String> args = new ArrayList<>(List.of("java", "-Dx=a b", "", "--accessToken", "secret-value-123456"));
        LaunchCommand lc = new LaunchCommand(args, Path.of("."));
        args.add("changed");
        assertEquals(5, lc.command().size());
        assertThrows(UnsupportedOperationException.class, () -> lc.command().add("x"));
        assertEquals("java \"-Dx=a b\" \"\" --accessToken " + Redactor.MASK, lc.redacted());
    }

    /**
     * Entries in a ':'-separated classpath. When the tests run on Windows the paths themselves start with a
     * drive letter ("D:\..."), so that colon is not counted as a separator.
     */
    private static int unixClasspathEntries(String cp) {
        return cp.replaceAll("(^|:)[A-Za-z]:\\\\", "$1").split(":").length;
    }
}
