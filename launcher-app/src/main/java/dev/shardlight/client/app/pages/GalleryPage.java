package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Cards;
import dev.shardlight.client.app.ui.Fields;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Lists;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProgressRing;
import dev.shardlight.client.app.ui.Shimmer;
import dev.shardlight.client.app.ui.StepProgress;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * A hidden page showing every shared component in each of its states, for checking the design system at a
 * glance (open it from the command palette, or with --page gallery). The samples are labelled as samples;
 * nothing here reads or changes real data.
 */
public final class GalleryPage implements Page {
    private final AppContext ctx;
    private final VBox root = new VBox();

    /** a row in the list and table samples */
    private record Sample(String name, String group, int size) {
    }

    public GalleryPage(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().add("gallery");
        root.getChildren().addAll(
            UiKit.label(I18n.tr("ui.gallery.intro"), "muted"),
            grid(section("buttons", buttons()), section("inputs", inputs())),
            grid(section("badges", badges()), section("progress", progress())),
            grid(section("feedback", feedback()), section("states", states())),
            section("cards", cards()),
            grid(section("lists", list()), section("tables", table())));
    }

    @Override
    public String title() {
        return I18n.tr("ui.gallery.title");
    }

    @Override
    public Region view() {
        return root;
    }

    private static String t(String key, Object... args) {
        return I18n.tr("ui.gallery." + key, args);
    }

    private static FlowPane grid(Node... sections) {
        FlowPane f = new FlowPane(sections);
        f.getStyleClass().add("gallery-grid");
        return f;
    }

    private static VBox section(String id, Node... content) {
        VBox v = new VBox(UiKit.label(t("section." + id), "h3"));
        v.getChildren().addAll(content);
        v.getStyleClass().addAll("card", "gallery-section");
        return v;
    }

    private static HBox row(Node... nodes) {
        HBox h = new HBox(nodes);
        h.getStyleClass().add("gallery-row");
        return h;
    }

    private static Label caption(String key) {
        return UiKit.label(t(key), "eyebrow");
    }

    private Node[] buttons() {
        Button primary = UiKit.primary(t("primary"), Icons.PLAY, () -> { });
        Button secondary = UiKit.button(t("secondary"), Icons.FOLDER, () -> { });
        Button ghost = UiKit.button(t("ghost"), null, () -> { }, "ghost");
        Button danger = UiKit.button(t("danger"), Icons.TRASH, () -> { }, "danger");
        Button icon = UiKit.iconButton(Icons.MORE, t("iconOnly"), () -> { });
        Button small = UiKit.button(t("small"), null, () -> { }, "small");
        Button large = UiKit.button(t("large"), Icons.PLUS, () -> { }, "primary", "large");
        Button disabled = UiKit.button(t("disabled"), Icons.DOWNLOAD, () -> { });
        StackPane explained = UiKit.explained(disabled);
        UiKit.disable(disabled, t("disabledWhy"));
        Button loading = UiKit.primary(t("save"), Icons.CHECK, () -> { });
        Button busy = UiKit.button(t("loadingDemo"), Icons.REFRESH, () -> { });
        busy.setOnAction(e -> {
            UiKit.loading(busy, true);
            PauseTransition p = new PauseTransition(Duration.millis(1500));
            p.setOnFinished(x -> UiKit.loading(busy, false));
            p.play();
        });
        Button copy = UiKit.copyButton(() -> t("copyText"), t("copy"));
        Button copied = UiKit.copyButton(() -> t("copyText"), t("copy"));
        javafx.application.Platform.runLater(() -> {
            UiKit.loading(loading, true);
            UiKit.copied(copied);
        });
        return new Node[] {
            caption("variants"), row(primary, secondary, ghost, danger, icon),
            caption("sizesStates"), row(small, large, explained),
            caption("loadingCopy"), row(loading, busy, copy, copied)};
    }

    private Node[] inputs() {
        Fields.SearchField search = new Fields.SearchField(t("searchPrompt"));
        Fields.NumberField memory = new Fields.NumberField(512, 32768, 512, 4096, t("memory"));
        Fields.Field memoryField = new Fields.Field(t("memory"), memory, t("memoryHelp")).bindError(memory.errorProperty());
        TextField name = new TextField(t("badName"));
        Fields.Field nameField = new Fields.Field(t("name"), name, null);
        nameField.setError(t("nameError"));
        Fields.ValueSlider slider = new Fields.ValueSlider(2, 32, 12, v -> t("chunks", Math.round(v)), t("renderDistance"));
        HBox seg = UiKit.segmented(List.of(t("segA"), t("segB"), t("segC")), 1, i -> { });
        CheckBox check = new CheckBox(t("checkbox"));
        check.setSelected(true);
        ToggleSwitch sw = new ToggleSwitch(true);
        return new Node[] {search, row(memoryField, nameField), new Fields.Field(t("renderDistance"), slider, null), row(seg),
            row(check, UiKit.settingRow(t("switch"), null, sw))};
    }

    private Node[] badges() {
        FlowPane chips = new FlowPane(UiKit.chip(t("chip"), null), UiKit.chip(t("chipAccent"), "accent", Icons.SHARD),
            UiKit.chip(t("chipSuccess"), "success"), UiKit.chip(t("chipWarning"), "warning"),
            UiKit.chip(t("chipDanger"), "danger"), UiKit.chip(t("chipInfo"), "info"));
        chips.getStyleClass().add("chip-row");
        FlowPane filters = new FlowPane();
        filters.getStyleClass().add("chip-row");
        for (String key : List.of("filterA", "filterB", "filterC")) {
            filters.getChildren().add(UiKit.filterChip(t(key), () -> filters.getChildren().removeIf(
                n -> n.getUserData() == key)));
            filters.getChildren().get(filters.getChildren().size() - 1).setUserData(key);
        }
        return new Node[] {caption("badges"), row(UiKit.badge(3), UiKit.badge(12), UiKit.badge(120)),
            caption("chips"), chips, caption("filters"), filters};
    }

    private Node[] progress() {
        javafx.scene.control.ProgressBar bar = UiKit.progress(0.65);
        ProgressRing ring = new ProgressRing(0.4);
        ProgressRing small = new ProgressRing(0.75);
        small.getStyleClass().add("small");
        ProgressRing unknown = new ProgressRing(-1);
        Shimmer shimmer = new Shimmer();
        StepProgress steps = new StepProgress(List.of(t("stepA"), t("stepB"), t("stepC"), t("stepD")), 2);
        return new Node[] {caption("bar"), bar, caption("rings"), row(ring, small, unknown), caption("shimmer"), shimmer,
            caption("steps"), steps};
    }

    private Node[] feedback() {
        Overlay o = ctx.overlay();
        Node tip = UiKit.button(t("tooltip"), Icons.INFO, () -> { }, "ghost");
        UiKit.tooltip(tip, t("tooltipText"));
        FlowPane toasts = new FlowPane(
            UiKit.button(t("toastSuccess"), null, () -> o.toast("success", t("toastSuccessText")), "small"),
            UiKit.button(t("toastInfo"), null, () -> o.toast("info", t("toastInfoText"),
                new Overlay.Action(I18n.tr("ui.toast.open"), () -> { })), "small"),
            UiKit.button(t("toastWarning"), null, () -> o.toast("warning", t("toastWarningText")), "small"),
            UiKit.button(t("toastError"), null, () -> o.toast("error", t("toastErrorText"),
                new Overlay.Action(I18n.tr("ui.state.retry"), () -> { })), "small"),
            UiKit.button(t("undo"), null, () -> o.undo(t("undoText"), () -> o.toast("info", t("undone"))), "small"),
            UiKit.button(t("confirm"), null, () -> o.confirm(t("confirmTitle"), t("confirmText"), List.of(t("confirmItem")),
                t("confirmButton"), true, () -> { }), "small"));
        toasts.getStyleClass().add("chip-row");
        return new Node[] {UiKit.banner("info", t("bannerInfo")), UiKit.banner("success", t("bannerSuccess")),
            UiKit.banner("warning", t("bannerWarning")), UiKit.banner("danger", t("bannerDanger")), row(tip), toasts};
    }

    private Node[] states() {
        return new Node[] {caption("skeleton"), UiKit.skeletonCard(),
            caption("empty"), UiKit.empty(Icons.of(Icons.LIBRARY, 40), t("emptyText"),
                UiKit.primary(t("emptyAction"), Icons.PLUS, () -> { })),
            caption("error"), UiKit.errorState(t("errorText"), () -> { }),
            caption("offline"), UiKit.offlineState(() -> { })};
    }

    private Node[] cards() {
        double art = ThemeManager.em(3.143);
        Node projectArt = UiKit.iconBox(Icons.of(Icons.CUBE, 22), art);
        VBox project = Cards.project(projectArt, t("projectName"), t("projectMeta"), t("projectDesc"),
            List.of(UiKit.chip(t("chipPreset"), null), UiKit.chip(t("chipAccent"), "accent", Icons.SHARD)),
            UiKit.primary(I18n.tr("ui.play.play"), Icons.PLAY, () -> { }), UiKit.button(I18n.tr("ui.library.open"), null, () -> { }));
        VBox project2 = Cards.project(UiKit.iconBox(Icons.of(Icons.BOLT, 22), art), t("projectName2"), t("projectMeta"),
            t("projectDesc2"), List.of(UiKit.chip(t("chipWarning"), "warning")),
            UiKit.primary(I18n.tr("ui.play.play"), Icons.PLAY, () -> { }), UiKit.button(I18n.tr("ui.library.open"), null, () -> { }));
        Cards.onOpen(project, () -> { });
        Cards.onOpen(project2, () -> { });
        Node mod = Cards.modResult(UiKit.iconBox(Icons.of(Icons.PACKAGE, 22), art), t("modName"), t("modAuthor"),
            t("modDesc"), List.of(UiKit.chip(t("modChip"), null), UiKit.chip(t("modChip2"), "info")),
            UiKit.primary(t("install"), Icons.DOWNLOAD, () -> { }));
        VBox preset = Cards.preset(Icons.GAUGE, t("presetA"), t("presetADesc"), true);
        VBox preset2 = Cards.preset(Icons.BOLT, t("presetB"), t("presetBDesc"), false);
        Region swatchA = new Region();
        swatchA.getStyleClass().addAll("theme-swatch-sample", "dark-sample");
        Region swatchB = new Region();
        swatchB.getStyleClass().addAll("theme-swatch-sample", "light-sample");
        VBox themeA = Cards.theme(t("themeDark"), swatchA, true);
        VBox themeB = Cards.theme(t("themeLight"), swatchB, false);
        Node server = Cards.server(UiKit.iconBox(Icons.of(Icons.SERVERS, 20), art), t("serverName"), t("serverAddress"),
            UiKit.chip(t("serverStatus"), "success"), UiKit.button(t("join"), Icons.PLAY, () -> { }));
        Region picture = new Region();
        picture.getStyleClass().add("world-sample");
        VBox world = Cards.world(picture, t("worldName"), t("worldMeta"), UiKit.iconButton(Icons.MORE, t("more"), () -> { }));
        Node stat = Cards.stat(Icons.CLOCK, t("statValue"), t("statLabel"));
        Node stat2 = Cards.stat(Icons.PACKAGE, t("statValue2"), t("statLabel2"));
        FlowPane f = new FlowPane(project, project2, preset, preset2, themeA, themeB, world);
        f.getStyleClass().add("gallery-cards");
        FlowPane f2 = new FlowPane(mod, server, stat, stat2);
        f2.getStyleClass().add("gallery-cards");
        return new Node[] {f, f2};
    }

    private List<Sample> samples() {
        return List.of(new Sample(t("rowA"), t("groupA"), 1840), new Sample(t("rowB"), t("groupA"), 512),
            new Sample(t("rowC"), t("groupA"), 96), new Sample(t("rowD"), t("groupB"), 3072),
            new Sample(t("rowE"), t("groupB"), 240), new Sample(t("rowF"), t("groupC"), 64),
            new Sample(t("rowG"), t("groupC"), 1200), new Sample(t("rowH"), t("groupC"), 8));
    }

    private Node[] list() {
        ListView<Sample> list = new ListView<>();
        list.getItems().setAll(samples());
        Lists.rows(list, s -> new VBox(UiKit.label(s.name(), "strong"), UiKit.label(s.group(), "faint", "small")),
            s -> List.of(UiKit.iconButton(Icons.FOLDER, t("rowOpen", s.name()), () -> { }),
                UiKit.iconButton(Icons.TRASH, t("rowRemove", s.name()), () -> { })));
        list.getStyleClass().add("gallery-list");
        list.getSelectionModel().selectIndices(1, 2);
        return new Node[] {UiKit.label(t("listHint"), "faint", "small"), Lists.stickyHeader(list, Sample::group)};
    }

    private Node[] table() {
        TableView<Sample> table = Lists.table(new TableView<>(), UiKit.empty(Icons.of(Icons.LIST, 32), t("emptyText"), null));
        TableColumn<Sample, String> name = Lists.column(t("colName"), Sample::name);
        TableColumn<Sample, String> group = Lists.column(t("colGroup"), Sample::group);
        TableColumn<Sample, Number> size = Lists.numberColumn(t("colSize"), Sample::size, n -> UiKit.bytes(n.longValue() * 1024));
        TableColumn<Sample, Sample> actions = Lists.actionsColumn(s -> List.of(
            UiKit.iconButton(Icons.TRASH, t("rowRemove", s.name()), () -> { })));
        table.getColumns().add(name);
        table.getColumns().add(group);
        table.getColumns().add(size);
        table.getColumns().add(actions);
        table.getItems().setAll(samples());
        table.getSortOrder().add(size);
        size.setSortType(TableColumn.SortType.DESCENDING);
        table.sort();
        table.getStyleClass().add("gallery-table");
        return new Node[] {UiKit.label(t("tableHint"), "faint", "small"), table};
    }
}
