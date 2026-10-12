package dev.shardlight.client.app.ui;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The frame around every page: a collapsible sidebar (labels at 240 px, icons only at 72 px) whose accent pill
 * slides to the active item, a 52 px top bar with breadcrumbs, back and forward, the page's own actions and the
 * global tools, the page area, the Play bar along the bottom, and the overlay for dialogs, popovers and toasts.
 *
 * <p>Navigation keeps a history: back and forward work with the buttons, Alt+Left / Alt+Right and the mouse's
 * side buttons, and a page shown again by going back keeps its scroll position. Pages not in the sidebar (a
 * project, a catalog entry) are pushed on top of the page that opened them and show up in the breadcrumbs.
 */
public final class Shell {
    /** a sidebar destination */
    public record NavItem(String id, String titleKey, String icon, Supplier<Page> factory) {
    }

    /** a place in the history: the page, the trail of pages leading to it, and the sidebar item it sits under */
    private record Entry(Page page, List<Page> trail, String navId) {
    }

    private static final int HISTORY = 50;
    private static final String SCROLL = "sl.scroll";

    private final StackPane root = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final VBox sidebar = new VBox();
    private final VBox nav = new VBox();
    private final Pane pillLayer = new Pane();
    private final StackPane pill = new StackPane();
    private final VBox pinned = new VBox();
    private final VBox sidebarFooter = new VBox();
    private final VBox brandText;
    private final Label version;
    private final Button collapse;
    private final StackPane pageHost = new StackPane();
    private final HBox topbar = new HBox();
    private final HBox crumbs = new HBox();
    private final HBox titleActions = new HBox();
    private final HBox tools = new HBox();
    private final StackPane playBarHost = new StackPane();
    private final Button back;
    private final Button forward;
    private final Overlay overlay;
    private final ToggleGroup group = new ToggleGroup();
    private final Map<String, NavItem> items = new LinkedHashMap<>();
    private final Map<String, Supplier<Page>> hidden = new LinkedHashMap<>();
    private final Map<String, Page> built = new LinkedHashMap<>();
    private final Map<String, ToggleButton> buttons = new LinkedHashMap<>();
    private final Map<String, StackPane> entries = new LinkedHashMap<>();
    private final Map<String, Label> badges = new LinkedHashMap<>();
    private final Deque<Entry> backStack = new ArrayDeque<>();
    private final Deque<Entry> forwardStack = new ArrayDeque<>();
    private final List<Consumer<Boolean>> collapseListeners = new ArrayList<>();
    private final Region navGrow = new Region();
    private Entry entry;
    private boolean collapsed;
    private boolean pillPlaced;
    private Runnable palette = () -> { };

    public Shell(Color accent) {
        sidebar.getStyleClass().add("sidebar");
        Node logo = ShardLogo.create(30, accent, ThemeManager.accent2());
        Label name = UiKit.label(BuildInfo.NAME, "brand-name");
        name.setWrapText(false);
        Label sub = UiKit.label(I18n.tr("ui.brand.subtitle"), "brand-sub");
        sub.setWrapText(false);
        brandText = new VBox(name, sub);
        HBox brand = new HBox(logo, brandText);
        brand.getStyleClass().add("brand");

        pinned.getStyleClass().add("sidebar-pinned");
        nav.getStyleClass().add("nav");
        VBox.setVgrow(navGrow, Priority.ALWAYS);
        pill.getStyleClass().add("nav-pill");
        Region bar = new Region();
        bar.getStyleClass().add("nav-pill-bar");
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(bar, Pos.CENTER_LEFT);
        pill.getChildren().add(bar);
        pill.setManaged(false);
        pill.setVisible(false);
        pill.setMouseTransparent(true);
        pillLayer.getChildren().add(pill);
        pillLayer.setMouseTransparent(true);
        StackPane navStack = new StackPane(pillLayer, nav);
        navStack.getStyleClass().add("nav-stack");
        VBox.setVgrow(navStack, Priority.ALWAYS);
        nav.getChildren().add(navGrow);
        nav.layoutBoundsProperty().addListener((o, a, b) -> placePill(false));

        collapse = new Button(I18n.tr("ui.sidebar.collapse"));
        collapse.setGraphic(Icons.of(Icons.SIDEBAR, 18));
        collapse.getStyleClass().addAll("nav-button", "sidebar-toggle");
        collapse.setMaxWidth(Double.MAX_VALUE);
        collapse.setMnemonicParsing(false);
        collapse.setOnAction(e -> setCollapsed(!collapsed));
        version = UiKit.label(I18n.tr("ui.sidebar.version", BuildInfo.VERSION), "sidebar-version", "faint", "caption",
            "tabular");
        version.setWrapText(false);
        sidebarFooter.getStyleClass().add("sidebar-footer");
        sidebar.getChildren().addAll(brand, pinned, navStack, sidebarFooter, collapse, version);

        back = UiKit.iconButton(Icons.BACK, I18n.tr("ui.back"), this::back);
        forward = UiKit.iconButton(Icons.FORWARD, I18n.tr("ui.forward"), this::forward);
        UiKit.tooltip(back, I18n.tr("ui.back") + "  (Alt+Left)");
        UiKit.tooltip(forward, I18n.tr("ui.forward") + "  (Alt+Right)");
        HBox history = new HBox(back, forward);
        history.setAlignment(Pos.CENTER_LEFT);
        history.getStyleClass().add("history-buttons");
        crumbs.getStyleClass().add("breadcrumbs");
        crumbs.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(crumbs, Priority.SOMETIMES);
        crumbs.setMinWidth(0);
        titleActions.getStyleClass().add("title-actions");
        titleActions.setAlignment(Pos.CENTER_RIGHT);
        tools.getStyleClass().add("topbar-tools");
        tools.setAlignment(Pos.CENTER_RIGHT);
        topbar.getStyleClass().add("topbar");
        topbar.setFillHeight(false);
        tools.setFillHeight(false);
        titleActions.setFillHeight(false);
        topbar.getChildren().addAll(history, crumbs, UiKit.spacer(), titleActions, tools);

        playBarHost.getStyleClass().add("playbar-host");
        BorderPane main = new BorderPane();
        main.setTop(topbar);
        main.setCenter(pageHost);
        main.setBottom(playBarHost);
        main.getStyleClass().add("main-area");
        frame.setLeft(sidebar);
        frame.setCenter(main);
        root.getChildren().add(frame);
        overlay = new Overlay(root, frame);
        updateHistoryButtons();

        root.addEventFilter(KeyEvent.KEY_PRESSED, this::shortcut);
        root.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !overlay.hasOpenDialog() && entry != null && entry.trail().size() > 1) {
                pop();
                e.consume();
            }
        });
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (overlay.hasOpenDialog()) {
                return;
            }
            if (e.getButton() == MouseButton.BACK) {
                back();
                e.consume();
            } else if (e.getButton() == MouseButton.FORWARD) {
                forward();
                e.consume();
            }
        });
    }

    private void shortcut(KeyEvent e) {
        if (overlay.hasOpenDialog() || e.getCode() == null) {
            return;
        }
        boolean shortcut = e.isShortcutDown() && !e.isAltDown();
        if (shortcut && e.getCode() == KeyCode.K) {
            overlay.closePopover();
            palette.run();
        } else if (shortcut && e.getCode() == KeyCode.F) {
            focusSearch();
        } else if (shortcut && e.getCode() == KeyCode.B) {
            setCollapsed(!collapsed);
        } else if (e.isAltDown() && !e.isShortcutDown() && e.getCode() == KeyCode.LEFT) {
            back();
        } else if (e.isAltDown() && !e.isShortcutDown() && e.getCode() == KeyCode.RIGHT) {
            forward();
        } else if (shortcut && e.getCode().isDigitKey() && !(e.getTarget() instanceof TextField)) {
            int idx = e.getCode().getCode() - KeyCode.DIGIT1.getCode();
            if (idx < 0 || idx >= items.size()) {
                return;
            }
            navigate(items.keySet().toArray(String[]::new)[idx]);
        } else {
            return;
        }
        e.consume();
    }

    /** Ctrl+F: the page's search field, or the command palette when the page has none */
    private void focusSearch() {
        Node view = entry == null ? null : entry.page().view();
        Node box = view == null ? null : view.lookup(".search-box");
        if (box instanceof Fields.SearchField sf && sf.isVisible()) {
            sf.focusField();
            return;
        }
        Node legacy = view == null ? null : view.lookup(".search-field");
        if (legacy == null) {
            legacy = titleActions.lookup(".search-field");
        }
        if (legacy instanceof TextField tf && tf.isVisible()) {
            tf.requestFocus();
            tf.selectAll();
            return;
        }
        palette.run();
    }

    public StackPane root() {
        return root;
    }

    public Overlay overlay() {
        return overlay;
    }

    /** the area at the bottom of the sidebar (the account chip) */
    public VBox sidebarFooter() {
        return sidebarFooter;
    }

    /** what Ctrl+K and the search box open */
    public void setPalette(Runnable r) {
        palette = r;
    }

    public void openPalette() {
        palette.run();
    }

    /** the current project pinned at the top of the sidebar */
    public void setPinned(Node n) {
        pinned.getChildren().setAll(n);
    }

    /** the global tools on the right of the top bar (search, downloads, notifications, account) */
    public void setTools(Node... nodes) {
        tools.getChildren().setAll(nodes);
    }

    public void setPlayBar(Node n) {
        playBarHost.getChildren().setAll(n);
    }

    public void addNav(NavItem item) {
        addNav(item, false);
    }

    /** a destination at the bottom of the sidebar (Settings) */
    public void addFooterNav(NavItem item) {
        addNav(item, true);
    }

    /** a page with no sidebar item, reachable by id (the component gallery) */
    public void addHidden(String id, Supplier<Page> factory) {
        hidden.put(id, factory);
    }

    private void addNav(NavItem item, boolean footer) {
        items.put(item.id(), item);
        String title = I18n.tr(item.titleKey());
        ToggleButton b = new ToggleButton(title);
        b.setGraphic(Icons.of(item.icon(), 19));
        b.getStyleClass().add("nav-button");
        b.setToggleGroup(group);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMnemonicParsing(false);
        b.setAccessibleText(title);
        UiKit.tooltip(b, title + "  (Ctrl+" + items.size() + ")");
        Label badge = UiKit.badge(0);
        badge.getStyleClass().add("nav-badge");
        badge.setMouseTransparent(true);
        StackPane entryBox = new StackPane(b, badge);
        entryBox.getStyleClass().add("nav-entry");
        StackPane.setAlignment(badge, Pos.CENTER_RIGHT);
        b.setOnAction(e -> {
            if (!b.isSelected()) {
                b.setSelected(true);
            }
            navigate(item.id());
        });
        buttons.put(item.id(), b);
        badges.put(item.id(), badge);
        entries.put(item.id(), entryBox);
        entryBox.boundsInParentProperty().addListener((o, a, c) -> placePill(false));
        if (footer) {
            nav.getChildren().add(entryBox);
        } else {
            nav.getChildren().add(nav.getChildren().indexOf(navGrow), entryBox);
        }
        applyCollapsed();
    }

    /** show a small count on a sidebar item (0 hides it) */
    public void setBadge(String id, int count) {
        Label l = badges.get(id);
        if (l != null) {
            UiKit.setBadge(l, count);
            entries.get(id).setAccessibleHelp(count > 0 ? I18n.tr("ui.sidebar.badge", count) : null);
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** icons only (72 px) or icons and labels (240 px) */
    public void setCollapsed(boolean c) {
        if (collapsed == c) {
            return;
        }
        collapsed = c;
        applyCollapsed();
        collapseListeners.forEach(l -> l.accept(c));
        if (!c) {
            Motion.fadeIn(sidebar, Motion.Speed.FAST);
        }
    }

    /** told whenever the sidebar collapses or expands */
    public void onCollapsedChanged(Consumer<Boolean> l) {
        collapseListeners.add(l);
    }

    private void applyCollapsed() {
        sidebar.getStyleClass().remove("collapsed");
        if (collapsed) {
            sidebar.getStyleClass().add("collapsed");
        }
        ContentDisplay d = collapsed ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT;
        for (ToggleButton b : buttons.values()) {
            b.setContentDisplay(d);
        }
        for (Label badge : badges.values()) {
            StackPane.setAlignment(badge, collapsed ? Pos.TOP_RIGHT : Pos.CENTER_RIGHT);
        }
        collapse.setContentDisplay(d);
        collapse.setText(I18n.tr(collapsed ? "ui.sidebar.expand" : "ui.sidebar.collapse"));
        collapse.setAccessibleText(collapse.getText());
        UiKit.tooltip(collapse, collapse.getText() + "  (Ctrl+B)");
        brandText.setVisible(!collapsed);
        brandText.setManaged(!collapsed);
        version.setVisible(!collapsed);
        version.setManaged(!collapsed);
        pillPlaced = false;
        Platform.runLater(() -> placePill(false));
    }

    public String currentId() {
        return entry == null ? null : entry.navId();
    }

    public Page page(String id) {
        return built.computeIfAbsent(id, k -> {
            NavItem item = items.get(k);
            return item != null ? item.factory().get() : hidden.get(k).get();
        });
    }

    /** switch to a sidebar (or hidden) page, as a new step in the history, scrolled to the top */
    public void navigate(String id) {
        if (!items.containsKey(id) && !hidden.containsKey(id)) {
            return;
        }
        Page p = page(id);
        if (entry != null && entry.page() == p && entry.trail().size() == 1) {
            display(entry, true);
            return;
        }
        go(new Entry(p, List.of(p), id), true);
    }

    /** open a page on top of the current one; it appears in the breadcrumbs */
    public void push(Page p) {
        List<Page> trail = new ArrayList<>(entry == null ? List.of() : entry.trail());
        trail.add(p);
        go(new Entry(p, List.copyOf(trail), entry == null ? null : entry.navId()), true);
    }

    /** go up one level in the breadcrumbs */
    public void pop() {
        if (entry == null || entry.trail().size() < 2) {
            return;
        }
        upTo(entry.trail().size() - 2);
    }

    private void upTo(int index) {
        Page target = entry.trail().get(index);
        if (!backStack.isEmpty() && backStack.peek().page() == target) {
            back();
            return;
        }
        go(new Entry(target, List.copyOf(entry.trail().subList(0, index + 1)), entry.navId()), false);
    }

    public boolean canGoBack() {
        return !backStack.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    public void back() {
        if (backStack.isEmpty()) {
            return;
        }
        forwardStack.push(entry);
        display(backStack.pop(), false);
    }

    public void forward() {
        if (forwardStack.isEmpty()) {
            return;
        }
        backStack.push(entry);
        display(forwardStack.pop(), false);
    }

    private void go(Entry next, boolean resetScroll) {
        if (entry != null) {
            backStack.push(entry);
            while (backStack.size() > HISTORY) {
                backStack.removeLast();
            }
        }
        forwardStack.clear();
        display(next, resetScroll);
    }

    /** rebuild the breadcrumbs and actions (after a language change, or when a page changes its title) */
    public void refreshChrome() {
        if (entry == null) {
            return;
        }
        crumbs.getChildren().clear();
        List<Page> trail = entry.trail();
        for (int i = 0; i < trail.size(); i++) {
            String t = trail.get(i) == entry.page() ? entry.page().title() : trail.get(i).title();
            if (i < trail.size() - 1) {
                int index = i;
                Button crumb = UiKit.button(t, null, () -> upTo(index), "ghost", "crumb");
                Node sep = Icons.of(Icons.CHEVRON_RIGHT, 14);
                sep.getStyleClass().add("faint");
                crumbs.getChildren().addAll(crumb, sep);
            } else {
                Label title = UiKit.label(t, "page-title");
                title.setWrapText(false);
                title.setMinWidth(0);
                crumbs.getChildren().add(title);
            }
        }
        Node a = entry.page().actions();
        titleActions.getChildren().setAll(a == null ? new Node[0] : new Node[] {a});
    }

    public Page current() {
        return entry == null ? null : entry.page();
    }

    private void display(Entry e, boolean resetScroll) {
        overlay.closePopover();
        entry = e;
        Page p = e.page();
        Node view = viewFor(p);
        if (resetScroll && view instanceof ScrollPane sp) {
            sp.setVvalue(0);
        }
        pageHost.getChildren().setAll(view);
        selectNav(e.navId());
        updateHistoryButtons();
        refreshChrome();
        p.onShow();
        Motion.enter(view, Motion.Speed.SLOW, Motion.ENTER_OFFSET);
    }

    /** a page's view, wrapped once in its own scroll pane so the scroll position survives leaving the page */
    private static Node viewFor(Page p) {
        Region v = p.view();
        if (!v.getStyleClass().contains("page")) {
            v.getStyleClass().add("page");
        }
        if (!p.scrolls()) {
            return v;
        }
        Object cached = v.getProperties().get(SCROLL);
        if (cached instanceof ScrollPane sp && sp.getContent() == v) {
            return sp;
        }
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("content-scroll");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        v.getProperties().put(SCROLL, sp);
        return sp;
    }

    private void updateHistoryButtons() {
        back.setDisable(backStack.isEmpty());
        forward.setDisable(forwardStack.isEmpty());
    }

    private void selectNav(String id) {
        ToggleButton b = id == null ? null : buttons.get(id);
        if (b != null) {
            b.setSelected(true);
        } else if (group.getSelectedToggle() != null) {
            group.getSelectedToggle().setSelected(false);
        }
        placePill(pillPlaced);
    }

    /** slide the accent pill behind the active sidebar item */
    private void placePill(boolean animate) {
        String id = entry == null ? null : entry.navId();
        StackPane target = id == null ? null : entries.get(id);
        if (target == null) {
            pill.setVisible(false);
            pillPlaced = false;
            return;
        }
        Bounds b = target.getBoundsInParent();
        if (b.getHeight() <= 0) {
            return;
        }
        pill.resizeRelocate(b.getMinX(), 0, b.getWidth(), b.getHeight());
        pill.setVisible(true);
        if (animate && pillPlaced) {
            Motion.moveY(pill, Motion.Speed.MEDIUM, b.getMinY());
        } else {
            pill.setTranslateY(b.getMinY());
        }
        pillPlaced = true;
    }
}
