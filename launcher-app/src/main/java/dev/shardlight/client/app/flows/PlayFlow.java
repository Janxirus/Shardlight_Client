package dev.shardlight.client.app.flows;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.TaskProgress;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.GameExit;
import dev.shardlight.client.launch.GameProcess;
import dev.shardlight.client.launch.LaunchOptions;
import dev.shardlight.client.launch.LaunchPlan;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import javafx.application.Platform;
import javafx.scene.control.Button;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pressing Play: make sure someone is signed in, get a fresh Minecraft session, install whatever is
 * missing, start the game, and report how it ended (with crash analysis when it didn't end well). Progress
 * shows on the Play bar rather than in a dialog: {@link #state(String)} says where each project is.
 */
public final class PlayFlow {
    private static final Logger LOG = Logger.getLogger(PlayFlow.class.getName());
    private final AppContext ctx;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, State> starting = new HashMap<>();
    private final Map<String, CancellationToken> cancels = new HashMap<>();
    private final Map<String, Failure> failures = new HashMap<>();

    /** where a project is on its way into the game */
    public enum Phase { IDLE, PREPARING, DOWNLOADING, LAUNCHING, RUNNING, ERROR }

    /**
     * A project's play state. {@code progress} is 0..1 while downloading (negative when unknown), {@code stage}
     * a localized line on what is happening, {@code startedAt} set while running, {@code error} set in ERROR.
     */
    public record State(Phase phase, double progress, String stage, Instant startedAt, String error) {
        static final State IDLE = new State(Phase.IDLE, -1, "", null, null);
    }

    private record Failure(Throwable error, LaunchOptions options) {
    }

    public PlayFlow(AppContext ctx) {
        this.ctx = ctx;
    }

    /** called on the FX thread whenever a game starts or stops */
    public void onStateChanged(Runnable r) {
        listeners.add(r);
    }

    public boolean isRunning(String projectId) {
        return ctx.services().launches().running(projectId).isPresent();
    }

    /** the play state of {@code projectId} (call on the FX thread) */
    public State state(String projectId) {
        if (projectId == null) {
            return State.IDLE;
        }
        Optional<GameProcess> running = ctx.services().launches().running(projectId);
        if (running.isPresent()) {
            return new State(Phase.RUNNING, 1, "", running.get().startedAt(), null);
        }
        State s = starting.get(projectId);
        if (s != null) {
            return s;
        }
        Failure f = failures.get(projectId);
        if (f != null) {
            return new State(Phase.ERROR, -1, "", null, Tasks.message(f.error()));
        }
        return State.IDLE;
    }

    /** the live game process of {@code projectId}, if it runs */
    public Optional<GameProcess> process(String projectId) {
        return ctx.services().launches().running(projectId);
    }

    public void play(Project project) {
        play(project, LaunchOptions.NONE);
    }

    /**
     * Start {@code project}; when it is already running, offer to stop it; while it is starting, cancel; after
     * an error, try again with the same options.
     */
    public void play(Project project, LaunchOptions options) {
        play(project, options, false);
    }

    /**
     * Start {@code project}; with {@code offline} the game starts single-player only, without contacting Microsoft,
     * for an account whose ownership was confirmed recently (see {@code AccountService#canPlayOffline}).
     */
    private void play(Project project, LaunchOptions options, boolean offline) {
        if (isRunning(project.id)) {
            confirmStop(project);
            return;
        }
        if (starting.containsKey(project.id)) {
            cancel(project.id);
            return;
        }
        Optional<Account> account = ctx.services().accounts().selected();
        if (account.isEmpty()) {
            SignInDialog.show(ctx, a -> play(project, options));
            return;
        }
        String accountId = account.get().id;
        ctx.selectProject(project.id);
        failures.remove(project.id);
        CancellationToken cancel = new CancellationToken();
        cancels.put(project.id, cancel);
        setState(project.id, new State(Phase.PREPARING, -1, I18n.tr("ui.task.starting"), null, null));
        AtomicReference<State> latest = new AtomicReference<>();
        AtomicBoolean scheduled = new AtomicBoolean();
        ProgressListener progress = (stageKey, detail, done, total) -> {
            latest.set(stateFor(stageKey, done, total));
            if (scheduled.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    scheduled.set(false);
                    if (starting.containsKey(project.id) && latest.get() != null) {
                        setState(project.id, latest.get());
                    }
                });
            }
        };
        Fx.run(() -> {
            var accounts = ctx.services().accounts();
            LaunchPlan plan;
            if (offline) {
                plan = ctx.services().launches().prepare(project, c -> accounts.offlineSession(accountId), options, progress, cancel);
            } else {
                // check the account first so a dead sign-in shows up before any download, then read the session
                // again once everything is installed: a long first download must not leave the game an old token
                progress.stage("ui.play.stage.session");
                accounts.session(accountId, cancel);
                plan = ctx.services().launches().prepare(project, c -> {
                    progress.stage("ui.play.stage.session");
                    return accounts.session(accountId, c);
                }, options, progress, cancel);
            }
            progress.stage("ui.play.stage.starting");
            cancel.check();
            return ctx.services().launches().start(plan);
        }, process -> {
            clearStarting(project.id);
            started(project, process);
        }, err -> {
            clearStarting(project.id);
            if (Tasks.isCancelled(err)) {
                ctx.overlay().toast("info", I18n.tr("ui.play.cancelled", project.name));
                fire();
            } else {
                failed(project, options, accountId, offline, Tasks.unwrap(err));
            }
        });
    }

    /** stop getting {@code projectId} ready (it keeps what was already downloaded) */
    public void cancel(String projectId) {
        CancellationToken c = cancels.get(projectId);
        if (c != null) {
            c.cancel();
            State s = starting.get(projectId);
            if (s != null) {
                setState(projectId, new State(s.phase(), s.progress(), I18n.tr("ui.task.cancelling"), null, null));
            }
        }
    }

    /** show what went wrong the last time {@code project} failed to start */
    public void showError(Project project) {
        Failure f = failures.get(project.id);
        if (f != null) {
            Tasks.showError(ctx.overlay(), I18n.tr("ui.play.failedTitle"), f.error());
        }
    }

    /** forget a failed start so the Play button goes back to normal */
    public void dismissError(String projectId) {
        if (failures.remove(projectId) != null) {
            fire();
        }
    }

    public void stop(String projectId) {
        ctx.services().launches().running(projectId).ifPresent(GameProcess::stop);
    }

    private static State stateFor(String stageKey, long done, long total) {
        String key = stageKey == null ? "" : stageKey;
        String text = key.isEmpty() ? "" : I18n.tr(key);
        double f = ProgressListener.fraction(done, total);
        if (key.equals("ui.play.stage.starting") || key.equals(dev.shardlight.client.launch.LaunchService.STAGE_PREPARE)) {
            return new State(Phase.LAUNCHING, -1, text, null, null);
        }
        if (total > 0 && (key.contains("download") || key.contains("java") || key.contains("install"))) {
            return new State(Phase.DOWNLOADING, f, text, null, null);
        }
        return new State(Phase.PREPARING, -1, text, null, null);
    }

    private void setState(String projectId, State s) {
        starting.put(projectId, s);
        TaskProgress.set("play-" + projectId, s.phase() == Phase.DOWNLOADING ? s.progress() : -1);
        fire();
    }

    private void clearStarting(String projectId) {
        starting.remove(projectId);
        cancels.remove(projectId);
        TaskProgress.clear("play-" + projectId);
    }

    private void confirmStop(Project project) {
        ctx.overlay().confirm(I18n.tr("ui.play.stopTitle"), I18n.tr("ui.play.stopMessage", project.name), List.of(),
            I18n.tr("ui.play.stop"), true, () -> stop(project.id));
    }

    private void started(Project project, GameProcess process) {
        ctx.overlay().toast("success", I18n.tr("ui.play.started", project.name));
        fire();
        updateDiscord();
        process.onExit().whenComplete((exit, err) -> Fx.ui(() -> {
            updateDiscord();
            fire();
            ctx.projectsChanged();
            if (err != null) {
                LOG.log(Level.WARNING, "Couldn't read how the game ended", err);
                return;
            }
            showEnded(project, exit);
        }));
    }

    /**
     * Discord Rich Presence follows the games that run: the one that started last is shown, and when none is left the
     * status is cleared. Does nothing unless the player switched it on in Settings.
     */
    private void updateDiscord() {
        var discord = ctx.services().discord();
        var newest = ctx.services().launches().runningAll().stream()
            .max(java.util.Comparator.comparing(GameProcess::startedAt));
        if (newest.isEmpty()) {
            discord.clear();
            return;
        }
        GameProcess g = newest.get();
        Project p = ctx.services().projects().get(g.projectId()).orElse(null);
        discord.show(dev.shardlight.client.discord.DiscordPresence.activityFor(p == null ? null : p.name,
            p == null ? null : p.minecraftVersion, g.startedAt()));
    }

    private void failed(Project project, LaunchOptions options, String accountId, boolean offline, Throwable err) {
        if (!offline && err instanceof LauncherException le && le.code() == ErrorCode.NETWORK
            && options.quickPlayServer() == null && ctx.services().accounts().canPlayOffline(accountId)) {
            // no internet, but the account is a confirmed owner: single-player still works
            fire();
            Button playOffline = UiKit.primary(I18n.tr("ui.play.offline.play"), Icons.PLAY, () -> { });
            Button close = new Button(I18n.tr("common.close"));
            close.setCancelButton(true);
            Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.play.offline.title"),
                UiKit.label(I18n.tr("ui.play.offline.message", project.name)), close, playOffline), true, false);
            close.setOnAction(e -> d.close());
            playOffline.setOnAction(e -> {
                d.close();
                play(project, options, true);
            });
            return;
        }
        if (err instanceof LauncherException le && le.code() == ErrorCode.AUTH_EXPIRED) {
            fire();
            Button signIn = UiKit.primary(I18n.tr("ui.play.signInAgain"), Icons.USER, () -> { });
            Button close = new Button(I18n.tr("common.close"));
            close.setCancelButton(true);
            Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.play.failedTitle"),
                UiKit.label(le.userMessage()), close, signIn), true, false);
            close.setOnAction(e -> d.close());
            signIn.setOnAction(e -> {
                d.close();
                SignInDialog.show(ctx, a -> play(project, options));
            });
            return;
        }
        failures.put(project.id, new Failure(err, options));
        fire();
        ctx.overlay().toast("danger", I18n.tr("ui.play.failedToast", project.name, Tasks.message(err)),
            new Overlay.Action(I18n.tr("ui.state.retry"), () -> play(project, options, offline)),
            new Overlay.Action(I18n.tr("ui.play.details"), () -> showError(project)));
    }

    /** tell the player how a game ended: a short note, or for a crash what was found and what to do */
    public void showEnded(Project project, GameExit exit) {
        if (!exit.crashed()) {
            ctx.overlay().toast("info", I18n.tr("ui.play.ended", project.name,
                UiKit.duration(exit.playTime().toMillis())));
            return;
        }
        CrashScreen.show(ctx, project, exit);
    }

    /** write a privacy-checked diagnostics zip to the launcher's logs folder and show it */
    public static void exportDiagnostics(AppContext ctx, Project project) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
        Path out = ctx.services().paths().logs().resolve("diagnostics-" + (project == null ? "launcher" : project.id) + "-"
            + stamp + ".zip");
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.diagnostics.exporting"), false, (p, c) -> {
            Path gameDir = project == null ? null : ctx.services().projects().gameDir(project);
            return ctx.services().diagnostics().export(project, gameDir, ctx.services().paths().logs(), out, false);
        }, zip -> {
            ctx.overlay().toast("success", I18n.tr("ui.diagnostics.exported", zip.getFileName()));
            ctx.openFolder(zip.getParent());
        });
    }

    private volatile String availableUpdate;

    /** the version of a newer launcher found by the start-up check, if any */
    public Optional<String> availableUpdate() {
        return Optional.ofNullable(availableUpdate);
    }

    /** on start: look for a newer launcher when updates are set up for this build (never installs anything) */
    public void checkForUpdatesQuietly() {
        var updates = ctx.services().updates();
        var settings = ctx.services().settings().get().updates;
        if (!updates.isConfigured() || !settings.checkOnStart) {
            return;
        }
        Fx.run(() -> updates.check(settings.channel, new dev.shardlight.client.task.CancellationToken()), info -> info
            .ifPresent(u -> {
                availableUpdate = u.version();
                ctx.shell().setBadge("settings", 1);
                ctx.overlay().toast("info", I18n.tr("ui.update.available", u.version()),
                    new Overlay.Action(I18n.tr("ui.toast.open"), () -> ctx.openSettings("updates")));
            }), err -> LOG.log(Level.FINE,
            "Update check failed", err));
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }
}
