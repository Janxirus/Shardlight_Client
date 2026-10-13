package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Fields;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Motion;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectArt;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.ArchivedProject;
import dev.shardlight.client.project.LibraryView;
import dev.shardlight.client.project.LibraryView.Filter;
import dev.shardlight.client.project.LibraryView.Section;
import dev.shardlight.client.project.LibraryView.Sort;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.settings.LauncherSettings.Library;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Every project as a card or a row. Sort by last played, name, created or your own order; filter by loader,
 * version and Shardlight; drag projects to reorder them or into folders; Play, Open folder and a menu show when you
 * point at a card. The trash lives behind its own toggle.
 */
public final class LibraryPage implements Page {
    private static final DataFormat PROJECT = new DataFormat("application/x-shardlight-project");
    private static final List<Sort> SORTS = List.of(Sort.RECENT, Sort.NAME, Sort.CREATED, Sort.CUSTOM);

    private final AppContext ctx;
    private final VBox root = new VBox();
    private final VBox content = new VBox();
    private final StackPane view = new StackPane(root);
    private final Fields.SearchField search = new Fields.SearchField(I18n.tr("ui.library.search"));
    private final ComboBox<String> loaderBox = new ComboBox<>();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> shardlightBox = new ComboBox<>();
    private final ComboBox<String> sortBox = new ComboBox<>();
    private final ToggleButton gridButton = new ToggleButton();
    private final ToggleButton listButton = new ToggleButton();
    private final ToggleButton trash = new ToggleButton();
    private final VBox toolbar = new VBox();
    private final VBox filterChips = new VBox();
    private final HBox actions;
    private boolean building;
    /** --capture only: pretend the library is empty */
    private boolean showEmpty;
    private boolean previewing;

    public LibraryPage(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().addAll("library", "page-column");
        content.getStyleClass().add("library-content");
        StackPane.setAlignment(root, Pos.TOP_CENTER);

        search.textProperty().addListener((o, a, b) -> refresh());
        search.getStyleClass().add("library-search");
        HBox.setHgrow(search, Priority.ALWAYS);
        for (ComboBox<String> box : List.of(loaderBox, versionBox, shardlightBox, sortBox)) {
            box.setOnAction(e -> {
                if (!building) {
                    onToolbarChanged();
                }
            });
        }
        loaderBox.setAccessibleText(I18n.tr("ui.library.filter.loader"));
        versionBox.setAccessibleText(I18n.tr("ui.library.filter.version"));
        shardlightBox.setAccessibleText(I18n.tr("ui.library.filter.shardlight"));
        sortBox.setAccessibleText(I18n.tr("ui.library.sort"));
        sortBox.getItems().setAll(I18n.tr("ui.library.sortRecent"), I18n.tr("ui.library.sortName"),
            I18n.tr("ui.library.sortCreated"), I18n.tr("ui.library.sortCustom"));
        shardlightBox.getItems().setAll(I18n.tr("ui.library.filter.anyShardlight"), I18n.tr("ui.library.filter.withShardlight"),
            I18n.tr("ui.library.filter.withoutShardlight"));
        shardlightBox.getSelectionModel().select(0);

        ToggleGroup views = new ToggleGroup();
        gridButton.setGraphic(Icons.of(Icons.GRID, 16));
        listButton.setGraphic(Icons.of(Icons.LIST, 16));
        for (ToggleButton b : List.of(gridButton, listButton)) {
            b.setToggleGroup(views);
            b.setOnAction(e -> {
                if (!b.isSelected()) {
                    b.setSelected(true);
                }
                saveLibrary(l -> l.view = b == listButton ? "list" : "grid");
                refresh();
            });
        }
        gridButton.setAccessibleText(I18n.tr("ui.library.viewGrid"));
        listButton.setAccessibleText(I18n.tr("ui.library.viewList"));
        UiKit.tooltip(gridButton, I18n.tr("ui.library.viewGrid"));
        UiKit.tooltip(listButton, I18n.tr("ui.library.viewList"));
        HBox viewSwitch = new HBox(gridButton, listButton);
        viewSwitch.getStyleClass().addAll("segmented", "icon-segmented");

        Button newFolder = UiKit.button(I18n.tr("ui.library.newFolder"), Icons.FOLDER, this::newFolder);
        trash.setText(I18n.tr("ui.library.trash"));
        trash.setGraphic(Icons.of(Icons.TRASH, 15));
        trash.setOnAction(e -> refresh());

        HBox top = new HBox(search, sortBox, viewSwitch, newFolder, trash);
        top.getStyleClass().add("toolbar");
        HBox filters = new HBox(loaderBox, versionBox, shardlightBox);
        filters.getStyleClass().add("toolbar");
        toolbar.getStyleClass().add("stack-tight");
        toolbar.getChildren().addAll(top, filters);
        filterChips.getStyleClass().add("filter-row");
        root.getChildren().addAll(toolbar, filterChips, content);

        actions = new HBox(
            UiKit.button(I18n.tr("ui.library.import"), Icons.ARCHIVE, () -> ProjectActions.importFile(ctx)),
            UiKit.primary(I18n.tr("ui.library.new"), Icons.PLUS, () -> ctx.newProject(null)));
        actions.getStyleClass().add("page-actions");
        ctx.onProjectsChanged(() -> {
            if (ctx.shell().current() == this) {
                refresh();
            }
        });
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.library");
    }

    @Override
    public Region view() {
        return view;
    }

    @Override
    public Node actions() {
        return actions;
    }

    @Override
    public void onShow() {
        refresh();
    }

    /** --capture only: show the page as it looks with no projects */
    public void previewEmpty(boolean on) {
        showEmpty = on;
        refresh();
    }

    /** --capture only: show the grid or the list without saving the choice */
    public void previewView(boolean list) {
        previewing = list;
        (list ? listButton : gridButton).setSelected(true);
        refresh();
    }

    // ------------------------------------------------------------------ state

    private Library library() {
        return ctx.services().settings().get().library;
    }

    private void saveLibrary(java.util.function.Consumer<Library> change) {
        try {
            ctx.services().settings().update(s -> change.accept(s.library));
        } catch (Exception e) {
            ctx.overlay().toast("danger", Tasks.message(e));
        }
    }

    private Sort sort() {
        return SORTS.get(Math.max(0, sortBox.getSelectionModel().getSelectedIndex()));
    }

    private Filter filter() {
        String loader = loaderBox.getSelectionModel().getSelectedIndex() <= 0 ? null
            : loaderKeys.getOrDefault(loaderBox.getValue(), loaderBox.getValue());
        String version = versionBox.getSelectionModel().getSelectedIndex() <= 0 ? null : versionBox.getValue();
        int sl = shardlightBox.getSelectionModel().getSelectedIndex();
        return new Filter(search.getText(), loader, version, sl == 1 ? Boolean.TRUE : sl == 2 ? Boolean.FALSE : null);
    }

    private final java.util.Map<String, String> loaderKeys = new java.util.HashMap<>();

    private void onToolbarChanged() {
        saveLibrary(l -> l.sort = sort().id());
        refresh();
    }

    /** fill the filter lists from the projects that exist, keeping what is chosen */
    private void syncFilters(List<Project> all) {
        building = true;
        try {
            String loader = loaderBox.getSelectionModel().getSelectedIndex() > 0 ? loaderBox.getValue() : null;
            String version = versionBox.getSelectionModel().getSelectedIndex() > 0 ? versionBox.getValue() : null;
            loaderKeys.clear();
            TreeSet<String> loaders = new TreeSet<>();
            for (Project p : all) {
                String name = ProjectText.loaderName(p);
                loaders.add(name);
                loaderKeys.put(name, LibraryView.loaderKey(p));
            }
            loaderBox.getItems().setAll(I18n.tr("ui.library.filter.anyLoader"));
            loaderBox.getItems().addAll(loaders);
            loaderBox.getSelectionModel().select(loader != null && loaders.contains(loader) ? loader : loaderBox.getItems().get(0));
            TreeSet<String> versions = new TreeSet<>(java.util.Comparator.reverseOrder());
            all.forEach(p -> versions.add(p.minecraftVersion));
            versionBox.getItems().setAll(I18n.tr("ui.library.filter.anyVersion"));
            versionBox.getItems().addAll(versions);
            versionBox.getSelectionModel().select(version != null && versions.contains(version) ? version : versionBox.getItems().get(0));
            Library lib = library();
            sortBox.getSelectionModel().select(Math.max(0, SORTS.indexOf(Sort.of(lib.sort))));
            if (!previewing) {
                (Objects.equals(lib.view, "list") ? listButton : gridButton).setSelected(true);
            }
        } finally {
            building = false;
        }
    }

    private boolean listView() {
        return listButton.isSelected();
    }

    // ------------------------------------------------------------------ rendering

    private void refresh() {
        content.getChildren().clear();
        filterChips.getChildren().clear();
        if (trash.isSelected()) {
            showTrash();
            return;
        }
        List<Project> all = showEmpty ? List.of() : ctx.services().projects().list();
        toolbar.setVisible(!all.isEmpty());
        toolbar.setManaged(!all.isEmpty());
        if (all.isEmpty()) {
            content.getChildren().add(UiKit.emptyState(Icons.LIBRARY, I18n.tr("ui.library.emptyTitle"),
                I18n.tr("ui.library.emptyBody"),
                UiKit.primary(I18n.tr("ui.library.createFirst"), Icons.PLUS, () -> ctx.newProject(null))));
            return;
        }
        syncFilters(all);
        Filter filter = filter();
        addFilterChips(filter);
        List<Section> sections = LibraryView.sections(all, filter, sort(), library());
        boolean any = sections.stream().anyMatch(s -> !s.projects().isEmpty());
        if (!any && filter.active()) {
            content.getChildren().add(UiKit.emptyState(Icons.SEARCH, I18n.tr("ui.library.noMatchesTitle"),
                I18n.tr("ui.library.noMatches"), UiKit.button(I18n.tr("ui.library.clearFilters"), null, this::clearFilters)));
            return;
        }
        boolean folders = !library().groups.isEmpty();
        String selected = ctx.selectedProject().map(p -> p.id).orElse(null);
        for (Section s : sections) {
            content.getChildren().add(section(s, folders, selected));
        }
    }

    private void addFilterChips(Filter f) {
        FlowPane row = new FlowPane();
        row.getStyleClass().add("chip-row");
        if (f.loader() != null) {
            row.getChildren().add(UiKit.filterChip(loaderBox.getValue(), () -> loaderBox.getSelectionModel().select(0)));
        }
        if (f.version() != null) {
            row.getChildren().add(UiKit.filterChip(f.version(), () -> versionBox.getSelectionModel().select(0)));
        }
        if (f.shardlight() != null) {
            row.getChildren().add(UiKit.filterChip(shardlightBox.getValue(), () -> shardlightBox.getSelectionModel().select(0)));
        }
        if (!row.getChildren().isEmpty()) {
            row.getChildren().add(UiKit.button(I18n.tr("ui.library.clearFilters"), null, this::clearFilters, "ghost", "small"));
            filterChips.getChildren().add(row);
        }
    }

    private void clearFilters() {
        building = true;
        search.field().clear();
        loaderBox.getSelectionModel().select(0);
        versionBox.getSelectionModel().select(0);
        shardlightBox.getSelectionModel().select(0);
        building = false;
        refresh();
    }

    private Node section(Section s, boolean folders, String selected) {
        VBox box = new VBox();
        box.getStyleClass().add("library-section");
        Library.Group g = s.group();
        String groupId = g == null ? null : g.id;
        if (g != null || folders) {
            box.getChildren().add(sectionHeader(s, g));
        }
        boolean collapsed = g != null && g.collapsed;
        if (!collapsed) {
            Node items = listView() ? list(s, groupId, selected) : grid(s, groupId, selected);
            box.getChildren().add(items);
            if (s.projects().isEmpty() && (g != null || folders)) {
                Label hint = UiKit.label(I18n.tr(g != null ? "ui.library.folderEmpty" : "ui.library.mainEmpty"), "faint", "small");
                hint.getStyleClass().add("drop-hint");
                box.getChildren().add(hint);
            }
        }
        dropTarget(box, groupId, null);
        return box;
    }

    private Node sectionHeader(Section s, Library.Group g) {
        HBox header = new HBox();
        header.getStyleClass().add("section-header");
        if (g != null) {
            Button fold = UiKit.iconButton(g.collapsed ? Icons.CHEVRON_RIGHT : Icons.CHEVRON_DOWN,
                I18n.tr(g.collapsed ? "ui.library.expand" : "ui.library.collapse", g.name), () -> {
                    saveLibrary(l -> LibraryView.group(l, g.id).ifPresent(x -> x.collapsed = !x.collapsed));
                    refresh();
                });
            Node icon = Icons.of(Icons.FOLDER, 16);
            icon.getStyleClass().add("muted");
            Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.library.folderMenu", g.name), () -> { });
            more.setOnAction(e -> folderMenu(g).show(more, Side.BOTTOM, 0, 0));
            header.getChildren().addAll(fold, icon, UiKit.label(g.name, "h3"),
                UiKit.chip(String.valueOf(g.projects.size()), null), UiKit.spacer(), more);
        } else {
            header.getChildren().addAll(UiKit.label(I18n.tr("ui.library.ungrouped"), "h3"),
                UiKit.chip(String.valueOf(s.projects().size()), null));
        }
        return header;
    }

    private ContextMenu folderMenu(Library.Group g) {
        MenuItem rename = item(I18n.tr("ui.library.renameFolder"), null, () -> Dialogs.text(ctx.overlay(),
            I18n.tr("ui.library.renameFolder"), I18n.tr("ui.library.folderName"), g.name, I18n.tr("ui.library.renameFolder"),
            v -> v.isBlank() ? I18n.tr("ui.project.nameEmpty") : null, v -> {
                saveLibrary(l -> LibraryView.group(l, g.id).ifPresent(x -> x.name = v));
                refresh();
            }));
        MenuItem delete = item(I18n.tr("ui.library.deleteFolder"), Icons.TRASH, () -> ctx.overlay().confirm(
            I18n.tr("ui.library.deleteFolderTitle", g.name), I18n.tr("ui.library.deleteFolderBody"), List.of(),
            I18n.tr("ui.library.deleteFolder"), false, () -> {
                saveLibrary(l -> LibraryView.removeGroup(l, g.id));
                refresh();
            }));
        delete.getStyleClass().add("danger");
        return new ContextMenu(rename, new SeparatorMenuItem(), delete);
    }

    private void newFolder() {
        Dialogs.text(ctx.overlay(), I18n.tr("ui.library.newFolder"), I18n.tr("ui.library.folderName"), "",
            I18n.tr("ui.library.createFolder"), v -> v.isBlank() ? I18n.tr("ui.project.nameEmpty") : null, v -> {
                saveLibrary(l -> LibraryView.addGroup(l, v));
                refresh();
            });
    }

    // ------------------------------------------------------------------ grid and list

    private Node grid(Section s, String groupId, String selected) {
        FlowPane grid = new FlowPane();
        grid.getStyleClass().add("library-grid");
        for (Project p : s.projects()) {
            grid.getChildren().add(card(p, groupId, p.id.equals(selected)));
        }
        return grid;
    }

    private Node list(Section s, String groupId, String selected) {
        VBox rows = new VBox();
        rows.getStyleClass().add("library-list");
        for (Project p : s.projects()) {
            rows.getChildren().add(row(p, groupId, p.id.equals(selected)));
        }
        return rows;
    }

    private Node card(Project p, String groupId, boolean selected) {
        var dir = ctx.services().projects().projectDir(p.id);
        ProjectArt art = ProjectArt.of(p, dir, 0, ThemeManager.em(8.6), 0.714);
        Node icon = ProjectIcons.tile(p.icon, dir, ThemeManager.em(2.571));
        boolean running = ctx.play().isRunning(p.id);
        Button play = UiKit.iconButton(running ? Icons.STOP : Icons.PLAY, I18n.tr(running ? "ui.play.stopAccessible"
            : "ui.play.playAccessible", p.name), () -> ctx.play().play(p));
        play.getStyleClass().add("art-action");
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolderFor", p.name),
            () -> ctx.openFolder(ctx.services().projects().gameDir(p)));
        folder.getStyleClass().add("art-action");
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.library.moreFor", p.name), () -> { });
        more.getStyleClass().add("art-action");
        HBox hover = new HBox(play, folder, more);
        hover.getStyleClass().add("hover-actions");
        hover.setOpacity(0);
        StackPane top = new StackPane(art, icon, hover);
        top.getStyleClass().add("card-art");
        StackPane.setAlignment(icon, Pos.BOTTOM_LEFT);
        StackPane.setMargin(icon, new Insets(0, 0, ThemeManager.em(0.571), ThemeManager.em(0.714)));
        StackPane.setAlignment(hover, Pos.TOP_RIGHT);
        if (running) {
            Label chip = UiKit.chip(I18n.tr("ui.play.running"), "success");
            StackPane.setAlignment(chip, Pos.TOP_LEFT);
            StackPane.setMargin(chip, new Insets(ThemeManager.em(0.571)));
            top.getChildren().add(chip);
        }

        Label name = UiKit.label(p.name, "project-name");
        name.setWrapText(false);
        Label version = UiKit.label(I18n.tr("ui.project.minecraft", p.minecraftVersion), "muted", "small");
        version.setWrapText(false);
        HBox meta = new HBox(version, UiKit.chip(ProjectText.loaderName(p), null));
        meta.getStyleClass().add("card-meta");
        Label details = UiKit.label(I18n.tr("ui.library.modCount", ProjectText.count(p, ContentKind.MOD, false)) + "  ·  "
            + ProjectText.lastPlayed(p), "faint", "small");
        details.setWrapText(false);
        VBox body = new VBox(name, meta, details);
        body.getStyleClass().add("card-body");

        VBox card = new VBox(top, body);
        card.getStyleClass().addAll("card", "library-card");
        if (selected) {
            card.getStyleClass().add("selected");
        }
        Motion.lift(card);
        Runnable reveal = () -> {
            boolean on = card.isHover() || card.isFocusWithin();
            if (on != (hover.getOpacity() > 0.5)) {
                if (on) {
                    Motion.fadeIn(hover, Motion.Speed.FAST);
                } else {
                    hover.setOpacity(0);
                }
            }
        };
        card.hoverProperty().addListener((o, a, b) -> reveal.run());
        card.focusWithinProperty().addListener((o, a, b) -> reveal.run());
        more.setOnAction(e -> menu(p).show(more, Side.BOTTOM, 0, 0));
        wire(card, p, groupId);
        return card;
    }

    private Node row(Project p, String groupId, boolean selected) {
        var dir = ctx.services().projects().projectDir(p.id);
        Node icon = ProjectIcons.tile(p.icon, dir, ThemeManager.em(2.571));
        Label name = UiKit.label(p.name, "project-name");
        name.setWrapText(false);
        Label version = UiKit.label(p.minecraftVersion, "muted", "small");
        VBox text = new VBox(name, version);
        text.getStyleClass().add("row-text");
        HBox.setHgrow(text, Priority.ALWAYS);
        Label mods = UiKit.label(I18n.tr("ui.library.modCount", ProjectText.count(p, ContentKind.MOD, false)), "muted", "small",
            "tabular");
        Label played = UiKit.label(ProjectText.lastPlayed(p), "faint", "small");
        played.setWrapText(false);
        boolean running = ctx.play().isRunning(p.id);
        Button play = UiKit.button(I18n.tr(running ? "ui.play.stop" : "ui.play.play"), running ? Icons.STOP : Icons.PLAY,
            () -> ctx.play().play(p), "primary", "small");
        play.setAccessibleText(I18n.tr(running ? "ui.play.stopAccessible" : "ui.play.playAccessible", p.name));
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolderFor", p.name),
            () -> ctx.openFolder(ctx.services().projects().gameDir(p)));
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.library.moreFor", p.name), () -> { });
        more.setOnAction(e -> menu(p).show(more, Side.BOTTOM, 0, 0));
        HBox row = new HBox(icon, text, UiKit.chip(ProjectText.loaderName(p), null), mods, played, play, folder, more);
        row.getStyleClass().addAll("card", "flat", "library-row");
        if (selected) {
            row.getStyleClass().add("selected");
        }
        wire(row, p, groupId);
        return row;
    }

    /** click opens the project; right-click, the menu key and Delete work; the card drags and accepts drops */
    private void wire(Region node, Project p, String groupId) {
        node.getStyleClass().add("interactive");
        node.setFocusTraversable(true);
        node.setAccessibleText(p.name + ", " + p.minecraftVersion + " " + ProjectText.loader(p));
        node.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu(p).show(node, e.getScreenX(), e.getScreenY());
            } else if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                ctx.openProject(p.id);
            }
        });
        node.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                ctx.openProject(p.id);
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE) {
                ProjectActions.moveToTrash(ctx, p, this::refresh);
            } else if (e.getCode() == KeyCode.CONTEXT_MENU) {
                menu(p).show(node, Side.BOTTOM, 0, 0);
            }
        });
        node.setOnDragDetected(e -> {
            Dragboard db = node.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent c = new ClipboardContent();
            c.put(PROJECT, p.id);
            db.setContent(c);
            db.setDragView(node.snapshot(null, null));
            e.consume();
        });
        dropTarget(node, groupId, p.id);
    }

    /** dropping a project on {@code node} puts it in {@code groupId}, before {@code beforeId} (null = at the end) */
    private void dropTarget(Node node, String groupId, String beforeId) {
        node.setOnDragOver(e -> {
            if (e.getDragboard().hasContent(PROJECT) && !Objects.equals(e.getDragboard().getContent(PROJECT), beforeId)) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });
        node.setOnDragEntered(e -> {
            if (e.getDragboard().hasContent(PROJECT) && !node.getStyleClass().contains("drop-target")) {
                node.getStyleClass().add("drop-target");
            }
        });
        node.setOnDragExited(e -> node.getStyleClass().remove("drop-target"));
        node.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasContent(PROJECT)) {
                move((String) db.getContent(PROJECT), groupId, beforeId);
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    private void move(String projectId, String groupId, String beforeId) {
        List<String> ids = ctx.services().projects().list().stream().map(p -> p.id).toList();
        saveLibrary(l -> {
            LibraryView.move(l, ids, projectId, groupId, beforeId);
            l.sort = Sort.CUSTOM.id();
        });
        refresh();
    }

    // ------------------------------------------------------------------ menus

    private ContextMenu menu(Project p) {
        MenuItem play = item(I18n.tr("ui.play.play"), Icons.PLAY, () -> ctx.play().play(p));
        MenuItem open = item(I18n.tr("ui.library.open"), Icons.LIBRARY, () -> ctx.openProject(p.id));
        MenuItem select = item(I18n.tr("ui.library.makeDefault"), Icons.STAR, () -> ctx.selectProject(p.id));
        MenuItem folder = item(I18n.tr("ui.library.openFolder"), Icons.FOLDER,
            () -> ctx.openFolder(ctx.services().projects().gameDir(p)));
        Menu moveTo = new Menu(I18n.tr("ui.library.moveToFolder"));
        moveTo.setGraphic(Icons.of(Icons.FOLDER, 14));
        for (Library.Group g : library().groups) {
            moveTo.getItems().add(item(g.name, null, () -> move(p.id, g.id, null)));
        }
        if (!library().groups.isEmpty()) {
            moveTo.getItems().add(new SeparatorMenuItem());
        }
        moveTo.getItems().add(item(I18n.tr("ui.library.noFolder"), null, () -> move(p.id, null, null)));
        moveTo.getItems().add(item(I18n.tr("ui.library.newFolder"), Icons.PLUS, () -> Dialogs.text(ctx.overlay(),
            I18n.tr("ui.library.newFolder"), I18n.tr("ui.library.folderName"), "", I18n.tr("ui.library.createFolder"),
            v -> v.isBlank() ? I18n.tr("ui.project.nameEmpty") : null, v -> {
                List<String> ids = ctx.services().projects().list().stream().map(x -> x.id).toList();
                saveLibrary(l -> {
                    var g = LibraryView.addGroup(l, v);
                    LibraryView.move(l, ids, p.id, g.id, null);
                });
                refresh();
            })));
        MenuItem rename = item(I18n.tr("ui.project.rename"), null, () -> ProjectActions.rename(ctx, p));
        MenuItem dup = item(I18n.tr("ui.project.duplicate"), Icons.COPY, () -> ProjectActions.duplicate(ctx, p));
        MenuItem export = item(I18n.tr("ui.project.export"), Icons.ARCHIVE, () -> ProjectActions.export(ctx, p));
        MenuItem backup = item(I18n.tr("ui.backup.now"), Icons.ARCHIVE, () -> ProjectActions.backupNow(ctx, p));
        MenuItem remove = item(I18n.tr("ui.project.trash"), Icons.TRASH, () -> ProjectActions.moveToTrash(ctx, p, this::refresh));
        remove.getStyleClass().add("danger");
        return new ContextMenu(play, open, select, folder, moveTo, new SeparatorMenuItem(), rename, dup, export, backup,
            new SeparatorMenuItem(), remove);
    }

    private static MenuItem item(String text, String icon, Runnable r) {
        MenuItem m = new MenuItem(text);
        if (icon != null) {
            m.setGraphic(Icons.of(icon, 14));
        }
        m.setOnAction(e -> r.run());
        return m;
    }

    // ------------------------------------------------------------------ trash

    private void showTrash() {
        toolbar.setVisible(true);
        toolbar.setManaged(true);
        Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().archived(), list -> {
            content.getChildren().clear();
            if (list.isEmpty()) {
                content.getChildren().add(UiKit.emptyState(Icons.TRASH, I18n.tr("ui.trash.emptyTitle"),
                    I18n.tr("ui.trash.emptyBody"), null));
                return;
            }
            VBox rows = new VBox();
            rows.getStyleClass().add("library-list");
            rows.getChildren().add(UiKit.label(I18n.tr("ui.trash.intro"), "muted"));
            for (ArchivedProject a : list) {
                Label name = UiKit.label(a.name(), "project-name");
                Label sub = UiKit.label(I18n.tr("ui.trash.row", ProjectText.ago(a.archivedAt()), UiKit.bytes(a.sizeBytes())),
                    "muted", "small");
                VBox text = new VBox(name, sub);
                HBox.setHgrow(text, Priority.ALWAYS);
                Button restore = UiKit.button(I18n.tr("ui.trash.restore"), Icons.REFRESH, () -> Tasks.quiet(ctx.overlay(),
                    () -> ctx.services().projects().restore(a.trashId()), p -> {
                        ctx.overlay().toast("success", I18n.tr("ui.trash.restored", p.name));
                        refresh();
                    }));
                Button delete = UiKit.button(I18n.tr("ui.trash.deleteForever"), Icons.TRASH,
                    () -> ProjectActions.deletePermanently(ctx, a, this::refresh), "danger");
                HBox row = new HBox(text, restore, delete);
                row.getStyleClass().addAll("card", "flat", "library-row");
                rows.getChildren().add(row);
            }
            content.getChildren().add(rows);
        });
    }
}
