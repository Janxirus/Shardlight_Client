package dev.shardlight.client.app.ui;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** The current project pinned at the top of the sidebar, with its art; click to open it, right-click for more. */
public final class SidebarProject {
    private final AppContext ctx;
    private final VBox box = new VBox();
    private boolean compact;

    public SidebarProject(AppContext ctx) {
        this.ctx = ctx;
        box.getStyleClass().add("pinned-project-box");
        ctx.onProjectsChanged(this::refresh);
        ctx.play().onStateChanged(this::refresh);
        ctx.shell().onCollapsedChanged(c -> {
            compact = c;
            refresh();
        });
        compact = ctx.shell().isCollapsed();
        refresh();
    }

    public VBox view() {
        return box;
    }

    private void refresh() {
        box.getChildren().clear();
        Optional<Project> sel = ctx.selectedProject();
        if (sel.isEmpty()) {
            Button create = UiKit.button(compact ? "" : I18n.tr("ui.home.newProject"), Icons.PLUS, () -> ctx.newProject(null),
                "ghost", "pinned-new");
            create.setMaxWidth(Double.MAX_VALUE);
            create.setAccessibleText(I18n.tr("ui.home.newProject"));
            UiKit.tooltip(create, I18n.tr("ui.home.newProject"));
            box.getChildren().add(create);
            return;
        }
        Project p = sel.get();
        boolean running = ctx.play().isRunning(p.id);
        HBox row = new HBox(ProjectIcons.tile(p.icon, ctx.services().projects().projectDir(p.id), ThemeManager.em(2.571)));
        row.getStyleClass().add("pinned-project");
        if (running) {
            row.getStyleClass().add("running");
        }
        if (!compact) {
            Label name = UiKit.label(p.name, "pinned-name");
            name.setWrapText(false);
            Label sub = UiKit.label(running ? I18n.tr("ui.play.running") : I18n.tr("ui.sidebar.pinned"),
                running ? "success-text" : "faint", "caption");
            sub.setWrapText(false);
            VBox text = new VBox(name, sub);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            row.getChildren().add(text);
        }
        row.setFocusTraversable(true);
        row.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        row.setAccessibleText(I18n.tr("ui.sidebar.openPinned", p.name));
        UiKit.tooltip(row, I18n.tr("ui.sidebar.openPinned", p.name));
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu(p).show(row, e.getScreenX(), e.getScreenY());
            } else {
                ctx.openProject(p.id);
            }
        });
        row.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                ctx.openProject(p.id);
            } else if (e.getCode() == KeyCode.CONTEXT_MENU) {
                menu(p).show(row, Side.RIGHT, 0, 0);
            }
        });
        box.getChildren().add(row);
    }

    private ContextMenu menu(Project p) {
        boolean running = ctx.play().isRunning(p.id);
        MenuItem play = new MenuItem(I18n.tr(running ? "ui.play.stop" : "ui.play.play"),
            Icons.of(running ? Icons.STOP : Icons.PLAY, 16));
        play.setOnAction(e -> ctx.play().play(p));
        MenuItem open = new MenuItem(I18n.tr("ui.library.open"), Icons.of(Icons.LIBRARY, 16));
        open.setOnAction(e -> ctx.openProject(p.id));
        MenuItem folder = new MenuItem(I18n.tr("ui.library.openFolder"), Icons.of(Icons.FOLDER, 16));
        folder.setOnAction(e -> ctx.openFolder(ctx.services().projects().gameDir(p)));
        return new ContextMenu(play, open, folder);
    }
}
