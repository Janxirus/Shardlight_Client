package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.CatalogInstall;
import dev.shardlight.client.app.flows.InstallButton;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Motion;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.RemoteImage;
import dev.shardlight.client.app.ui.TabStrip;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.InstallPlan;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.task.CancellationToken;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.net.URI;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

/**
 * One catalog project: a gallery header, the title with an Install button that also picks the project it goes
 * into, a preview of exactly what installing would download (dependencies included), and tabs for the
 * description and the versions.
 */
public final class CatalogProjectPage implements Page {
    private static final int GALLERY_SHOWN = 8;
    private static final int VERSIONS_SHOWN = 40;

    private final AppContext ctx;
    private final CatalogProvider provider;
    private final CatalogProject summary;
    private final ObjectProperty<Project> target = new SimpleObjectProperty<>();
    private final VBox column = new VBox();
    private final StackPane view = new StackPane(column);
    private final VBox tabBody = new VBox();
    private final VBox versions = new VBox();
    private final VBox preview = new VBox();
    private final CheckBox allVersions = new CheckBox();
    private final ChangeListener<Project> targetListener = (o, a, b) -> {
        loadPreview();
        loadVersions();
    };
    private CatalogProject full;
    private int tab;
    private CancellationToken previewToken = new CancellationToken();
    private String loadError;

    /** @param initialTarget the project the install goes into (the page can change it); may be null */
    public CatalogProjectPage(AppContext ctx, CatalogProvider provider, CatalogProject item, Project initialTarget) {
        this.ctx = ctx;
        this.provider = provider;
        this.summary = item;
        target.set(initialTarget);
        column.getStyleClass().addAll("catalog-page", "page-column", "stack");
        StackPane.setAlignment(column, Pos.TOP_CENTER);
        preview.getStyleClass().add("stack-tight");
        versions.getStyleClass().add("stack-tight");
        allVersions.setText(I18n.tr("ui.catalog.allVersions"));
        allVersions.setOnAction(e -> loadVersions());
        target.addListener(new WeakChangeListener<>(targetListener));
        render(item);
        Fx.run(() -> provider.project(item.id(), new CancellationToken()), p -> {
            full = p;
            loadError = null;
            render(p);
        }, err -> {
            loadError = Tasks.message(err);
            render(summary);
        });
    }

    @Override
    public String title() {
        return summary.title();
    }

    @Override
    public Region view() {
        return view;
    }

    @Override
    public Node actions() {
        URI page = full != null && full.pageUrl() != null ? full.pageUrl() : summary.pageUrl();
        return page == null ? null : UiKit.button(I18n.tr("ui.catalog.openOn", provider.displayName()), Icons.EXTERNAL,
            () -> ctx.openUrl(page.toString()));
    }

    // ------------------------------------------------------------------ layout

    private void render(CatalogProject p) {
        column.getChildren().clear();
        if (loadError != null) {
            column.getChildren().add(UiKit.banner("warning", loadError, UiKit.button(I18n.tr("common.retry"), Icons.REFRESH, () -> {
                loadError = null;
                Fx.run(() -> provider.project(summary.id(), new CancellationToken()), full2 -> {
                    full = full2;
                    render(full2);
                }, err -> {
                    loadError = Tasks.message(err);
                    render(summary);
                });
            })));
        }
        column.getChildren().add(gallery(p));
        column.getChildren().add(titleRow(p));

        TabStrip strip = new TabStrip(List.of(I18n.tr("ui.catalog.description"), I18n.tr("ui.catalog.versions")), tab, i -> {
            tab = i;
            showTab(p);
        });
        VBox main = new VBox(strip, tabBody);
        main.getStyleClass().add("catalog-main");
        HBox.setHgrow(main, Priority.ALWAYS);
        VBox side = sidebar(p);
        HBox body = new HBox(main, side);
        body.getStyleClass().add("catalog-body");
        column.getChildren().add(body);
        showTab(p);
        loadPreview();
    }

    /** a large picture with thumbnails under it; without pictures a plain banner with the icon */
    private Node gallery(CatalogProject p) {
        List<URI> pictures = p.gallery() == null ? List.of() : p.gallery().stream().limit(GALLERY_SHOWN).toList();
        StackPane hero = new StackPane();
        hero.getStyleClass().add("gallery-hero");
        if (pictures.isEmpty()) {
            hero.getStyleClass().add("empty");
            hero.getChildren().add(Icons.of(Icons.IMAGE, 40));
            hero.getChildren().get(0).getStyleClass().add("faint");
            hero.setAccessibleText(I18n.tr("ui.catalog.noGallery"));
            return hero;
        }
        Rectangle clip = new Rectangle();
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        hero.setClip(clip);
        ImageView big = RemoteImage.wide(pictures.get(0), 880);
        big.fitHeightProperty().bind(hero.heightProperty());
        big.setPreserveRatio(true);
        hero.getChildren().add(big);
        hero.setAccessibleText(I18n.tr("ui.catalog.galleryPicture", 1, pictures.size()));
        if (pictures.size() == 1) {
            return hero;
        }
        HBox thumbs = new HBox();
        thumbs.getStyleClass().add("gallery-thumbs");
        for (int i = 0; i < pictures.size(); i++) {
            ImageView t = RemoteImage.wide(pictures.get(i), 112);
            t.setFitHeight(64);
            t.setPreserveRatio(true);
            StackPane cell = new StackPane(t);
            cell.getStyleClass().add("gallery-thumb");
            if (i == 0) {
                cell.getStyleClass().add("selected");
            }
            cell.setFocusTraversable(true);
            int idx = i;
            Runnable pick = () -> {
                big.setImage(RemoteImage.wide(pictures.get(idx), 880).getImage());
                hero.setAccessibleText(I18n.tr("ui.catalog.galleryPicture", idx + 1, pictures.size()));
                thumbs.getChildren().forEach(n -> n.getStyleClass().remove("selected"));
                cell.getStyleClass().add("selected");
            };
            cell.setOnMouseClicked(e -> pick.run());
            cell.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    pick.run();
                    e.consume();
                }
            });
            thumbs.getChildren().add(cell);
        }
        return new VBox(hero, thumbs);
    }

    private Node titleRow(CatalogProject p) {
        NumberFormat nf = NumberFormat.getIntegerInstance(I18n.locale());
        FlowPane meta = new FlowPane();
        meta.getStyleClass().add("chip-row");
        meta.getChildren().add(UiKit.chip(I18n.tr("ui.discover.downloads", nf.format(p.downloads())), null, Icons.DOWNLOAD));
        if (p.follows() > 0) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.discover.follows", nf.format(p.follows())), null, Icons.STAR));
        }
        if (p.license() != null) {
            meta.getChildren().add(UiKit.chip(p.license(), null));
        }
        if (p.clientSide() != null) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.catalog.client", p.clientSide()), null));
        }
        p.loaders().stream().limit(5).forEach(l -> meta.getChildren().add(UiKit.chip(l, null)));
        VBox head = new VBox(UiKit.label(p.title(), "h1"),
            UiKit.label(p.author() == null ? "" : I18n.tr("ui.discover.by", p.author()), "muted"),
            UiKit.label(p.summary()), meta);
        head.getStyleClass().add("stack-tight");
        HBox.setHgrow(head, Priority.ALWAYS);
        InstallButton install = new InstallButton(ctx, provider, p, target, () -> null, this::loadPreview, true);
        VBox action = new VBox(install);
        action.getStyleClass().add("catalog-action");
        HBox top = new HBox(RemoteImage.square(p.iconUrl(), 88, Icons.PACKAGE), head, action);
        top.getStyleClass().add("catalog-title");
        return top;
    }

    private VBox sidebar(CatalogProject p) {
        VBox side = new VBox();
        side.getStyleClass().addAll("card", "catalog-side");
        side.getChildren().add(UiKit.label(I18n.tr("ui.catalog.preview"), "h3"));
        if (p.kind() == ContentKind.MODPACK) {
            side.getChildren().add(UiKit.label(I18n.tr("ui.catalog.previewPack"), "muted", "small"));
            return side;
        }
        side.getChildren().add(preview);
        return side;
    }

    private void showTab(CatalogProject p) {
        tabBody.getChildren().clear();
        if (tab == 0) {
            if (p.body() != null && !p.body().isBlank()) {
                Label body = UiKit.label(plain(p.body()), "catalog-description");
                tabBody.getChildren().add(body);
            } else {
                tabBody.getChildren().add(UiKit.label(I18n.tr("ui.catalog.noDescription"), "muted"));
            }
        } else {
            tabBody.getChildren().addAll(allVersions, versions);
            loadVersions();
        }
        tabBody.getStyleClass().remove("tab-body");
        tabBody.getStyleClass().add("tab-body");
        Motion.enter(tabBody, Motion.Speed.FAST, Motion.ENTER_OFFSET);
    }

    // ------------------------------------------------------------------ install preview

    /** plan the install into the chosen project without downloading anything, so the page shows what comes with it */
    private void loadPreview() {
        previewToken.cancel();
        CancellationToken token = new CancellationToken();
        previewToken = token;
        Project t = target.get();
        CatalogProject item = full != null ? full : summary;
        if (item.kind() == ContentKind.MODPACK) {
            return;
        }
        if (t == null) {
            preview.getChildren().setAll(UiKit.label(I18n.tr("ui.catalog.previewNoProject"), "muted", "small"));
            return;
        }
        preview.getChildren().setAll(UiKit.label(I18n.tr("ui.catalog.previewFor", t.name, t.minecraftVersion), "muted", "small"),
            UiKit.skeleton("line"), UiKit.skeleton("short"), UiKit.skeleton("block"));
        Fx.run(() -> {
            CatalogVersion v = CatalogInstall.best(provider, item, t, token).orElse(null);
            if (v == null) {
                return null;
            }
            return new Previewed(v, ctx.services().planner().plan(t, provider, v, item.kind(), false, token));
        }, got -> {
            if (token.isCancelled()) {
                return;
            }
            if (got == null) {
                preview.getChildren().setAll(UiKit.banner("warning", I18n.tr("ui.catalog.noCompatible")));
                return;
            }
            preview.getChildren().setAll(UiKit.label(I18n.tr("ui.catalog.previewVersion", got.version().versionNumber(), t.name),
                "muted", "small"));
            preview.getChildren().add(CatalogInstall.files(got.plan()));
            preview.getChildren().addAll(CatalogInstall.issues(got.plan()));
        }, err -> {
            if (token.isCancelled() || Tasks.isCancelled(err)) {
                return;
            }
            preview.getChildren().setAll(UiKit.errorState(Tasks.message(err), this::loadPreview));
        });
    }

    private record Previewed(CatalogVersion version, InstallPlan plan) {
    }

    // ------------------------------------------------------------------ versions

    /** a readable plain-text version of a Markdown or HTML description (no remote content is executed) */
    static String plain(String text) {
        String s = text.replaceAll("(?s)<!--.*?-->", "")
            .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n\n").replaceAll("<[^>]+>", "")
            .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
            .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
            .replaceAll("(?m)^#{1,6}\\s*", "")
            .replaceAll("\\*\\*|__|`", "")
            .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        s = s.replaceAll("\n{3,}", "\n\n").trim();
        return s.length() > 6000 ? s.substring(0, 6000) + "…" : s;
    }

    private void loadVersions() {
        if (tab != 1) {
            return;
        }
        Project t = target.get();
        versions.getChildren().setAll(UiKit.skeletonCard(), UiKit.skeletonCard());
        boolean all = allVersions.isSelected() || t == null;
        String mc = all ? null : t.minecraftVersion;
        var loader = all || summary.kind() != ContentKind.MOD || t.loader.isVanilla() ? null : t.loader.type;
        Fx.run(() -> provider.versions(summary.id(), mc, loader, new CancellationToken()), list -> showVersions(list, all, t),
            err -> versions.getChildren().setAll(UiKit.errorState(Tasks.message(err), this::loadVersions)));
    }

    private void showVersions(List<CatalogVersion> list, boolean all, Project t) {
        versions.getChildren().clear();
        if (list.isEmpty()) {
            versions.getChildren().add(UiKit.emptyState(Icons.PACKAGE, I18n.tr("ui.catalog.noVersionsTitle"),
                I18n.tr(all ? "ui.catalog.noVersions" : "ui.catalog.noCompatible"), null));
            return;
        }
        DateTimeFormatter df = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(I18n.locale())
            .withZone(ZoneId.systemDefault());
        CatalogProject item = full != null ? full : summary;
        for (CatalogVersion v : list.stream().limit(VERSIONS_SHOWN).toList()) {
            FlowPane meta = new FlowPane();
            meta.getStyleClass().add("chip-row");
            meta.getChildren().add(UiKit.chip(v.channel(), "release".equals(v.channel()) ? "success" : "warning"));
            if (!v.gameVersions().isEmpty()) {
                meta.getChildren().add(UiKit.label(String.join(", ", v.gameVersions().stream().limit(6).toList()), "muted", "small"));
            }
            if (!v.loaders().isEmpty()) {
                meta.getChildren().add(UiKit.label(String.join(", ", v.loaders()), "faint", "small"));
            }
            VBox text = new VBox(UiKit.label(v.name() == null || v.name().isBlank() ? v.versionNumber() : v.name(), "project-name"),
                meta, UiKit.label(v.published() == null ? "" : df.format(v.published()), "faint", "small"));
            text.getStyleClass().add("row-text");
            HBox.setHgrow(text, Priority.ALWAYS);
            Button install = UiKit.button(I18n.tr("ui.discover.install"), Icons.DOWNLOAD,
                () -> CatalogInstall.install(ctx, provider, item, v, target.get(), this::loadPreview));
            install.setAccessibleText(I18n.tr("ui.discover.installAccessible", v.versionNumber()));
            HBox row = new HBox(text, install);
            row.getStyleClass().addAll("card", "flat", "version-row");
            versions.getChildren().add(row);
        }
    }
}
