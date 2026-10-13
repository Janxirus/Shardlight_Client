package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.SetChangeListener;
import javafx.collections.WeakSetChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;

import java.util.function.Supplier;

/**
 * The Install button of a catalog item. It shows its state (Install, Installing, Installed) and carries the
 * project it installs into: the arrow beside it lists the projects, and the choice is shared with the page through
 * {@code target}. Modpacks have no target; they become a new project.
 */
public final class InstallButton extends HBox {
    private final AppContext ctx;
    private final CatalogProvider provider;
    private final CatalogProject item;
    private final ObjectProperty<Project> target;
    private final Supplier<CatalogVersion> version;
    private final Runnable after;
    private final boolean large;
    private final Button main = new Button();
    private final Button arrow;
    // held strongly here, referenced weakly by the long-lived sources
    private final ChangeListener<Project> targetListener = (o, a, b) -> refresh();
    private final SetChangeListener<String> busyListener = c -> refresh();

    /**
     * @param version the version to install, or null from the supplier for the newest compatible one
     * @param after   runs after a successful install (may be null)
     * @param large   the page-header size: the label names the project it installs into
     */
    public InstallButton(AppContext ctx, CatalogProvider provider, CatalogProject item, ObjectProperty<Project> target,
                         Supplier<CatalogVersion> version, Runnable after, boolean large) {
        this.ctx = ctx;
        this.provider = provider;
        this.item = item;
        this.target = target;
        this.version = version;
        this.after = after;
        this.large = large;
        getStyleClass().add("split-button");
        main.getStyleClass().add("split-main");
        arrow = UiKit.iconButton(Icons.CHEVRON_DOWN, I18n.tr("ui.install.chooseProject"), this::chooseProject);
        arrow.getStyleClass().remove("ghost");
        arrow.getStyleClass().add("split-arrow");
        if (large) {
            main.getStyleClass().add("large");
            arrow.getStyleClass().add("large");
        }
        main.setOnAction(e -> start());
        getChildren().addAll(main, arrow);
        boolean pack = item.kind() == ContentKind.MODPACK;
        arrow.setVisible(!pack);
        arrow.setManaged(!pack);
        target.addListener(new WeakChangeListener<>(targetListener));
        CatalogInstall.busy().addListener(new WeakSetChangeListener<>(busyListener));
        refresh();
    }

    static boolean isInstalled(Project p, CatalogProvider provider, CatalogProject item) {
        if (p == null) {
            return false;
        }
        for (ContentEntry e : p.content) {
            if (item.id().equals(e.projectId) && e.source.name().equalsIgnoreCase(provider.id())) {
                return true;
            }
        }
        return false;
    }

    private void start() {
        if (item.kind() == ContentKind.MODPACK) {
            CatalogInstall.install(ctx, provider, item, version.get(), null, after);
        } else {
            CatalogInstall.install(ctx, provider, item, version.get(), target.get(), () -> {
                refresh();
                if (after != null) {
                    after.run();
                }
            });
        }
    }

    /** show the state: busy, a modpack, no project, installed in the chosen project, or ready to install */
    public void refresh() {
        Project p = target.get();
        boolean pack = item.kind() == ContentKind.MODPACK;
        boolean busy = CatalogInstall.busy().contains(CatalogInstall.key(provider, item));
        UiKit.loading(main, false);
        main.getStyleClass().removeAll("primary", "installed");
        getStyleClass().remove("primary-split");
        arrow.setDisable(busy);
        if (pack) {
            setMain(I18n.tr("ui.discover.newProjectFromPack"), Icons.PLUS, true);
            UiKit.tooltip(main, I18n.tr("ui.install.packTip"));
        } else if (p == null) {
            setMain(I18n.tr("ui.discover.install"), Icons.DOWNLOAD, true);
            UiKit.tooltip(main, I18n.tr("ui.install.noProject"));
        } else if (isInstalled(p, provider, item)) {
            setMain(I18n.tr("ui.discover.installed"), Icons.CHECK, false);
            main.getStyleClass().add("installed");
            UiKit.tooltip(main, I18n.tr("ui.install.reinstallTip", p.name));
        } else {
            setMain(large ? I18n.tr("ui.install.toProject", p.name) : I18n.tr("ui.discover.install"), Icons.DOWNLOAD, true);
            UiKit.tooltip(main, I18n.tr("ui.install.intoTip", p.name, p.minecraftVersion));
        }
        main.setAccessibleText(p == null || pack ? I18n.tr("ui.discover.installAccessible", item.title())
            : I18n.tr("ui.install.accessibleInto", item.title(), p.name));
        if (busy) {
            UiKit.loading(main, true);
        }
    }

    private void setMain(String text, String icon, boolean primary) {
        main.setText(text);
        main.setGraphic(Icons.of(icon, 16));
        if (primary) {
            main.getStyleClass().add("primary");
            getStyleClass().add("primary-split");
        }
    }

    private void chooseProject() {
        ContextMenu menu = new ContextMenu();
        for (Project p : ctx.services().projects().list()) {
            MenuItem mi = new MenuItem(p.name + "  ·  " + p.minecraftVersion + " "
                + (p.loader.isVanilla() ? I18n.tr("ui.project.vanilla") : p.loader.type.displayName()));
            if (target.get() != null && target.get().id.equals(p.id)) {
                mi.setGraphic(Icons.of(Icons.CHECK, 16));
            }
            mi.setOnAction(e -> target.set(p));
            menu.getItems().add(mi);
        }
        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem fresh = new MenuItem(I18n.tr("ui.install.newProject"));
        fresh.setGraphic(Icons.of(Icons.PLUS, 16));
        fresh.setOnAction(e -> ctx.newProject(null));
        menu.getItems().add(fresh);
        menu.show(arrow, javafx.geometry.Side.BOTTOM, 0, 4);
    }
}
