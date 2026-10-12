package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.CatalogInstall;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.RemoteImage;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.SearchPage;
import dev.shardlight.client.catalog.SearchQuery;
import dev.shardlight.client.catalog.SortOrder;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.task.CancellationToken;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Browse Modrinth (and CurseForge when an approved API key is configured) for mods, resource packs, shader
 * packs and modpacks that fit the chosen project's Minecraft version and loader.
 */
public final class DiscoverPage implements Page {
    private static final int PAGE = 20;
    private final AppContext ctx;
    private final VBox root = new VBox(14);
    private final TextField search = new TextField();
    private final ComboBox<Project> target = new ComboBox<>();
    private final ComboBox<SortOrder> sort = new ComboBox<>();
    private final FlowPane categoryBar = new FlowPane(6, 6);
    private final VBox results = new VBox(8);
    private final Label status = UiKit.label("", "muted", "small");
    private final Button more = new Button();
    private final HBox sourceBar = new HBox(6);
    private final HBox kindBar;
    private final Set<String> categories = new LinkedHashSet<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(350));
    private String source = "modrinth";
    private ContentKind kind = ContentKind.MOD;
    private int offset;
    private CancellationToken inflight = new CancellationToken();

    public DiscoverPage(AppContext ctx) {
        this.ctx = ctx;
        search.setPromptText(I18n.tr("ui.discover.search"));
        search.setAccessibleText(I18n.tr("ui.discover.search"));
        search.getStyleClass().add("search-field");
        HBox.setHgrow(search, Priority.ALWAYS);
        debounce.setOnFinished(e -> runSearch(true));
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        search.setOnAction(e -> runSearch(true));

        sort.getItems().setAll(SortOrder.values());
        sort.setValue(SortOrder.RELEVANCE);
        sort.setCellFactory(l -> sortCell());
        sort.setButtonCell(sortCell());
        sort.setAccessibleText(I18n.tr("ui.discover.sort"));
        sort.setOnAction(e -> runSearch(true));

        target.setCellFactory(l -> ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setButtonCell(ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setPrefWidth(260);
        target.setAccessibleText(I18n.tr("ui.discover.for"));
        target.setOnAction(e -> runSearch(true));

        kindBar = UiKit.segmented(List.of(I18n.tr("ui.discover.mods"), I18n.tr("ui.discover.resourcePacks"),
            I18n.tr("ui.discover.shaders"), I18n.tr("ui.discover.modpacks")), 0, i -> {
                kind = switch (i) {
                    case 1 -> ContentKind.RESOURCE_PACK;
                    case 2 -> ContentKind.SHADER_PACK;
                    case 3 -> ContentKind.MODPACK;
                    default -> ContentKind.MOD;
                };
                categories.clear();
                loadCategories();
                runSearch(true);
            });

        more.setText(I18n.tr("ui.discover.more"));
        more.setOnAction(e -> runSearch(false));
        more.setVisible(false);
        more.setManaged(false);

        HBox row1 = new HBox(10, sourceBar, kindBar, UiKit.spacer(), UiKit.label(I18n.tr("ui.discover.for"), "muted"), target);
        row1.setAlignment(Pos.CENTER_LEFT);
        HBox row2 = new HBox(10, search, sort);
        row2.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(row1, row2, categoryBar, status, results, more);
    }

    /** open Discover on a content kind (used by a project's Content tab) */
    public static void openFor(AppContext ctx, ContentKind kind) {
        if (ctx.shell().page("discover") instanceof DiscoverPage d) {
            d.kind = kind;
            int idx = switch (kind) {
                case RESOURCE_PACK -> 1;
                case SHADER_PACK -> 2;
                case MODPACK -> 3;
                default -> 0;
            };
            if (d.kindBar.getChildren().get(idx) instanceof ToggleButton t) {
                t.setSelected(true);
            }
            d.categories.clear();
        }
        ctx.shell().navigate("discover");
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.discover");
    }

    @Override
    public Region view() {
        return root;
    }

    @Override
    public void onShow() {
        Project keep = target.getValue();
        List<Project> projects = ctx.services().projects().list();
        target.getItems().setAll(projects);
        Optional<Project> sel = ctx.selectedProject();
        String want = sel.map(p -> p.id).orElse(keep == null ? null : keep.id);
        target.setValue(projects.stream().filter(p -> p.id.equals(want)).findFirst().orElse(projects.isEmpty() ? null : projects.get(0)));
        buildSources();
        loadCategories();
        runSearch(true);
    }

    private void buildSources() {
        sourceBar.getChildren().clear();
        for (CatalogProvider p : ctx.services().catalogs().all()) {
            ToggleButton b = new ToggleButton(p.displayName());
            b.setSelected(p.id().equals(source));
            b.getStyleClass().add("source-toggle");
            if (!p.isAvailable()) {
                b.setDisable(false);
                UiKit.tooltip(b, I18n.tr(p.unavailableReasonKey()));
            }
            b.setOnAction(e -> {
                if (!p.isAvailable()) {
                    b.setSelected(false);
                    ctx.overlay().message(p.displayName(), I18n.tr(p.unavailableReasonKey()) + "\n\n" + I18n.tr("ui.discover.curseHowTo"));
                    return;
                }
                source = p.id();
                categories.clear();
                buildSources();
                loadCategories();
                runSearch(true);
            });
            sourceBar.getChildren().add(b);
        }
    }

    private Optional<CatalogProvider> provider() {
        return ctx.services().catalogs().get(source).filter(CatalogProvider::isAvailable)
            .or(() -> ctx.services().catalogs().get("modrinth"));
    }

    private void loadCategories() {
        categoryBar.getChildren().clear();
        Optional<CatalogProvider> p = provider();
        if (p.isEmpty()) {
            return;
        }
        ContentKind k = kind;
        Fx.run(() -> p.get().categories(k, new CancellationToken()), list -> {
            if (k != kind) {
                return;
            }
            categoryBar.getChildren().clear();
            for (String c : list.stream().limit(24).toList()) {
                ToggleButton b = new ToggleButton(pretty(c));
                b.getStyleClass().add("chip-toggle");
                b.setSelected(categories.contains(c));
                b.setOnAction(e -> {
                    if (b.isSelected()) {
                        categories.add(c);
                    } else {
                        categories.remove(c);
                    }
                    runSearch(true);
                });
                categoryBar.getChildren().add(b);
            }
        }, err -> { });
    }

    private static String pretty(String category) {
        String s = category.replace('-', ' ').replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void runSearch(boolean reset) {
        Optional<CatalogProvider> provider = provider();
        if (provider.isEmpty()) {
            return;
        }
        if (reset) {
            offset = 0;
            results.getChildren().clear();
        }
        inflight.cancel();
        CancellationToken token = new CancellationToken();
        inflight = token;
        Project p = target.getValue();
        String mc = p == null ? null : p.minecraftVersion;
        var loader = kind == ContentKind.MOD && p != null && !p.loader.isVanilla() ? p.loader.type : null;
        SearchQuery q = new SearchQuery(search.getText() == null ? "" : search.getText().trim(), kind, mc, loader,
            new ArrayList<>(categories), sort.getValue() == null ? SortOrder.RELEVANCE : sort.getValue(), offset, PAGE);
        status.setText(I18n.tr("ui.discover.searching"));
        more.setDisable(true);
        Fx.run(() -> provider.get().search(q, token), page -> {
            if (token.isCancelled()) {
                return;
            }
            show(provider.get(), page, p);
        }, err -> {
            if (token.isCancelled() || Tasks.isCancelled(err)) {
                return;
            }
            status.setText("");
            results.getChildren().setAll(UiKit.banner("danger", Tasks.message(err),
                UiKit.button(I18n.tr("common.retry"), Icons.REFRESH, () -> runSearch(true))));
        });
    }

    private void show(CatalogProvider provider, SearchPage page, Project p) {
        offset = page.offset() + page.hits().size();
        String scope = p == null ? I18n.tr("ui.discover.anyVersion")
            : kind == ContentKind.MOD && !p.loader.isVanilla() ? p.minecraftVersion + " · " + p.loader.type.displayName()
            : p.minecraftVersion;
        status.setText(I18n.tr("ui.discover.results", NumberFormat.getIntegerInstance(I18n.locale()).format(page.total()), scope));
        if (kind == ContentKind.MOD && p != null && p.loader.isVanilla()) {
            results.getChildren().add(UiKit.banner("warning", I18n.tr("ui.discover.vanillaProject")));
        }
        for (CatalogProject item : page.hits()) {
            results.getChildren().add(row(provider, item, p));
        }
        if (page.hits().isEmpty() && results.getChildren().isEmpty()) {
            results.getChildren().add(UiKit.emptyState(Icons.SEARCH, I18n.tr("ui.discover.noneTitle"), I18n.tr("ui.discover.noneBody"), null));
        }
        boolean hasMore = offset < page.total();
        more.setVisible(hasMore);
        more.setManaged(hasMore);
        more.setDisable(false);
    }

    private Node row(CatalogProvider provider, CatalogProject item, Project p) {
        Node icon = RemoteImage.square(item.iconUrl(), 56, Icons.PACKAGE);
        Label title = UiKit.label(item.title(), "project-name");
        Label by = UiKit.label(item.author() == null ? "" : I18n.tr("ui.discover.by", item.author()), "muted", "small");
        HBox head = new HBox(8, title, by);
        head.setAlignment(Pos.BASELINE_LEFT);
        Label summary = UiKit.label(item.summary() == null ? "" : item.summary(), "muted");
        FlowPane meta = new FlowPane(6, 4);
        NumberFormat nf = NumberFormat.getCompactNumberInstance(I18n.locale(), NumberFormat.Style.SHORT);
        meta.getChildren().add(UiKit.chip(I18n.tr("ui.discover.downloads", nf.format(item.downloads())), null, Icons.DOWNLOAD));
        if (item.follows() > 0) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.discover.follows", nf.format(item.follows())), null, Icons.STAR));
        }
        if (item.categories() != null) {
            item.categories().stream().limit(4).forEach(c -> meta.getChildren().add(UiKit.chip(pretty(c), null)));
        }
        boolean installed = p != null && isInstalled(p, provider, item);
        if (installed) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.discover.installed"), "success", Icons.CHECK));
        }
        VBox text = new VBox(4, head, summary, meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button install = item.kind() == ContentKind.MODPACK
            ? UiKit.primary(I18n.tr("ui.discover.newProjectFromPack"), Icons.PLUS, () -> CatalogInstall.install(ctx, provider, item,
                null, null, null))
            : UiKit.primary(I18n.tr(installed ? "ui.discover.reinstall" : "ui.discover.install"), Icons.DOWNLOAD,
                () -> CatalogInstall.install(ctx, provider, item, null, target.getValue(), () -> runSearch(true)));
        install.setAccessibleText(I18n.tr("ui.discover.installAccessible", item.title()));
        Button details = UiKit.button(I18n.tr("ui.discover.details"), null,
            () -> ctx.shell().push(new CatalogProjectPage(ctx, provider, item, target.getValue())));
        VBox buttons = new VBox(6, install, details);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        install.setMaxWidth(Double.MAX_VALUE);
        details.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(14, icon, text, buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("card", "flat", "result-row");
        row.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                ctx.shell().push(new CatalogProjectPage(ctx, provider, item, target.getValue()));
            }
        });
        return row;
    }

    static boolean isInstalled(Project p, CatalogProvider provider, CatalogProject item) {
        for (ContentEntry e : p.content) {
            if (item.id().equals(e.projectId) && e.source.name().equalsIgnoreCase(provider.id())) {
                return true;
            }
        }
        return false;
    }

    private static ListCell<SortOrder> sortCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(SortOrder s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : I18n.tr("ui.discover.sort." + s.name().toLowerCase(java.util.Locale.ROOT)));
            }
        };
    }

}
