package dev.shardlight.client.launch;

import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.InstalledVersion;
import dev.shardlight.client.minecraft.ResolvedVersion;
import dev.shardlight.client.minecraft.Rules;
import dev.shardlight.client.minecraft.model.Argument;
import dev.shardlight.client.minecraft.model.Library;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.util.OsInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Builds the command line that starts Minecraft, the way the official launcher does. A pure function of
 * its {@link LaunchContext} (apart from registering the access token with {@link Redactor}).
 *
 * <p>Order: Java executable; the version's JVM arguments (rules applied) or, for legacy versions, the
 * standard set; {@code -Xms}/{@code -Xmx}; the Log4j argument; the extra JVM arguments; the main class;
 * the game arguments ({@code arguments.game} with features, or the legacy {@code minecraftArguments});
 * legacy {@code --server/--port} when a server was requested from a version without quick play;
 * {@code --fullscreen}; the extra game arguments. Placeholders are filled in one pass, so a value can
 * never introduce another placeholder, and extra arguments are passed literally.
 */
public final class LaunchCommandBuilder {
    private static final Logger LOG = Logger.getLogger(LaunchCommandBuilder.class.getName());
    /** placeholders whose argument pair is dropped when they resolve to an empty value */
    private static final Set<String> QUICK_PLAY_PLACEHOLDERS = Set.of("${quickPlayPath}", "${quickPlaySingleplayer}",
        "${quickPlayMultiplayer}", "${quickPlayRealms}");
    /** the quick play log file name inside the game directory */
    public static final String QUICK_PLAY_LOG = "quickPlayLog.json";
    static final String HEAP_DUMP_PATH =
        "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump";

    private LaunchCommandBuilder() {
    }

    /**
     * Build the command.
     *
     * @throws LauncherException {@link ErrorCode#AUTH_FAILED} without a signed-in session,
     *                           {@link ErrorCode#JAVA_NOT_FOUND} when the Java is older than the version needs,
     *                           {@link ErrorCode#LAUNCH_FAILED} when the version isn't installed
     */
    public static LaunchCommand build(LaunchContext ctx) throws LauncherException {
        InstalledVersion inst = ctx.installed();
        if (inst == null || inst.version() == null || inst.version().mainClass() == null) {
            throw new LauncherException(ErrorCode.LAUNCH_FAILED, "install.launch.notInstalled");
        }
        if (ctx.java() == null || ctx.java().executable() == null) {
            throw new LauncherException(ErrorCode.LAUNCH_FAILED, "install.launch.noJava");
        }
        MinecraftSession session = ctx.session();
        if (session == null || isBlank(session.accessToken()) || isBlank(session.username()) || isBlank(session.uuid())) {
            throw new LauncherException(ErrorCode.AUTH_FAILED, "install.launch.noSession");
        }
        if (ctx.java().majorVersion() > 0 && ctx.java().majorVersion() < inst.javaMajorVersion()) {
            throw new LauncherException(ErrorCode.JAVA_NOT_FOUND, "install.launch.javaTooOld", inst.id(),
                inst.javaMajorVersion(), ctx.java().majorVersion());
        }
        if (ctx.gameDir() == null || ctx.nativesDir() == null) {
            throw LauncherException.internal("launch context without game or natives directory");
        }
        Redactor.register(session.accessToken());

        ResolvedVersion v = inst.version();
        OsInfo os = ctx.os() == null ? OsInfo.current() : ctx.os();
        Path gameDir = ctx.gameDir().toAbsolutePath().normalize();
        boolean customResolution = ctx.width() > 0 && ctx.height() > 0;
        boolean wantWorld = !isBlank(ctx.quickPlaySingleplayer());
        boolean wantServer = !isBlank(ctx.quickPlayMultiplayer());
        boolean quickPlaySupported = v.supportsQuickPlay();
        // with quick play a world wins over a server; without it only a server can be joined (--server/--port)
        boolean singleplayer = quickPlaySupported && wantWorld;
        boolean multiplayer = quickPlaySupported && !wantWorld && wantServer;
        boolean legacyServer = !quickPlaySupported && wantServer;
        boolean quickPlay = singleplayer || multiplayer;
        Map<String, Boolean> features = Rules.features(ctx.demo(), customResolution, quickPlay, singleplayer, multiplayer,
            false);
        Map<String, String> values = placeholders(ctx, inst, v, os, gameDir, customResolution, quickPlay, singleplayer,
            multiplayer);

        List<String> cmd = new ArrayList<>();
        cmd.add(ctx.java().executable().toAbsolutePath().toString());

        // JVM arguments
        List<String> jvm = new ArrayList<>();
        if (!mentions(v.jvmArguments(), "${classpath}")) {
            jvm.addAll(legacyJvmArguments(os, usesLwjgl3(v)));
        }
        for (Argument a : v.jvmArguments()) {
            if (Rules.allows(a.rules(), os, features)) {
                jvm.addAll(a.values());
            }
        }
        for (String a : jvm) {
            cmd.add(substitute(a, values));
        }
        int max = Math.max(0, ctx.maxMemoryMb());
        int min = Math.max(0, ctx.minMemoryMb());
        if (max > 0 && min > max) {
            min = max;
        }
        if (min > 0) {
            cmd.add("-Xms" + min + "M");
        }
        if (max > 0) {
            cmd.add("-Xmx" + max + "M");
        }
        if (!isBlank(inst.loggingArgument())) {
            cmd.add(inst.loggingArgument());
        }
        cmd.addAll(splitArgs(ctx.extraJvmArgs()));
        cmd.add(v.mainClass());

        // game arguments
        List<String> raw = new ArrayList<>();
        boolean legacy = v.minecraftArguments() != null;
        if (legacy) {
            raw.addAll(splitWhitespace(v.minecraftArguments()));
        }
        for (Argument a : v.gameArguments()) {
            if (Rules.allows(a.rules(), os, features)) {
                raw.addAll(a.values());
            }
        }
        List<String> game = new ArrayList<>();
        for (String a : raw) {
            String value = substitute(a, values);
            if (QUICK_PLAY_PLACEHOLDERS.contains(a) && value.isEmpty()) {
                if (!game.isEmpty() && game.get(game.size() - 1).startsWith("--")) {
                    game.remove(game.size() - 1);
                }
                continue;
            }
            game.add(value);
        }
        if (legacy && v.gameArguments().isEmpty() && v.minecraftArguments().contains("--username")) {
            if (customResolution && !v.minecraftArguments().contains("${resolution_width}")) {
                game.addAll(List.of("--width", values.get("resolution_width"), "--height", values.get("resolution_height")));
            }
            if (ctx.demo() && !game.contains("--demo")) {
                game.add("--demo");
            }
        }
        if (legacyServer) {
            String[] hp = hostAndPort(ctx.quickPlayMultiplayer());
            game.addAll(List.of("--server", hp[0], "--port", hp[1]));
        } else if (wantWorld && !quickPlaySupported) {
            LOG.info("Minecraft " + v.id() + " can't open a world directly; starting normally");
        }
        if (ctx.fullscreen() && !game.contains("--fullscreen")) {
            game.add("--fullscreen");
        }
        cmd.addAll(game);
        cmd.addAll(splitArgs(ctx.extraGameArgs()));
        return new LaunchCommand(cmd, gameDir);
    }

    private static Map<String, String> placeholders(LaunchContext ctx, InstalledVersion inst, ResolvedVersion v, OsInfo os,
                                                    Path gameDir, boolean customResolution, boolean quickPlay,
                                                    boolean singleplayer, boolean multiplayer) {
        MinecraftSession s = ctx.session();
        Path assetsRoot = inst.assetsRoot().toAbsolutePath().normalize();
        Path gameAssets = inst.virtualAssets() ? inst.virtualAssetsDir().toAbsolutePath().normalize()
            : inst.mapToResources() ? gameDir.resolve("resources") : assetsRoot;
        List<String> cp = new ArrayList<>();
        for (Path p : inst.classpath()) {
            cp.add(p.toAbsolutePath().normalize().toString());
        }
        Map<String, String> m = new HashMap<>();
        m.put("auth_player_name", s.username());
        m.put("version_name", inst.id());
        m.put("game_directory", gameDir.toString());
        m.put("assets_root", assetsRoot.toString());
        m.put("game_assets", gameAssets.toString());
        m.put("assets_index_name", inst.assetIndexId());
        m.put("auth_uuid", s.uuid());
        m.put("auth_access_token", s.accessToken());
        m.put("auth_session", "token:" + s.accessToken() + ":" + s.uuid());
        m.put("clientid", nullToEmpty(s.clientId()));
        m.put("auth_xuid", nullToEmpty(s.xuid()));
        m.put("user_type", isBlank(s.userType()) ? "msa" : s.userType());
        m.put("user_properties", "{}");
        m.put("version_type", isBlank(v.type()) ? "release" : v.type());
        m.put("resolution_width", String.valueOf(customResolution ? ctx.width() : 854));
        m.put("resolution_height", String.valueOf(customResolution ? ctx.height() : 480));
        m.put("natives_directory", ctx.nativesDir().toAbsolutePath().normalize().toString());
        m.put("launcher_name", isBlank(ctx.launcherName()) ? "shardlight-client" : ctx.launcherName());
        m.put("launcher_version", nullToEmpty(ctx.launcherVersion()));
        m.put("classpath", String.join(os.classpathSeparator(), cp));
        m.put("classpath_separator", os.classpathSeparator());
        m.put("library_directory", inst.librariesRoot().toAbsolutePath().normalize().toString());
        m.put("quickPlayPath", quickPlay ? gameDir.resolve(QUICK_PLAY_LOG).toString() : "");
        m.put("quickPlaySingleplayer", singleplayer ? ctx.quickPlaySingleplayer().trim() : "");
        m.put("quickPlayMultiplayer", multiplayer ? ctx.quickPlayMultiplayer().trim() : "");
        m.put("quickPlayRealms", "");
        return m;
    }

    /** the JVM arguments the official launcher adds for versions whose JSON has none */
    static List<String> legacyJvmArguments(OsInfo os, boolean lwjgl3) {
        List<String> out = new ArrayList<>();
        if (os.isWindows()) {
            out.add(HEAP_DUMP_PATH);
        }
        if (os.os() == OsInfo.Os.MACOS && lwjgl3) {
            // LWJGL 3 needs the first thread on macOS; LWJGL 2 (Minecraft before 1.13) breaks with it
            out.add("-XstartOnFirstThread");
        }
        if (os.arch() == OsInfo.Arch.X86) {
            out.add("-Xss1M");
        }
        out.add("-Djava.library.path=${natives_directory}");
        out.add("-Dminecraft.launcher.brand=${launcher_name}");
        out.add("-Dminecraft.launcher.version=${launcher_version}");
        out.add("-cp");
        out.add("${classpath}");
        return out;
    }

    /** replace every known {@code ${name}} in one pass; unknown placeholders are left as they are */
    static String substitute(String arg, Map<String, String> values) {
        if (arg.indexOf("${") < 0) {
            return arg;
        }
        StringBuilder out = new StringBuilder(arg.length() + 32);
        int i = 0;
        while (i < arg.length()) {
            int start = arg.indexOf("${", i);
            if (start < 0) {
                break;
            }
            int end = arg.indexOf('}', start + 2);
            if (end < 0) {
                break;
            }
            String value = values.get(arg.substring(start + 2, end));
            out.append(arg, i, start).append(value != null ? value : arg.substring(start, end + 1));
            i = end + 1;
        }
        out.append(arg, i, arg.length());
        return out.toString();
    }

    /**
     * Split user-typed arguments on whitespace; double quotes group text containing spaces and are removed
     * ({@code -Dname="a b"} gives {@code -Dname=a b}). Backslashes are kept as typed (Windows paths).
     */
    public static List<String> splitArgs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean token = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                token = true;
            } else if (!quoted && Character.isWhitespace(ch)) {
                if (token) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    token = false;
                }
            } else {
                cur.append(ch);
                token = true;
            }
        }
        if (token) {
            out.add(cur.toString());
        }
        return out;
    }

    private static List<String> splitWhitespace(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.trim().split("\\s+")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** {host, port} of {@code host}, {@code host:port} or {@code [ipv6]:port}; port 25565 by default */
    static String[] hostAndPort(String address) {
        String a = address.trim();
        String host = a;
        String port = "25565";
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 0) {
                host = a.substring(1, end);
                String rest = a.substring(end + 1);
                if (rest.startsWith(":")) {
                    port = rest.substring(1);
                }
            }
        } else {
            int colon = a.lastIndexOf(':');
            if (colon > 0 && a.indexOf(':') == colon) {
                host = a.substring(0, colon);
                port = a.substring(colon + 1);
            }
        }
        try {
            int p = Integer.parseInt(port.trim());
            port = p > 0 && p < 65536 ? String.valueOf(p) : "25565";
        } catch (NumberFormatException e) {
            port = "25565";
        }
        return new String[] {host, port};
    }

    private static boolean mentions(List<Argument> args, String token) {
        for (Argument a : args) {
            for (String s : a.values()) {
                if (s.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean usesLwjgl3(ResolvedVersion v) {
        for (Library l : v.libraries()) {
            if (l.name() != null && l.name().startsWith("org.lwjgl:lwjgl:3")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
