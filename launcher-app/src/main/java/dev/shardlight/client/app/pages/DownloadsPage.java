package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.download.DownloadItem;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.download.DownloadState;
import dev.shardlight.client.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The download queue: every download the launcher makes, grouped by what it is for (a game version, a mod
 * and its dependencies, a modpack), with progress, speed, verification state, cancel and retry.
 */
public final class DownloadsPage implements Page {
    /** how many files of one group are listed when it is expanded */
    private static final int MAX_ROWS = 80;

    private final AppContext ctx;
    private final VBox root = new VBox(12);
    private final VBox groups = new VBox(10);
    private final Set<String> expanded = new HashSet<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private boolean visible;

    public DownloadsPage(AppContext ctx) {
        this.ctx = ctx;
        root.getChildren().addAll(UiKit.label(I18n.tr("ui.downloads.intro"), "muted"), groups);
        ctx.services().downloads().addListener(item -> schedule());
        root.sceneProperty().addListener((o, a, b) -> visible = b != null);
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.downloads");
    }

    @Override
    public Region view() {
        return root;
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

    /** one group's summary */
    record Group(String name, List<DownloadItem> items, long done, long total, long speed, int finished, int failed,
                 int cancelled, boolean active) {
    }

    static List<Group> group(List<DownloadItem> items) {
        Map<String, List<DownloadItem>> byGroup = new LinkedHashMap<>();
        for (DownloadItem i : items) {
            byGroup.computeIfAbsent(i.group() == null ? "" : i.group(), k -> new ArrayList<>()).add(i);
        }
        List<Group> out = new ArrayList<>();
        byGroup.forEach((name, list) -> {
            long done = 0;
            long total = 0;
            long speed = 0;
            int finished = 0;
            int failed = 0;
            int cancelled = 0;
            for (DownloadItem i : list) {
                done += Math.max(0, i.bytesDone());
                total += Math.max(0, i.bytesTotal());
                if (i.state() == DownloadState.RUNNING) {
                    speed += i.bytesPerSecond();
                }
                if (i.state().isFinished()) {
                    finished++;
                }
                if (i.state() == DownloadState.FAILED) {
                    failed++;
                }
                if (i.state() == DownloadState.CANCELLED) {
                    cancelled++;
                }
            }
            out.add(new Group(name, list, done, total, speed, finished, failed, cancelled, finished < list.size()));
        });
        // active groups first, then the most recently finished
        out.sort(Comparator.comparing((Group g) -> !g.active())
            .thenComparing(g -> -g.items().stream().mapToLong(DownloadItem::finishedAt).max().orElse(0)));
        return out;
    }

    private void render() {
        List<Group> all = group(ctx.services().downloads().items());
        groups.getChildren().clear();
        if (all.isEmpty()) {
            groups.getChildren().add(UiKit.emptyState(Icons.DOWNLOAD, I18n.tr("ui.downloads.emptyTitle"),
                I18n.tr("ui.downloads.emptyBody"), null));
            return;
        }
        for (Group g : all) {
            groups.getChildren().add(card(g));
        }
    }

    private Node card(Group g) {
        DownloadManager dm = ctx.services().downloads();
        String state;
        String chipKind;
        if (g.active()) {
            state = I18n.tr("ui.downloads.active");
            chipKind = "accent";
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
        Label title = UiKit.label(g.name().isBlank() ? I18n.tr("ui.downloads.other") : g.name(), "project-name");
        String detail = I18n.tr("ui.downloads.files", g.finished(), g.items().size());
        if (g.total() > 0) {
            detail += "  ·  " + UiKit.bytes(g.done()) + " / " + UiKit.bytes(g.total());
        }
        if (g.active() && g.speed() > 0) {
            detail += "  ·  " + I18n.tr("ui.downloads.speed", UiKit.bytes(g.speed()));
        }
        VBox text = new VBox(3, new HBox(8, title, UiKit.chip(state, chipKind)), UiKit.label(detail, "muted", "small"));
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox buttons = new HBox(6);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        if (g.active()) {
            buttons.getChildren().add(UiKit.button(I18n.tr("common.cancel"), Icons.CLOSE, () -> {
                g.items().stream().filter(i -> !i.state().isFinished()).forEach(i -> dm.cancel(i.id()));
                render();
            }));
        }
        if (!g.active() && (g.failed() > 0 || g.cancelled() > 0)) {
            buttons.getChildren().add(UiKit.button(I18n.tr("ui.downloads.retry"), Icons.REFRESH, () -> {
                g.items().stream().filter(i -> i.state() == DownloadState.FAILED || i.state() == DownloadState.CANCELLED)
                    .forEach(i -> dm.retry(i.id()));
                render();
            }));
        }
        boolean open = expanded.contains(g.name());
        Button toggle = UiKit.button(I18n.tr(open ? "ui.downloads.hide" : "ui.downloads.show"), null, () -> {
            if (!expanded.remove(g.name())) {
                expanded.add(g.name());
            }
            render();
        });
        buttons.getChildren().add(toggle);

        HBox head = new HBox(12, UiKit.iconBox(Icons.of(Icons.DOWNLOAD, 18), 36), text, buttons);
        head.setAlignment(Pos.CENTER_LEFT);
        double fraction = g.total() > 0 ? (double) g.done() / g.total() : g.items().isEmpty() ? 1
            : (double) g.finished() / g.items().size();
        ProgressBar bar = UiKit.progress(g.active() ? fraction : 1);
        bar.setMaxWidth(Double.MAX_VALUE);
        if (!g.active() && g.failed() > 0) {
            bar.getStyleClass().add("danger");
        }
        VBox card = UiKit.card(head, bar);
        if (open) {
            card.getChildren().add(details(g));
        }
        return card;
    }

    private Node details(Group g) {
        DownloadManager dm = ctx.services().downloads();
        VBox rows = new VBox(4);
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
            VBox t = new VBox(1, l, s);
            HBox.setHgrow(t, Priority.ALWAYS);
            HBox row = new HBox(8, t);
            row.setAlignment(Pos.CENTER_LEFT);
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
            case QUEUED -> 2;
            case CANCELLED -> 3;
            case DONE, SKIPPED -> 4;
        };
    }

    static String stateText(DownloadItem i) {
        return switch (i.state()) {
            case QUEUED -> I18n.tr("ui.downloads.state.queued");
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
     * Keep the sidebar badge showing how many downloads are queued or running, and say when a batch the
     * user isn't watching finishes (if they want that).
     */
    public static void installBadge(AppContext ctx) {
        AtomicBoolean pending = new AtomicBoolean();
        Set<String> activeGroups = new HashSet<>();
        Runnable update = () -> {
            pending.set(false);
            List<Group> all = group(ctx.services().downloads().items());
            int active = (int) all.stream().filter(Group::active).mapToLong(g -> g.items().size() - g.finished()).sum();
            ctx.shell().setBadge("downloads", active);
            for (Group g : all) {
                if (g.active()) {
                    activeGroups.add(g.name());
                } else if (activeGroups.remove(g.name()) && ctx.services().settings().get().downloads.notifyWhenDone
                    && !"downloads".equals(ctx.shell().currentId()) && !ctx.overlay().hasOpenDialog()) {
                    String name = g.name().isBlank() ? I18n.tr("ui.downloads.other") : g.name();
                    ctx.overlay().toast(g.failed() > 0 ? "danger" : "success",
                        I18n.tr(g.failed() > 0 ? "ui.downloads.toastFailed" : "ui.downloads.toastDone", name));
                }
            }
            activeGroups.retainAll(all.stream().map(Group::name).toList());
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
}
