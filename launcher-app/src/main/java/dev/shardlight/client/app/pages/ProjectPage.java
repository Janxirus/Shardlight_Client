package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.diagnostics.Finding;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.loader.LoaderVersion;
import dev.shardlight.client.project.Project;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** One project: overview, content, worlds, backups, screenshots, performance, settings and logs. */
public final class ProjectPage implements Page {
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox(14);
    private final VBox header = new VBox();
    private final TabPane tabs = new TabPane();
    private final ContentTab content;
    private final WorldsTab worlds;
    private final BackupsTab backups;
    private final ScreenshotsTab screenshots;
    private final PresetPanel presets;
    private final VBox overview = new VBox(14);
    private final VBox settings = new VBox(14);
    private final VBox logs = new VBox(12);
    private String title = "";

    public ProjectPage(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
        this.content = new ContentTab(ctx, projectId);
        this.worlds = new WorldsTab(ctx, projectId);
        this.backups = new BackupsTab(ctx, projectId);
        this.screenshots = new ScreenshotsTab(ctx, projectId);
        this.presets = new PresetPanel(ctx, projectId);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            tab("ui.projectPage.overview", overview, this::refreshOverview),
            tab("ui.projectPage.content", content.view(), content::refresh),
            tab("ui.projectPage.worlds", worlds.view(), worlds::refresh),
            tab("ui.projectPage.backups", backups.view(), backups::refresh),
            tab("ui.projectPage.screenshots", screenshots.view(), screenshots::refresh),
            tab("ui.projectPage.performance", presets.view(), presets::refresh),
            tab("ui.projectPage.settings", settings, this::refreshSettings),
            tab("ui.projectPage.logs", logs, this::refreshLogs));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.getChildren().addAll(header, tabs);
        root.setPadding(new Insets(0));
        ctx.play().onStateChanged(() -> {
            if (ctx.shell().current() == this) {
                refreshHeader();
            }
        });
    }

    private Tab tab(String key, Region body, Runnable onSelect) {
        ScrollPane sp = new ScrollPane(body);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("content-scroll");
        body.setPadding(new Insets(14, 2, 14, 2));
        Tab t = new Tab(I18n.tr(key), sp);
        t.setOnSelectionChanged(e -> {
            if (t.isSelected()) {
                onSelect.run();
            }
        });
        return t;
    }

    /** open a tab by index (0 overview .. 7 logs) */
    public ProjectPage select(int index) {
        tabs.getSelectionModel().select(index);
        return this;
    }

    private Optional<Project> project() {
        return ctx.services().projects().get(projectId);
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Region view() {
        return root;
    }

    @Override
    public boolean scrolls() {
        return false;
    }

    @Override
    public void onShow() {
        refreshHeader();
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getOnSelectionChanged() != null) {
            selected.getOnSelectionChanged().handle(null);
        }
    }

    private void refreshHeader() {
        header.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            title = I18n.tr("ui.projectPage.gone");
            header.getChildren().add(UiKit.banner("warning", I18n.tr("ui.projectPage.goneBody")));
            tabs.setDisable(true);
            ctx.shell().refreshChrome();
            return;
        }
        Project p = found.get();
        title = p.name;
        ctx.shell().refreshChrome();
        Node icon = ProjectIcons.tile(p.icon, ctx.services().projects().projectDir(p.id), 56);
        FlowPane chips = new FlowPane(6, 6,
            UiKit.chip(I18n.tr("ui.project.minecraft", p.minecraftVersion), null, Icons.CUBE),
            UiKit.chip(ProjectText.loader(p), null, Icons.PACKAGE),
            UiKit.chip(ProjectText.memory(p), null, Icons.GAUGE),
            UiKit.chip(ProjectText.preset(p), "accent", Icons.BOLT));
        if (p.hasShardlight()) {
            chips.getChildren().add(UiKit.chip(ProjectText.modules(p), "accent", Icons.SHARD));
        }
        VBox text = new VBox(6, UiKit.label(p.name, "h2"), chips);
        HBox.setHgrow(text, Priority.ALWAYS);
        boolean running = ctx.play().isRunning(p.id);
        Button play = UiKit.primary(I18n.tr(running ? "ui.play.stop" : "ui.play.play"), running ? Icons.STOP : Icons.PLAY,
            () -> ctx.play().play(p));
        play.setAccessibleText(I18n.tr(running ? "ui.play.stopAccessible" : "ui.play.playAccessible", p.name));
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolder"),
            () -> ctx.openFolder(ctx.services().projects().gameDir(p)));
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.library.moreFor", p.name), () -> { });
        more.setOnAction(e -> menu(p).show(more, Side.BOTTOM, 0, 0));
        HBox row = new HBox(14, icon, text, play, folder, more);
        row.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().add(row);
    }

    private ContextMenu menu(Project p) {
        MenuItem rename = new MenuItem(I18n.tr("ui.project.rename"));
        rename.setOnAction(e -> ProjectActions.rename(ctx, p));
        MenuItem icon = new MenuItem(I18n.tr("ui.projectPage.changeIcon"));
        icon.setOnAction(e -> changeIcon(p));
        MenuItem dup = new MenuItem(I18n.tr("ui.project.duplicate"));
        dup.setOnAction(e -> ProjectActions.duplicate(ctx, p));
        MenuItem export = new MenuItem(I18n.tr("ui.project.export"));
        export.setOnAction(e -> ProjectActions.export(ctx, p));
        MenuItem mrpack = new MenuItem(I18n.tr("ui.projectPage.exportModpack"));
        mrpack.setOnAction(e -> ModpackExportDialog.show(ctx, p));
        MenuItem select = new MenuItem(I18n.tr("ui.library.makeDefault"));
        select.setOnAction(e -> ctx.selectProject(p.id));
        MenuItem trash = new MenuItem(I18n.tr("ui.project.trash"));
        trash.getStyleClass().add("danger");
        trash.setOnAction(e -> ProjectActions.moveToTrash(ctx, p, () -> ctx.shell().navigate("library")));
        return new ContextMenu(select, rename, icon, dup, export, mrpack, new javafx.scene.control.SeparatorMenuItem(), trash);
    }

    private void changeIcon(Project p) {
        Path f = Dialogs.openFile(ctx.stage(), I18n.tr("ui.wizard.iconFromFile"), I18n.tr("ui.wizard.images"),
            "*.png", "*.jpg", "*.jpeg");
        if (f != null) {
            Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().setIconFromFile(p.id, f), x -> {
                ctx.projectsChanged();
                refreshHeader();
            });
        }
    }

    // ------------------------------------------------------------------ overview

    private void refreshOverview() {
        overview.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        FlowPane stats = new FlowPane(28, 12,
            UiKit.stat(UiKit.duration(p.totalPlayMillis), I18n.tr("ui.projectPage.playTime")),
            UiKit.stat(p.lastPlayedAt > 0 ? ProjectText.ago(p.lastPlayedAt) : I18n.tr("ui.project.never"),
                I18n.tr("ui.projectPage.lastPlayed")),
            UiKit.stat(String.valueOf(ProjectText.count(p, dev.shardlight.client.content.ContentKind.MOD, true)),
                I18n.tr("ui.projectPage.modsOn")),
            UiKit.stat(String.valueOf(ProjectText.count(p, dev.shardlight.client.content.ContentKind.RESOURCE_PACK, true)),
                I18n.tr("ui.projectPage.packsOn")),
            UiKit.stat(p.createdAt > 0 ? ProjectText.ago(p.createdAt) : "-", I18n.tr("ui.projectPage.created")));
        VBox statsCard = UiKit.card(stats);
        overview.getChildren().add(statsCard);

        Tasks.quiet(ctx.overlay(), () -> ctx.services().content().warnings(p), warnings -> {
            for (var w : warnings) {
                overview.getChildren().add(1, UiKit.banner("warning", w.message()));
            }
        });
        if (p.hasShardlight()) {
            overview.getChildren().add(UiKit.banner("info", I18n.tr("ui.projectPage.shardlightLine", ProjectText.modules(p)),
                UiKit.button(I18n.tr("ui.projectPage.manageShardlight"), Icons.SHARD, () -> {
                    ctx.selectProject(p.id);
                    ctx.shell().navigate("shardlight");
                })));
        }

        TextArea notes = new TextArea(p.notes == null ? "" : p.notes);
        notes.setPrefRowCount(4);
        notes.setWrapText(true);
        notes.setPromptText(I18n.tr("ui.projectPage.notesPrompt"));
        notes.setAccessibleText(I18n.tr("ui.projectPage.notes"));
        notes.focusedProperty().addListener((o, a, b) -> {
            if (!b && !notes.getText().equals(p.notes)) {
                String text = notes.getText();
                Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().update(p.id, x -> x.notes = text), x -> { });
            }
        });
        overview.getChildren().add(UiKit.section(I18n.tr("ui.projectPage.notes"), notes));

        FlowPane quick = new FlowPane(10, 10,
            UiKit.button(I18n.tr("ui.home.findMods"), Icons.DISCOVER, () -> {
                ctx.selectProject(p.id);
                ctx.shell().navigate("discover");
            }),
            UiKit.button(I18n.tr("ui.backup.now"), Icons.ARCHIVE, () -> ProjectActions.backupNow(ctx, p)),
            UiKit.button(I18n.tr("ui.projectPage.openMods"), Icons.FOLDER,
                () -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve("mods"))),
            UiKit.button(I18n.tr("ui.diagnostics.export"), Icons.LOGS, () -> PlayFlow.exportDiagnostics(ctx, p)));
        overview.getChildren().add(UiKit.section(I18n.tr("ui.home.shortcuts"), quick));
    }

    // ------------------------------------------------------------------ settings

    private void refreshSettings() {
        settings.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        VBox game = UiKit.card(UiKit.label(I18n.tr("ui.projectPage.game"), "h3"));
        game.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.version"),
            I18n.tr("ui.projectPage.versionHint"), versionControl(p)));
        game.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.loader"),
            I18n.tr("ui.projectPage.loaderHint"), loaderControl(p)));
        Button verify = UiKit.button(I18n.tr("ui.projectPage.verify"), Icons.CHECK, () -> verify(p));
        game.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.repairTitle"), I18n.tr("ui.projectPage.repairHint"), verify));

        VBox java = UiKit.card(UiKit.label(I18n.tr("ui.projectPage.javaTitle"), "h3"));
        Spinner<Integer> max = new Spinner<>(1024, 65536, p.java.maxMemoryMb, 512);
        max.setEditable(true);
        max.setPrefWidth(120);
        max.valueProperty().addListener((o, a, b) -> save(x -> x.java.maxMemoryMb = b));
        java.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.maxMemory"), I18n.tr("ui.projectPage.maxMemoryHint"), max));
        Spinner<Integer> min = new Spinner<>(256, 65536, p.java.minMemoryMb, 256);
        min.setEditable(true);
        min.setPrefWidth(120);
        min.valueProperty().addListener((o, a, b) -> save(x -> x.java.minMemoryMb = b));
        java.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.minMemory"), null, min));
        TextField path = text(p.java.javaPath, I18n.tr("ui.wizard.javaAuto"), v -> save(x -> x.java.javaPath = v.isBlank() ? null : v));
        path.setPrefWidth(320);
        java.getChildren().add(UiKit.settingRow(I18n.tr("ui.wizard.javaPath"), I18n.tr("ui.wizard.javaHint"), path));
        TextField jvm = text(p.java.extraJvmArgs, "", v -> save(x -> x.java.extraJvmArgs = v));
        jvm.setPrefWidth(320);
        java.getChildren().add(UiKit.settingRow(I18n.tr("ui.wizard.jvmArgs"), I18n.tr("ui.wizard.jvmArgsHint"), jvm));
        TextField gameArgs = text(p.java.extraGameArgs, "", v -> save(x -> x.java.extraGameArgs = v));
        gameArgs.setPrefWidth(320);
        java.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.gameArgs"), null, gameArgs));

        VBox window = UiKit.card(UiKit.label(I18n.tr("ui.projectPage.windowTitle"), "h3"));
        Spinner<Integer> w = new Spinner<>(640, 7680, p.window.width, 10);
        w.setEditable(true);
        w.setPrefWidth(100);
        w.valueProperty().addListener((o, a, b) -> save(x -> x.window.width = b));
        Spinner<Integer> h = new Spinner<>(480, 4320, p.window.height, 10);
        h.setEditable(true);
        h.setPrefWidth(100);
        h.valueProperty().addListener((o, a, b) -> save(x -> x.window.height = b));
        HBox size = new HBox(6, w, UiKit.label("×"), h);
        size.setAlignment(Pos.CENTER_LEFT);
        window.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.windowSize"), null, size));
        ToggleSwitch full = new ToggleSwitch(p.window.fullscreen);
        full.selectedProperty().addListener((o, a, b) -> save(x -> x.window.fullscreen = b));
        window.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.fullscreen"), null, full));

        settings.getChildren().addAll(game, java, window);
    }

    private Node versionControl(Project p) {
        Label current = UiKit.label(p.minecraftVersion);
        current.setStyle("-fx-font-weight: bold;");
        Button change = UiKit.button(I18n.tr("ui.projectPage.change"), null, () -> Dialogs.text(ctx.overlay(),
            I18n.tr("ui.projectPage.changeVersion"), I18n.tr("ui.projectPage.changeVersionHint"), p.minecraftVersion,
            I18n.tr("ui.projectPage.change"), v -> v.isBlank() ? I18n.tr("ui.wizard.pickVersion") : null,
            v -> Tasks.quiet(ctx.overlay(), () -> ctx.services().versions().find(v, new dev.shardlight.client.task.CancellationToken()), found -> {
                if (found.isEmpty()) {
                    ctx.overlay().message(I18n.tr("ui.projectPage.changeVersion"), I18n.tr("ui.projectPage.unknownVersion", v));
                    return;
                }
                ctx.overlay().confirm(I18n.tr("ui.projectPage.changeVersion"), I18n.tr("ui.projectPage.changeVersionWarn", v),
                    List.of(I18n.tr("ui.projectPage.changeVersionBackup")), I18n.tr("ui.projectPage.change"), false,
                    () -> save(x -> {
                        x.minecraftVersion = v;
                        x.installedVersionId = null;
                        if (!x.loader.isVanilla()) {
                            x.loader.version = null;
                        }
                    }, true));
            })));
        HBox h = new HBox(10, current, change);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Node loaderControl(Project p) {
        ComboBox<LoaderType> type = new ComboBox<>();
        type.getItems().setAll(LoaderType.values());
        type.setValue(p.loader.isVanilla() ? LoaderType.VANILLA : p.loader.type);
        ComboBox<String> version = new ComboBox<>();
        version.setPrefWidth(150);
        Runnable loadVersions = () -> {
            LoaderType t = type.getValue();
            version.getItems().clear();
            version.setDisable(t == LoaderType.VANILLA);
            if (t == LoaderType.VANILLA) {
                return;
            }
            Tasks.quiet(ctx.overlay(), () -> ctx.services().loaders().versions(t, p.minecraftVersion,
                new dev.shardlight.client.task.CancellationToken()), list -> {
                version.getItems().setAll(list.stream().map(LoaderVersion::version).toList());
                String want = t == p.loader.type ? p.loader.version : list.stream().filter(LoaderVersion::recommended)
                    .map(LoaderVersion::version).findFirst().orElse(list.isEmpty() ? null : list.get(0).version());
                version.setValue(want);
            });
        };
        loadVersions.run();
        Button apply = UiKit.button(I18n.tr("common.save"), Icons.CHECK, () -> {
            LoaderType t = type.getValue();
            String v = version.getValue();
            if (t != LoaderType.VANILLA && (v == null || v.isBlank())) {
                ctx.overlay().toast("warning", I18n.tr("ui.wizard.pickLoaderVersion"));
                return;
            }
            LoaderSpec spec = t == LoaderType.VANILLA ? LoaderSpec.vanilla() : new LoaderSpec(t, v);
            List<String> details = new ArrayList<>();
            if (p.hasShardlight() && t != LoaderType.FABRIC) {
                details.add(I18n.tr("ui.projectPage.loaderShardlightWarn"));
            }
            details.add(I18n.tr("ui.projectPage.loaderModsWarn"));
            ctx.overlay().confirm(I18n.tr("ui.projectPage.changeLoader"), I18n.tr("ui.projectPage.changeLoaderTo", spec),
                details, I18n.tr("ui.projectPage.change"), false, () -> save(x -> {
                    x.loader = spec;
                    x.installedVersionId = null;
                }, true));
        });
        type.setOnAction(e -> loadVersions.run());
        HBox h = new HBox(8, type, version, apply);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void verify(Project p) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.projectPage.verifying", p.name), true, (pr, c) -> {
            String id = ctx.services().setup().installGame(p, pr, c);
            var report = ctx.services().installer().verify(id, pr, c);
            if (!report.isOk()) {
                ctx.services().installer().repair(id, pr, c);
                return I18n.tr("ui.projectPage.repaired", report.missing().size() + report.corrupt().size());
            }
            return I18n.tr("ui.projectPage.verified");
        }, msg -> ctx.overlay().toast("success", msg));
    }

    @FunctionalInterface
    private interface Change {
        void apply(Project p);
    }

    private void save(Change change) {
        save(change, false);
    }

    private void save(Change change, boolean refresh) {
        Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().update(projectId, change::apply), x -> {
            if (refresh) {
                ctx.projectsChanged();
                refreshHeader();
                refreshSettings();
            }
        });
    }

    private static TextField text(String value, String prompt, java.util.function.Consumer<String> onCommit) {
        TextField f = new TextField(value == null ? "" : value);
        f.setPromptText(prompt);
        Runnable commit = () -> onCommit.accept(f.getText() == null ? "" : f.getText().trim());
        f.setOnAction(e -> commit.run());
        f.focusedProperty().addListener((o, a, b) -> {
            if (!b) {
                commit.run();
            }
        });
        return f;
    }

    // ------------------------------------------------------------------ logs

    private void refreshLogs() {
        logs.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        Path gameDir = ctx.services().projects().gameDir(p);
        Path latest = gameDir.resolve("logs").resolve("latest.log");
        TextArea view = new TextArea();
        view.setEditable(false);
        view.getStyleClass().add("log-view");
        view.setPrefRowCount(18);
        view.setWrapText(false);
        view.setAccessibleText(I18n.tr("ui.logs.latest"));
        Tasks.quiet(ctx.overlay(), () -> tail(latest, 1500), text -> {
            view.setText(text.isEmpty() ? I18n.tr("ui.logs.none") : text);
            view.positionCaret(view.getLength());
        });
        Button analyze = UiKit.button(I18n.tr("ui.logs.analyze"), Icons.SEARCH, () -> analyze(latest));
        Button folder = UiKit.button(I18n.tr("ui.logs.openFolder"), Icons.FOLDER, () -> ctx.openFolder(gameDir.resolve("logs")));
        Button export = UiKit.button(I18n.tr("ui.diagnostics.export"), Icons.ARCHIVE, () -> PlayFlow.exportDiagnostics(ctx, p));
        logs.getChildren().addAll(new HBox(10, UiKit.label(I18n.tr("ui.logs.latest"), "h3"), UiKit.spacer(), analyze, folder,
            export), view);

        VBox crashes = new VBox(6);
        Path crashDir = gameDir.resolve("crash-reports");
        List<Path> reports = new ArrayList<>();
        if (Files.isDirectory(crashDir)) {
            try (Stream<Path> s = Files.list(crashDir)) {
                s.filter(f -> f.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing((Path f) -> f.toFile().lastModified()).reversed())
                    .limit(10).forEach(reports::add);
            } catch (IOException ignored) {
                // shown as "no crash reports"
            }
        }
        if (reports.isEmpty()) {
            crashes.getChildren().add(UiKit.label(I18n.tr("ui.logs.noCrashes"), "muted"));
        }
        for (Path r : reports) {
            Button a = UiKit.button(I18n.tr("ui.logs.analyze"), Icons.SEARCH, () -> analyze(r));
            HBox row = new HBox(10, UiKit.label(r.getFileName().toString()), UiKit.spacer(),
                UiKit.label(ProjectText.ago(r.toFile().lastModified()), "faint", "small"), a);
            row.setAlignment(Pos.CENTER_LEFT);
            crashes.getChildren().add(row);
        }
        logs.getChildren().add(UiKit.section(I18n.tr("ui.logs.crashReports"), crashes));
    }

    private void analyze(Path file) {
        Tasks.quiet(ctx.overlay(), () -> ctx.services().crashAnalyzer().analyze(file), findings -> {
            VBox body = new VBox(8);
            body.setPrefWidth(600);
            if (findings.isEmpty()) {
                body.getChildren().add(UiKit.label(I18n.tr("ui.logs.nothingFound"), "muted"));
            }
            for (Finding f : findings) {
                Object[] args = f.args() == null ? new Object[0] : f.args().toArray();
                VBox text = new VBox(2, UiKit.label(I18n.tr(f.titleKey(), args), "h3"),
                    UiKit.label(I18n.tr(f.detailKey(), args), "muted"));
                body.getChildren().add(UiKit.banner(f.severity().name().equals("ERROR") ? "danger"
                    : f.severity().name().equals("WARNING") ? "warning" : "info", text));
            }
            Button ok = UiKit.primary(I18n.tr("common.close"), null, () -> { });
            var d = ctx.overlay().show(dev.shardlight.client.app.ui.Overlay.modal(I18n.tr("ui.logs.analysisTitle",
                file.getFileName()), body, ok), true, true);
            ok.setOnAction(e -> d.close());
        });
    }

    /** the last {@code maxLines} lines of a text file ("" when it doesn't exist) */
    static String tail(Path file, int maxLines) throws IOException {
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
        String text = dev.shardlight.client.log.Redactor.redact(new String(data, StandardCharsets.UTF_8));
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - maxLines);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
