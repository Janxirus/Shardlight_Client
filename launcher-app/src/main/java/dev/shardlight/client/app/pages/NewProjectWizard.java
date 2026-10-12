package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.flows.ProjectCreation;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Disclosure;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.StepProgress;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.loader.LoaderVersion;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.VersionSummary;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.JavaSettings;
import dev.shardlight.client.project.NewProjectRequest;
import dev.shardlight.client.project.ProjectTemplate;
import dev.shardlight.client.project.ProjectTemplates;
import dev.shardlight.client.project.SuggestedContent;
import dev.shardlight.client.shardlight.ShardlightModule;
import dev.shardlight.client.shardlight.ShardlightTheme;
import dev.shardlight.client.task.CancellationToken;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The new-project wizard in three steps: template, basics (name, icon, Minecraft version) and review. The
 * loader, Shardlight modules, performance preset, Java and memory sit in a collapsed Advanced part of the
 * basics step with good defaults (Balanced, latest release, Fabric). The review lists exactly what will be
 * downloaded and its size. Nothing is downloaded until the player confirms.
 */
public final class NewProjectWizard {
    private static final List<String> STEP_KEYS = List.of("template", "basics", "review");

    private final AppContext ctx;
    private final Overlay.Dialog dialog;
    private final StepProgress progress = new StepProgress(
        STEP_KEYS.stream().map(k -> I18n.tr("ui.wizard.step." + k)).toList(), 0);
    private final StackPane content = new StackPane();
    private final Button back = new Button();
    private final Button next = new Button();
    private final Label stepTitle = UiKit.label("", "h2");
    private int step;

    // choices
    private ProjectTemplate template;
    private String name = "";
    private String icon = "builtin:grass";
    private Path iconFile;
    private String minecraftVersion;
    private LoaderType loader = LoaderType.FABRIC;
    private String loaderVersion;
    private final Set<String> modules = new LinkedHashSet<>();
    private String theme;
    private Preset preset = Preset.DEFAULT;
    private boolean vanillaVideo;
    private int memoryMb = 4096;
    private String javaPath;
    private String extraJvmArgs = "";
    private boolean installNow = true;
    private Path locationParent;
    private boolean advancedOpen;
    private boolean focusName;
    private final Set<SuggestedContent> chosenSuggestions = new LinkedHashSet<>();

    // loaded lists
    private List<VersionSummary> versions;
    private String versionsError;
    private boolean showSnapshots;
    private final Map<String, List<LoaderVersion>> loaderVersions = new HashMap<>();
    private final Set<String> loadingLoader = new LinkedHashSet<>();
    private String loaderError;
    private GameInstaller.InstallEstimate estimate;
    private String estimateVersion;
    private String estimateError;
    private boolean estimating;
    private final CancellationToken cancel = new CancellationToken();

    private final boolean preview;

    private NewProjectWizard(AppContext ctx, String templateId, boolean preview) {
        this.ctx = ctx;
        this.preview = preview;
        this.memoryMb = Math.min(ctx.services().settings().get().java.maxMemoryMb, defaultMaxMemory());
        back.setText(I18n.tr("ui.wizard.back"));
        back.setOnAction(e -> go(step - 1));
        next.getStyleClass().add("primary");
        next.setOnAction(e -> onNext());
        Button cancelButton = new Button(I18n.tr("common.cancel"));
        cancelButton.setCancelButton(true);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(430);
        scroll.setPrefViewportWidth(700);
        scroll.getStyleClass().add("content-scroll");
        VBox body = new VBox(12, progress, stepTitle, scroll);
        body.getStyleClass().add("wizard-body");
        VBox modal = Overlay.modal(I18n.tr("ui.wizard.title"), body, cancelButton, back, next);
        modal.setPrefWidth(780);
        this.dialog = ctx.overlay().show(modal, true, true);
        dialog.onClose(cancel::cancel);
        cancelButton.setOnAction(e -> dialog.close());

        useTemplate(ProjectTemplates.byId(templateId == null ? "" : templateId)
            .or(() -> ProjectTemplates.byId(ProjectTemplates.BALANCED)).orElseThrow());
        go(templateId == null || ProjectTemplates.byId(templateId).isEmpty() ? 0 : 1);
        if (!preview) {
            loadVersions();
        }
    }

    public static void show(AppContext ctx, String templateId) {
        new NewProjectWizard(ctx, templateId, false);
    }

    /**
     * --capture only: open on {@code step} with made-up version lists and download size, so the screenshots show
     * every step filled in. {@code loaded} false shows the version list as still loading.
     */
    public static void preview(AppContext ctx, String templateId, int step, boolean advanced, boolean loaded) {
        NewProjectWizard w = new NewProjectWizard(ctx, templateId, true);
        w.advancedOpen = advanced;
        if (loaded) {
            java.time.Instant now = java.time.Instant.parse("2026-10-01T00:00:00Z");
            w.versions = List.of(new VersionSummary("26.3", VersionSummary.RELEASE, null, null, now, 1),
                new VersionSummary("1.21.4", VersionSummary.RELEASE, null, null, now, 1));
            w.minecraftVersion = "26.3";
            w.loaderVersions.put(w.loader + "@26.3", List.of(new LoaderVersion("0.19.5", true, true),
                new LoaderVersion("0.19.4", true, false)));
            w.loaderVersion = "0.19.5";
            w.estimateVersion = "26.3";
            w.estimate = new GameInstaller.InstallEstimate(612_400_000L, 3_104, true);
        }
        w.go(step);
    }

    private static int defaultMaxMemory() {
        long total = totalMemoryMb();
        if (total <= 0) {
            return 4096;
        }
        // leave room for the system; 4 GB is plenty for most packs, never default above 8 GB
        return (int) Math.max(1024, Math.min(8192, (total / 2) / 512 * 512));
    }

    static long totalMemoryMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // not available on this runtime
        }
        return -1;
    }

    private static String iconFor(ProjectTemplate t) {
        return switch (t.id()) {
            case "shardlight-default" -> "shard";
            case "ultra-performance" -> "bolt";
            case "creative-building" -> "palette";
            case "pvp" -> "star";
            case "exploration" -> "world";
            case "modded-survival" -> "package";
            case "balanced" -> "gauge";
            default -> "grass";
        };
    }

    private void useTemplate(ProjectTemplate t) {
        String oldName = template == null ? "" : I18n.tr(template.nameKey());
        if (name.equals(oldName)) {
            name = "";
        }
        template = t;
        loader = t.suggestedLoader() == null ? LoaderType.VANILLA : t.suggestedLoader();
        loaderVersion = null;
        loaderError = null;
        preset = t.preset() == null || (t.preset() == Preset.DEFAULT && !ProjectTemplates.BALANCED.equals(t.id()))
            ? SettingsPage.defaultPreset(ctx.services().settings().get()) : t.preset();
        modules.clear();
        modules.addAll(t.preselectedModules());
        theme = t.shardlightTheme();
        chosenSuggestions.clear();
        advancedOpen = t.custom();
        if (name.isBlank()) {
            name = I18n.tr(t.nameKey());
        }
        icon = "builtin:" + iconFor(t);
        iconFile = null;
        loadLoaderVersions();
    }

    private void go(int target) {
        int clamped = Math.max(0, Math.min(STEP_KEYS.size() - 1, target));
        focusName = clamped == 1 && (clamped != step || content.getChildren().isEmpty());
        step = clamped;
        progress.setCurrent(step);
        stepTitle.setText(I18n.tr("ui.wizard.heading." + STEP_KEYS.get(step)));
        back.setDisable(step == 0);
        boolean last = step == STEP_KEYS.size() - 1;
        next.setText(I18n.tr(last ? "ui.wizard.create" : "ui.wizard.next"));
        content.getChildren().setAll(switch (step) {
            case 0 -> templateStep();
            case 1 -> basicsStep();
            default -> reviewStep();
        });
        if (last) {
            startEstimate(false);
        }
    }

    private boolean shardlightPossible() {
        return loader == LoaderType.FABRIC || loader == LoaderType.QUILT;
    }

    private void onNext() {
        String problem = step == 1 ? basicsProblem() : null;
        if (problem != null) {
            ctx.overlay().toast("warning", problem);
            return;
        }
        if (step == STEP_KEYS.size() - 1) {
            create();
        } else {
            go(step + 1);
        }
    }

    private String basicsProblem() {
        String nameProblem = ProjectActions.validateName(name);
        if (nameProblem != null) {
            return nameProblem;
        }
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return I18n.tr("ui.wizard.pickVersion");
        }
        if (loader != LoaderType.VANILLA && (loaderVersion == null || loaderVersion.isBlank())) {
            return I18n.tr(loadingLoader.isEmpty() ? "ui.wizard.pickLoaderVersion" : "ui.wizard.loaderStillLoading");
        }
        return null;
    }

    // ------------------------------------------------------------------ step 1: template

    private Node templateStep() {
        FlowPane grid = new FlowPane();
        grid.getStyleClass().add("template-grid");
        for (ProjectTemplate t : ProjectTemplates.all()) {
            Label n = UiKit.label(I18n.tr(t.nameKey()), "preset-name");
            Label d = UiKit.label(I18n.tr(t.descriptionKey()), "muted", "small");
            d.setWrapText(true);
            FlowPane chips = new FlowPane();
            chips.getStyleClass().add("chip-row");
            if (t.suggestedLoader() != null) {
                chips.getChildren().add(UiKit.chip(t.suggestedLoader().displayName(), null));
            }
            if (t.preset() != null) {
                chips.getChildren().add(UiKit.chip(I18n.tr(t.preset().nameKey()),
                    t.preset() == Preset.ULTRA_PERFORMANCE ? "warning" : null));
            }
            if (!t.preselectedModules().isEmpty()) {
                chips.getChildren().add(UiKit.chip(I18n.tr("ui.wizard.withShardlight"), "accent", Icons.SHARD));
            }
            Node art = ProjectIcons.builtin(iconFor(t)).<Node>map(b -> ProjectIcons.glyphTile(b, 44))
                .orElseGet(() -> new Region());
            VBox text = new VBox(4, n, d);
            HBox head = new HBox(12, art, text);
            HBox.setHgrow(text, Priority.ALWAYS);
            VBox card = new VBox(8, head, chips);
            card.getStyleClass().addAll("preset-card", "template-card");
            card.setFocusTraversable(true);
            card.setAccessibleText(n.getText() + ". " + d.getText());
            if (template != null && template.id().equals(t.id())) {
                card.getStyleClass().add("selected");
            }
            Runnable pick = () -> {
                if (template == null || !template.id().equals(t.id())) {
                    useTemplate(t);
                }
                go(0);
            };
            card.setOnMouseClicked(e -> pick.run());
            card.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.SPACE) {
                    pick.run();
                    e.consume();
                }
            });
            grid.getChildren().add(card);
        }
        return new VBox(10, UiKit.label(I18n.tr("ui.wizard.templateIntro"), "muted"), grid);
    }

    // ------------------------------------------------------------------ step 2: basics

    private Node basicsStep() {
        TextField field = new TextField(name);
        field.setAccessibleText(I18n.tr("ui.project.nameLabel"));
        Label problem = UiKit.label("", "danger-text", "small");
        problem.managedProperty().bind(problem.textProperty().isNotEmpty());
        field.textProperty().addListener((o, a, b) -> {
            name = b == null ? "" : b.trim();
            String p = ProjectActions.validateName(name);
            problem.setText(p == null ? "" : p);
        });
        VBox nameBox = new VBox(4, UiKit.label(I18n.tr("ui.project.nameLabel"), "strong"), field, problem);

        FlowPane icons = new FlowPane();
        icons.getStyleClass().add("chip-row");
        for (ProjectIcons.Builtin b : ProjectIcons.BUILTIN) {
            StackPane holder = new StackPane(ProjectIcons.glyphTile(b, 40));
            holder.getStyleClass().add("icon-choice");
            if (iconFile == null && icon.equals("builtin:" + b.id())) {
                holder.getStyleClass().add("selected");
            }
            holder.setFocusTraversable(true);
            holder.setAccessibleText(I18n.tr("ui.wizard.iconNamed", b.id()));
            Runnable pick = () -> {
                icon = "builtin:" + b.id();
                iconFile = null;
                go(1);
            };
            holder.setOnMouseClicked(e -> pick.run());
            holder.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                    pick.run();
                    e.consume();
                }
            });
            icons.getChildren().add(holder);
        }
        Button pickFile = UiKit.button(I18n.tr("ui.wizard.iconFromFile"), Icons.IMAGE, () -> {
            Path f = Dialogs.openFile(ctx.stage(), I18n.tr("ui.wizard.iconFromFile"), I18n.tr("ui.wizard.images"),
                "*.png", "*.jpg", "*.jpeg");
            if (f != null) {
                iconFile = f;
                go(1);
            }
        });
        Label fileLabel = UiKit.label(iconFile == null ? "" : I18n.tr("ui.wizard.iconChosen", iconFile.getFileName()),
            "muted", "small");
        VBox iconBox = new VBox(6, UiKit.label(I18n.tr("ui.wizard.icon"), "strong"), icons, new HBox(10, pickFile, fileLabel));
        ((HBox) iconBox.getChildren().get(2)).setAlignment(Pos.CENTER_LEFT);

        Disclosure advanced = new Disclosure(I18n.tr("ui.wizard.advanced"), advancedOpen, advancedContent());
        advanced.expandedProperty().addListener((o, a, b) -> advancedOpen = b);

        VBox page = new VBox(16, nameBox, iconBox, versionBox(), advanced);
        page.getStyleClass().add("wizard-page");
        if (focusName) {
            javafx.application.Platform.runLater(field::requestFocus);
        }
        return page;
    }

    private void loadVersions() {
        versionsError = null;
        Fx.run(() -> ctx.services().versions().versions(false, cancel), list -> {
            versions = list;
            versionsError = null;
            if (minecraftVersion == null) {
                minecraftVersion = list.stream().filter(VersionSummary::isRelease).map(VersionSummary::id).findFirst()
                    .orElse(null);
            }
            loadLoaderVersions();
            if (step == 1) {
                go(1);
            }
        }, err -> {
            versionsError = Tasks.message(err);
            if (step == 1) {
                go(1);
            }
        });
    }

    private Node versionBox() {
        VBox box = new VBox(6, UiKit.label(I18n.tr("ui.wizard.versionLabel"), "strong"));
        if (versions == null && versionsError == null) {
            box.getChildren().addAll(UiKit.skeleton("line"), UiKit.label(I18n.tr("ui.wizard.loadingVersions"), "muted", "small"));
            return box;
        }
        if (versionsError != null) {
            TextField manual = new TextField(minecraftVersion == null ? "" : minecraftVersion);
            manual.setAccessibleText(I18n.tr("ui.wizard.versionManual"));
            manual.setPromptText("1.21.4");
            manual.textProperty().addListener((o, a, b) -> {
                minecraftVersion = b == null ? null : b.trim();
                loaderVersion = null;
            });
            box.getChildren().addAll(UiKit.banner("warning", I18n.tr("ui.wizard.versionsFailed", versionsError),
                    UiKit.button(I18n.tr("ui.state.retry"), Icons.REFRESH, () -> {
                        versions = null;
                        versionsError = null;
                        go(1);
                        loadVersions();
                    })),
                UiKit.label(I18n.tr("ui.wizard.versionManual"), "muted", "small"), manual);
            return box;
        }
        ComboBox<VersionSummary> pick = new ComboBox<>();
        pick.setAccessibleText(I18n.tr("ui.wizard.versionLabel"));
        pick.setPrefWidth(260);
        pick.setCellFactory(l -> versionCell());
        pick.setButtonCell(versionCell());
        List<VersionSummary> shown = versions.stream().filter(v -> showSnapshots || v.isRelease()).toList();
        pick.getItems().setAll(shown);
        shown.stream().filter(v -> v.id().equals(minecraftVersion)).findFirst().ifPresent(pick::setValue);
        pick.setOnAction(e -> {
            VersionSummary v = pick.getValue();
            if (v != null && !v.id().equals(minecraftVersion)) {
                minecraftVersion = v.id();
                loaderVersion = null;
                loaderError = null;
                loadLoaderVersions();
            }
        });
        CheckBox snaps = new CheckBox(I18n.tr("ui.wizard.showSnapshots"));
        snaps.setSelected(showSnapshots);
        snaps.setOnAction(e -> {
            showSnapshots = snaps.isSelected();
            go(1);
        });
        String latest = versions.stream().filter(VersionSummary::isRelease).map(VersionSummary::id).findFirst().orElse("");
        HBox row = new HBox(12, pick, snaps);
        row.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(row,
            UiKit.label(I18n.tr(latest.equals(minecraftVersion) ? "ui.wizard.versionLatest" : "ui.wizard.versionOlder"),
                "muted", "small"));
        return box;
    }

    private static ListCell<VersionSummary> versionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(VersionSummary v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(v.id());
                setGraphic(v.isRelease() ? null
                    : UiKit.chip(I18n.tr(v.isSnapshot() ? "ui.wizard.snapshot" : "ui.wizard.oldVersion"), "warning"));
                setContentDisplay(ContentDisplay.RIGHT);
            }
        };
    }

    // ------------------------------------------------------------------ advanced (inside step 2)

    private Node[] advancedContent() {
        List<Node> out = new ArrayList<>();
        out.add(UiKit.label(I18n.tr("ui.wizard.advancedIntro"), "muted", "small"));
        out.add(loaderBox());
        if (shardlightPossible()) {
            out.add(shardlightBox());
        }
        out.add(presetBox());
        out.add(javaBox());
        out.add(locationBox());
        return out.toArray(new Node[0]);
    }

    private void loadLoaderVersions() {
        if (preview || loader == LoaderType.VANILLA || minecraftVersion == null || minecraftVersion.isBlank()) {
            return;
        }
        String key = loader + "@" + minecraftVersion;
        if (loaderVersions.containsKey(key)) {
            chooseLoaderVersion(loaderVersions.get(key));
            return;
        }
        if (!loadingLoader.add(key)) {
            return;
        }
        loaderError = null;
        LoaderType forType = loader;
        String forVersion = minecraftVersion;
        Fx.run(() -> ctx.services().loaders().versions(forType, forVersion, cancel), list -> {
            loadingLoader.remove(key);
            loaderVersions.put(key, list);
            if (loader == forType && forVersion.equals(minecraftVersion)) {
                chooseLoaderVersion(list);
                refreshLoaderViews();
            }
        }, err -> {
            loadingLoader.remove(key);
            if (loader == forType && forVersion.equals(minecraftVersion)) {
                loaderError = Tasks.message(err);
                refreshLoaderViews();
            }
        });
    }

    /** the version the player left alone: recommended, else stable, else newest */
    private void chooseLoaderVersion(List<LoaderVersion> list) {
        if (list.isEmpty()) {
            return;
        }
        loaderVersion = list.stream().filter(v -> v.version().equals(loaderVersion)).findFirst()
            .or(() -> list.stream().filter(LoaderVersion::recommended).findFirst())
            .or(() -> list.stream().filter(LoaderVersion::stable).findFirst())
            .orElse(list.get(0)).version();
    }

    /** redraw only when the loader part or the review is on screen, so typing a name is never interrupted */
    private void refreshLoaderViews() {
        if (step == 2 || (step == 1 && advancedOpen)) {
            go(step);
        }
    }

    private Node loaderBox() {
        ComboBox<LoaderType> type = new ComboBox<>();
        type.getItems().setAll(LoaderType.values());
        type.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(LoaderType t) {
                return t == null ? "" : t == LoaderType.VANILLA ? I18n.tr("ui.project.vanilla") : t.displayName();
            }

            @Override
            public LoaderType fromString(String string) {
                return loader;
            }
        });
        type.setValue(loader);
        type.setAccessibleText(I18n.tr("ui.wizard.step.loader"));
        type.setOnAction(e -> {
            LoaderType t = type.getValue();
            if (t != null && t != loader) {
                loader = t;
                loaderVersion = null;
                loaderError = null;
                if (!shardlightPossible()) {
                    modules.clear();
                    theme = null;
                }
                loadLoaderVersions();
                go(1);
            }
        });
        VBox box = new VBox(6, UiKit.label(I18n.tr("ui.wizard.step.loader"), "strong"),
            UiKit.label(I18n.tr("ui.wizard.loader." + loader.name().toLowerCase(Locale.ROOT)), "muted", "small"));
        HBox row = new HBox(12, type);
        row.setAlignment(Pos.CENTER_LEFT);
        if (loader != LoaderType.VANILLA) {
            ComboBox<LoaderVersion> pick = new ComboBox<>();
            pick.setAccessibleText(I18n.tr("ui.wizard.loaderVersion"));
            pick.setCellFactory(l -> loaderCell());
            pick.setButtonCell(loaderCell());
            pick.setPrefWidth(240);
            String key = loader + "@" + minecraftVersion;
            List<LoaderVersion> list = loaderVersions.get(key);
            if (list != null) {
                pick.getItems().setAll(list);
                list.stream().filter(v -> v.version().equals(loaderVersion)).findFirst().ifPresent(pick::setValue);
            }
            pick.setOnAction(e -> {
                if (pick.getValue() != null) {
                    loaderVersion = pick.getValue().version();
                }
            });
            row.getChildren().add(pick);
            String status = loaderError != null ? loaderError
                : list == null ? I18n.tr("ui.wizard.loadingLoader", loader.displayName())
                : list.isEmpty() ? I18n.tr("ui.wizard.loaderNone", loader.displayName(), minecraftVersion) : "";
            if (!status.isEmpty()) {
                row.getChildren().add(UiKit.label(status, loaderError != null ? "danger-text" : "muted", "small"));
            }
            if (loaderError != null) {
                row.getChildren().add(UiKit.button(I18n.tr("ui.state.retry"), Icons.REFRESH, () -> {
                    loaderError = null;
                    loadLoaderVersions();
                    go(1);
                }));
            }
        }
        box.getChildren().add(row);
        if (loader == LoaderType.FORGE || loader == LoaderType.NEOFORGE) {
            box.getChildren().add(UiKit.banner("warning", I18n.tr("ui.wizard.forgeWarning")));
        }
        if (ctx.services().loaders().provider(loader).map(p -> p.isExperimental()).orElse(false)) {
            box.getChildren().add(UiKit.chip(I18n.tr("ui.wizard.experimental"), "warning"));
        }
        return box;
    }

    private static ListCell<LoaderVersion> loaderCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(LoaderVersion v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText(null);
                    return;
                }
                String suffix = v.recommended() ? "  " + I18n.tr("ui.wizard.recommended")
                    : v.stable() ? "" : "  " + I18n.tr("ui.wizard.unstable");
                setText(v.version() + suffix);
            }
        };
    }

    private Node shardlightBox() {
        VBox box = new VBox(8, UiKit.label(I18n.tr("ui.wizard.step.shardlight"), "strong"),
            UiKit.label(I18n.tr("ui.wizard.shardlightIntro"), "muted", "small"));
        var catalog = ctx.services().shardlightCatalog();
        for (ShardlightModule m : catalog.modules()) {
            boolean supported = minecraftVersion != null && m.minecraftVersions().contains(minecraftVersion);
            ToggleSwitch sw = new ToggleSwitch(modules.contains(m.id()), I18n.tr(m.nameKey()));
            sw.setDisable(!supported);
            sw.selectedProperty().addListener((o, a, b) -> {
                if (b) {
                    modules.add(m.id());
                } else {
                    modules.remove(m.id());
                }
                if (m.id().equals("ui") && !b) {
                    theme = null;
                }
                go(1);
            });
            String desc = I18n.tr(m.descriptionKey());
            if (!supported) {
                desc = I18n.tr("ui.wizard.moduleUnsupported", String.join(", ", m.minecraftVersions())) + " " + desc;
            }
            box.getChildren().add(UiKit.settingRow(I18n.tr(m.nameKey()), desc, sw));
        }
        if (modules.contains("ui")) {
            ComboBox<String> themes = new ComboBox<>();
            themes.getItems().add("");
            catalog.themes().forEach(t -> themes.getItems().add(t.id()));
            themes.setCellFactory(l -> themeCell(catalog.themes()));
            themes.setButtonCell(themeCell(catalog.themes()));
            themes.setValue(theme == null ? "" : theme);
            themes.setOnAction(e -> theme = themes.getValue() == null || themes.getValue().isEmpty() ? null : themes.getValue());
            box.getChildren().add(UiKit.settingRow(I18n.tr("ui.wizard.theme"), I18n.tr("ui.wizard.themeHint"), themes));
        }
        if (!modules.isEmpty()) {
            box.getChildren().add(UiKit.banner("info", I18n.tr("ui.wizard.shardlightNeeds")));
        }
        return box;
    }

    private static ListCell<String> themeCell(List<ShardlightTheme> themes) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) {
                    setText(null);
                    return;
                }
                if (id.isEmpty()) {
                    setText(I18n.tr("ui.wizard.noTheme"));
                    return;
                }
                setText(themes.stream().filter(t -> t.id().equals(id)).findFirst()
                    .map(t -> t.displayName() + " (" + t.packTitle() + ")").orElse(id));
            }
        };
    }

    private Node presetBox() {
        ComboBox<Preset> pick = new ComboBox<>();
        pick.getItems().setAll(Preset.values());
        pick.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Preset p) {
                return p == null ? "" : I18n.tr(p.nameKey()) + (p == Preset.DEFAULT ? "  " + I18n.tr("ui.preset.default") : "");
            }

            @Override
            public Preset fromString(String string) {
                return preset;
            }
        });
        pick.setValue(preset);
        pick.setAccessibleText(I18n.tr("ui.wizard.step.preset"));
        Label about = UiKit.label(I18n.tr(preset.descriptionKey()), "muted", "small");
        pick.setOnAction(e -> {
            if (pick.getValue() != null) {
                preset = pick.getValue();
                about.setText(I18n.tr(preset.descriptionKey()));
            }
        });
        Button ultra = new Button(I18n.tr("ui.preset.ultraOneClick"));
        ultra.setGraphic(Icons.of(Icons.BOLT, 18));
        ultra.getStyleClass().add("ultra-button");
        ultra.setOnAction(e -> {
            preset = Preset.ULTRA_PERFORMANCE;
            go(1);
        });
        CheckBox vanilla = new CheckBox(I18n.tr("ui.preset.vanillaOptIn"));
        vanilla.setSelected(vanillaVideo);
        vanilla.setWrapText(true);
        vanilla.setOnAction(e -> vanillaVideo = vanilla.isSelected());
        VBox box = new VBox(6, UiKit.label(I18n.tr("ui.wizard.step.preset"), "strong"), new HBox(12, pick, ultra), about,
            vanilla, UiKit.label(I18n.tr("ui.preset.vanillaOptInHint"), "faint", "small"));
        ((HBox) box.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);
        if (modules.isEmpty()) {
            box.getChildren().add(UiKit.banner("info", I18n.tr("ui.wizard.presetWithoutShardlight")));
        }
        return box;
    }

    private Node javaBox() {
        long total = totalMemoryMb();
        int maxSlider = (int) Math.max(2048, Math.min(32768, total > 0 ? total - 1024 : 16384));
        Slider mem = new Slider(1024, maxSlider, Math.min(memoryMb, maxSlider));
        mem.setMajorTickUnit(1024);
        mem.setMinorTickCount(1);
        mem.setSnapToTicks(true);
        mem.setBlockIncrement(512);
        mem.setPrefWidth(320);
        mem.setAccessibleText(I18n.tr("ui.wizard.memory"));
        Label memLabel = UiKit.label(UiKit.bytes(memoryMb * 1024L * 1024L), "h3");
        mem.valueProperty().addListener((o, a, b) -> {
            memoryMb = (int) (Math.round(b.doubleValue() / 512.0) * 512);
            memLabel.setText(UiKit.bytes(memoryMb * 1024L * 1024L));
        });
        Label memHint = UiKit.label(total > 0 ? I18n.tr("ui.wizard.memoryHint", UiKit.bytes(total * 1024L * 1024L))
            : I18n.tr("ui.wizard.memoryHintUnknown"), "faint", "small");

        TextField path = new TextField(javaPath == null ? "" : javaPath);
        path.setPromptText(I18n.tr("ui.wizard.javaAuto"));
        path.setAccessibleText(I18n.tr("ui.wizard.javaPath"));
        path.textProperty().addListener((o, a, b) -> javaPath = b == null || b.isBlank() ? null : b.trim());
        Button browse = UiKit.button(I18n.tr("ui.wizard.browse"), Icons.FOLDER, () -> {
            Path f = Dialogs.openFile(ctx.stage(), I18n.tr("ui.wizard.javaPath"), I18n.tr("ui.wizard.javaFilter"),
                "java*", "javaw*");
            if (f != null) {
                path.setText(f.toString());
            }
        });
        HBox.setHgrow(path, Priority.ALWAYS);
        TextField jvm = new TextField(extraJvmArgs);
        jvm.setAccessibleText(I18n.tr("ui.wizard.jvmArgs"));
        jvm.textProperty().addListener((o, a, b) -> extraJvmArgs = b == null ? "" : b.trim());
        HBox memRow = new HBox(14, mem, memLabel);
        memRow.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6,
            UiKit.label(I18n.tr("ui.wizard.step.java"), "strong"),
            UiKit.label(I18n.tr("ui.wizard.memory"), "small"), memRow, memHint,
            UiKit.label(I18n.tr("ui.wizard.javaPath"), "small"), new HBox(8, path, browse),
            UiKit.label(I18n.tr("ui.wizard.javaHint"), "faint", "small"),
            UiKit.label(I18n.tr("ui.wizard.jvmArgs"), "small"), jvm,
            UiKit.label(I18n.tr("ui.wizard.jvmArgsHint"), "faint", "small"));
    }

    private Node locationBox() {
        Label where = UiKit.label("", "mono");
        where.setWrapText(true);
        Label problem = UiKit.label("", "danger-text", "small");
        Runnable show = () -> where.setText(locationParent == null ? I18n.tr("ui.wizard.locationDefault")
            : ctx.services().projects().suggestNewGameDir(locationParent, name).toString());
        show.run();
        Button useDefault = UiKit.button(I18n.tr("ui.wizard.locationUseDefault"), null, () -> { });
        useDefault.setVisible(locationParent != null);
        useDefault.setManaged(locationParent != null);
        Button choose = UiKit.button(I18n.tr("ui.wizard.locationChoose"), Icons.FOLDER, () -> {
            Path picked = Dialogs.chooseFolder(ctx.stage(), I18n.tr("ui.wizard.locationTitle"), locationParent);
            if (picked == null) {
                return;
            }
            if (picked.toAbsolutePath().normalize().startsWith(ctx.services().paths().root())) {
                problem.setText(I18n.tr("ui.move.insideData"));
                return;
            }
            problem.setText("");
            locationParent = picked;
            useDefault.setVisible(true);
            useDefault.setManaged(true);
            show.run();
        });
        useDefault.setOnAction(e -> {
            locationParent = null;
            problem.setText("");
            useDefault.setVisible(false);
            useDefault.setManaged(false);
            show.run();
        });
        HBox buttons = new HBox(8, choose, useDefault);
        return new VBox(6, UiKit.label(I18n.tr("ui.wizard.location"), "strong"), where, buttons, problem,
            UiKit.label(I18n.tr("ui.wizard.locationHint"), "faint", "small"));
    }

    // ------------------------------------------------------------------ step 3: review

    /** ask the game installer what is still missing for this version; the answer redraws the review */
    private void startEstimate(boolean force) {
        if (minecraftVersion == null || estimating || preview) {
            return;
        }
        if (!force && minecraftVersion.equals(estimateVersion) && (estimate != null || estimateError != null)) {
            return;
        }
        estimating = true;
        estimate = null;
        estimateError = null;
        estimateVersion = minecraftVersion;
        String v = minecraftVersion;
        Fx.run(() -> ctx.services().installer().estimate(v, cancel), result -> {
            estimating = false;
            if (v.equals(minecraftVersion)) {
                estimate = result;
            }
            if (step == 2) {
                go(2);
            }
        }, err -> {
            estimating = false;
            estimateError = Tasks.message(err);
            if (step == 2) {
                go(2);
            }
        });
    }

    private Node reviewStep() {
        VBox page = new VBox(16);
        page.getStyleClass().add("wizard-page");
        String loaderText = loader == LoaderType.VANILLA ? I18n.tr("ui.project.vanilla") : loader.displayName() + " " + loaderVersion;
        page.getChildren().add(UiKit.card(
            summaryRow(I18n.tr("ui.wizard.review.name"), name + "  ·  " + I18n.tr(template.nameKey())),
            summaryRow(I18n.tr("ui.wizard.review.minecraft"), minecraftVersion + "  ·  " + loaderText),
            summaryRow(I18n.tr("ui.wizard.review.shardlight"), shardlightSummary()),
            summaryRow(I18n.tr("ui.wizard.review.location"), locationParent == null ? I18n.tr("ui.wizard.locationDefault")
                : ctx.services().projects().suggestNewGameDir(locationParent, name).toString()),
            summaryRow(I18n.tr("ui.wizard.review.settings"), I18n.tr(preset.nameKey())
                + (vanillaVideo ? " (" + I18n.tr("ui.wizard.review.vanillaToo") + ")" : "") + "  ·  "
                + UiKit.bytes(memoryMb * 1024L * 1024L) + "  ·  "
                + (javaPath == null ? I18n.tr("ui.wizard.javaAuto") : javaPath))));

        page.getChildren().add(downloadList());

        if (!template.suggestions().isEmpty() && loader != LoaderType.VANILLA) {
            VBox suggestions = new VBox(6, UiKit.label(I18n.tr("ui.wizard.suggestions"), "h3"),
                UiKit.label(I18n.tr("ui.wizard.suggestionsHint"), "muted", "small"));
            for (SuggestedContent s : template.suggestions()) {
                CheckBox c = new CheckBox(s.projectSlug() + "  ·  " + I18n.tr(s.reasonKey()));
                c.setWrapText(true);
                c.setSelected(chosenSuggestions.contains(s));
                c.setOnAction(e -> {
                    if (c.isSelected()) {
                        chosenSuggestions.add(s);
                    } else {
                        chosenSuggestions.remove(s);
                    }
                    go(2);
                });
                suggestions.getChildren().add(c);
            }
            page.getChildren().add(suggestions);
        }
        CheckBox install = new CheckBox(I18n.tr("ui.wizard.installNow"));
        install.setSelected(installNow);
        install.setOnAction(e -> {
            installNow = install.isSelected();
            go(2);
        });
        page.getChildren().addAll(install, UiKit.label(I18n.tr("ui.wizard.installNowHint"), "muted", "small"));
        return page;
    }

    private String shardlightSummary() {
        if (modules.isEmpty() || !shardlightPossible()) {
            return I18n.tr("ui.shardlight.none");
        }
        return String.join(", ", modules.stream().map(m -> I18n.tr("ui.shardlight.module." + m)).toList())
            + (theme == null ? "" : "  ·  " + ctx.services().shardlightCatalog().theme(theme)
                .map(t -> t.displayName()).orElse(theme));
    }

    /** every download this project will start, with the size where it is known */
    private Node downloadList() {
        VBox list = new VBox(0);
        list.getStyleClass().add("download-preview");
        String later = I18n.tr("ui.wizard.review.sizeLater");

        String gameSize;
        String gameVariant = null;
        if (estimating || (estimate == null && estimateError == null)) {
            gameSize = I18n.tr("ui.wizard.review.checking");
        } else if (estimateError != null) {
            gameSize = I18n.tr("ui.wizard.review.checkFailed");
            gameVariant = "warning";
        } else if (estimate.files() == 0) {
            gameSize = I18n.tr("ui.wizard.review.alreadyHere");
            gameVariant = "success";
        } else {
            gameSize = (estimate.exact() ? "" : "≥ ") + UiKit.bytes(estimate.bytes());
        }
        list.getChildren().add(downloadRow(Icons.CUBE, I18n.tr("ui.wizard.review.dl.minecraft", minecraftVersion),
            estimate != null && estimate.files() > 0 ? I18n.tr("ui.wizard.review.dl.minecraftAbout", estimate.files())
                : I18n.tr("ui.wizard.review.dl.minecraftAboutShort"), gameSize, gameVariant));
        if (loader != LoaderType.VANILLA) {
            list.getChildren().add(downloadRow(Icons.PACKAGE, loader.displayName() + " " + loaderVersion,
                I18n.tr("ui.wizard.review.dl.loader"), later, null));
        }
        if (!modules.isEmpty() && shardlightPossible()) {
            for (String m : modules) {
                list.getChildren().add(downloadRow(Icons.SHARD, I18n.tr("ui.shardlight.module." + m),
                    I18n.tr("ui.wizard.review.dl.shardlight"), later, null));
            }
            list.getChildren().add(downloadRow(Icons.PACKAGE, "Fabric API", I18n.tr("ui.wizard.review.dl.fabricApi"), later, null));
        }
        for (SuggestedContent s : chosenSuggestions) {
            list.getChildren().add(downloadRow(Icons.PACKAGE, s.projectSlug(), I18n.tr(s.reasonKey()), later, null));
        }

        for (int i = 1; i < list.getChildren().size(); i++) {
            list.getChildren().get(i).getStyleClass().add("ruled");
        }
        VBox box = new VBox(10, UiKit.label(I18n.tr("ui.wizard.review.downloads"), "h3"));
        if (!installNow) {
            box.getChildren().add(UiKit.banner("info", I18n.tr("ui.wizard.review.notNow")));
        }
        box.getChildren().add(list);
        if (estimateError != null) {
            box.getChildren().add(UiKit.banner("warning", I18n.tr("ui.wizard.review.checkFailedAbout", estimateError),
                UiKit.button(I18n.tr("ui.state.retry"), Icons.REFRESH, () -> startEstimate(true))));
        } else if (estimate != null) {
            Label total = UiKit.label(estimate.files() == 0 ? I18n.tr("ui.wizard.review.totalNone")
                : I18n.tr(estimate.exact() ? "ui.wizard.review.total" : "ui.wizard.review.totalAtLeast",
                    UiKit.bytes(estimate.bytes())), "strong");
            box.getChildren().add(total);
            if (loader != LoaderType.VANILLA || !modules.isEmpty() || !chosenSuggestions.isEmpty()) {
                box.getChildren().add(UiKit.label(I18n.tr("ui.wizard.review.totalNote"), "muted", "small"));
            }
        }
        return box;
    }

    private static Node downloadRow(String icon, String title, String about, String size, String variant) {
        VBox text = new VBox(2, UiKit.label(title, "strong"), UiKit.label(about, "muted", "small"));
        HBox.setHgrow(text, Priority.ALWAYS);
        Node sizeNode = variant == null ? UiKit.label(size, "muted", "tabular") : UiKit.chip(size, variant);
        HBox row = new HBox(12, UiKit.iconBox(Icons.of(icon, 18), 34), text, sizeNode);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("download-preview-row");
        return row;
    }

    private static Node summaryRow(String label, String value) {
        Label l = UiKit.label(label, "muted");
        l.setMinWidth(130);
        Label v = UiKit.label(value == null ? "-" : value, "strong");
        v.setWrapText(true);
        HBox h = new HBox(12, l, v);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    // ------------------------------------------------------------------ create

    private void create() {
        JavaSettings java = new JavaSettings();
        java.maxMemoryMb = memoryMb;
        java.minMemoryMb = Math.min(512, memoryMb);
        java.javaPath = javaPath;
        java.extraJvmArgs = extraJvmArgs;
        LoaderSpec spec = loader == LoaderType.VANILLA ? LoaderSpec.vanilla() : new LoaderSpec(loader, loaderVersion);
        Set<String> chosenModules = shardlightPossible() ? Set.copyOf(modules) : Set.of();
        NewProjectRequest req = new NewProjectRequest(name, icon, template.id(), minecraftVersion, spec, chosenModules,
            chosenModules.contains("ui") ? theme : null, preset, java);
        List<SuggestedContent> suggestions = new ArrayList<>(chosenSuggestions);
        boolean vanilla = vanillaVideo;
        Path pictured = iconFile;
        boolean install = installNow;
        Path location = locationParent;
        dialog.close();
        ProjectCreation.run(ctx, req, vanilla, pictured, install, suggestions, location);
    }
}
