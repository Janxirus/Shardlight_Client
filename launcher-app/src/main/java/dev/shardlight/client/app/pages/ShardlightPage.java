package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Cards;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
 * The optional Shardlight add-ons for one project. The four modules are feature cards with a switch for the
 * current project, and the fifteen themes are large tiles grouped Classic and New; both apply as soon as you
 * choose, with an Undo toast. Below them sit the performance presets and the local module library.
 */
public final class ShardlightPage implements Page {
    private final AppContext ctx;
    private final VBox root = new VBox();
    private final StackPane view = new StackPane(root);
    private final ComboBox<Project> target = new ComboBox<>();
    private String loadedFor;
    private PresetPanel presets;

    public ShardlightPage(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().addAll("shardlight-page", "page-column", "stack");
        StackPane.setAlignment(root, Pos.TOP_CENTER);
        target.setCellFactory(l -> ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setButtonCell(ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setPrefWidth(ThemeManager.em(21));
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
        return view;
    }

    @Override
    public Node actions() {
        Label name = UiKit.label(I18n.tr("ui.shardlight.project"), "muted");
        name.setWrapText(false);
        name.setMinWidth(Region.USE_PREF_SIZE);
        HBox box = new HBox(name, target);
        box.getStyleClass().add("button-row");
        return box;
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
        return UiKit.label(I18n.tr("ui.shardlight.intro"), "muted", "page-intro");
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
            root.getChildren().add(modules(p));
            root.getChildren().addAll(report(p));
            root.getChildren().add(themes(p));
        }
        VBox perf = new VBox(UiKit.label(I18n.tr("ui.shardlight.performance"), "h2"),
            UiKit.label(I18n.tr("ui.shardlight.performanceIntro"), "muted"), presets.view());
        perf.getStyleClass().add("stack-tight");
        presets.refresh();
        root.getChildren().addAll(perf, library());
    }

    // ------------------------------------------------------------------ modules

    private Node modules(Project p) {
        FlowPane cards = new FlowPane();
        cards.getStyleClass().add("module-grid");
        var library = ctx.services().shardlightLibrary();
        for (ShardlightModule m : ctx.services().shardlightCatalog().modules()) {
            boolean on = p.shardlightModules.contains(m.id());
            Optional<LibraryItem> jar;
            try {
                jar = library.moduleJar(m.id(), p.minecraftVersion);
            } catch (LauncherException e) {
                jar = Optional.empty();
            }
            cards.getChildren().add(moduleCard(p, m, on, jar));
        }
        return UiKit.section(I18n.tr("ui.shardlight.modules"), UiKit.label(I18n.tr("ui.shardlight.modulesHint"), "faint", "small"),
            cards);
    }

    private Node moduleCard(Project p, ShardlightModule m, boolean on, Optional<LibraryItem> jar) {
        String name = I18n.tr(m.nameKey());
        ImageView art = new ImageView(new Image(getClass().getResource("/app/modules/" + m.id() + ".png").toExternalForm(),
            ThemeManager.em(5.5) * 2, 0, true, true));
        art.setFitWidth(ThemeManager.em(5.5));
        art.setFitHeight(ThemeManager.em(5.5));
        art.setPreserveRatio(true);
        StackPane artBox = new StackPane(art);
        artBox.getStyleClass().add("module-art");
        VBox titles = new VBox(UiKit.label(name, "h3"), UiKit.label(I18n.tr("ui.shardlight.module." + m.id() + ".about"), "muted"));
        titles.getStyleClass().add("card-head-text");
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox head = new HBox(artBox, titles);
        head.getStyleClass().add("module-head");

        FlowPane chips = new FlowPane();
        chips.getStyleClass().add("chip-row");
        if (on) {
            chips.getChildren().add(UiKit.chip(I18n.tr("ui.shardlight.inProject"), "success", Icons.CHECK));
        }
        chips.getChildren().add(jar.isPresent()
            ? UiKit.chip(I18n.tr("ui.shardlight.version", jar.get().version()), null, Icons.PACKAGE)
            : UiKit.chip(I18n.tr("ui.shardlight.notImported"), "warning", Icons.WARNING));

        ToggleSwitch sw = new ToggleSwitch(on, I18n.tr("ui.shardlight.useIn", name, p.name));
        Label state = UiKit.label(I18n.tr(on ? "ui.shardlight.on" : "ui.shardlight.off"), "strong");
        HBox toggle = new HBox(sw, state);
        toggle.getStyleClass().add("button-row");
        Node switchNode = toggle;
        if (jar.isEmpty() && !on) {
            sw.setDisable(true);
            switchNode = new HBox(UiKit.button(I18n.tr("ui.shardlight.getModule"), Icons.DOWNLOAD, () -> download(m)));
        } else {
            sw.selectedProperty().addListener((o, a, b) -> setModule(p, m, b));
        }
        Button whatsNew = UiKit.button(I18n.tr("ui.shardlight.whatsNew"), Icons.EXTERNAL, () -> ctx.openUrl(releasesUrl(m)), "ghost");
        UiKit.tooltip(whatsNew, I18n.tr("ui.shardlight.whatsNewTip"));
        whatsNew.setAccessibleText(I18n.tr("ui.shardlight.whatsNewFor", name));
        HBox foot = new HBox(switchNode, UiKit.spacer(), whatsNew);
        foot.getStyleClass().add("card-foot-row");
        VBox card = new VBox(head, chips, foot);
        card.getStyleClass().addAll("card", "module-card");
        if (on) {
            card.getStyleClass().add("selected");
        }
        return card;
    }

    private static String releasesUrl(ShardlightModule m) {
        String page = m.pageUrl() == null ? "https://github.com/Janxirus" : m.pageUrl();
        return page.endsWith("/") ? page + "releases" : page + "/releases";
    }

    private void download(ShardlightModule m) {
        var lib = ctx.services().shardlightLibrary();
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.shardlight.downloading", I18n.tr(m.nameKey())), true,
            (pr, c) -> lib.downloadLatest(m.id(), pr, c), item -> {
                ctx.overlay().toast(item.isPresent() ? "success" : "info", item.map(i -> I18n.tr("ui.shardlight.downloaded", i.version()))
                    .orElse(I18n.tr("ui.shardlight.nothingNew")));
                render();
            }, err -> {
                if (err instanceof LauncherException e && e.code() == ErrorCode.NOT_FOUND
                    && "shardlight.download.notPublished".equals(e.messageKey())) {
                    // neither a Modrinth project nor a public GitHub release exists yet: say so and offer the local jar
                    ctx.overlay().confirm(I18n.tr("ui.shardlight.notPublishedTitle"),
                        I18n.tr("ui.shardlight.notPublished", I18n.tr(m.nameKey())), List.of(),
                        I18n.tr("ui.shardlight.import"), false, this::importFiles);
                } else {
                    Tasks.showError(ctx.overlay(), I18n.tr("ui.shardlight.downloading", I18n.tr(m.nameKey())), err);
                }
            });
    }

    /** problems and notes about the project's current set of modules */
    private List<Node> report(Project p) {
        List<Node> out = new java.util.ArrayList<>();
        try {
            var r = ctx.services().shardlight().check(p, Set.copyOf(p.shardlightModules));
            for (var e : r.errors()) {
                out.add(UiKit.banner("danger", e.text()));
            }
            for (var w : r.warnings()) {
                out.add(UiKit.banner("warning", w.text()));
            }
        } catch (Exception e) {
            out.add(UiKit.banner("danger", Tasks.message(e)));
        }
        return out;
    }

    private void setModule(Project p, ShardlightModule m, boolean enable) {
        Set<String> before = new LinkedHashSet<>(p.shardlightModules);
        Set<String> next = new LinkedHashSet<>(before);
        if (enable) {
            next.add(m.id());
        } else {
            next.remove(m.id());
        }
        try {
            var r = ctx.services().shardlight().check(p, Set.copyOf(next));
            if (!r.errors().isEmpty()) {
                ctx.overlay().message(I18n.tr("ui.shardlight.cantUse", I18n.tr(m.nameKey())),
                    String.join("\n", r.errors().stream().map(e -> e.text()).toList()));
                render();
                return;
            }
        } catch (Exception e) {
            Tasks.showError(ctx.overlay(), I18n.tr(m.nameKey()), e);
            render();
            return;
        }
        String theme = next.contains("ui") ? p.shardlightTheme : null;
        applyNow(p, next, theme, before, p.shardlightTheme,
            I18n.tr(enable ? "ui.shardlight.turnedOn" : "ui.shardlight.turnedOff", I18n.tr(m.nameKey()), p.name));
    }

    // ------------------------------------------------------------------ themes

    private Node themes(Project p) {
        if (!p.shardlightModules.contains("ui")) {
            return UiKit.section(I18n.tr("ui.shardlight.themes"), UiKit.label(I18n.tr("ui.shardlight.themesNeedUi"), "muted"));
        }
        List<ShardlightTheme> all = ctx.services().shardlightCatalog().themes();
        FlowPane classic = new FlowPane();
        classic.getStyleClass().add("theme-grid");
        classic.getChildren().add(themeTile(p, null, I18n.tr("ui.wizard.noTheme"), I18n.tr("ui.shardlight.noThemeHint"), null));
        FlowPane fresh = new FlowPane();
        fresh.getStyleClass().add("theme-grid");
        for (ShardlightTheme t : all) {
            (t.classic() ? classic : fresh).getChildren().add(themeTile(p, t.id(), t.displayName(), t.packTitle(), t));
        }
        VBox box = new VBox(UiKit.label(I18n.tr("ui.shardlight.classicThemes"), "h3"), classic,
            UiKit.label(I18n.tr("ui.shardlight.newThemes"), "h3"), fresh);
        box.getStyleClass().add("stack");
        return UiKit.section(I18n.tr("ui.shardlight.themes"), UiKit.label(I18n.tr("ui.shardlight.themesHint"), "faint", "small"), box);
    }

    private Node themeTile(Project p, String id, String name, String sub, ShardlightTheme t) {
        Node picture;
        if (t != null) {
            Image logo = new Image(getClass().getResource("/app/themes/" + t.id().toLowerCase(java.util.Locale.ROOT) + ".png")
                .toExternalForm(), ThemeManager.em(5) * 2, 0, true, false);
            ImageView v = new ImageView(logo);
            v.setFitWidth(ThemeManager.em(5));
            v.setFitHeight(ThemeManager.em(5));
            v.setPreserveRatio(true);
            v.setSmooth(false);
            picture = v;
        } else {
            picture = Icons.of(Icons.SPARKLE, 32);
            picture.getStyleClass().add("faint");
        }
        boolean selected = Objects.equals(id, p.shardlightTheme);
        VBox card = Cards.theme(name, picture, selected);
        card.getStyleClass().add("theme-tile");
        card.getChildren().add(UiKit.label(sub, "faint", "small"));
        card.setAccessibleText(name + ", " + sub + (selected ? ", " + I18n.tr("ui.shardlight.selected") : ""));
        if (t != null && t.description() != null) {
            UiKit.tooltip(card, t.description());
        }
        Cards.onOpen(card, () -> {
            if (!selected) {
                setTheme(p, id, name);
            }
        });
        return card;
    }

    private void setTheme(Project p, String id, String name) {
        applyNow(p, new LinkedHashSet<>(p.shardlightModules), id, new LinkedHashSet<>(p.shardlightModules), p.shardlightTheme,
            I18n.tr("ui.shardlight.themeApplied", name, p.name));
    }

    // ------------------------------------------------------------------ applying

    /** apply at once; the toast offers Undo, which applies the previous modules and theme the same way */
    private void applyNow(Project p, Set<String> modules, String theme, Set<String> undoModules, String undoTheme, String doneText) {
        if (ctx.play().isRunning(p.id)) {
            ctx.overlay().message(I18n.tr("ui.shardlight.changeBlocked"), I18n.tr("ui.project.closeGameFirst"));
            render();
            return;
        }
        Set<String> chosen = Set.copyOf(modules);
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.shardlight.applying", p.name), true,
            (pr, c) -> ctx.services().setup().applyShardlight(p, chosen, theme, pr, c), outcome -> {
                if (!outcome.installedDependencies().isEmpty()) {
                    ctx.overlay().toast("info", I18n.tr("ui.shardlight.depsInstalled", String.join(", ", outcome.installedDependencies())));
                }
                for (String w : outcome.warnings()) {
                    ctx.overlay().toast("warning", w);
                }
                ctx.overlay().undo(doneText, () -> {
                    Set<String> back = Set.copyOf(undoModules);
                    Tasks.withProgress(ctx.overlay(), I18n.tr("ui.shardlight.applying", p.name), true,
                        (pr2, c2) -> ctx.services().setup().applyShardlight(p, back, back.contains("ui") ? undoTheme : null, pr2, c2),
                        o2 -> {
                            ctx.projectsChanged();
                            ctx.services().projects().get(p.id).ifPresent(this::load);
                        }, err -> {
                            Tasks.showError(ctx.overlay(), p.name, err);
                            ctx.services().projects().get(p.id).ifPresent(this::load);
                        });
                });
                ctx.projectsChanged();
                ctx.services().projects().get(p.id).ifPresent(this::load);
            }, err -> {
                Tasks.showError(ctx.overlay(), p.name, err);
                ctx.services().projects().get(p.id).ifPresent(this::load);
            });
    }

    // ------------------------------------------------------------------ library

    private Node library() {
        VBox box = new VBox();
        box.getStyleClass().add("stack-tight");
        var lib = ctx.services().shardlightLibrary();
        List<LibraryItem> items;
        try {
            items = lib.items();
        } catch (LauncherException e) {
            items = List.of();
            box.getChildren().add(UiKit.banner("danger", e.userMessage()));
        }
        Button importFiles = UiKit.primary(I18n.tr("ui.shardlight.import"), Icons.ARCHIVE, this::importFiles);
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolder"),
            () -> ctx.openFolder(ctx.services().paths().content()));
        FlowPane downloads = new FlowPane();
        downloads.getStyleClass().add("button-row");
        for (ShardlightModule m : ctx.services().shardlightCatalog().modules()) {
            downloads.getChildren().add(UiKit.button(I18n.tr("ui.shardlight.downloadLatest", I18n.tr(m.nameKey())), Icons.DOWNLOAD,
                () -> download(m)));
        }
        HBox bar = new HBox(importFiles, UiKit.spacer(), folder);
        bar.getStyleClass().add("button-row");
        box.getChildren().addAll(UiKit.label(I18n.tr("ui.shardlight.libraryTitle"), "h2"),
            UiKit.label(I18n.tr("ui.shardlight.libraryIntro"), "muted"), bar, downloads);
        if (items.isEmpty()) {
            box.getChildren().add(UiKit.label(I18n.tr("ui.shardlight.libraryEmpty"), "faint"));
        }
        for (LibraryItem i : items) {
            Label name = UiKit.label(itemName(i), "project-name");
            Label sub = UiKit.label(I18n.tr("ui.shardlight.itemRow", i.version() == null ? "-" : i.version(), UiKit.bytes(i.size()),
                ProjectText.ago(i.importedAt())), "faint", "small");
            VBox text = new VBox(name, sub);
            text.getStyleClass().add("row-text");
            HBox.setHgrow(text, Priority.ALWAYS);
            Button remove = UiKit.iconButton(Icons.TRASH, I18n.tr("ui.shardlight.removeItem"), () -> ctx.overlay().confirm(
                I18n.tr("ui.shardlight.removeItemTitle"), I18n.tr("ui.shardlight.removeItemMessage", itemName(i)), List.of(),
                I18n.tr("common.delete"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                    lib.remove(i.id());
                    return true;
                }, x -> render())));
            HBox row = new HBox(UiKit.iconBox(Icons.of("module".equals(i.kind()) ? Icons.PACKAGE : Icons.PALETTE, 16), 34), text,
                remove);
            row.getStyleClass().addAll("card", "flat", "library-row");
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
