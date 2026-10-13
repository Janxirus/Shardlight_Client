package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.InstallButton;
import dev.shardlight.client.app.ui.Cards;
import dev.shardlight.client.app.ui.Fields;
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
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.task.CancellationToken;
import javafx.animation.PauseTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Search-first browsing of Modrinth (and CurseForge when an approved API key is configured). A large search
 * field on top, a filter rail on the left (categories, version, loader, environment, sort) and result cards with
 * an Install button that shows its state. While a search runs the old results stay (or skeleton cards show when
 * there are none); when the network fails the last results stay with a message and Retry.
 */
public final class DiscoverPage implements Page {
    private static final int PAGE = 20;
    private static final int CATEGORIES_SHOWN = 10;
    private static final int SKELETONS = 4;

    /** a choice in the version or loader filter: follow the project, any, or one value */
    private record Choice(String label, String value, boolean followsProject) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final AppContext ctx;
    private final VBox column = new VBox();
    private final StackPane view = new StackPane(column);
    private final Fields.SearchField search = new Fields.SearchField(I18n.tr("ui.discover.search"));
    private final ObjectProperty<Project> target = new SimpleObjectProperty<>();
    private final ComboBox<Project> targetBox = new ComboBox<>();
    private final ComboBox<Choice> versionBox = new ComboBox<>();
    private final ComboBox<Choice> loaderBox = new ComboBox<>();
    private final ComboBox<SortOrder> sort = new ComboBox<>();
    private final FlowPane envBox;
    private final VBox categoryList = new VBox();
    private final Button showAllCategories = UiKit.button("", null, this::toggleCategories, "ghost", "small");
    private final VBox results = new VBox();
    private final VBox notice = new VBox();
    private final Label status = UiKit.label("", "muted");
    private final FlowPane activeFilters = new FlowPane();
    private final Button more = new Button();
    private final HBox sourceBar = new HBox();
    private final HBox kindBar;
    private final Set<String> categories = new LinkedHashSet<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(350));
    private List<String> allCategories = List.of();
    private boolean categoriesExpanded;
    private String source = "modrinth";
    private ContentKind kind = ContentKind.MOD;
    private SearchQuery.Environment environment;
    private int offset;
    private int shown;
    private boolean updating;
    private CancellationToken inflight = new CancellationToken();
    private CatalogProvider forced;

    public DiscoverPage(AppContext ctx) {
        this.ctx = ctx;
        column.getStyleClass().addAll("discover", "page-column", "stack");
        StackPane.setAlignment(column, Pos.TOP_CENTER);

        search.getStyleClass().add("discover-search");
        debounce.setOnFinished(e -> runSearch(true));
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        search.field().setOnAction(e -> runSearch(true));

        sort.getItems().setAll(SortOrder.values());
        sort.setValue(SortOrder.RELEVANCE);
        sort.setCellFactory(l -> sortCell());
        sort.setButtonCell(sortCell());
        sort.setAccessibleText(I18n.tr("ui.discover.sort"));
        sort.setOnAction(e -> filtersChanged());

        targetBox.setCellFactory(l -> ProjectText.cell(I18n.tr("ui.discover.noProject")));
        targetBox.setButtonCell(ProjectText.cell(I18n.tr("ui.discover.noProject")));
        targetBox.setAccessibleText(I18n.tr("ui.discover.installInto"));
        targetBox.setOnAction(e -> {
            if (!updating) {
                target.set(targetBox.getValue());
            }
        });
        target.addListener((o, a, b) -> {
            if (!java.util.Objects.equals(targetBox.getValue(), b)) {
                updating = true;
                targetBox.setValue(b);
                updating = false;
            }
            fillVersionChoices();
            filtersChanged();
        });

        versionBox.setAccessibleText(I18n.tr("ui.discover.version"));
        versionBox.setOnAction(e -> filtersChanged());
        loaderBox.setAccessibleText(I18n.tr("ui.discover.loader"));
        loaderBox.setOnAction(e -> filtersChanged());
        fillLoaderChoices();

        envBox = UiKit.segmentedWrapping(List.of(I18n.tr("ui.discover.env.any"), I18n.tr("ui.discover.env.client"),
            I18n.tr("ui.discover.env.server")), 0, i -> {
                environment = i == 1 ? SearchQuery.Environment.CLIENT : i == 2 ? SearchQuery.Environment.SERVER : null;
                filtersChanged();
            });

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

        sourceBar.getStyleClass().add("button-row");
        HBox searchRow = new HBox(search, sourceBar);
        searchRow.getStyleClass().add("discover-search-row");
        HBox.setHgrow(search, Priority.ALWAYS);

        HBox kindRow = new HBox(kindBar);
        kindRow.getStyleClass().add("button-row");

        activeFilters.getStyleClass().add("chip-row");
        notice.getStyleClass().add("stack-tight");
        results.getStyleClass().add("discover-results");
        more.getStyleClass().add("discover-more");
        VBox main = new VBox(status, activeFilters, notice, results, more);
        main.getStyleClass().add("discover-main");
        HBox.setHgrow(main, Priority.ALWAYS);
        HBox body = new HBox(rail(), main);
        body.getStyleClass().add("discover-body");
        column.getChildren().addAll(searchRow, kindRow, body);
    }

    private VBox rail() {
        showAllCategories.setOnAction(e -> toggleCategories());
        categoryList.getStyleClass().add("filter-list");
        Button reset = UiKit.button(I18n.tr("ui.discover.reset"), Icons.REFRESH, this::resetFilters, "ghost", "small");
        VBox rail = new VBox(
            UiKit.label(I18n.tr("ui.discover.filters"), "h3"),
            new Fields.Field(I18n.tr("ui.discover.installInto"), fill(targetBox), null),
            new Fields.Field(I18n.tr("ui.discover.version"), fill(versionBox), null),
            new Fields.Field(I18n.tr("ui.discover.loader"), fill(loaderBox), null),
            new Fields.Field(I18n.tr("ui.discover.environment"), envBox, null),
            new Fields.Field(I18n.tr("ui.discover.sort"), fill(sort), null),
            UiKit.section(I18n.tr("ui.discover.categories"), categoryList, showAllCategories),
            reset);
        rail.getStyleClass().addAll("card", "filter-rail");
        return rail;
    }

    private static <T extends Region> T fill(T c) {
        c.setMaxWidth(Double.MAX_VALUE);
        return c;
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
        return view;
    }

    @Override
    public void onShow() {
        Project keep = target.get();
        List<Project> projects = ctx.services().projects().list();
        updating = true;
        targetBox.getItems().setAll(projects);
        updating = false;
        Optional<Project> sel = ctx.selectedProject();
        String want = sel.map(p -> p.id).orElse(keep == null ? null : keep.id);
        Project pick = projects.stream().filter(p -> p.id.equals(want)).findFirst().orElse(projects.isEmpty() ? null : projects.get(0));
        boolean same = java.util.Objects.equals(pick == null ? null : pick.id, keep == null ? null : keep.id);
        updating = true;
        target.set(pick);
        targetBox.setValue(pick);
        updating = false;
        fillVersionChoices();
        buildSources();
        loadCategories();
        if (!same || results.getChildren().isEmpty()) {
            runSearch(true);
        } else {
            refreshButtons();
        }
    }

    // ------------------------------------------------------------------ filters

    private void fillLoaderChoices() {
        Choice pick = loaderBox.getValue();
        List<Choice> items = new ArrayList<>();
        items.add(new Choice(I18n.tr("ui.discover.loader.project"), null, true));
        items.add(new Choice(I18n.tr("ui.discover.loader.any"), null, false));
        for (LoaderType t : LoaderType.values()) {
            if (t != LoaderType.VANILLA) {
                items.add(new Choice(t.displayName(), t.name(), false));
            }
        }
        loaderBox.getItems().setAll(items);
        loaderBox.setValue(pick == null ? items.get(0) : items.stream().filter(c -> c.label().equals(pick.label())).findFirst().orElse(items.get(0)));
    }

    private void fillVersionChoices() {
        Choice pick = versionBox.getValue();
        Project p = target.get();
        List<Choice> items = new ArrayList<>();
        items.add(new Choice(p == null ? I18n.tr("ui.discover.version.any") : I18n.tr("ui.discover.version.project", p.minecraftVersion),
            null, true));
        items.add(new Choice(I18n.tr("ui.discover.version.any"), null, false));
        Set<String> seen = new LinkedHashSet<>();
        for (Project q : ctx.services().projects().list()) {
            if (seen.add(q.minecraftVersion)) {
                items.add(new Choice(q.minecraftVersion, q.minecraftVersion, false));
            }
        }
        boolean wasUpdating = updating;
        updating = true;
        versionBox.getItems().setAll(items);
        Choice keep = pick == null || pick.followsProject() ? items.get(0)
            : items.stream().filter(c -> c.label().equals(pick.label())).findFirst().orElse(items.get(0));
        versionBox.setValue(keep);
        updating = wasUpdating;
    }

    private void filtersChanged() {
        if (!updating) {
            runSearch(true);
        }
    }

    private void resetFilters() {
        updating = true;
        categories.clear();
        sort.setValue(SortOrder.RELEVANCE);
        versionBox.setValue(versionBox.getItems().get(0));
        loaderBox.setValue(loaderBox.getItems().get(0));
        environment = null;
        ((ToggleButton) envBox.getChildren().get(0)).setSelected(true);
        search.field().clear();
        updating = false;
        showCategories();
        runSearch(true);
    }

    private void buildSources() {
        sourceBar.getChildren().clear();
        for (CatalogProvider p : ctx.services().catalogs().all()) {
            ToggleButton b = new ToggleButton(p.displayName());
            b.setSelected(p.id().equals(source));
            b.getStyleClass().add("source-toggle");
            if (!p.isAvailable()) {
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
        boolean modrinth = "modrinth".equals(source);
        envBox.setDisable(!modrinth);
        if (!modrinth) {
            environment = null;
            ((ToggleButton) envBox.getChildren().get(0)).setSelected(true);
            UiKit.tooltip(envBox, I18n.tr("ui.discover.env.modrinthOnly"));
        }
    }

    /** --capture only: search a made-up catalog instead of the real one; {@code clear} drops the results on screen first */
    public void previewCatalog(CatalogProvider catalog, boolean clear) {
        forced = catalog;
        if (clear) {
            results.getChildren().clear();
        }
        loadCategories();
        runSearch(clear);
    }

    private Optional<CatalogProvider> provider() {
        if (forced != null) {
            return Optional.of(forced);
        }
        return ctx.services().catalogs().get(source).filter(CatalogProvider::isAvailable)
            .or(() -> ctx.services().catalogs().get("modrinth"));
    }

    private void loadCategories() {
        Optional<CatalogProvider> p = provider();
        if (p.isEmpty()) {
            return;
        }
        ContentKind k = kind;
        Fx.run(() -> p.get().categories(k, new CancellationToken()), list -> {
            if (k != kind) {
                return;
            }
            allCategories = list;
            showCategories();
        }, err -> {
            allCategories = List.of();
            showCategories();
        });
    }

    private void toggleCategories() {
        categoriesExpanded = !categoriesExpanded;
        showCategories();
    }

    private void showCategories() {
        categoryList.getChildren().clear();
        List<String> names = new ArrayList<>(allCategories.stream().limit(30).toList());
        // chosen categories always stay visible, even when the list is collapsed
        List<String> visible = categoriesExpanded ? names
            : new ArrayList<>(names.stream().limit(CATEGORIES_SHOWN).toList());
        for (String c : categories) {
            if (!visible.contains(c)) {
                visible.add(c);
            }
        }
        for (String c : visible) {
            CheckBox box = new CheckBox(pretty(c));
            box.setSelected(categories.contains(c));
            box.setOnAction(e -> {
                if (box.isSelected()) {
                    categories.add(c);
                } else {
                    categories.remove(c);
                }
                runSearch(true);
            });
            categoryList.getChildren().add(box);
        }
        boolean collapsible = names.size() > CATEGORIES_SHOWN;
        showAllCategories.setVisible(collapsible);
        showAllCategories.setManaged(collapsible);
        showAllCategories.setText(I18n.tr(categoriesExpanded ? "ui.discover.showFewer" : "ui.discover.showAll"));
        if (allCategories.isEmpty()) {
            categoryList.getChildren().setAll(UiKit.label(I18n.tr("ui.discover.noCategories"), "faint", "small"));
        }
    }

    private static String pretty(String category) {
        String key = "ui.category." + category.toLowerCase(Locale.ROOT);
        if (I18n.has(key)) {
            return I18n.tr(key);
        }
        String s = category.replace('-', ' ').replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ search

    private void runSearch(boolean reset) {
        Optional<CatalogProvider> provider = provider();
        if (provider.isEmpty()) {
            return;
        }
        if (reset) {
            offset = 0;
        }
        inflight.cancel();
        CancellationToken token = new CancellationToken();
        inflight = token;
        Project p = target.get();
        Choice v = versionBox.getValue();
        Choice l = loaderBox.getValue();
        String mc = v == null ? null : v.followsProject() ? (p == null ? null : p.minecraftVersion) : v.value();
        LoaderType picked = null;
        if (kind == ContentKind.MOD && l != null) {
            if (l.followsProject()) {
                picked = p != null && !p.loader.isVanilla() ? p.loader.type : null;
            } else if (l.value() != null) {
                picked = LoaderType.valueOf(l.value());
            }
        }
        final LoaderType loader = picked;
        SearchQuery q = new SearchQuery(search.getText() == null ? "" : search.getText().trim(), kind, mc, loader,
            new ArrayList<>(categories), sort.getValue() == null ? SortOrder.RELEVANCE : sort.getValue(), offset, PAGE,
            environment);
        notice.getChildren().clear();
        status.setText(I18n.tr("ui.discover.searching"));
        more.setDisable(true);
        if (reset && results.getChildren().isEmpty()) {
            showSkeletons();
        }
        showActiveFilters(mc, loader);
        Fx.run(() -> provider.get().search(q, token), page -> {
            if (token.isCancelled()) {
                return;
            }
            show(provider.get(), page, p, mc, loader, reset);
        }, err -> {
            if (token.isCancelled() || Tasks.isCancelled(err)) {
                return;
            }
            failed(Tasks.unwrap(err), reset);
        });
    }

    private void showSkeletons() {
        results.getChildren().clear();
        for (int i = 0; i < SKELETONS; i++) {
            results.getChildren().add(UiKit.skeletonCard());
        }
    }

    /** keep whatever results are on screen and say what went wrong, with Retry */
    private void failed(Throwable err, boolean reset) {
        status.setText("");
        more.setDisable(false);
        boolean offline = err instanceof LauncherException le && le.code() == ErrorCode.NETWORK;
        boolean hasResults = results.getChildren().stream().anyMatch(n -> n.getStyleClass().contains("mod-result-card"));
        if (hasResults) {
            String name = provider().map(CatalogProvider::displayName).orElse("Modrinth");
            notice.getChildren().setAll(UiKit.banner("warning", offline ? I18n.tr("ui.discover.offline", name) : Tasks.message(err),
                UiKit.button(I18n.tr("common.retry"), Icons.REFRESH, () -> runSearch(reset))));
            return;
        }
        Runnable retry = () -> runSearch(true);
        results.getChildren().setAll(offline ? UiKit.offlineState(retry) : UiKit.errorState(Tasks.message(err), retry));
        more.setVisible(false);
        more.setManaged(false);
    }

    private void showActiveFilters(String mc, LoaderType loader) {
        activeFilters.getChildren().clear();
        for (String c : categories) {
            activeFilters.getChildren().add(UiKit.filterChip(pretty(c), () -> {
                categories.remove(c);
                showCategories();
                runSearch(true);
            }));
        }
        if (environment != null) {
            activeFilters.getChildren().add(UiKit.filterChip(I18n.tr(environment == SearchQuery.Environment.CLIENT
                ? "ui.discover.env.client" : "ui.discover.env.server"), () -> ((ToggleButton) envBox.getChildren().get(0)).fire()));
        }
        activeFilters.setVisible(!activeFilters.getChildren().isEmpty());
        activeFilters.setManaged(activeFilters.isVisible());
    }

    private void show(CatalogProvider provider, SearchPage page, Project p, String mc, LoaderType loader, boolean reset) {
        if (reset) {
            results.getChildren().clear();
            shown = 0;
        }
        offset = page.offset() + page.hits().size();
        String scope = mc == null ? I18n.tr("ui.discover.anyVersion")
            : kind == ContentKind.MOD && loader != null ? mc + " · " + loader.displayName() : mc;
        status.setText(I18n.tr("ui.discover.results", NumberFormat.getIntegerInstance(I18n.locale()).format(page.total()), scope));
        notice.getChildren().clear();
        if (kind == ContentKind.MOD && p != null && p.loader.isVanilla() && loader == null) {
            notice.getChildren().add(UiKit.banner("warning", I18n.tr("ui.discover.vanillaProject")));
        }
        for (CatalogProject item : page.hits()) {
            results.getChildren().add(row(provider, item));
            shown++;
        }
        if (shown == 0) {
            results.getChildren().setAll(UiKit.emptyState(Icons.SEARCH, I18n.tr("ui.discover.noneTitle"),
                I18n.tr("ui.discover.noneBody"), UiKit.button(I18n.tr("ui.discover.reset"), Icons.REFRESH, this::resetFilters)));
        }
        boolean hasMore = offset < page.total();
        more.setVisible(hasMore);
        more.setManaged(hasMore);
        more.setDisable(false);
    }

    private Node row(CatalogProvider provider, CatalogProject item) {
        Node icon = RemoteImage.square(item.iconUrl(), 64, Icons.PACKAGE);
        NumberFormat nf = NumberFormat.getCompactNumberInstance(I18n.locale(), NumberFormat.Style.SHORT);
        List<Node> chips = new ArrayList<>();
        chips.add(UiKit.chip(I18n.tr("ui.discover.downloads", nf.format(item.downloads())), null, Icons.DOWNLOAD));
        if (item.follows() > 0) {
            chips.add(UiKit.chip(I18n.tr("ui.discover.follows", nf.format(item.follows())), null, Icons.STAR));
        }
        if (item.categories() != null) {
            item.categories().stream().limit(3).forEach(c -> chips.add(UiKit.chip(pretty(c), null)));
        }
        InstallButton install = new InstallButton(ctx, provider, item, target, () -> null, null, false);
        HBox card = Cards.modResult(icon, item.title(), item.author() == null ? "" : I18n.tr("ui.discover.by", item.author()),
            item.summary() == null ? "" : item.summary(), chips, install);
        card.getStyleClass().add("discover-result");
        card.setAccessibleHelp(I18n.tr("ui.discover.openDetails"));
        Cards.onOpen(card, () -> ctx.shell().push(new CatalogProjectPage(ctx, provider, item, target.get())));
        return card;
    }

    private void refreshButtons() {
        for (Node n : results.getChildren()) {
            if (n instanceof HBox card) {
                for (Node c : card.getChildren()) {
                    if (c instanceof InstallButton b) {
                        b.refresh();
                    }
                }
            }
        }
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
