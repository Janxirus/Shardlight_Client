package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.flows.ProjectMoveDialog;
import dev.shardlight.client.app.flows.ModpackUpdateFlow;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Motion;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.PlayButton;
import dev.shardlight.client.app.ui.ProjectArt;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.TabStrip;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.loader.LoaderVersion;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectService;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One project: a wide header with its art, and eight tabs under a sliding underline. */
public final class ProjectPage implements Page {
    /** the tabs in order; their ids are what {@code AppContext.openProjectTab} takes */
    public static final List<String> TABS = List.of("overview", "content", "worlds", "screenshots", "backups", "performance",
        "logs", "settings");
    private static final List<String> TAB_KEYS = List.of("ui.projectPage.overview", "ui.projectPage.content",
        "ui.projectPage.worlds", "ui.projectPage.screenshots", "ui.projectPage.backups", "ui.projectPage.performance",
        "ui.projectPage.logs", "ui.projectPage.settings");

    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox();
    private final StackPane header = new StackPane();
    private final StackPane body = new StackPane();
    private final TabStrip strip;
    private final List<ScrollPane> panes = new ArrayList<>();
    private final List<Runnable> loaders = new ArrayList<>();
    private final OverviewTab overview;
    private final ContentTab content;
    private final WorldsTab worlds;
    private final ScreenshotsTab screenshots;
    private final BackupsTab backups;
    private final PresetPanel presets;
    private final LogsTab logs;
    private final VBox settings = new VBox();
    private String title = "";

    public ProjectPage(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
        this.overview = new OverviewTab(ctx, projectId);
        this.content = new ContentTab(ctx, projectId);
        this.worlds = new WorldsTab(ctx, projectId);
        this.screenshots = new ScreenshotsTab(ctx, projectId);
        this.backups = new BackupsTab(ctx, projectId);
        this.presets = new PresetPanel(ctx, projectId);
        this.logs = new LogsTab(ctx, projectId);
        settings.getStyleClass().add("stack");
        root.getStyleClass().add("project-page");
        header.getStyleClass().add("project-header");
        addTab(overview.view(), overview::refresh);
        addTab(content.view(), content::refresh);
        addTab(worlds.view(), worlds::refresh);
        addTab(screenshots.view(), screenshots::refresh);
        addTab(backups.view(), backups::refresh);
        addTab(presets.view(), presets::refresh);
        addTab(logs.view(), logs::refresh);
        addTab(settings, this::refreshSettings);
        strip = new TabStrip(TAB_KEYS.stream().map(I18n::tr).toList(), 0, this::show);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren().addAll(header, strip, body);
        showPane(0);
        ctx.play().onStateChanged(() -> {
            if (ctx.shell().current() == this) {
                refreshHeader();
            }
        });
    }

    private void addTab(Region content, Runnable load) {
        content.getStyleClass().add("tab-body");
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("content-scroll");
        panes.add(sp);
        loaders.add(load);
        body.getChildren().add(sp);
    }

    private void showPane(int index) {
        for (int i = 0; i < panes.size(); i++) {
            panes.get(i).setVisible(i == index);
            panes.get(i).setManaged(i == index);
        }
    }

    private void show(int index) {
        showPane(index);
        loaders.get(index).run();
        Motion.enter(panes.get(index), Motion.Speed.MEDIUM, Motion.ENTER_OFFSET);
    }

    public String projectId() {
        return projectId;
    }

    /** open a tab by index (see {@link #TABS}) */
    public ProjectPage select(int index) {
        if (index == strip.selected()) {
            showPane(index);
            loaders.get(index).run();
        } else {
            strip.select(index);
        }
        return this;
    }

    /** --capture only: the first screenshot in the full-size viewer */
    public void previewViewer() {
        screenshots.previewViewer();
    }

    /** --capture only: the crash analysis dialog */
    public void previewExplain() {
        logs.previewExplain();
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
        loaders.get(strip.selected()).run();
    }

    private void refreshHeader() {
        header.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            title = I18n.tr("ui.projectPage.gone");
            header.getChildren().add(UiKit.banner("warning", I18n.tr("ui.projectPage.goneBody")));
            strip.setDisable(true);
            body.setDisable(true);
            ctx.shell().refreshChrome();
            return;
        }
        Project p = found.get();
        title = p.name;
        ctx.shell().refreshChrome();
        var dir = ctx.services().projects().projectDir(p.id);
        ProjectArt art = ProjectArt.of(p, dir, 0, ThemeManager.em(11.4), 1);
        Region scrim = new Region();
        scrim.getStyleClass().add("hero-scrim");
        scrim.setMouseTransparent(true);

        Node icon = ProjectIcons.tile(p.icon, dir, ThemeManager.em(5.7));
        Label name = UiKit.label(p.name, "h1", "on-art");
        name.setWrapText(false);
        FlowPane chips = new FlowPane(
            UiKit.chip(I18n.tr("ui.project.minecraft", p.minecraftVersion), "on-art", Icons.CUBE),
            UiKit.chip(ProjectText.loader(p), "on-art", Icons.PACKAGE),
            UiKit.chip(ProjectText.preset(p), p.preset != null && p.preset.active == Preset.ULTRA_PERFORMANCE
                ? "warning" : "accent", Icons.BOLT),
            UiKit.chip(p.hasShardlight() ? ProjectText.modules(p) : I18n.tr("ui.home.noModules"), "on-art", Icons.SHARD));
        chips.getStyleClass().add("chip-row");
        VBox text = new VBox(name, chips);
        text.getStyleClass().add("header-text");

        Button play = PlayButton.of(ctx, p, "large");
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.library.moreFor", p.name), () -> { });
        more.getStyleClass().add("art-action");
        more.setOnAction(e -> menu(p).show(more, Side.BOTTOM, 0, 0));
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolder"),
            () -> ctx.openFolder(ctx.services().projects().gameDir(p)));
        folder.getStyleClass().add("art-action");
        HBox actions = new HBox(play, folder, more);
        actions.getStyleClass().add("header-actions");

        HBox content = new HBox(icon, text, UiKit.spacer(), actions);
        content.getStyleClass().add("hero-content");
        Button changeArt = UiKit.button(I18n.tr("ui.projectPage.changeArt"), Icons.IMAGE, () -> changeIcon(p), "art-action", "small");
        header.getChildren().addAll(art, scrim, content, changeArt);
        StackPane.setAlignment(content, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(changeArt, Pos.TOP_RIGHT);
        header.setAccessibleText(p.name);
    }

    private ContextMenu menu(Project p) {
        MenuItem select = item(I18n.tr("ui.library.makeDefault"), Icons.STAR, () -> ctx.selectProject(p.id));
        MenuItem rename = item(I18n.tr("ui.project.rename"), null, () -> ProjectActions.rename(ctx, p));
        MenuItem art = item(I18n.tr("ui.projectPage.changeArt"), Icons.IMAGE, () -> changeIcon(p));
        MenuItem generated = item(I18n.tr("ui.projectPage.generatedArt"), Icons.SPARKLE, () -> Tasks.quiet(ctx.overlay(),
            () -> ctx.services().projects().update(p.id, x -> x.icon = ProjectService.DEFAULT_ICON), x -> {
                ctx.projectsChanged();
                refreshHeader();
            }));
        generated.setDisable(p.icon == null || !p.icon.startsWith("file:"));
        MenuItem dup = item(I18n.tr("ui.project.duplicate"), Icons.COPY, () -> ProjectActions.duplicate(ctx, p));
        MenuItem export = item(I18n.tr("ui.project.export"), Icons.ARCHIVE, () -> ProjectActions.export(ctx, p));
        MenuItem mrpack = item(I18n.tr("ui.projectPage.exportModpack"), Icons.PACKAGE, () -> ModpackExportDialog.show(ctx, p));
        MenuItem backup = item(I18n.tr("ui.backup.now"), Icons.ARCHIVE, () -> ProjectActions.backupNow(ctx, p));
        MenuItem trash = item(I18n.tr("ui.project.trash"), Icons.TRASH,
            () -> ProjectActions.moveToTrash(ctx, p, () -> ctx.shell().navigate("library")));
        trash.getStyleClass().add("danger");
        return new ContextMenu(select, rename, art, generated, new SeparatorMenuItem(), dup, export, mrpack, backup,
            new SeparatorMenuItem(), trash);
    }

    private static MenuItem item(String text, String icon, Runnable r) {
        MenuItem m = new MenuItem(text);
        if (icon != null) {
            m.setGraphic(Icons.of(icon, 14));
        }
        m.setOnAction(e -> r.run());
        return m;
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

    // ------------------------------------------------------------------ settings

    private void refreshSettings() {
        settings.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        VBox game = UiKit.card(UiKit.label(I18n.tr("ui.projectPage.game"), "h3"));
        game.getStyleClass().add("stack-tight");
        game.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.version"),
            I18n.tr("ui.projectPage.versionHint"), versionControl(p)));
        game.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.loader"),
            I18n.tr("ui.projectPage.loaderHint"), loaderControl(p)));
        Button verify = UiKit.button(I18n.tr("ui.projectPage.verify"), Icons.CHECK, () -> verify(p));
        game.getChildren().add(UiKit.settingRow(I18n.tr("ui.projectPage.repairTitle"), I18n.tr("ui.projectPage.repairHint"), verify));

        VBox java = UiKit.card(UiKit.label(I18n.tr("ui.projectPage.javaTitle"), "h3"));
        java.getStyleClass().add("stack-tight");
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
        window.getStyleClass().add("stack-tight");
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

        VBox where = UiKit.card(UiKit.label(I18n.tr("ui.move.row"), "h3"));
        where.getStyleClass().add("stack-tight");
        Label gamePath = UiKit.label(ctx.services().projects().gameDir(p).toString(), "mono", "small");
        gamePath.setWrapText(true);
        Button move = UiKit.button(I18n.tr("ui.move.button"), Icons.FOLDER, () -> ProjectMoveDialog.show(ctx, p));
        where.getChildren().add(UiKit.settingRow(p.gameDirLocation == null ? I18n.tr("ui.move.here") : I18n.tr("ui.move.outside"),
            I18n.tr("ui.move.rowHint"), move));
        where.getChildren().add(gamePath);

        settings.getChildren().addAll(game, java, window, where);
        if (ctx.services().modpackUpdater().canUpdate(p)) {
            VBox pack = UiKit.card(UiKit.label(I18n.tr("ui.update.cardTitle"), "h3"));
            pack.getStyleClass().add("stack-tight");
            Button update = UiKit.button(I18n.tr("ui.update.button"), Icons.UPDATE, () -> ModpackUpdateFlow.start(ctx, p));
            pack.getChildren().add(UiKit.settingRow(I18n.tr("ui.update.rowTitle"), I18n.tr("ui.update.rowHint"), update));
            settings.getChildren().add(pack);
        }
    }

    private Node versionControl(Project p) {
        Label current = UiKit.label(p.minecraftVersion);
        current.getStyleClass().add("strong");
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
        type.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(LoaderType t) {
                return t == null ? "" : t.displayName();
            }

            @Override
            public LoaderType fromString(String s) {
                return null;
            }
        });
        type.setValue(p.loader.isVanilla() ? LoaderType.VANILLA : p.loader.type);
        ComboBox<String> version = new ComboBox<>();
        version.setPrefWidth(150);
        version.setAccessibleText(I18n.tr("ui.projectPage.loaderVersion"));
        Runnable loadVersions = () -> {
            LoaderType t = type.getValue();
            version.getItems().clear();
            version.setDisable(t == LoaderType.VANILLA);
            if (t == LoaderType.VANILLA) {
                return;
            }
            // show the project's own version straight away; the full list needs the loader's website
            if (t == p.loader.type && p.loader.version != null) {
                version.getItems().setAll(p.loader.version);
                version.setValue(p.loader.version);
            }
            // offline, the list simply keeps the project's own version
            dev.shardlight.client.app.ui.Fx.run(() -> ctx.services().loaders().versions(t, p.minecraftVersion,
                new dev.shardlight.client.task.CancellationToken()), list -> {
                List<String> ids = new ArrayList<>(list.stream().map(LoaderVersion::version).toList());
                String want = t == p.loader.type && p.loader.version != null ? p.loader.version
                    : list.stream().filter(LoaderVersion::recommended).map(LoaderVersion::version).findFirst()
                        .orElse(list.isEmpty() ? null : list.get(0).version());
                if (want != null && !ids.contains(want)) {
                    ids.add(0, want);
                }
                version.getItems().setAll(ids);
                version.setValue(want);
            }, err -> java.util.logging.Logger.getLogger(ProjectPage.class.getName())
                .fine("Couldn't load the " + t.displayName() + " versions: " + err));
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

}
