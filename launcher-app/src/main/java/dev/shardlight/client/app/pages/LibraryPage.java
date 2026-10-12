package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.ArchivedProject;
import dev.shardlight.client.project.Project;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Every project as a tile, with search, sorting, a context menu and the trash. */
public final class LibraryPage implements Page {
    private final AppContext ctx;
    private final VBox root = new VBox(16);
    private final TextField search = new TextField();
    private final ComboBox<String> sort = new ComboBox<>();
    private final ToggleButton trash = new ToggleButton();
    private final javafx.scene.layout.TilePane grid = new javafx.scene.layout.TilePane();
    private final HBox actions;

    public LibraryPage(AppContext ctx) {
        this.ctx = ctx;
        search.setPromptText(I18n.tr("ui.library.search"));
        search.getStyleClass().add("search-field");
        search.setAccessibleText(I18n.tr("ui.library.search"));
        search.textProperty().addListener((o, a, b) -> refresh());
        HBox.setHgrow(search, Priority.ALWAYS);
        search.setMaxWidth(420);
        sort.getItems().setAll(I18n.tr("ui.library.sortRecent"), I18n.tr("ui.library.sortName"),
            I18n.tr("ui.library.sortCreated"), I18n.tr("ui.library.sortPlayTime"));
        sort.getSelectionModel().select(0);
        sort.setAccessibleText(I18n.tr("ui.library.sort"));
        sort.setOnAction(e -> refresh());
        trash.setText(I18n.tr("ui.library.trash"));
        trash.setGraphic(Icons.of(Icons.TRASH, 15));
        trash.setOnAction(e -> refresh());
        HBox toolbar = new HBox(10, search, sort, UiKit.spacer(), trash);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        grid.getStyleClass().add("tile-grid");
        root.getChildren().addAll(toolbar, grid);
        actions = new HBox(8,
            UiKit.button(I18n.tr("ui.library.import"), Icons.ARCHIVE, () -> ProjectActions.importFile(ctx)),
            UiKit.primary(I18n.tr("ui.library.new"), Icons.PLUS, () -> ctx.newProject(null)));
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
        return root;
    }

    @Override
    public Node actions() {
        return actions;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        grid.getChildren().clear();
        if (trash.isSelected()) {
            showTrash();
            return;
        }
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Project> all = ctx.services().projects().list();
        List<Project> shown = all.stream()
            .filter(p -> q.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(q)
                || (p.minecraftVersion != null && p.minecraftVersion.contains(q))
                || ProjectText.loader(p).toLowerCase(Locale.ROOT).contains(q))
            .sorted(comparator())
            .toList();
        if (all.isEmpty()) {
            grid.getChildren().add(UiKit.emptyState(Icons.LIBRARY, I18n.tr("ui.library.emptyTitle"),
                I18n.tr("ui.library.emptyBody"), UiKit.primary(I18n.tr("ui.library.new"), Icons.PLUS, () -> ctx.newProject(null))));
            return;
        }
        if (shown.isEmpty()) {
            grid.getChildren().add(UiKit.label(I18n.tr("ui.library.noMatches", search.getText()), "muted"));
            return;
        }
        String selected = ctx.selectedProject().map(p -> p.id).orElse(null);
        for (Project p : shown) {
            grid.getChildren().add(tile(p, p.id.equals(selected)));
        }
    }

    private Comparator<Project> comparator() {
        return switch (sort.getSelectionModel().getSelectedIndex()) {
            case 1 -> Comparator.comparing((Project p) -> p.name.toLowerCase(Locale.ROOT));
            case 2 -> Comparator.comparingLong((Project p) -> p.createdAt).reversed();
            case 3 -> Comparator.comparingLong((Project p) -> p.totalPlayMillis).reversed();
            default -> Comparator.comparingLong((Project p) -> p.lastPlayedAt).reversed()
                .thenComparing(p -> p.name.toLowerCase(Locale.ROOT));
        };
    }

    private Node tile(Project p, boolean selected) {
        Label name = UiKit.label(p.name, "project-name");
        Label sub = UiKit.label(p.minecraftVersion + "  ·  " + ProjectText.loader(p), "muted", "small");
        VBox head = new VBox(2, name, sub);
        HBox.setHgrow(head, Priority.ALWAYS);
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.library.moreFor", p.name), () -> { });
        more.setOnAction(e -> menu(p).show(more, Side.BOTTOM, 0, 0));
        HBox top = new HBox(10, ProjectIcons.tile(p.icon, ctx.services().projects().projectDir(p.id), 44), head, more);
        top.setAlignment(Pos.CENTER_LEFT);

        FlowPane chips = new FlowPane(6, 6);
        Preset preset = p.preset == null || p.preset.active == null ? Preset.DEFAULT : p.preset.active;
        chips.getChildren().add(UiKit.chip(ProjectText.preset(p), preset == Preset.ULTRA_PERFORMANCE ? "warning" : null));
        if (p.hasShardlight()) {
            chips.getChildren().add(UiKit.chip(I18n.tr("ui.library.shardlightChip", p.shardlightModules.size()), "accent", Icons.SHARD));
        }
        if (ctx.play().isRunning(p.id)) {
            chips.getChildren().add(UiKit.chip(I18n.tr("ui.play.running"), "success"));
        }
        // one line each, with the buttons on the bottom edge, so Play and Open line up across a row of tiles
        Label content = UiKit.label(ProjectText.contentSummary(p), "faint", "small");
        content.setWrapText(false);
        Label played = UiKit.label(ProjectText.lastPlayed(p), "faint", "small");
        played.setWrapText(false);
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        Button play = UiKit.primary(I18n.tr(ctx.play().isRunning(p.id) ? "ui.play.stop" : "ui.play.play"),
            ctx.play().isRunning(p.id) ? Icons.STOP : Icons.PLAY, () -> ctx.play().play(p));
        play.setAccessibleText(I18n.tr("ui.play.playAccessible", p.name));
        Button open = UiKit.button(I18n.tr("ui.library.open"), null, () -> ctx.openProject(p.id));
        HBox buttons = new HBox(8, play, open);

        VBox tile = new VBox(10, top, chips, content, played, grow, buttons);
        tile.getStyleClass().add("project-tile");
        dev.shardlight.client.app.ui.Motion.lift(tile);
        if (selected) {
            tile.getStyleClass().add("selected");
        }
        tile.setFocusTraversable(true);
        tile.setAccessibleText(p.name + ", " + sub.getText());
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                menu(p).show(tile, e.getScreenX(), e.getScreenY());
            } else if (e.getClickCount() >= 2) {
                ctx.openProject(p.id);
            }
        });
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                ctx.openProject(p.id);
            } else if (e.getCode() == KeyCode.DELETE) {
                ProjectActions.moveToTrash(ctx, p, this::refresh);
            } else if (e.getCode() == KeyCode.CONTEXT_MENU) {
                menu(p).show(tile, Side.BOTTOM, 0, 0);
            }
        });
        return tile;
    }

    private ContextMenu menu(Project p) {
        MenuItem play = item(I18n.tr("ui.play.play"), Icons.PLAY, () -> ctx.play().play(p));
        MenuItem open = item(I18n.tr("ui.library.open"), Icons.LIBRARY, () -> ctx.openProject(p.id));
        MenuItem select = item(I18n.tr("ui.library.makeDefault"), Icons.STAR, () -> ctx.selectProject(p.id));
        MenuItem folder = item(I18n.tr("ui.library.openFolder"), Icons.FOLDER,
            () -> ctx.openFolder(ctx.services().projects().gameDir(p)));
        MenuItem rename = item(I18n.tr("ui.project.rename"), null, () -> ProjectActions.rename(ctx, p));
        MenuItem dup = item(I18n.tr("ui.project.duplicate"), Icons.COPY, () -> ProjectActions.duplicate(ctx, p));
        MenuItem export = item(I18n.tr("ui.project.export"), Icons.ARCHIVE, () -> ProjectActions.export(ctx, p));
        MenuItem backup = item(I18n.tr("ui.backup.now"), Icons.ARCHIVE, () -> ProjectActions.backupNow(ctx, p));
        MenuItem remove = item(I18n.tr("ui.project.trash"), Icons.TRASH, () -> ProjectActions.moveToTrash(ctx, p, this::refresh));
        remove.getStyleClass().add("danger");
        return new ContextMenu(play, open, select, folder, new SeparatorMenuItem(), rename, dup, export, backup,
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

    private void showTrash() {
        Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().archived(), list -> {
            grid.getChildren().clear();
            if (list.isEmpty()) {
                grid.getChildren().add(UiKit.emptyState(Icons.TRASH, I18n.tr("ui.trash.emptyTitle"),
                    I18n.tr("ui.trash.emptyBody"), null));
                return;
            }
            VBox rows = new VBox(8);
            rows.setPrefWidth(760);
            rows.getChildren().add(UiKit.label(I18n.tr("ui.trash.intro"), "muted"));
            for (ArchivedProject a : list) {
                Label name = UiKit.label(a.name(), "project-name");
                Label sub = UiKit.label(I18n.tr("ui.trash.row", ProjectText.ago(a.archivedAt()), UiKit.bytes(a.sizeBytes())),
                    "muted", "small");
                VBox text = new VBox(2, name, sub);
                HBox.setHgrow(text, Priority.ALWAYS);
                Button restore = UiKit.button(I18n.tr("ui.trash.restore"), Icons.REFRESH, () -> Tasks.quiet(ctx.overlay(),
                    () -> ctx.services().projects().restore(a.trashId()), p -> {
                        ctx.overlay().toast("success", I18n.tr("ui.trash.restored", p.name));
                        refresh();
                    }));
                Button delete = UiKit.button(I18n.tr("ui.trash.deleteForever"), Icons.TRASH,
                    () -> ProjectActions.deletePermanently(ctx, a, this::refresh), "danger");
                HBox row = new HBox(10, text, restore, delete);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().addAll("card", "flat");
                rows.getChildren().add(row);
            }
            grid.getChildren().add(rows);
        });
    }
}
