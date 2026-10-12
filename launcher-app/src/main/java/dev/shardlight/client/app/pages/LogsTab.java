package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.app.ui.Fields;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.diagnostics.Finding;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.project.Project;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The game log in a monospace list with a colour for each level, search, follow mode (the list keeps up with the
 * file and stays at the bottom), copy, and "Explain this crash", which runs the crash analysis on the newest crash
 * report or the log. Crash reports are listed under the log.
 */
final class LogsTab {
    private static final int LINES = 1500;
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox();
    private final ListView<LogLines.Line> view = new ListView<>();
    private final Fields.SearchField search = new Fields.SearchField(I18n.tr("ui.logs.search"));
    private final ToggleButton follow = new ToggleButton();
    private List<LogLines.Line> lines = List.of();
    private String lastText = "";
    private ScheduledExecutorService poller;
    private ScheduledFuture<?> polling;

    LogsTab(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
        root.getStyleClass().add("stack");
        view.getStyleClass().add("log-view");
        view.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        view.setAccessibleText(I18n.tr("ui.logs.latest"));
        view.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(LogLines.Line item, boolean empty) {
                super.updateItem(item, empty);
                for (LogLines.Level level : LogLines.Level.values()) {
                    getStyleClass().remove(level.styleClass());
                }
                setText(empty || item == null ? null : item.text());
                if (!empty && item != null) {
                    getStyleClass().add(item.level().styleClass());
                }
            }
        });
        view.setPlaceholder(UiKit.label(I18n.tr("ui.logs.none"), "muted"));
        view.setPrefHeight(ThemeManager.em(34));
        search.textProperty().addListener((o, a, b) -> showLines());
        follow.setText(I18n.tr("ui.logs.follow"));
        follow.setGraphic(Icons.of(Icons.FORWARD, 14));
        UiKit.tooltip(follow, I18n.tr("ui.logs.followTip"));
        follow.selectedProperty().addListener((o, a, on) -> setFollowing(on));
    }

    VBox view() {
        return root;
    }

    private Optional<Project> project() {
        return ctx.services().projects().get(projectId);
    }

    private Path gameDir(Project p) {
        return ctx.services().projects().gameDir(p);
    }

    void refresh() {
        root.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        Path gameDir = gameDir(p);
        Path latest = gameDir.resolve("logs").resolve("latest.log");
        List<Path> reports = crashReports(gameDir);

        Button copy = UiKit.button(I18n.tr("ui.logs.copy"), Icons.COPY, () -> { });
        copy.setOnAction(e -> {
            List<LogLines.Line> chosen = view.getSelectionModel().getSelectedItems().isEmpty() ? view.getItems()
                : view.getSelectionModel().getSelectedItems();
            ClipboardContent c = new ClipboardContent();
            c.putString(String.join("\n", chosen.stream().map(LogLines.Line::text).toList()));
            Clipboard.getSystemClipboard().setContent(c);
            UiKit.copied(copy);
        });
        Path explainTarget = reports.isEmpty() ? latest : reports.get(0);
        Button explain = UiKit.primary(I18n.tr("ui.logs.explain"), Icons.SPARKLE, () -> analyze(explainTarget));
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.logs.openFolder"), () -> ctx.openFolder(gameDir.resolve("logs")));
        Button export = UiKit.iconButton(Icons.ARCHIVE, I18n.tr("ui.diagnostics.export"), () -> PlayFlow.exportDiagnostics(ctx, p));
        HBox bar = new HBox(search, follow, copy, folder, export, UiKit.spacer(), explain);
        bar.getStyleClass().add("tab-bar");
        root.getChildren().addAll(bar, view);

        Fx.run(() -> tail(latest), text -> {
            lastText = text;
            lines = LogLines.parse(text);
            showLines();
            scrollToEnd();
        }, err -> view.getItems().clear());

        VBox crashes = new VBox();
        crashes.getStyleClass().add("stack-tight");
        if (reports.isEmpty()) {
            crashes.getChildren().add(UiKit.label(I18n.tr("ui.logs.noCrashes"), "muted"));
        }
        for (Path r : reports) {
            Button a = UiKit.button(I18n.tr("ui.logs.analyze"), Icons.SEARCH, () -> analyze(r));
            HBox row = new HBox(UiKit.label(r.getFileName().toString(), "mono"), UiKit.spacer(),
                UiKit.label(ProjectText.ago(r.toFile().lastModified()), "faint", "small"), a);
            row.getStyleClass().add("crash-row");
            crashes.getChildren().add(row);
        }
        root.getChildren().add(UiKit.section(I18n.tr("ui.logs.crashReports"), crashes));
    }

    /** --capture only: run the analysis on the newest crash report */
    void previewExplain() {
        project().ifPresent(p -> {
            List<Path> reports = crashReports(gameDir(p));
            analyze(reports.isEmpty() ? gameDir(p).resolve("logs").resolve("latest.log") : reports.get(0));
        });
    }

    private static List<Path> crashReports(Path gameDir) {
        List<Path> reports = new ArrayList<>();
        Path crashDir = gameDir.resolve("crash-reports");
        if (Files.isDirectory(crashDir)) {
            try (Stream<Path> s = Files.list(crashDir)) {
                s.filter(f -> f.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing((Path f) -> f.toFile().lastModified()).reversed())
                    .limit(10).forEach(reports::add);
            } catch (IOException ignored) {
                // shown as "no crash reports"
            }
        }
        return reports;
    }

    private void showLines() {
        view.getItems().setAll(LogLines.filter(lines, search.getText()));
    }

    private void scrollToEnd() {
        if (!view.getItems().isEmpty()) {
            view.scrollTo(view.getItems().size() - 1);
        }
    }

    // ------------------------------------------------------------------ follow

    private void setFollowing(boolean on) {
        if (polling != null) {
            polling.cancel(false);
            polling = null;
        }
        if (!on) {
            return;
        }
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        Path latest = gameDir(found.get()).resolve("logs").resolve("latest.log");
        if (poller == null) {
            poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "log-follow");
                t.setDaemon(true);
                return t;
            });
        }
        polling = poller.scheduleWithFixedDelay(() -> {
            try {
                if (root.getScene() == null) {
                    Platform.runLater(() -> follow.setSelected(false));
                    return;
                }
                String text = tail(latest);
                Platform.runLater(() -> {
                    if (!text.equals(lastText)) {
                        lastText = text;
                        lines = LogLines.parse(text);
                        showLines();
                    }
                    scrollToEnd();
                });
            } catch (IOException e) {
                // the file may be rewritten as the game starts; try again next time
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ crash analysis

    private void analyze(Path file) {
        Tasks.quiet(ctx.overlay(), () -> ctx.services().crashAnalyzer().analyze(file), findings -> {
            VBox body = new VBox();
            body.getStyleClass().addAll("stack-tight", "analysis");
            if (findings.isEmpty()) {
                body.getChildren().add(UiKit.label(I18n.tr("ui.logs.nothingFound"), "muted"));
            }
            for (Finding f : findings) {
                Object[] args = f.args() == null ? new Object[0] : f.args().toArray();
                VBox text = new VBox(UiKit.label(I18n.tr(f.titleKey(), args), "h3"),
                    UiKit.label(I18n.tr(f.detailKey(), args), "muted"));
                body.getChildren().add(UiKit.banner(f.severity().name().equals("ERROR") ? "danger"
                    : f.severity().name().equals("WARNING") ? "warning" : "info", text));
            }
            Button ok = UiKit.primary(I18n.tr("common.close"), null, () -> { });
            var d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.logs.analysisTitle", file.getFileName()), body, ok), true, true);
            ok.setOnAction(e -> d.close());
        });
    }

    /** the last {@link #LINES} lines of the log ("" when it doesn't exist), with secrets removed */
    static String tail(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        long size = Files.size(file);
        byte[] data;
        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            long start = Math.max(0, size - 512 * 1024);
            ch.position(start);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) (size - start));
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // keep reading
            }
            data = buf.array();
        }
        String text = Redactor.redact(new String(data, StandardCharsets.UTF_8));
        String[] all = text.split("\\R");
        int from = Math.max(0, all.length - LINES);
        return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
    }
}
