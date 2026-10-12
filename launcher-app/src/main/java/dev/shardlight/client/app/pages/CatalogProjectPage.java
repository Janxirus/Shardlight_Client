package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.CatalogInstall;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.RemoteImage;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.task.CancellationToken;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

/** One catalog project: description, pictures, and its versions with an Install button each. */
public final class CatalogProjectPage implements Page {
    private final AppContext ctx;
    private final CatalogProvider provider;
    private final CatalogProject summary;
    private final Project target;
    private final VBox root = new VBox(16);
    private final VBox versions = new VBox(6);
    private final CheckBox allVersions = new CheckBox();
    private CatalogProject full;

    public CatalogProjectPage(AppContext ctx, CatalogProvider provider, CatalogProject item, Project target) {
        this.ctx = ctx;
        this.provider = provider;
        this.summary = item;
        this.target = target;
        allVersions.setText(I18n.tr("ui.catalog.allVersions"));
        allVersions.setOnAction(e -> loadVersions());
        render(item);
        Fx.run(() -> provider.project(item.id(), new CancellationToken()), p -> {
            full = p;
            render(p);
        }, err -> root.getChildren().add(1, UiKit.banner("warning", Tasks.message(err))));
    }

    @Override
    public String title() {
        return summary.title();
    }

    @Override
    public Region view() {
        return root;
    }

    @Override
    public Node actions() {
        URI page = full != null && full.pageUrl() != null ? full.pageUrl() : summary.pageUrl();
        return page == null ? null : UiKit.button(I18n.tr("ui.catalog.openOn", provider.displayName()), Icons.EXTERNAL,
            () -> ctx.openUrl(page.toString()));
    }

    private void render(CatalogProject p) {
        root.getChildren().clear();
        NumberFormat nf = NumberFormat.getIntegerInstance(I18n.locale());
        FlowPane meta = new FlowPane(6, 6,
            UiKit.chip(I18n.tr("ui.discover.downloads", nf.format(p.downloads())), null, Icons.DOWNLOAD));
        if (p.license() != null) {
            meta.getChildren().add(UiKit.chip(p.license(), null));
        }
        if (p.clientSide() != null) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.catalog.client", p.clientSide()), null));
        }
        if (p.loaders() != null) {
            p.loaders().stream().limit(5).forEach(l -> meta.getChildren().add(UiKit.chip(l, null)));
        }
        VBox head = new VBox(6, UiKit.label(p.title(), "h1"),
            UiKit.label(p.author() == null ? "" : I18n.tr("ui.discover.by", p.author()), "muted"),
            UiKit.label(p.summary() == null ? "" : p.summary()), meta);
        HBox.setHgrow(head, Priority.ALWAYS);
        Button install = p.kind() == ContentKind.MODPACK
            ? UiKit.primary(I18n.tr("ui.discover.newProjectFromPack"), Icons.PLUS, () -> CatalogInstall.install(ctx, provider, p, null, null, null))
            : UiKit.primary(I18n.tr("ui.catalog.installLatest"), Icons.DOWNLOAD, () -> CatalogInstall.install(ctx, provider, p, null,
                target, null));
        install.getStyleClass().add("large");
        HBox top = new HBox(18, RemoteImage.square(p.iconUrl(), 96, Icons.PACKAGE), head, install);
        top.setAlignment(Pos.TOP_LEFT);
        root.getChildren().add(top);
        if (target != null) {
            root.getChildren().add(UiKit.label(I18n.tr("ui.catalog.forProject", target.name, target.minecraftVersion,
                target.loader.isVanilla() ? I18n.tr("ui.project.vanilla") : target.loader.type.displayName()), "faint", "small"));
        }
        if (p.gallery() != null && !p.gallery().isEmpty()) {
            HBox gallery = new HBox(10);
            p.gallery().stream().limit(3).forEach(u -> gallery.getChildren().add(RemoteImage.wide(u, 300)));
            root.getChildren().add(gallery);
        }
        if (p.body() != null && !p.body().isBlank()) {
            Label body = UiKit.label(plain(p.body()));
            body.setMaxWidth(860);
            root.getChildren().add(UiKit.section(I18n.tr("ui.catalog.description"), body));
        }
        root.getChildren().add(UiKit.section(I18n.tr("ui.catalog.versions"), allVersions, versions));
        loadVersions();
    }

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
        versions.getChildren().setAll(UiKit.label(I18n.tr("ui.catalog.loadingVersions"), "muted"));
        boolean all = allVersions.isSelected() || target == null;
        String mc = all ? null : target.minecraftVersion;
        var loader = all || summary.kind() != ContentKind.MOD || target.loader.isVanilla() ? null : target.loader.type;
        Fx.run(() -> provider.versions(summary.id(), mc, loader, new CancellationToken()), list -> showVersions(list, all),
            err -> versions.getChildren().setAll(UiKit.banner("danger", Tasks.message(err))));
    }

    private void showVersions(List<CatalogVersion> list, boolean all) {
        versions.getChildren().clear();
        if (list.isEmpty()) {
            versions.getChildren().add(UiKit.label(I18n.tr(all ? "ui.catalog.noVersions" : "ui.catalog.noCompatible"), "muted"));
            return;
        }
        DateTimeFormatter df = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(I18n.locale())
            .withZone(ZoneId.systemDefault());
        CatalogProject item = full != null ? full : summary;
        for (CatalogVersion v : list.stream().limit(40).toList()) {
            FlowPane meta = new FlowPane(6, 4);
            meta.getChildren().add(UiKit.chip(v.channel() == null ? "?" : v.channel(),
                "release".equals(v.channel()) ? "success" : "warning"));
            if (v.gameVersions() != null) {
                meta.getChildren().add(UiKit.label(String.join(", ", v.gameVersions().stream().limit(6).toList()), "muted", "small"));
            }
            if (v.loaders() != null && !v.loaders().isEmpty()) {
                meta.getChildren().add(UiKit.label(String.join(", ", v.loaders()), "faint", "small"));
            }
            VBox text = new VBox(3, UiKit.label(v.name() == null || v.name().isBlank() ? v.versionNumber() : v.name(), "project-name"),
                meta, UiKit.label(v.published() == null ? "" : df.format(v.published()), "faint", "small"));
            HBox.setHgrow(text, Priority.ALWAYS);
            Button install = UiKit.button(I18n.tr("ui.discover.install"), Icons.DOWNLOAD,
                () -> CatalogInstall.install(ctx, provider, item, v, target, null));
            HBox row = new HBox(10, text, install);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().addAll("card", "flat");
            versions.getChildren().add(row);
        }
    }
}
