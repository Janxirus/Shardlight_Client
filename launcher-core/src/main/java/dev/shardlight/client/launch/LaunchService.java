package dev.shardlight.client.launch;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.diagnostics.CrashAnalyzer;
import dev.shardlight.client.diagnostics.Finding;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.java.JavaInstall;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.loader.LoaderService;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.InstalledVersion;
import dev.shardlight.client.minecraft.LaunchPreparation;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.OsInfo;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts the game for a project: installs whatever is missing (loader, game files, Java), unpacks native
 * libraries, builds the command and runs it, then watches the game until it ends and analyses a crash.
 * One game per project at a time; different projects can run side by side.
 *
 * <p>A small {@code .game.pid} file in the project folder remembers a running game, so a launcher that was
 * restarted while the game was still open doesn't start a second copy in the same folder.
 */
public final class LaunchService {
    private static final Logger LOG = Logger.getLogger(LaunchService.class.getName());
    public static final String STAGE_PREPARE = "launch.stage.prepare";
    /** the launcher's redacted copy of the game output, in the game's {@code logs} folder */
    public static final String LOG_FILE = "shardlight-launch.log";
    static final String PID_FILE = ".game.pid";
    /** crash reports older than the launch (minus this much clock slack) belong to an earlier session */
    private static final long CLOCK_SLACK_MILLIS = 2000;

    private final ProjectAccess projects;
    private final LoaderService loaders;
    private final GameInstaller installer;
    private final JavaRuntimeService java;
    private final BooleanSupplier preferMojang;
    private final CrashAnalyzer analyzer;
    private final OsInfo os;
    private final Map<String, GameProcess> running = new ConcurrentHashMap<>();

    public LaunchService(AppPaths paths, ProjectAccess projects, LoaderService loaders, GameInstaller installer,
                         JavaRuntimeService java) {
        this(paths, projects, loaders, installer, java, () -> true, new CrashAnalyzer(), OsInfo.current());
    }

    /**
     * @param preferMojang whether to use Mojang's Java runtime for each version when the project doesn't pick
     *                     a Java (the launcher setting)
     */
    public LaunchService(AppPaths paths, ProjectAccess projects, LoaderService loaders, GameInstaller installer,
                         JavaRuntimeService java, BooleanSupplier preferMojang, CrashAnalyzer analyzer, OsInfo os) {
        this.projects = projects;
        this.loaders = loaders;
        this.installer = installer;
        this.java = java;
        this.preferMojang = preferMojang;
        this.analyzer = analyzer;
        this.os = os;
        LOG.fine("Launch service for " + paths.root());
    }

    /** the game this launcher started for {@code projectId}, while it runs */
    public Optional<GameProcess> running(String projectId) {
        GameProcess g = projectId == null ? null : running.get(projectId);
        return g != null && g.isAlive() ? Optional.of(g) : Optional.empty();
    }

    /** every game this launcher started that is still running */
    public List<GameProcess> runningAll() {
        List<GameProcess> all = new ArrayList<>();
        for (GameProcess g : running.values()) {
            if (g.isAlive()) {
                all.add(g);
            }
        }
        return all;
    }

    /** true when the project's game runs, started by this launcher or by an earlier run of it */
    public boolean isRunning(String projectId) {
        return running(projectId).isPresent() || runningElsewhere(projectId);
    }

    /**
     * Get everything ready to start {@code project}: the mod loader, game files, Java runtime and native
     * libraries, then the command. Long-running (downloads); call off the UI thread.
     *
     * @throws LauncherException {@link ErrorCode#ALREADY_RUNNING}, {@link ErrorCode#NOT_FOUND} (project or world
     *                           gone), and every install, Java and download error
     */
    public LaunchPlan prepare(Project project, MinecraftSession session, LaunchOptions options, ProgressListener progress,
                              CancellationToken cancel) throws LauncherException {
        if (session == null) {
            throw new LauncherException(ErrorCode.AUTH_FAILED, "install.launch.noSession");
        }
        return prepare(project, c -> session, options, progress, cancel);
    }

    /** where {@link #prepare} gets the player's session from, asked once everything is installed */
    @FunctionalInterface
    public interface SessionSource {
        MinecraftSession get(CancellationToken cancel) throws LauncherException;
    }

    /**
     * Like {@link #prepare(Project, MinecraftSession, LaunchOptions, ProgressListener, CancellationToken)}, but the
     * session is asked for only after the downloads, right before the command is built. A first start can spend
     * a long time downloading, and the game must not start with a token that ran out meanwhile.
     */
    public LaunchPlan prepare(Project project, SessionSource sessions, LaunchOptions options, ProgressListener progress,
                              CancellationToken cancel) throws LauncherException {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        LaunchOptions o = options == null ? LaunchOptions.NONE : options;
        if (sessions == null) {
            throw new LauncherException(ErrorCode.AUTH_FAILED, "install.launch.noSession");
        }
        Project current = projects.get(project.id).orElseThrow(() ->
            new LauncherException(ErrorCode.NOT_FOUND, "launch.error.projectGone", project.name));
        if (isRunning(current.id)) {
            throw new LauncherException(ErrorCode.ALREADY_RUNNING, "launch.error.alreadyRunning", current.name);
        }
        if (current.minecraftVersion == null || current.minecraftVersion.isBlank()) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "launch.error.noVersion", current.name);
        }
        Path gameDir = projects.gameDir(current);
        if (current.gameDirLocation != null && !Files.isDirectory(gameDir)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.location.missing", current.name, gameDir.toString());
        }
        if (o.quickPlayWorld() != null) {
            if (!PathSafety.isSafeSegment(o.quickPlayWorld())
                || !Files.isDirectory(gameDir.resolve(GameDirs.SAVES).resolve(o.quickPlayWorld()))) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "launch.error.worldGone", o.quickPlayWorld());
            }
        }

        String versionId = loaders.ensureInstalled(current.minecraftVersion, current.loader, p, c);
        InstalledVersion installed = installer.install(versionId, p, c);
        c.check();
        JavaInstall runtime = java.select(installed.javaMajorVersion(), installed.javaComponent(), current.java.javaPath,
            preferMojang.getAsBoolean(), p, c);
        c.check();

        p.stage(STAGE_PREPARE);
        Path nativesDir = projects.projectDir(current.id).resolve("natives").resolve(versionId);
        try {
            GameDirs.create(gameDir);
        } catch (IOException e) {
            throw LauncherException.io(e, gameDir.toString());
        }
        LaunchPreparation.prepare(installed, gameDir, nativesDir, c);

        MinecraftSession session = sessions.get(c);
        if (session == null) {
            throw new LauncherException(ErrorCode.AUTH_FAILED, "install.launch.noSession");
        }
        c.check();
        Redactor.register(session.accessToken());
        LaunchContext ctx = new LaunchContext(installed, runtime, gameDir, nativesDir, session, current.java.minMemoryMb,
            current.java.maxMemoryMb, current.java.extraJvmArgs, current.java.extraGameArgs, current.window.width,
            current.window.height, current.window.fullscreen, o.quickPlayWorld(), o.quickPlayServer(), o.demo(),
            BuildInfo.LAUNCHER_BRAND, BuildInfo.VERSION, os);
        LaunchCommand command = LaunchCommandBuilder.build(ctx);

        if (!versionId.equals(current.installedVersionId)) {
            current.installedVersionId = versionId;
            current = projects.save(current);
        }
        return new LaunchPlan(current, versionId, installed, runtime, gameDir, command);
    }

    /**
     * Start the game. Returns as soon as the process runs; {@link GameProcess#onExit()} reports how it ended.
     *
     * @throws LauncherException {@link ErrorCode#ALREADY_RUNNING}, {@link ErrorCode#LAUNCH_FAILED}
     */
    public GameProcess start(LaunchPlan plan) throws LauncherException {
        String id = plan.project().id;
        synchronized (running) {
            if (isRunning(id)) {
                throw new LauncherException(ErrorCode.ALREADY_RUNNING, "launch.error.alreadyRunning", plan.project().name);
            }
            ProcessBuilder pb = new ProcessBuilder(plan.command().command())
                .directory(plan.command().workingDirectory().toFile())
                .redirectErrorStream(true);
            // a JAVA_TOOL_OPTIONS meant for something else can stop the game from starting; the project's own
            // Java arguments are the place for options
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            pb.environment().remove("_JAVA_OPTIONS");
            String redacted = plan.command().redacted();
            LOG.info("Starting " + plan.project().name + " (" + id + ", " + plan.versionId() + ") with " + plan.java()
                + ": " + redacted);
            Instant startedAt = Instant.now();
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new LauncherException(ErrorCode.LAUNCH_FAILED, e, "launch.error.startFailed",
                    plan.java().executable().toString(), String.valueOf(e.getMessage()));
            }
            writePidFile(id, process, startedAt);
            String header = BuildInfo.NAME + " " + BuildInfo.VERSION + " started " + plan.project().name + " ("
                + plan.versionId() + ") at " + startedAt + "\nJava: " + plan.java() + "\nCommand: " + redacted + "\n";
            Path logFile = plan.gameDir().resolve(GameDirs.LOGS).resolve(LOG_FILE);
            GameProcess game = GameProcess.watch(id, process, startedAt, logFile, header, g -> ended(plan, g, process));
            running.put(id, game);
            recordPlay(id, startedAt, Duration.ZERO);
            return game;
        }
    }

    private GameExit ended(LaunchPlan plan, GameProcess game, Process process) {
        String id = plan.project().id;
        running.remove(id, game);
        deletePidFile(id);
        int code = process.exitValue();
        Instant end = Instant.now();
        Duration playTime = Duration.between(game.startedAt(), end);
        Optional<Path> report = crashReport(plan.gameDir(), game.startedAt());
        boolean crashed = !game.stopRequested() && (code != 0 || report.isPresent());
        List<Finding> findings = List.of();
        if (crashed) {
            String reportText = null;
            if (report.isPresent()) {
                try {
                    reportText = CrashAnalyzer.readTail(report.get());
                } catch (IOException e) {
                    LOG.log(Level.FINE, "Couldn't read " + report.get(), e);
                }
            }
            findings = analyzer.analyze(code, reportText, game.recentLines());
        }
        LOG.info("The game of project " + id + " ended with exit code " + code + " after " + playTime.toSeconds() + " s"
            + (game.stopRequested() ? " (stopped by the player)" : crashed ? " (crashed)" : ""));
        recordPlay(id, end, playTime);
        return new GameExit(code, crashed, game.stopRequested(), crashed ? report : Optional.empty(), playTime, findings);
    }

    /** remember when the project was played and for how long */
    private void recordPlay(String id, Instant when, Duration played) {
        try {
            Optional<Project> p = projects.get(id);
            if (p.isPresent()) {
                Project copy = p.get();
                copy.lastPlayedAt = when.toEpochMilli();
                copy.totalPlayMillis += Math.max(0, played.toMillis());
                projects.save(copy);
            }
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't save the play time of project " + id, e);
        }
    }

    /** the newest crash report or Java fatal error log written since {@code since} */
    static Optional<Path> crashReport(Path gameDir, Instant since) {
        long after = since.toEpochMilli() - CLOCK_SLACK_MILLIS;
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        for (Path dir : List.of(gameDir.resolve(GameDirs.CRASH_REPORTS), gameDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path f : ds) {
                    String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    boolean report = dir.equals(gameDir) ? name.startsWith("hs_err_pid") && name.endsWith(".log")
                        : name.endsWith(".txt");
                    if (!report || !Files.isRegularFile(f)) {
                        continue;
                    }
                    long t = Files.getLastModifiedTime(f).toMillis();
                    if (t >= after && t > bestTime) {
                        best = f;
                        bestTime = t;
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Couldn't look for crash reports in " + dir, e);
            }
        }
        return Optional.ofNullable(best);
    }

    // ---------------------------------------------------------------- games started by an earlier launcher run

    private Path pidFile(String projectId) {
        return projects.projectDir(projectId).resolve(PID_FILE);
    }

    private void writePidFile(String projectId, Process process, Instant startedAt) {
        try {
            Files.writeString(pidFile(projectId), process.pid() + "\n" + startedAt.toEpochMilli() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Couldn't remember the game process of " + projectId, e);
        }
    }

    private void deletePidFile(String projectId) {
        try {
            Files.deleteIfExists(pidFile(projectId));
        } catch (IOException e) {
            LOG.log(Level.FINE, "Couldn't remove " + pidFile(projectId), e);
        }
    }

    /** a game for this project that a previous run of the launcher started and that is still open */
    boolean runningElsewhere(String projectId) {
        if (projectId == null || running.containsKey(projectId) && running.get(projectId).isAlive()) {
            return false;
        }
        Path file = pidFile(projectId);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).trim().split("\\R");
            long pid = Long.parseLong(lines[0].trim());
            long started = lines.length > 1 ? Long.parseLong(lines[1].trim()) : 0;
            Optional<ProcessHandle> h = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            boolean alive = h.isPresent() && looksLikeTheGame(h.get(), started);
            if (!alive) {
                Files.deleteIfExists(file);
            }
            return alive;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Ignoring the unreadable " + file, e);
            return false;
        }
    }

    /** the process id wasn't reused by another program since the game started */
    private static boolean looksLikeTheGame(ProcessHandle h, long startedMillis) {
        ProcessHandle.Info info = h.info();
        Optional<String> command = info.command();
        if (command.isPresent() && !command.get().toLowerCase(Locale.ROOT).contains("java")) {
            return false;
        }
        Optional<Instant> start = info.startInstant();
        return start.isEmpty() || startedMillis == 0 || Math.abs(start.get().toEpochMilli() - startedMillis) < 60_000;
    }
}
