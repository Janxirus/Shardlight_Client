package dev.shardlight.client.app.ui;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.i18n.I18n;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The window frame: sidebar navigation (Home, Library, Discover, Shardlight, Multiplayer, Downloads,
 * Settings), a title bar per page, the page area, and the overlay for dialogs and toasts. Pages that are
 * not in the sidebar (a project's details, the new-project wizard) are pushed with {@link #push} and get
 * a back button.
 */
public final class Shell {
    /** a sidebar destination */
    public record NavItem(String id, String titleKey, String icon, Supplier<Page> factory) {
    }

    private final StackPane root = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final VBox sidebar = new VBox();
    private final VBox nav = new VBox(4);
    private final StackPane pageHost = new StackPane();
    private final HBox topbar = new HBox();
    private final Label title = new Label();
    private final HBox titleActions = new HBox(8);
    private final Button back;
    private final Overlay overlay;
    private final ToggleGroup group = new ToggleGroup();
    private final Map<String, NavItem> items = new LinkedHashMap<>();
    private final Map<String, Page> built = new LinkedHashMap<>();
    private final Map<String, ToggleButton> buttons = new LinkedHashMap<>();
    private final Map<String, Label> badges = new LinkedHashMap<>();
    private final Deque<Page> stack = new ArrayDeque<>();
    private final VBox sidebarFooter = new VBox();
    private Page current;
    private String currentId;

    public Shell(Color accent) {
        sidebar.getStyleClass().add("sidebar");
        Node logo = ShardLogo.create(34, accent, ThemeManager.accent2());
        Label name = UiKit.label(BuildInfo.NAME, "brand-name");
        name.setWrapText(false);
        Label sub = UiKit.label(I18n.tr("ui.brand.subtitle"), "brand-sub");
        sub.setWrapText(false);
        HBox brand = new HBox(logo, new VBox(1, name, sub));
        brand.getStyleClass().add("brand");
        sidebarFooter.getStyleClass().add("sidebar-footer");
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        sidebar.getChildren().addAll(brand, nav, grow, sidebarFooter);

        back = UiKit.iconButton(Icons.BACK, I18n.tr("ui.back"), this::pop);
        back.setVisible(false);
        back.setManaged(false);
        title.getStyleClass().add("page-title");
        title.setWrapText(false);
        topbar.getStyleClass().add("topbar");
        titleActions.setAlignment(Pos.CENTER_RIGHT);
        topbar.getChildren().addAll(back, title, UiKit.spacer(), titleActions);

        BorderPane main = new BorderPane();
        main.setTop(topbar);
        main.setCenter(pageHost);
        frame.setLeft(sidebar);
        frame.setCenter(main);
        root.getChildren().add(frame);
        overlay = new Overlay(root, frame);

        root.setOnKeyPressed(e -> {
            if (overlay.hasOpenDialog()) {
                return;
            }
            if (e.isShortcutDown() && e.getCode().isDigitKey()) {
                int idx = e.getCode().getCode() - KeyCode.DIGIT1.getCode();
                if (idx >= 0 && idx < items.size()) {
                    navigate(items.keySet().toArray(String[]::new)[idx]);
                    e.consume();
                }
            } else if (e.getCode() == KeyCode.ESCAPE && !stack.isEmpty()) {
                pop();
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN).match(e) && !stack.isEmpty()) {
                pop();
                e.consume();
            }
        });
    }

    public StackPane root() {
        return root;
    }

    public Overlay overlay() {
        return overlay;
    }

    /** the area under the navigation (account chip, download indicator) */
    public VBox sidebarFooter() {
        return sidebarFooter;
    }

    public void addNav(NavItem item) {
        items.put(item.id(), item);
        ToggleButton b = new ToggleButton(I18n.tr(item.titleKey()));
        b.setGraphic(Icons.of(item.icon(), 19));
        b.getStyleClass().add("nav-button");
        b.setToggleGroup(group);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMnemonicParsing(false);
        b.setAccessibleText(I18n.tr(item.titleKey()));
        int index = items.size();
        UiKit.tooltip(b, I18n.tr(item.titleKey()) + "  (Ctrl+" + index + ")");
        Label badge = new Label();
        badge.getStyleClass().add("nav-badge");
        badge.setVisible(false);
        badge.setManaged(false);
        HBox content = new HBox(b, badge);
        content.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(b, Priority.ALWAYS);
        b.setOnAction(e -> {
            if (!b.isSelected()) {
                b.setSelected(true);
            }
            navigate(item.id());
        });
        buttons.put(item.id(), b);
        badges.put(item.id(), badge);
        nav.getChildren().add(content);
    }

    /** show a small count next to a sidebar item (0 hides it) */
    public void setBadge(String id, int count) {
        Label l = badges.get(id);
        if (l != null) {
            l.setText(String.valueOf(count));
            l.setVisible(count > 0);
            l.setManaged(count > 0);
        }
    }

    public String currentId() {
        return currentId;
    }

    public Page page(String id) {
        return built.computeIfAbsent(id, k -> items.get(k).factory().get());
    }

    /** switch to a sidebar page (clears pushed pages) */
    public void navigate(String id) {
        NavItem item = items.get(id);
        if (item == null) {
            return;
        }
        stack.clear();
        currentId = id;
        ToggleButton b = buttons.get(id);
        if (b != null) {
            b.setSelected(true);
        }
        display(page(id));
    }

    /** open a page on top of the current one, with a back button */
    public void push(Page p) {
        if (current != null) {
            stack.push(current);
        }
        display(p);
    }

    public void pop() {
        if (stack.isEmpty()) {
            return;
        }
        display(stack.pop());
    }

    /** rebuild the title area (after a language change, or when a page changes its actions) */
    public void refreshChrome() {
        if (current != null) {
            title.setText(current.title());
            Node a = current.actions();
            titleActions.getChildren().setAll(a == null ? new Node[0] : new Node[] {a});
        }
    }

    public Page current() {
        return current;
    }

    private void display(Page p) {
        current = p;
        back.setVisible(!stack.isEmpty());
        back.setManaged(!stack.isEmpty());
        Node view;
        if (p.scrolls()) {
            ScrollPane sp = new ScrollPane(p.view());
            sp.setFitToWidth(true);
            sp.getStyleClass().add("content-scroll");
            sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            view = sp;
        } else {
            view = p.view();
        }
        p.view().getStyleClass().remove("page");
        p.view().getStyleClass().add("page");
        pageHost.getChildren().setAll(view);
        refreshChrome();
        p.onShow();
        if (!UiSettings.reducedMotion()) {
            FadeTransition f = new FadeTransition(Duration.millis(130), view);
            f.setFromValue(0.35);
            f.setToValue(1);
            f.play();
        }
        frame.setPadding(Insets.EMPTY);
    }
}
