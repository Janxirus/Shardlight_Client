package dev.shardlight.client.app.flows;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.diagnostics.Finding;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.GameExit;
import dev.shardlight.client.launch.GameProcess;
import dev.shardlight.client.launch.LaunchOptions;
import dev.shardlight.client.launch.LaunchPlan;
import dev.shardlight.client.project.Project;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pressing Play: make sure someone is signed in, get a fresh Minecraft session, install whatever is
 * missing, start the game, and report how it ended (with crash analysis when it didn't end well).
 */
public final class PlayFlow {
    private static final Logger LOG = Logger.getLogger(PlayFlow.class.getName());
    private final AppContext ctx;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

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

    public void play(Project project) {
        play(project, LaunchOptions.NONE);
    }

    /** start {@code project}; when it is already running, offer to stop it */
    public void play(Project project, LaunchOptions options) {
        if (isRunning(project.id)) {
            confirmStop(project);
            return;
        }
        Optional<Account> account = ctx.services().accounts().selected();
        if (account.isEmpty()) {
            SignInDialog.show(ctx, a -> play(project, options));
            return;
        }
        String accountId = account.get().id;
        ctx.selectProject(project.id);
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.play.starting", project.name), true, (progress, cancel) -> {
            progress.stage("ui.play.stage.session");
            var session = ctx.services().accounts().session(accountId, cancel);
            LaunchPlan plan = ctx.services().launches().prepare(project, session, options, progress, cancel);
            progress.stage("ui.play.stage.starting");
            cancel.check();
            return ctx.services().launches().start(plan);
        }, process -> started(project, process), err -> failed(project, options, err));
    }

    public void stop(String projectId) {
        ctx.services().launches().running(projectId).ifPresent(GameProcess::stop);
    }

    private void confirmStop(Project project) {
        ctx.overlay().confirm(I18n.tr("ui.play.stopTitle"), I18n.tr("ui.play.stopMessage", project.name), List.of(),
            I18n.tr("ui.play.stop"), true, () -> stop(project.id));
    }

    private void started(Project project, GameProcess process) {
        ctx.overlay().toast("success", I18n.tr("ui.play.started", project.name));
        fire();
        process.onExit().whenComplete((exit, err) -> Fx.ui(() -> {
            fire();
            ctx.projectsChanged();
            if (err != null) {
                LOG.log(Level.WARNING, "Couldn't read how the game ended", err);
                return;
            }
            showEnded(project, exit);
        }));
    }

    private void failed(Project project, LaunchOptions options, Throwable err) {
        if (err instanceof LauncherException le && le.code() == ErrorCode.AUTH_EXPIRED) {
            Button signIn = UiKit.primary(I18n.tr("ui.play.signInAgain"), Icons.USER, () -> { });
            Button close = new Button(I18n.tr("common.close"));
            Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.play.failedTitle"),
                UiKit.label(le.userMessage()), close, signIn), true, false);
            close.setOnAction(e -> d.close());
            signIn.setOnAction(e -> {
                d.close();
                SignInDialog.show(ctx, a -> play(project, options));
            });
            return;
        }
        Tasks.showError(ctx.overlay(), I18n.tr("ui.play.failedTitle"), err);
    }

    /** tell the player how a game ended: a short note, or for a crash what was found and what to do */
    public void showEnded(Project project, GameExit exit) {
        if (!exit.crashed()) {
            ctx.overlay().toast("info", I18n.tr("ui.play.ended", project.name,
                UiKit.duration(exit.playTime().toMillis())));
            return;
        }
        VBox body = new VBox(10);
        body.setPrefWidth(620);
        body.getChildren().add(UiKit.label(I18n.tr("ui.play.crashed", exit.exitCode())));
        for (Finding f : exit.findings()) {
            Object[] args = f.args() == null ? new Object[0] : f.args().toArray();
            String kind = switch (f.severity().name()) {
                case "ERROR" -> "danger";
                case "WARNING" -> "warning";
                default -> "info";
            };
            VBox text = new VBox(2, UiKit.label(I18n.tr(f.titleKey(), args), "h3"),
                UiKit.label(I18n.tr(f.detailKey(), args), "muted"));
            body.getChildren().add(UiKit.banner(kind, text));
        }
        if (exit.findings().isEmpty()) {
            body.getChildren().add(UiKit.label(I18n.tr("ui.play.noFindings"), "muted"));
        }
        body.getChildren().add(UiKit.label(I18n.tr("ui.play.crashHint"), "faint", "small"));
        Button close = new Button(I18n.tr("common.close"));
        Button report = UiKit.button(I18n.tr("ui.play.openCrashReport"), Icons.LOGS, () -> { });
        report.setDisable(exit.crashReport().isEmpty());
        Button export = UiKit.button(I18n.tr("ui.diagnostics.export"), Icons.ARCHIVE, () -> { });
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.play.crashedTitle", project.name), body,
            report, export, close), true, true);
        close.setOnAction(e -> d.close());
        report.setOnAction(e -> exit.crashReport().map(Path::getParent).ifPresent(ctx::openFolder));
        export.setOnAction(e -> {
            d.close();
            exportDiagnostics(ctx, project);
        });
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

    /** on start: look for a newer launcher when updates are set up for this build (never installs anything) */
    public void checkForUpdatesQuietly() {
        var updates = ctx.services().updates();
        var settings = ctx.services().settings().get().updates;
        if (!updates.isConfigured() || !settings.checkOnStart) {
            return;
        }
        Fx.run(() -> updates.check(settings.channel, new dev.shardlight.client.task.CancellationToken()), info -> info
            .ifPresent(u -> ctx.overlay().toast("info", I18n.tr("ui.update.available", u.version()))), err -> LOG.log(Level.FINE,
            "Update check failed", err));
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }
}
