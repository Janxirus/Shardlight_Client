package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.shardlight.LibraryItem;
import dev.shardlight.client.shardlight.ShardlightModule;
import dev.shardlight.client.shardlight.ShardlightTheme;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The optional Shardlight add-ons for one project: pick modules and a theme, apply them (deselected
 * modules are removed from the project), tune performance presets, and manage the local module library.
 */
public final class ShardlightPage implements Page {
    private final AppContext ctx;
    private final VBox root = new VBox(18);
    private final ComboBox<Project> target = new ComboBox<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private String pendingTheme;
    private String loadedFor;
    private PresetPanel presets;

    public ShardlightPage(AppContext ctx) {
        this.ctx = ctx;
        target.setCellFactory(l -> ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setButtonCell(ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setPrefWidth(300);
        target.setAccessibleText(I18n.tr("ui.shardlight.project"));
        target.setOnAction(e -> {
            Project p = target.getValue();
            if (p != null && !p.id.equals(loadedFor)) {
                ctx.selectProject(p.id);
                load(p);
            }
        });
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.shardlight");
    }

    @Override
    public Region view() {
        return root;
    }

    @Override
    public Node actions() {
        return new HBox(8, UiKit.label(I18n.tr("ui.shardlight.project"), "muted"), target);
    }

    @Override
    public void onShow() {
        List<Project> projects = ctx.services().projects().list();
        target.getItems().setAll(projects);
        Optional<Project> sel = ctx.selectedProject();
        target.setValue(sel.flatMap(s -> projects.stream().filter(p -> p.id.equals(s.id)).findFirst()).orElse(null));
        loadedFor = null;
        sel.ifPresentOrElse(this::load, this::renderNoProject);
    }

    private void load(Project p) {
        loadedFor = p.id;
        pending.clear();
        pending.addAll(p.shardlightModules);
        pendingTheme = p.shardlightTheme;
        presets = new PresetPanel(ctx, p.id);
        render();
    }

    private Optional<Project> project() {
        return loadedFor == null ? Optional.empty() : ctx.services().projects().get(loadedFor);
    }

    private void renderNoProject() {
        root.getChildren().setAll(intro(), UiKit.emptyState(Icons.SHARD, I18n.tr("ui.shardlight.noProjectTitle"),
            I18n.tr("ui.shardlight.noProjectBody"), UiKit.primary(I18n.tr("ui.shardlight.createProject"), Icons.PLUS,
                () -> ctx.newProject("shardlight-default"))), library());
    }

    private Node intro() {
        VBox v = new VBox(6, UiKit.label(I18n.tr("ui.shardlight.introTitle"), "h2"),
            UiKit.label(I18n.tr("ui.shardlight.intro"), "muted"));
        v.setMaxWidth(860);
        return v;
    }

    private void render() {
        root.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            renderNoProject();
            return;
        }
        Project p = found.get();
        root.getChildren().add(intro());
        boolean fabric = p.loader.type == LoaderType.FABRIC || p.loader.type == LoaderType.QUILT;
        if (!fabric) {
            root.getChildren().add(UiKit.banner("info", I18n.tr("ui.shardlight.needsFabric", p.name, ProjectText.loader(p)),
                UiKit.button(I18n.tr("ui.shardlight.createProject"), Icons.PLUS, () -> ctx.newProject("shardlight-default"))));
        } else {
            root.getChildren().addAll(modules(p), themes(p), applyBar(p));
        }
        VBox perf = new VBox(10, UiKit.label(I18n.tr("ui.shardlight.performance"), "h2"),
            UiKit.label(I18n.tr("ui.shardlight.performanceIntro"), "muted"), presets.view());
        presets.refresh();
        root.getChildren().addAll(perf, library());
    }

    private Node modules(Project p) {
        FlowPane cards = new FlowPane(12, 12);
        var library = ctx.services().shardlightLibrary();
        for (ShardlightModule m : ctx.services().shardlightCatalog().modules()) {
            ToggleSwitch sw = new ToggleSwitch(pending.contains(m.id()));
            sw.setAccessibleText(I18n.tr(m.nameKey()));
            sw.selectedProperty().addListener((o, a, b) -> {
                if (b) {
                    pending.add(m.id());
                } else {
                    pending.remove(m.id());
                }
                render();
            });
            Label name = UiKit.label(I18n.tr(m.nameKey()), "h3");
            HBox head = new HBox(10, name, UiKit.spacer(), sw);
            head.setAlignment(Pos.CENTER_LEFT);
            Label desc = UiKit.label(I18n.tr(m.descriptionKey()), "muted", "small");
            FlowPane chips = new FlowPane(5, 5);
            boolean installed = p.shardlightModules.contains(m.id());
            if (installed) {
                chips.getChildren().add(UiKit.chip(I18n.tr("ui.shardlight.inProject"), "success", Icons.CHECK));
            }
            Optional<LibraryItem> jar = library.moduleJar(m.id(), p.minecraftVersion);
            chips.getChildren().add(jar.isPresent()
                ? UiKit.chip(I18n.tr("ui.shardlight.ready", jar.get().version()), null, Icons.PACKAGE)
                : UiKit.chip(I18n.tr("ui.shardlight.notImported"), "warning", Icons.WARNING));
            VBox card = new VBox(8, head, desc, chips);
            card.getStyleClass().add("card");
            if (pending.contains(m.id())) {
                card.getStyleClass().add("selected");
            }
            card.setPrefWidth(300);
            cards.getChildren().add(card);
        }
        return UiKit.section(I18n.tr("ui.shardlight.modules"), UiKit.label(I18n.tr("ui.shardlight.modulesHint"), "faint", "small"),
            cards);
    }

    private Node themes(Project p) {
        if (!pending.contains("ui")) {
            return UiKit.section(I18n.tr("ui.shardlight.themes"), UiKit.label(I18n.tr("ui.shardlight.themesNeedUi"), "muted"));
        }
        FlowPane grid = new FlowPane(10, 10);
        grid.getChildren().add(swatch(null, I18n.tr("ui.wizard.noTheme"), I18n.tr("ui.shardlight.noThemeHint"), null));
        for (ShardlightTheme t : ctx.services().shardlightCatalog().themes()) {
            grid.getChildren().add(swatch(t.id(), t.displayName(), t.packTitle() + (t.classic() ? "  ·  " + I18n.tr("ui.shardlight.classic") : ""),
                t));
        }
        return UiKit.section(I18n.tr("ui.shardlight.themes"), UiKit.label(I18n.tr("ui.shardlight.themesHint"), "faint", "small"), grid);
    }

    private Node swatch(String id, String name, String sub, ShardlightTheme t) {
        StackPane colour = new StackPane();
        colour.setPrefHeight(52);
        colour.setMinHeight(52);
        if (t != null) {
            colour.setStyle("-fx-background-radius: 0.6em; -fx-background-color: linear-gradient(to bottom right, "
                + safe(t.panel()) + " 0%, " + safe(t.accent()) + " 70%, " + safe(t.edge()) + " 100%);");
        } else {
            colour.getStyleClass().add("icon-box");
            colour.getChildren().add(Icons.of(Icons.CLOSE, 18));
        }
        VBox box = new VBox(6, colour, UiKit.label(name, "preset-name"), UiKit.label(sub, "faint", "small"));
        box.getStyleClass().add("theme-swatch");
        if (Objects.equals(id, pendingTheme)) {
            box.getStyleClass().add("selected");
        }
        box.setFocusTraversable(true);
        box.setAccessibleText(name + ". " + sub);
        Runnable pick = () -> {
            pendingTheme = id;
            render();
        };
        box.setOnMouseClicked(e -> pick.run());
        box.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                pick.run();
            }
        });
        if (t != null && t.description() != null) {
            UiKit.tooltip(box, t.description());
        }
        return box;
    }

    private static String safe(String hex) {
        return hex != null && hex.matches("#[0-9a-fA-F]{6}") ? hex : "#444444";
    }

    private Node applyBar(Project p) {
        boolean changed = !pending.equals(new LinkedHashSet<>(p.shardlightModules)) || !Objects.equals(pendingTheme, p.shardlightTheme)
            || pending.stream().anyMatch(m -> p.content.stream().noneMatch(e -> e.source.name().equals("SHARDLIGHT")
            && m.equals(e.projectId)));
        VBox box = new VBox(8);
        box.getStyleClass().add("card");
        try {
            var report = ctx.services().shardlight().check(p, Set.copyOf(pending));
            for (String e : report.errors()) {
                box.getChildren().add(UiKit.banner("danger", e));
            }
            for (String w : report.warnings()) {
                box.getChildren().add(UiKit.banner("warning", w));
            }
            for (String i : report.info()) {
                box.getChildren().add(UiKit.label("• " + i, "muted", "small"));
            }
        } catch (Exception e) {
            box.getChildren().add(UiKit.banner("danger", Tasks.message(e)));
        }
        Label summary = UiKit.label(pending.isEmpty() ? I18n.tr("ui.shardlight.applyNone")
            : I18n.tr("ui.shardlight.applySome", String.join(", ", pending.stream().map(m -> I18n.tr("ui.shardlight.module." + m)).toList())),
            "muted");
        HBox.setHgrow(summary, Priority.ALWAYS);
        Button apply = UiKit.primary(I18n.tr("ui.shardlight.apply"), Icons.CHECK, () -> apply(p));
        apply.setDisable(!changed);
        Button reset = UiKit.button(I18n.tr("ui.shardlight.undoChanges"), null, () -> load(p));
        reset.setDisable(!changed);
        HBox row = new HBox(10, summary, reset, apply);
        row.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(row, UiKit.label(I18n.tr("ui.shardlight.applyHint"), "faint", "small"));
        return box;
    }

    private void apply(Project p) {
        Set<String> chosen = Set.copyOf(pending);
        String theme = chosen.contains("ui") ? pendingTheme : null;
        if (ctx.play().isRunning(p.id)) {
            ctx.overlay().message(I18n.tr("ui.shardlight.apply"), I18n.tr("ui.project.closeGameFirst"));
            return;
        }
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.shardlight.applying", p.name), true,
            (pr, c) -> ctx.services().setup().applyShardlight(p, chosen, theme, pr, c), outcome -> {
                var r = outcome.result();
                ctx.overlay().toast("success", I18n.tr("ui.shardlight.applied", r.added().size(), r.removed().size()));
                if (!outcome.installedDependencies().isEmpty()) {
                    ctx.overlay().toast("info", I18n.tr("ui.shardlight.depsInstalled", String.join(", ", outcome.installedDependencies())));
                }
                for (String w : outcome.warnings()) {
                    ctx.overlay().toast("warning", w);
                }
                ctx.projectsChanged();
                ctx.services().projects().get(p.id).ifPresent(this::load);
            });
    }

    // ------------------------------------------------------------------ library

    private Node library() {
        VBox box = new VBox(10);
        var lib = ctx.services().shardlightLibrary();
        List<LibraryItem> items = lib.items();
        Button importFiles = UiKit.primary(I18n.tr("ui.shardlight.import"), Icons.ARCHIVE, this::importFiles);
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolder"),
            () -> ctx.openFolder(ctx.services().paths().content()));
        FlowPane downloads = new FlowPane(8, 8);
        for (ShardlightModule m : ctx.services().shardlightCatalog().modules()) {
            downloads.getChildren().add(UiKit.button(I18n.tr("ui.shardlight.downloadLatest", I18n.tr(m.nameKey())), Icons.DOWNLOAD,
                () -> Tasks.withProgress(ctx.overlay(), I18n.tr("ui.shardlight.downloading", I18n.tr(m.nameKey())), true,
                    (pr, c) -> lib.downloadLatestRelease(m.id(), pr, c), item -> {
                        ctx.overlay().toast("success", item.map(i -> I18n.tr("ui.shardlight.downloaded", i.version()))
                            .orElse(I18n.tr("ui.shardlight.nothingNew")));
                        render();
                    })));
        }
        HBox bar = new HBox(8, importFiles, UiKit.spacer(), folder);
        bar.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(UiKit.label(I18n.tr("ui.shardlight.libraryTitle"), "h2"),
            UiKit.label(I18n.tr("ui.shardlight.libraryIntro"), "muted"), bar, downloads);
        if (items.isEmpty()) {
            box.getChildren().add(UiKit.label(I18n.tr("ui.shardlight.libraryEmpty"), "faint"));
        }
        for (LibraryItem i : items) {
            Label name = UiKit.label(itemName(i), "project-name");
            Label sub = UiKit.label(I18n.tr("ui.shardlight.itemRow", i.version() == null ? "-" : i.version(), UiKit.bytes(i.size()),
                ProjectText.ago(i.importedAt())), "faint", "small");
            VBox text = new VBox(2, name, sub);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button remove = UiKit.iconButton(Icons.TRASH, I18n.tr("ui.shardlight.removeItem"), () -> ctx.overlay().confirm(
                I18n.tr("ui.shardlight.removeItemTitle"), I18n.tr("ui.shardlight.removeItemMessage", itemName(i)), List.of(),
                I18n.tr("common.delete"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                    lib.remove(i.id());
                    return true;
                }, x -> render())));
            HBox row = new HBox(10, UiKit.iconBox(Icons.of("module".equals(i.kind()) ? Icons.PACKAGE : Icons.PALETTE, 16), 34), text,
                remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().addAll("card", "flat");
            box.getChildren().add(row);
        }
        return box;
    }

    private String itemName(LibraryItem i) {
        return switch (i.kind()) {
            case "module" -> I18n.tr("ui.shardlight.module." + i.moduleOrThemeId());
            case "sound-pack" -> I18n.tr("ui.shardlight.soundPack", themeName(i.moduleOrThemeId()));
            default -> I18n.tr("ui.shardlight.uiPack", themeName(i.moduleOrThemeId()));
        };
    }

    private String themeName(String id) {
        return ctx.services().shardlightCatalog().theme(id).map(ShardlightTheme::displayName).orElse(id);
    }

    private void importFiles() {
        List<Path> files = Dialogs.openFiles(ctx.stage(), I18n.tr("ui.shardlight.import"), I18n.tr("ui.shardlight.importFilter"),
            "*.jar", "*.zip");
        if (files.isEmpty()) {
            return;
        }
        var lib = ctx.services().shardlightLibrary();
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.shardlight.importing"), true, (pr, c) -> {
            int added = 0;
            java.util.List<String> skipped = new java.util.ArrayList<>();
            for (Path f : files) {
                pr.progress("ui.shardlight.importing", f.getFileName().toString(), added, files.size());
                var r = lib.importFile(f, c);
                added += r.added().size();
                skipped.addAll(r.skipped());
            }
            return new Imported(added, skipped);
        }, r -> {
            ctx.overlay().toast(r.added() > 0 ? "success" : "warning", I18n.tr("ui.shardlight.imported", r.added()));
            if (!r.skipped().isEmpty()) {
                ctx.overlay().message(I18n.tr("ui.shardlight.skippedTitle"), String.join("\n", r.skipped()));
            }
            render();
        });
    }

    private record Imported(int added, List<String> skipped) {
    }

}
