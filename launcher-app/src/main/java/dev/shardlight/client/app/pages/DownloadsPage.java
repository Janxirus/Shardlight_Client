package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Disclosure;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProgressRing;
import dev.shardlight.client.app.ui.TaskProgress;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.download.DownloadGroup;
import dev.shardlight.client.download.DownloadItem;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadState;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The download queue. The download in progress sits at the top with a progress ring, speed, time left and
 * Pause, Resume and Cancel; the downloads waiting for their turn sit under it; a history you can collapse
 * comes last. A failure shows in place with Retry. The sidebar badge and the top bar indicator are computed
 * from the same {@link DownloadGroup}s, so they always agree with this page.
 */
public final class DownloadsPage implements Page {
    private List<DownloadItem> previewItems;
    /** how many files of one group are listed when it is expanded */
    private static final int MAX_ROWS = 80;

    private final AppContext ctx;
    private final VBox root = new VBox();
    private final StackPane view = new StackPane(root);
    private final Set<String> expanded = new HashSet<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private boolean historyOpen = true;
    private boolean visible;

    public DownloadsPage(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().addAll("downloads", "page-column", "stack");
        StackPane.setAlignment(root, Pos.TOP_CENTER);
        ctx.services().downloads().addListener(item -> schedule());
        view.sceneProperty().addListener((o, a, b) -> visible = b != null);
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.downloads");
    }

    @Override
    public Region view() {
        return view;
    }

    @Override
    public Node actions() {
        return UiKit.button(I18n.tr("ui.downloads.clear"), Icons.CLOSE, () -> {
            ctx.services().downloads().clearFinished();
            render();
        });
    }

    @Override
    public void onShow() {
        render();
    }

    /** coalesce listener calls (they come from download threads) into at most a few repaints a second */
    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            PauseTransition wait = new PauseTransition(Duration.millis(250));
            wait.setOnFinished(e -> {
                scheduled.set(false);
                if (visible) {
                    render();
                }
            });
            wait.play();
        });
    }

    /** --capture only: show these items instead of the real downloads (an empty list shows the empty page) */
    public void previewItems(List<DownloadItem> items) {
        previewItems = items;
        render();
    }

    private void render() {
        List<DownloadGroup> all = DownloadGroup.group(previewItems != null ? previewItems : ctx.services().downloads().items());
        if (previewItems == null) {
            updateChrome(ctx, all);
        }
        root.getChildren().clear();
        if (all.isEmpty()) {
            root.getChildren().add(UiKit.emptyState(Icons.DOWNLOAD, I18n.tr("ui.downloads.emptyTitle"),
                I18n.tr("ui.downloads.emptyBody"), null));
            return;
        }
        List<DownloadGroup> active = all.stream().filter(DownloadGroup::active).toList();
        List<DownloadGroup> history = all.stream().filter(g -> !g.active()).toList();
        root.getChildren().add(summary(DownloadGroup.totals(all)));
        if (!active.isEmpty()) {
            root.getChildren().add(hero(active.get(0)));
        }
        if (active.size() > 1) {
            VBox queue = new VBox();
            queue.getStyleClass().add("stack-tight");
            for (DownloadGroup g : active.subList(1, active.size())) {
                queue.getChildren().add(row(g, true));
            }
            root.getChildren().add(UiKit.section(I18n.tr("ui.downloads.queue", active.size() - 1), queue));
        }
        if (!history.isEmpty()) {
            VBox rows = new VBox();
            rows.getStyleClass().add("stack-tight");
            for (DownloadGroup g : history) {
                rows.getChildren().add(row(g, false));
            }
            Disclosure d = new Disclosure(I18n.tr("ui.downloads.history", history.size()), historyOpen, rows);
            d.expandedProperty().addListener((o, a, b) -> historyOpen = b);
            root.getChildren().add(d);
        }
    }

    /** one sentence that says what the badge and the top bar say */
    private static Node summary(DownloadGroup.Totals t) {
        String text;
        if (t.active() == 0) {
            text = I18n.tr("ui.downloads.idle");
        } else if (t.fraction() >= 0) {
            text = I18n.tr("ui.downloads.indicator", t.active(), Math.round(t.fraction() * 100));
        } else {
            text = I18n.tr("ui.downloads.indicatorUnknown", t.active());
        }
        return UiKit.label(text, "muted", "tabular", "downloads-summary");
    }

    // ------------------------------------------------------------------ the download in progress

    private Node hero(DownloadGroup g) {
        DownloadManager dm = ctx.services().downloads();
        boolean paused = g.isPaused();
        ProgressRing ring = new ProgressRing(g.total() > 0 || g.finished() > 0 ? g.fraction() : -1);
        ring.getStyleClass().add("large");
        Label percent = UiKit.label(g.total() > 0 || g.finished() > 0 ? Math.round(g.fraction() * 100) + "%" : "", "strong", "tabular");
        percent.setWrapText(false);
        StackPane ringBox = new StackPane(ring, percent);
        ringBox.getStyleClass().add("download-ring");

        Label title = UiKit.label(name(g), "h2");
        Label state = UiKit.chip(I18n.tr(paused ? "ui.downloads.paused" : "ui.downloads.active"), paused ? "warning" : "accent");
        HBox titleRow = new HBox(title, state);
        titleRow.getStyleClass().add("download-title");
        VBox text = new VBox(titleRow, UiKit.label(figures(g, paused), "muted", "tabular"));
        text.getStyleClass().add("row-text");
        String now = g.runningLabel();
        if (now != null && !paused) {
            Label file = UiKit.label(now, "faint", "small");
            file.setWrapText(false);
            text.getChildren().add(file);
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox buttons = controls(g, dm, paused);
        HBox top = new HBox(ringBox, text, buttons);
        top.getStyleClass().add("download-hero-top");
        VBox card = new VBox(top);
        card.getStyleClass().addAll("card", "download-hero");
        if (g.failed() > 0) {
            card.getChildren().add(failure(g, dm));
        }
        if (expanded.contains(g.name())) {
            card.getChildren().add(details(g));
        }
        card.getChildren().add(filesToggle(g));
        return card;
    }

    /** Pause or Resume, and Cancel */
    private HBox controls(DownloadGroup g, DownloadManager dm, boolean paused) {
        HBox buttons = new HBox();
        buttons.getStyleClass().add("button-row");
        Button pause = paused
            ? UiKit.button(I18n.tr("ui.downloads.resume"), Icons.PLAY, () -> {
                dm.resumeGroup(g.name());
                render();
            })
            : UiKit.button(I18n.tr("ui.downloads.pause"), Icons.PAUSE, () -> {
                dm.pauseGroup(g.name());
                render();
            });
        pause.setAccessibleText(I18n.tr(paused ? "ui.downloads.resumeNamed" : "ui.downloads.pauseNamed", name(g)));
        Button cancel = UiKit.button(I18n.tr("common.cancel"), Icons.CLOSE, () -> {
            g.items().stream().filter(i -> !i.state().isFinished()).forEach(i -> dm.cancel(i.id()));
            dm.resumeGroup(g.name());
            render();
        }, "ghost");
        cancel.setAccessibleText(I18n.tr("ui.downloads.cancelNamed", name(g)));
        buttons.getChildren().addAll(pause, cancel);
        return buttons;
    }

    /** "142 MB of 310 MB · 8.2 MB/s · 21 s left" */
    private static String figures(DownloadGroup g, boolean paused) {
        StringBuilder b = new StringBuilder();
        b.append(g.total() > 0 ? I18n.tr("ui.downloads.progress", UiKit.bytes(g.done()), UiKit.bytes(g.total()))
            : I18n.tr("ui.downloads.files", g.finished(), g.items().size()));
        if (paused) {
            return b.append("  ·  ").append(I18n.tr("ui.downloads.heldBack")).toString();
        }
        if (g.speed() > 0) {
            b.append("  ·  ").append(I18n.tr("ui.downloads.speed", UiKit.bytes(g.speed())));
        }
        long eta = g.etaMillis();
        if (eta >= 0) {
            b.append("  ·  ").append(I18n.tr("ui.downloads.timeLeft", UiKit.eta(eta)));
        }
        return b.toString();
    }

    /** what went wrong, right where the download is, with Retry for the files that failed */
    private Node failure(DownloadGroup g, DownloadManager dm) {
        LauncherException e = g.firstError();
        String why = e == null ? I18n.tr("ui.downloads.state.failed") : e.userMessage();
        return UiKit.banner("danger", I18n.tr("ui.downloads.failedBecause", g.failed(), why),
            UiKit.button(I18n.tr("ui.downloads.retry"), Icons.REFRESH, () -> {
                g.items().stream().filter(i -> i.state() == DownloadState.FAILED).forEach(i -> dm.retry(i.id()));
                render();
            }));
    }

    private Node filesToggle(DownloadGroup g) {
        boolean open = expanded.contains(g.name());
        Button toggle = UiKit.button(I18n.tr(open ? "ui.downloads.hide" : "ui.downloads.show"),
            open ? Icons.CHEVRON_DOWN : Icons.CHEVRON_RIGHT, () -> {
                if (!expanded.remove(g.name())) {
                    expanded.add(g.name());
                }
                render();
            }, "ghost", "small");
        HBox box = new HBox(toggle);
        box.getStyleClass().add("button-row");
        return box;
    }

    private static String name(DownloadGroup g) {
        return g.name().isBlank() ? I18n.tr("ui.downloads.other") : g.name();
    }

    // ------------------------------------------------------------------ queue and history rows

    /** a waiting download (with Pause and Cancel) or a finished one (with Retry when it failed or was cancelled) */
    private Node row(DownloadGroup g, boolean waiting) {
        DownloadManager dm = ctx.services().downloads();
        String state;
        String chipKind;
        if (g.active()) {
            state = I18n.tr(g.isPaused() ? "ui.downloads.paused" : "ui.downloads.waiting");
            chipKind = g.isPaused() ? "warning" : null;
        } else if (g.failed() > 0) {
            state = I18n.tr("ui.downloads.failedCount", g.failed());
            chipKind = "danger";
        } else if (g.cancelled() > 0) {
            state = I18n.tr("ui.downloads.cancelled");
            chipKind = "warning";
        } else {
            state = I18n.tr("ui.downloads.done");
            chipKind = "success";
        }
        String detail = I18n.tr("ui.downloads.files", g.finished(), g.items().size());
        if (g.total() > 0) {
            detail += "  ·  " + UiKit.bytes(g.done()) + " / " + UiKit.bytes(g.total());
        }
        HBox titleRow = new HBox(UiKit.label(name(g), "project-name"), UiKit.chip(state, chipKind));
        titleRow.getStyleClass().add("download-title");
        VBox text = new VBox(titleRow, UiKit.label(detail, "muted", "small", "tabular"));
        text.getStyleClass().add("row-text");
        if (!g.active() && g.failed() > 0) {
            LauncherException e = g.firstError();
            text.getChildren().add(UiKit.label(e == null ? I18n.tr("ui.downloads.state.failed") : e.userMessage(), "danger-text", "small"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox buttons = new HBox();
        buttons.getStyleClass().add("button-row");
        if (g.active()) {
            boolean paused = g.isPaused();
            Button pause = UiKit.iconButton(paused ? Icons.PLAY : Icons.PAUSE,
                I18n.tr(paused ? "ui.downloads.resumeNamed" : "ui.downloads.pauseNamed", name(g)), () -> {
                    if (paused) {
                        dm.resumeGroup(g.name());
                    } else {
                        dm.pauseGroup(g.name());
                    }
                    render();
                });
            Button cancel = UiKit.iconButton(Icons.CLOSE, I18n.tr("ui.downloads.cancelNamed", name(g)), () -> {
                g.items().stream().filter(i -> !i.state().isFinished()).forEach(i -> dm.cancel(i.id()));
                dm.resumeGroup(g.name());
                render();
            });
            buttons.getChildren().addAll(pause, cancel);
        } else if (g.failed() > 0 || g.cancelled() > 0) {
            buttons.getChildren().add(UiKit.button(I18n.tr("ui.downloads.retry"), Icons.REFRESH, () -> {
                g.items().stream().filter(i -> i.state() == DownloadState.FAILED || i.state() == DownloadState.CANCELLED)
                    .forEach(i -> dm.retry(i.id()));
                render();
            }));
        }
        boolean open = expanded.contains(g.name());
        buttons.getChildren().add(UiKit.iconButton(open ? Icons.CHEVRON_DOWN : Icons.CHEVRON_RIGHT,
            I18n.tr(open ? "ui.downloads.hide" : "ui.downloads.show"), () -> {
                if (!expanded.remove(g.name())) {
                    expanded.add(g.name());
                }
                render();
            }));

        String icon = g.active() ? Icons.DOWNLOAD : g.failed() > 0 ? Icons.WARNING : g.cancelled() > 0 ? Icons.CLOSE : Icons.CHECK;
        HBox head = new HBox(UiKit.iconBox(Icons.of(icon, 16), 36), text, buttons);
        head.getStyleClass().add("download-row-head");
        VBox card = new VBox(head);
        card.getStyleClass().addAll("card", "flat", "download-row");
        if (g.active()) {
            ProgressBar bar = UiKit.progress(g.fraction());
            bar.setMaxWidth(Double.MAX_VALUE);
            card.getChildren().add(bar);
        }
        if (open) {
            card.getChildren().add(details(g));
        }
        return card;
    }

    private Node details(DownloadGroup g) {
        DownloadManager dm = ctx.services().downloads();
        VBox rows = new VBox();
        rows.getStyleClass().add("stack-tight");
        // problems and running files first
        List<DownloadItem> sorted = new ArrayList<>(g.items());
        sorted.sort(Comparator.comparingInt(DownloadsPage::rank));
        for (DownloadItem i : sorted.stream().limit(MAX_ROWS).toList()) {
            String name = i.label() != null && !i.label().isBlank() ? i.label()
                : i.target() == null ? "?" : i.target().getFileName().toString();
            String sub = stateText(i);
            Label l = UiKit.label(name, "small");
            Label s = UiKit.label(sub, i.state() == DownloadState.FAILED ? "danger-text" : "faint", "small");
            s.setWrapText(true);
            VBox t = new VBox(l, s);
            t.getStyleClass().add("row-text");
            HBox.setHgrow(t, Priority.ALWAYS);
            HBox row = new HBox(t);
            row.getStyleClass().add("download-file-row");
            if (i.state() == DownloadState.FAILED || i.state() == DownloadState.CANCELLED) {
                row.getChildren().add(UiKit.iconButton(Icons.REFRESH, I18n.tr("ui.downloads.retry"), () -> {
                    dm.retry(i.id());
                    render();
                }));
            } else if (!i.state().isFinished()) {
                row.getChildren().add(UiKit.iconButton(Icons.CLOSE, I18n.tr("common.cancel"), () -> {
                    dm.cancel(i.id());
                    render();
                }));
            }
            rows.getChildren().add(row);
        }
        if (g.items().size() > MAX_ROWS) {
            rows.getChildren().add(UiKit.label(I18n.tr("ui.downloads.more", g.items().size() - MAX_ROWS), "faint", "small"));
        }
        rows.getStyleClass().add("download-details");
        return rows;
    }

    private static int rank(DownloadItem i) {
        return switch (i.state()) {
            case FAILED -> 0;
            case RUNNING, VERIFYING -> 1;
            case PAUSED -> 2;
            case QUEUED -> 3;
            case CANCELLED -> 4;
            case DONE, SKIPPED -> 5;
        };
    }

    static String stateText(DownloadItem i) {
        return switch (i.state()) {
            case QUEUED -> I18n.tr("ui.downloads.state.queued");
            case PAUSED -> I18n.tr("ui.downloads.state.paused", UiKit.bytes(i.bytesDone()));
            case RUNNING -> i.bytesTotal() > 0
                ? I18n.tr("ui.downloads.state.running", UiKit.bytes(i.bytesDone()), UiKit.bytes(i.bytesTotal()),
                    UiKit.bytes(i.bytesPerSecond()))
                : I18n.tr("ui.downloads.state.runningUnknown", UiKit.bytes(i.bytesDone()));
            case VERIFYING -> I18n.tr("ui.downloads.state.verifying");
            case DONE -> I18n.tr("ui.downloads.state.done", UiKit.bytes(Math.max(i.bytesDone(), i.bytesTotal())));
            case SKIPPED -> I18n.tr("ui.downloads.state.skipped");
            case CANCELLED -> I18n.tr("ui.downloads.state.cancelled");
            case FAILED -> i.error() == null ? I18n.tr("ui.downloads.state.failed")
                : I18n.tr("ui.downloads.state.failedWhy", i.error().userMessage(), i.attempts());
        };
    }

    /**
     * Keep the sidebar badge, the top bar ring and the taskbar showing the same figures as this page, and say
     * when a batch the user isn't watching finishes (if they want that).
     */
    public static void installBadge(AppContext ctx) {
        AtomicBoolean pending = new AtomicBoolean();
        Set<String> activeGroups = new HashSet<>();
        Runnable update = () -> {
            pending.set(false);
            List<DownloadGroup> all = DownloadGroup.group(ctx.services().downloads().items());
            updateChrome(ctx, all);
            for (DownloadGroup g : all) {
                if (g.active()) {
                    activeGroups.add(g.name());
                } else if (activeGroups.remove(g.name()) && ctx.services().settings().get().downloads.notifyWhenDone
                    && !"downloads".equals(ctx.shell().currentId()) && !ctx.overlay().hasOpenDialog()) {
                    String name = g.name().isBlank() ? I18n.tr("ui.downloads.other") : g.name();
                    ctx.overlay().toast(g.failed() > 0 ? "danger" : "success",
                        I18n.tr(g.failed() > 0 ? "ui.downloads.toastFailed" : "ui.downloads.toastDone", name));
                }
            }
            activeGroups.retainAll(all.stream().map(DownloadGroup::name).toList());
        };
        ctx.services().downloads().addListener(item -> {
            if (pending.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    PauseTransition wait = new PauseTransition(Duration.millis(400));
                    wait.setOnFinished(e -> update.run());
                    wait.play();
                });
            }
        });
        update.run();
    }

    /** the sidebar badge, the top bar ring and the taskbar progress, all from the same groups the page shows */
    static void updateChrome(AppContext ctx, List<DownloadGroup> all) {
        DownloadGroup.Totals t = DownloadGroup.totals(all);
        ctx.shell().setBadge("downloads", t.active());
        ctx.tools().setDownloads(t.active(), t.fraction());
        if (t.active() > 0) {
            TaskProgress.set("downloads", t.fraction());
        } else {
            TaskProgress.clear("downloads");
        }
    }
}
