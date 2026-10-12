package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.ProjectCreation;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.loader.LoaderVersion;
import dev.shardlight.client.minecraft.VersionSummary;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.JavaSettings;
import dev.shardlight.client.project.NewProjectRequest;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectTemplate;
import dev.shardlight.client.project.ProjectTemplates;
import dev.shardlight.client.project.SuggestedContent;
import dev.shardlight.client.shardlight.ShardlightModule;
import dev.shardlight.client.shardlight.ShardlightTheme;
import dev.shardlight.client.task.CancellationToken;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The new-project wizard: template, name and icon, Minecraft version, mod loader, optional Shardlight
 * modules (none unless the player picks them, or picked the Shardlight template), performance preset,
 * Java and memory, then a review. Nothing is downloaded until the player confirms.
 */
public final class NewProjectWizard {
    private static final String[] STEP_KEYS = {"template", "name", "version", "loader", "shardlight", "preset", "java",
        "review"};

    private final AppContext ctx;
    private final Overlay.Dialog dialog;
    private final VBox steps = new VBox();
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
    private LoaderType loader = LoaderType.VANILLA;
    private String loaderVersion;
    private final Set<String> modules = new LinkedHashSet<>();
    private String theme;
    private Preset preset = Preset.DEFAULT;
    private boolean vanillaVideo;
    private int memoryMb = 4096;
    private String javaPath;
    private String extraJvmArgs = "";
    private boolean installNow = true;
    private final Set<SuggestedContent> chosenSuggestions = new LinkedHashSet<>();

    // loaded lists
    private List<VersionSummary> versions;
    private String versionsError;
    private boolean showSnapshots;
    private final java.util.Map<String, List<LoaderVersion>> loaderVersions = new java.util.HashMap<>();
    private final CancellationToken cancel = new CancellationToken();

    private NewProjectWizard(AppContext ctx, String templateId) {
        this.ctx = ctx;
        this.memoryMb = Math.min(ctx.services().settings().get().java.maxMemoryMb, defaultMaxMemory());
        steps.getStyleClass().add("wizard-steps");
        steps.setMinWidth(170);
        steps.setPadding(new Insets(4, 12, 4, 0));
        back.setText(I18n.tr("ui.wizard.back"));
        back.setOnAction(e -> go(step - 1));
        next.getStyleClass().add("primary");
        next.setDefaultButton(true);
        next.setOnAction(e -> onNext());
        Button cancelButton = new Button(I18n.tr("common.cancel"));
        cancelButton.setCancelButton(true);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(430);
        scroll.setPrefViewportWidth(620);
        scroll.getStyleClass().add("content-scroll");
        VBox right = new VBox(12, stepTitle, scroll);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(16, steps, right);
        VBox modal = Overlay.modal(I18n.tr("ui.wizard.title"), body, cancelButton, UiKit.spacer(), back, next);
        modal.setPrefWidth(880);
        this.dialog = ctx.overlay().show(modal, true, true);
        dialog.onClose(cancel::cancel);
        cancelButton.setOnAction(e -> dialog.close());

        ProjectTemplates.byId(templateId == null ? "" : templateId).ifPresent(this::useTemplate);
        go(template == null ? 0 : 1);
        loadVersions();
    }

    public static void show(AppContext ctx, String templateId) {
        new NewProjectWizard(ctx, templateId);
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

    private void useTemplate(ProjectTemplate t) {
        template = t;
        loader = t.suggestedLoader() == null ? LoaderType.VANILLA : t.suggestedLoader();
        loaderVersion = null;
        preset = t.preset() == null ? Preset.DEFAULT : t.preset();
        modules.clear();
        modules.addAll(t.preselectedModules());
        theme = t.shardlightTheme();
        chosenSuggestions.clear();
        if (name.isBlank()) {
            name = I18n.tr(t.nameKey());
        }
        icon = switch (t.id()) {
            case "shardlight-default" -> "builtin:shard";
            case "ultra-performance" -> "builtin:bolt";
            case "creative-building" -> "builtin:palette";
            case "pvp" -> "builtin:star";
            case "exploration" -> "builtin:world";
            case "modded-survival" -> "builtin:package";
            case "balanced" -> "builtin:gauge";
            default -> "builtin:grass";
        };
    }

    private void go(int target) {
        int s = Math.max(0, Math.min(STEP_KEYS.length - 1, target));
        if (s == 4 && !shardlightPossible()) {
            s = target > step ? 5 : 3;
        }
        step = s;
        steps.getChildren().clear();
        for (int i = 0; i < STEP_KEYS.length; i++) {
            Label l = UiKit.label((i + 1) + "  " + I18n.tr("ui.wizard.step." + STEP_KEYS[i]), "wizard-step");
            l.setMaxWidth(Double.MAX_VALUE);
            if (i == step) {
                l.getStyleClass().add("current");
            } else if (i < step) {
                l.getStyleClass().add("done");
            }
            if (i == 4 && !shardlightPossible()) {
                l.setText((i + 1) + "  " + I18n.tr("ui.wizard.step.shardlight") + "  " + I18n.tr("ui.wizard.skipped"));
            }
            steps.getChildren().add(l);
        }
        stepTitle.setText(I18n.tr("ui.wizard.heading." + STEP_KEYS[step]));
        back.setDisable(step == 0);
        next.setText(I18n.tr(step == STEP_KEYS.length - 1 ? "ui.wizard.create" : "ui.wizard.next"));
        content.getChildren().setAll(switch (step) {
            case 0 -> templateStep();
            case 1 -> nameStep();
            case 2 -> versionStep();
            case 3 -> loaderStep();
            case 4 -> shardlightStep();
            case 5 -> presetStep();
            case 6 -> javaStep();
            default -> reviewStep();
        });
    }

    private boolean shardlightPossible() {
        return loader == LoaderType.FABRIC || loader == LoaderType.QUILT;
    }

    private void onNext() {
        String problem = switch (step) {
            case 0 -> template == null ? I18n.tr("ui.wizard.pickTemplate") : null;
            case 1 -> ProjectActions.validateName(name);
            case 2 -> minecraftVersion == null || minecraftVersion.isBlank() ? I18n.tr("ui.wizard.pickVersion") : null;
            case 3 -> loader != LoaderType.VANILLA && (loaderVersion == null || loaderVersion.isBlank())
                ? I18n.tr("ui.wizard.pickLoaderVersion") : null;
            default -> null;
        };
        if (problem != null) {
            ctx.overlay().toast("warning", problem);
            return;
        }
        if (step == STEP_KEYS.length - 1) {
            create();
        } else {
            go(step + 1);
        }
    }

    // ------------------------------------------------------------------ steps

    private Node templateStep() {
        FlowPane grid = new FlowPane(10, 10);
        for (ProjectTemplate t : ProjectTemplates.all()) {
            Label n = UiKit.label(I18n.tr(t.nameKey()), "preset-name");
            Label d = UiKit.label(I18n.tr(t.descriptionKey()), "muted", "small");
            FlowPane chips = new FlowPane(5, 5);
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
            VBox card = new VBox(6, n, d, chips);
            card.getStyleClass().add("preset-card");
            card.setPrefWidth(196);
            card.setMinHeight(130);
            card.setFocusTraversable(true);
            card.setAccessibleText(n.getText() + ". " + d.getText());
            if (template != null && template.id().equals(t.id())) {
                card.getStyleClass().add("selected");
            }
            Runnable pick = () -> {
                String oldName = template == null ? "" : I18n.tr(template.nameKey());
                if (name.equals(oldName)) {
                    name = "";
                }
                useTemplate(t);
                go(1);
            };
            card.setOnMouseClicked(e -> pick.run());
            card.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    pick.run();
                }
            });
            grid.getChildren().add(card);
        }
        return new VBox(10, UiKit.label(I18n.tr("ui.wizard.templateIntro"), "muted"), grid);
    }

    private Node nameStep() {
        TextField field = new TextField(name);
        field.setAccessibleText(I18n.tr("ui.project.nameLabel"));
        field.textProperty().addListener((o, a, b) -> name = b == null ? "" : b.trim());
        Label problem = UiKit.label("", "danger-text", "small");
        field.textProperty().addListener((o, a, b) -> {
            String p = ProjectActions.validateName(b == null ? "" : b.trim());
            problem.setText(p == null ? "" : p);
        });
        FlowPane icons = new FlowPane(8, 8);
        for (ProjectIcons.Builtin b : ProjectIcons.BUILTIN) {
            Node tile = ProjectIcons.glyphTile(b, 46);
            StackPane holder = new StackPane(tile);
            holder.getStyleClass().add("icon-choice");
            if (icon.equals("builtin:" + b.id())) {
                holder.getStyleClass().add("selected");
            }
            holder.setFocusTraversable(true);
            holder.setAccessibleText(I18n.tr("ui.wizard.iconNamed", b.id()));
            holder.setOnMouseClicked(e -> {
                icon = "builtin:" + b.id();
                iconFile = null;
                go(1);
            });
            icons.getChildren().add(holder);
        }
        Button pick = UiKit.button(I18n.tr("ui.wizard.iconFromFile"), Icons.IMAGE, () -> {
            Path f = Dialogs.openFile(ctx.stage(), I18n.tr("ui.wizard.iconFromFile"), I18n.tr("ui.wizard.images"),
                "*.png", "*.jpg", "*.jpeg");
            if (f != null) {
                iconFile = f;
                go(1);
            }
        });
        Label fileLabel = UiKit.label(iconFile == null ? "" : I18n.tr("ui.wizard.iconChosen", iconFile.getFileName()),
            "muted", "small");
        Platform.later(field::requestFocus);
        return new VBox(10, UiKit.label(I18n.tr("ui.project.nameLabel"), "small"), field, problem,
            UiKit.label(I18n.tr("ui.wizard.icon"), "small"), icons, new HBox(10, pick, fileLabel));
    }

    private void loadVersions() {
        Fx.run(() -> ctx.services().versions().versions(false, cancel), list -> {
            versions = list;
            versionsError = null;
            if (minecraftVersion == null) {
                minecraftVersion = list.stream().filter(VersionSummary::isRelease).map(VersionSummary::id).findFirst()
                    .orElse(null);
            }
            if (step == 2) {
                go(2);
            }
        }, err -> {
            versionsError = Tasks.message(err);
            if (step == 2) {
                go(2);
            }
        });
    }

    private Node versionStep() {
        VBox box = new VBox(10);
        if (versions == null && versionsError == null) {
            box.getChildren().add(UiKit.label(I18n.tr("ui.wizard.loadingVersions"), "muted"));
            return box;
        }
        if (versionsError != null) {
            TextField manual = new TextField(minecraftVersion == null ? "" : minecraftVersion);
            manual.setAccessibleText(I18n.tr("ui.wizard.versionManual"));
            manual.textProperty().addListener((o, a, b) -> minecraftVersion = b == null ? null : b.trim());
            box.getChildren().addAll(UiKit.banner("warning", versionsError,
                    UiKit.button(I18n.tr("common.retry"), Icons.REFRESH, () -> {
                        versionsError = null;
                        versions = null;
                        go(2);
                        loadVersions();
                    })),
                UiKit.label(I18n.tr("ui.wizard.versionManual"), "small"), manual);
            return box;
        }
        CheckBox snaps = new CheckBox(I18n.tr("ui.wizard.showSnapshots"));
        snaps.setSelected(showSnapshots);
        TextField filter = new TextField();
        filter.setPromptText(I18n.tr("ui.wizard.filterVersions"));
        filter.setAccessibleText(I18n.tr("ui.wizard.filterVersions"));
        ListView<VersionSummary> list = new ListView<>();
        list.setPrefHeight(320);
        list.setCellFactory(l -> new ListCell<>() {
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
                setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            }
        });
        Runnable fill = () -> {
            String q = filter.getText() == null ? "" : filter.getText().trim();
            list.getItems().setAll(versions.stream()
                .filter(v -> showSnapshots || v.isRelease())
                .filter(v -> q.isEmpty() || v.id().contains(q)).toList());
            list.getItems().stream().filter(v -> v.id().equals(minecraftVersion)).findFirst().ifPresent(v -> {
                list.getSelectionModel().select(v);
                list.scrollTo(v);
            });
        };
        snaps.setOnAction(e -> {
            showSnapshots = snaps.isSelected();
            fill.run();
        });
        filter.textProperty().addListener((o, a, b) -> fill.run());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && !b.id().equals(minecraftVersion)) {
                minecraftVersion = b.id();
                loaderVersion = null;
            }
        });
        fill.run();
        HBox top = new HBox(10, filter, snaps);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filter, Priority.ALWAYS);
        box.getChildren().addAll(UiKit.label(I18n.tr("ui.wizard.versionIntro"), "muted"), top, list);
        return box;
    }

    private Node loaderStep() {
        VBox box = new VBox(10, UiKit.label(I18n.tr("ui.wizard.loaderIntro", minecraftVersion), "muted"));
        FlowPane cards = new FlowPane(10, 10);
        for (LoaderType t : LoaderType.values()) {
            boolean experimental = ctx.services().loaders().provider(t).map(p -> p.isExperimental()).orElse(false);
            Label n = UiKit.label(t.displayName(), "preset-name");
            Label d = UiKit.label(I18n.tr("ui.wizard.loader." + t.name().toLowerCase(Locale.ROOT)), "muted", "small");
            VBox card = new VBox(6, n, d);
            if (experimental) {
                card.getChildren().add(UiKit.chip(I18n.tr("ui.wizard.experimental"), "warning"));
            }
            card.getStyleClass().add("preset-card");
            card.setPrefWidth(190);
            card.setFocusTraversable(true);
            card.setAccessibleText(n.getText() + ". " + d.getText());
            if (t == loader) {
                card.getStyleClass().add("selected");
            }
            card.setOnMouseClicked(e -> {
                if (loader != t) {
                    loader = t;
                    loaderVersion = null;
                    if (!shardlightPossible()) {
                        modules.clear();
                    }
                }
                go(3);
            });
            cards.getChildren().add(card);
        }
        box.getChildren().add(cards);
        if (loader != LoaderType.VANILLA) {
            ComboBox<LoaderVersion> pick = new ComboBox<>();
            pick.setAccessibleText(I18n.tr("ui.wizard.loaderVersion"));
            pick.setCellFactory(l -> loaderCell());
            pick.setButtonCell(loaderCell());
            pick.setPrefWidth(320);
            Label status = UiKit.label(I18n.tr("ui.wizard.loadingLoader", loader.displayName()), "muted", "small");
            String key = loader + "@" + minecraftVersion;
            Runnable fill = () -> {
                List<LoaderVersion> list = loaderVersions.get(key);
                pick.getItems().setAll(list);
                if (list.isEmpty()) {
                    status.setText(I18n.tr("ui.wizard.loaderNone", loader.displayName(), minecraftVersion));
                    return;
                }
                status.setText("");
                LoaderVersion chosen = list.stream().filter(v -> v.version().equals(loaderVersion)).findFirst()
                    .or(() -> list.stream().filter(LoaderVersion::recommended).findFirst())
                    .or(() -> list.stream().filter(LoaderVersion::stable).findFirst())
                    .orElse(list.get(0));
                pick.setValue(chosen);
                loaderVersion = chosen.version();
            };
            pick.setOnAction(e -> {
                if (pick.getValue() != null) {
                    loaderVersion = pick.getValue().version();
                }
            });
            if (loaderVersions.containsKey(key)) {
                fill.run();
            } else {
                LoaderType forType = loader;
                Fx.run(() -> ctx.services().loaders().versions(forType, minecraftVersion, cancel), list -> {
                    loaderVersions.put(key, list);
                    if (loader == forType) {
                        fill.run();
                    }
                }, err -> status.setText(Tasks.message(err)));
            }
            box.getChildren().addAll(UiKit.label(I18n.tr("ui.wizard.loaderVersion"), "small"), new HBox(10, pick, status));
            if (loader == LoaderType.FORGE || loader == LoaderType.NEOFORGE) {
                box.getChildren().add(UiKit.banner("warning", I18n.tr("ui.wizard.forgeWarning")));
            }
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

    private Node shardlightStep() {
        VBox box = new VBox(12, UiKit.label(I18n.tr("ui.wizard.shardlightIntro"), "muted"));
        var catalog = ctx.services().shardlightCatalog();
        for (ShardlightModule m : catalog.modules()) {
            boolean supported = m.minecraftVersions().contains(minecraftVersion);
            ToggleSwitch sw = new ToggleSwitch(modules.contains(m.id()));
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
                go(4);
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

    private Node presetStep() {
        VBox box = new VBox(12, UiKit.label(I18n.tr("ui.wizard.presetIntro"), "muted"));
        Button ultra = new Button(I18n.tr("ui.preset.ultraOneClick"));
        ultra.setGraphic(Icons.of(Icons.BOLT, 18));
        ultra.getStyleClass().add("ultra-button");
        if (preset == Preset.ULTRA_PERFORMANCE) {
            ultra.getStyleClass().add("active");
        }
        ultra.setOnAction(e -> {
            preset = Preset.ULTRA_PERFORMANCE;
            go(5);
        });
        FlowPane cards = new FlowPane(10, 10);
        for (Preset p : Preset.values()) {
            VBox card = new VBox(4, UiKit.label(I18n.tr(p.nameKey()), "preset-name"),
                UiKit.label(I18n.tr(p.descriptionKey()), "muted", "small"));
            if (p == Preset.DEFAULT) {
                card.getChildren().add(UiKit.chip(I18n.tr("ui.preset.default"), "accent"));
            }
            card.getStyleClass().add("preset-card");
            card.setPrefWidth(190);
            card.setFocusTraversable(true);
            if (p == preset) {
                card.getStyleClass().add("selected");
            }
            card.setOnMouseClicked(e -> {
                preset = p;
                go(5);
            });
            cards.getChildren().add(card);
        }
        CheckBox vanilla = new CheckBox(I18n.tr("ui.preset.vanillaOptIn"));
        vanilla.setSelected(vanillaVideo);
        vanilla.setWrapText(true);
        vanilla.setOnAction(e -> vanillaVideo = vanilla.isSelected());
        box.getChildren().addAll(ultra, cards, vanilla, UiKit.label(I18n.tr("ui.preset.vanillaOptInHint"), "faint", "small"));
        if (modules.isEmpty()) {
            box.getChildren().add(UiKit.banner("info", I18n.tr("ui.wizard.presetWithoutShardlight")));
        }
        return box;
    }

    private Node javaStep() {
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
        return new VBox(10,
            UiKit.label(I18n.tr("ui.wizard.memory"), "small"), new HBox(14, mem, memLabel), memHint,
            UiKit.label(I18n.tr("ui.wizard.javaPath"), "small"), new HBox(8, path, browse),
            UiKit.label(I18n.tr("ui.wizard.javaHint"), "faint", "small"),
            UiKit.label(I18n.tr("ui.wizard.jvmArgs"), "small"), jvm,
            UiKit.label(I18n.tr("ui.wizard.jvmArgsHint"), "faint", "small"));
    }

    private Node reviewStep() {
        VBox box = new VBox(10);
        box.getChildren().add(row(I18n.tr("ui.wizard.review.template"), I18n.tr(template.nameKey())));
        box.getChildren().add(row(I18n.tr("ui.wizard.review.name"), name));
        box.getChildren().add(row(I18n.tr("ui.wizard.review.minecraft"), minecraftVersion));
        box.getChildren().add(row(I18n.tr("ui.wizard.review.loader"),
            loader == LoaderType.VANILLA ? I18n.tr("ui.project.vanilla") : loader.displayName() + " " + loaderVersion));
        box.getChildren().add(row(I18n.tr("ui.wizard.review.shardlight"), modules.isEmpty() ? I18n.tr("ui.shardlight.none")
            : String.join(", ", modules.stream().map(m -> I18n.tr("ui.shardlight.module." + m)).toList())
            + (theme == null ? "" : "  ·  " + theme)));
        box.getChildren().add(row(I18n.tr("ui.wizard.review.preset"), I18n.tr(preset.nameKey())
            + (vanillaVideo ? "  ·  " + I18n.tr("ui.wizard.review.vanillaToo") : "")));
        box.getChildren().add(row(I18n.tr("ui.wizard.review.memory"), UiKit.bytes(memoryMb * 1024L * 1024L)));
        box.getChildren().add(row(I18n.tr("ui.wizard.review.java"), javaPath == null ? I18n.tr("ui.wizard.javaAuto") : javaPath));

        if (!template.suggestions().isEmpty() && loader != LoaderType.VANILLA) {
            box.getChildren().add(UiKit.label(I18n.tr("ui.wizard.suggestions"), "h3"));
            box.getChildren().add(UiKit.label(I18n.tr("ui.wizard.suggestionsHint"), "faint", "small"));
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
                });
                box.getChildren().add(c);
            }
        }
        CheckBox install = new CheckBox(I18n.tr("ui.wizard.installNow"));
        install.setSelected(installNow);
        install.setOnAction(e -> installNow = install.isSelected());
        box.getChildren().addAll(new javafx.scene.control.Separator(), install,
            UiKit.label(I18n.tr("ui.wizard.installNowHint"), "faint", "small"));
        return box;
    }

    private static Node row(String label, String value) {
        Label l = UiKit.label(label, "muted");
        l.setMinWidth(150);
        Label v = UiKit.label(value == null ? "-" : value);
        v.setStyle("-fx-font-weight: bold;");
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
        dialog.close();
        ProjectCreation.run(ctx, req, vanilla, pictured, install, suggestions);
    }


    /** tiny helper so focus requests run after the step is on screen */
    private static final class Platform {
        static void later(Runnable r) {
            javafx.application.Platform.runLater(r);
        }
    }
}
