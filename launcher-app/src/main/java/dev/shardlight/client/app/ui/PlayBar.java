package dev.shardlight.client.app.ui;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.GameProcess;
import dev.shardlight.client.project.Project;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The bar along the bottom of every page: the selected project (click to switch, right-click for its actions),
 * its version, loader and preset, the live log, and the big Play button, which shows where the project is:
 * idle, preparing, downloading with a percentage, launching, running with the play time (click to stop), or
 * an error (click to retry).
 */
public final class PlayBar {
    private static final int MAX_LOG_LINES = 5000;
    private final AppContext ctx;
    private final HBox bar = new HBox();
    private final VBox root;
    private final StackPane art = new StackPane();
    private final Label name = new Label();
    private final Label meta = new Label();
    private final HBox project;
    private final Button log;
    private final Button play = new Button();
    private final Button details;
    private final ProgressBar progress = new ProgressBar(0);
    private final Timeline clock;

    public PlayBar(AppContext ctx) {
        this.ctx = ctx;
        name.getStyleClass().addAll("playbar-name");
        meta.getStyleClass().addAll("playbar-meta", "muted", "small");
        art.getStyleClass().add("playbar-art");
        VBox text = new VBox(name, meta);
        text.getStyleClass().add("playbar-text");
        Node chevron = Icons.of(Icons.CHEVRON_DOWN, 14);
        chevron.getStyleClass().add("faint");
        project = new HBox(art, text, chevron);
        project.getStyleClass().add("playbar-project");
        project.setAlignment(Pos.CENTER_LEFT);
        project.setFocusTraversable(true);
        project.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu().ifPresent(m -> m.show(project, e.getScreenX(), e.getScreenY()));
            } else {
                switcher();
            }
        });
        project.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER, SPACE -> switcher();
                case CONTEXT_MENU -> menu().ifPresent(m -> m.show(project, Side.TOP, 0, 0));
                default -> {
                    return;
                }
            }
            e.consume();
        });
        UiKit.tooltip(project, I18n.tr("ui.playbar.switchTip"));

        log = UiKit.button(I18n.tr("ui.playbar.log"), Icons.LOGS, this::openLog, "ghost");
        details = UiKit.button(I18n.tr("ui.play.details"), null, this::showError, "ghost", "small");
        play.getStyleClass().addAll("play-button", "playbar-play");
        play.setMnemonicParsing(false);
        play.setOnAction(e -> pressPlay());
        StackPane playWrap = UiKit.explained(play);

        bar.getStyleClass().add("playbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(project, Priority.SOMETIMES);
        bar.getChildren().addAll(project, UiKit.spacer(), details, log, playWrap);
        progress.getStyleClass().addAll("thin", "playbar-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.managedProperty().bind(progress.visibleProperty());
        root = new VBox(progress, bar);
        root.getStyleClass().add("playbar-root");

        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        clock.setCycleCount(Animation.INDEFINITE);

        ctx.play().onStateChanged(this::refresh);
        ctx.onProjectsChanged(this::refresh);
        ctx.onAccountsChanged(this::refresh);
        refresh();
    }

    public Node view() {
        return root;
    }

    private Optional<Project> project() {
        return ctx.selectedProject();
    }

    /** refresh everything from the selected project and its play state */
    public void refresh() {
        Optional<Project> sel = project();
        clock.stop();
        play.getStyleClass().removeAll("running", "busy", "error");
        UiKit.loading(play, false);
        progress.setVisible(false);
        details.setVisible(false);
        details.setManaged(false);
        if (sel.isEmpty()) {
            art.getChildren().setAll(UiKit.iconBox(Icons.of(Icons.CUBE, 18), ThemeManager.em(2.857)));
            name.setText(I18n.tr("ui.playbar.noProject"));
            meta.setText(I18n.tr("ui.playbar.noProjectHint"));
            setPlay(I18n.tr("ui.play.play"), Icons.PLAY);
            UiKit.disable(play, I18n.tr("ui.playbar.needProject"));
            UiKit.disable(log, I18n.tr("ui.playbar.needProject"));
            return;
        }
        Project p = sel.get();
        art.getChildren().setAll(ProjectIcons.tile(p.icon, ctx.services().projects().projectDir(p.id), ThemeManager.em(2.857)));
        name.setText(p.name);
        String details0 = String.join("  ·  ", p.minecraftVersion == null ? "" : p.minecraftVersion,
            ProjectText.loader(p), ProjectText.preset(p));
        meta.setText(details0);
        project.setAccessibleText(I18n.tr("ui.playbar.projectAccessible", p.name, details0));
        UiKit.disable(play, null);
        UiKit.disable(log, null);
        PlayFlow.State s = ctx.play().state(p.id);
        switch (s.phase()) {
            case IDLE -> {
                setPlay(I18n.tr("ui.play.play"), Icons.PLAY);
                play.setAccessibleText(I18n.tr("ui.play.playAccessible", p.name));
                UiKit.tooltip(play, I18n.tr("ui.playbar.playTip"));
            }
            case PREPARING, LAUNCHING -> {
                play.getStyleClass().add("busy");
                setPlay(I18n.tr(s.phase() == PlayFlow.Phase.LAUNCHING ? "ui.playbar.launching" : "ui.playbar.preparing"),
                    null);
                play.setGraphic(UiKit.dots());
                meta.setText(s.stage());
                play.setAccessibleText(play.getText());
                UiKit.tooltip(play, I18n.tr("ui.playbar.cancelTip"));
            }
            case DOWNLOADING -> {
                play.getStyleClass().add("busy");
                long pct = Math.round(Math.max(0, s.progress()) * 100);
                setPlay(I18n.tr("ui.playbar.downloading", pct), Icons.DOWNLOAD);
                meta.setText(s.stage());
                progress.setVisible(true);
                progress.setProgress(s.progress());
                play.setAccessibleText(play.getText());
                UiKit.tooltip(play, I18n.tr("ui.playbar.cancelTip"));
            }
            case RUNNING -> {
                play.getStyleClass().add("running");
                setPlay(I18n.tr("ui.playbar.stop", clock(s.startedAt())), Icons.STOP);
                play.setAccessibleText(I18n.tr("ui.play.stopAccessible", p.name));
                UiKit.tooltip(play, I18n.tr("ui.play.stopTip"));
                clock.play();
            }
            case ERROR -> {
                play.getStyleClass().add("error");
                setPlay(I18n.tr("ui.state.retry"), Icons.REFRESH);
                meta.setText(s.error());
                details.setVisible(true);
                details.setManaged(true);
                play.setAccessibleText(I18n.tr("ui.playbar.retryAccessible", p.name));
                UiKit.tooltip(play, I18n.tr("ui.playbar.retryTip"));
            }
        }
        boolean running = s.phase() == PlayFlow.Phase.RUNNING;
        UiKit.tooltip(log, I18n.tr(running ? "ui.playbar.logTip" : "ui.playbar.logsTip"));
    }

    private void setPlay(String text, String icon) {
        play.setText(text);
        play.setGraphic(icon == null ? null : Icons.of(icon, 20));
    }

    private void tick() {
        if (Motion.suspended()) {
            return; // minimised: the clock catches up on the first tick after the window is restored
        }
        project().ifPresent(p -> {
            PlayFlow.State s = ctx.play().state(p.id);
            if (s.phase() == PlayFlow.Phase.RUNNING) {
                play.setText(I18n.tr("ui.playbar.stop", clock(s.startedAt())));
            } else {
                refresh();
            }
        });
    }

    /** elapsed time since {@code start} as h:mm:ss (or m:ss under an hour) */
    static String clock(Instant start) {
        long s = start == null ? 0 : Math.max(0, java.time.Duration.between(start, Instant.now()).getSeconds());
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format(I18n.locale(), "%d:%02d:%02d", h, m, sec)
            : String.format(I18n.locale(), "%d:%02d", m, sec);
    }

    private void pressPlay() {
        project().ifPresent(p -> {
            if (ctx.play().state(p.id).phase() == PlayFlow.Phase.ERROR) {
                ctx.play().dismissError(p.id);
            }
            ctx.play().play(p);
        });
    }

    private void showError() {
        project().ifPresent(p -> ctx.play().showError(p));
    }

    private void switcher() {
        List<Project> all = ctx.services().projects().list();
        VBox panel = new VBox();
        panel.getStyleClass().add("project-switcher");
        panel.getChildren().add(UiKit.label(I18n.tr("ui.playbar.switchTitle"), "eyebrow"));
        String selected = project().map(p -> p.id).orElse(null);
        for (Project p : all.stream().limit(12).toList()) {
            Button b = UiKit.button(p.name, null, () -> {
                ctx.overlay().closePopover();
                ctx.selectProject(p.id);
            }, "ghost", "menu-row");
            b.setGraphic(ProjectIcons.tile(p.icon, ctx.services().projects().projectDir(p.id), ThemeManager.em(1.714)));
            b.setMaxWidth(Double.MAX_VALUE);
            if (p.id.equals(selected)) {
                b.getStyleClass().add("current");
            }
            panel.getChildren().add(b);
        }
        Button create = UiKit.button(I18n.tr("ui.home.newProject"), Icons.PLUS, () -> {
            ctx.overlay().closePopover();
            ctx.newProject(null);
        }, "ghost", "menu-row");
        create.setMaxWidth(Double.MAX_VALUE);
        panel.getChildren().add(create);
        ctx.overlay().popover(project, panel, false);
    }

    /** the right-click menu: the same actions as the bar and the project page */
    private Optional<ContextMenu> menu() {
        return project().map(p -> {
            boolean running = ctx.play().isRunning(p.id);
            List<MenuItem> items = new ArrayList<>();
            items.add(item(I18n.tr(running ? "ui.play.stop" : "ui.play.play"), running ? Icons.STOP : Icons.PLAY,
                () -> ctx.play().play(p)));
            items.add(item(I18n.tr("ui.library.open"), Icons.LIBRARY, () -> ctx.openProject(p.id)));
            items.add(item(I18n.tr("ui.playbar.log"), Icons.LOGS, this::openLog));
            items.add(item(I18n.tr("ui.library.openFolder"), Icons.FOLDER,
                () -> ctx.openFolder(ctx.services().projects().gameDir(p))));
            return new ContextMenu(items.toArray(MenuItem[]::new));
        });
    }

    private static MenuItem item(String text, String icon, Runnable action) {
        MenuItem m = new MenuItem(text, Icons.of(icon, 16));
        m.setOnAction(e -> action.run());
        return m;
    }

    /** the running game's log, live; or the project's Logs tab when it isn't running */
    private void openLog() {
        Optional<Project> sel = project();
        if (sel.isEmpty()) {
            return;
        }
        Project p = sel.get();
        Optional<GameProcess> proc = ctx.play().process(p.id);
        if (proc.isEmpty()) {
            ctx.openProjectTab(p.id, "logs");
            return;
        }
        GameProcess g = proc.get();
        ListView<String> lines = new ListView<>();
        lines.getStyleClass().addAll("log-view", "live-log");
        lines.getItems().setAll(g.recentLines());
        List<String> pending = new ArrayList<>();
        Consumer<String> listener = line -> {
            boolean schedule;
            synchronized (pending) {
                schedule = pending.isEmpty();
                pending.add(line);
            }
            if (schedule) {
                Platform.runLater(() -> {
                    List<String> batch;
                    synchronized (pending) {
                        batch = new ArrayList<>(pending);
                        pending.clear();
                    }
                    lines.getItems().addAll(batch);
                    int extra = lines.getItems().size() - MAX_LOG_LINES;
                    if (extra > 0) {
                        lines.getItems().remove(0, extra);
                    }
                    lines.scrollTo(lines.getItems().size() - 1);
                });
            }
        };
        g.addLineListener(listener);
        lines.scrollTo(Math.max(0, lines.getItems().size() - 1));
        Button copy = UiKit.copyButton(() -> String.join(System.lineSeparator(), lines.getItems()),
            I18n.tr("ui.playbar.copyLog"));
        Button folder = UiKit.button(I18n.tr("ui.playbar.logFolder"), Icons.FOLDER,
            () -> ctx.openFolder(g.logFile().getParent()));
        Button close = new Button(I18n.tr("common.close"));
        close.setCancelButton(true);
        VBox body = new VBox(lines);
        body.getStyleClass().add("live-log-body");
        VBox.setVgrow(lines, Priority.ALWAYS);
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.playbar.logTitle", p.name), body, copy, folder,
            close), true, true);
        d.onClose(() -> g.removeLineListener(listener));
        close.setOnAction(e -> d.close());
    }
}
