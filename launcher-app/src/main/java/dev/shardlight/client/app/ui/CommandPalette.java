package dev.shardlight.client.app.ui;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.Project;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ctrl+K: one search box for everything. Fuzzy-matches projects, installed mods, pages, settings sections and
 * actions such as New project, Open logs and Switch to Ultra Performance. Up and Down move, Enter runs, Escape
 * closes; the mouse works too.
 */
public final class CommandPalette {
    private static final Logger LOG = Logger.getLogger(CommandPalette.class.getName());
    private static final int MAX_RESULTS = 60;
    private static final int MAX_MODS = 600;
    private static final List<String> SETTINGS = List.of("accounts", "appearance", "java", "downloads", "content",
        "privacy", "updates", "storage", "about");

    /** something the palette can do; {@code keywords} are matched as well as the title */
    public record Command(String group, String title, String subtitle, String icon, String shortcut, String keywords,
                          Runnable run) {
    }

    private record Scored(Command command, int score, int order) {
    }

    private final AppContext ctx;
    private Overlay.Dialog open;

    public CommandPalette(AppContext ctx) {
        this.ctx = ctx;
    }

    public void show() {
        if (open != null) {
            return;
        }
        List<Command> all = commands();
        TextField input = new TextField();
        input.getStyleClass().add("palette-input");
        input.setPromptText(I18n.tr("ui.palette.prompt"));
        input.setAccessibleText(I18n.tr("ui.palette.title"));
        Node icon = Icons.of(Icons.SEARCH, 18);
        icon.getStyleClass().add("faint");
        HBox top = new HBox(icon, input);
        top.getStyleClass().add("palette-search");
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);

        ListView<Command> list = new ListView<>();
        list.getStyleClass().add("palette-list");
        list.setFocusTraversable(false);
        list.setCellFactory(l -> new Cell());
        Label empty = UiKit.label(I18n.tr("ui.palette.noResults"), "muted");
        list.setPlaceholder(empty);
        Label hint = UiKit.label(I18n.tr("ui.palette.hint"), "faint", "caption");

        VBox card = new VBox(top, list, hint);
        card.getStyleClass().addAll("modal", "palette");
        card.setAccessibleText(I18n.tr("ui.palette.title"));
        Runnable filter = () -> {
            list.getItems().setAll(filter(all, input.getText()));
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(0);
                list.scrollTo(0);
            }
        };
        input.textProperty().addListener((o, a, b) -> filter.run());
        filter.run();
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            int n = list.getItems().size();
            if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                if (n > 0) {
                    int next = e.getCode() == KeyCode.DOWN ? Math.min(n - 1, i + 1) : Math.max(0, i - 1);
                    list.getSelectionModel().select(next);
                    list.scrollTo(Math.max(0, next - 3));
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                run(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> run(list.getSelectionModel().getSelectedItem()));
        open = ctx.overlay().show(card, true, false, "palette-layer");
        open.onClose(() -> open = null);
        Platform.runLater(input::requestFocus);
    }

    private void run(Command c) {
        if (c == null || open == null) {
            return;
        }
        open.close();
        c.run().run();
    }

    /** the best matches for {@code query}; with no query, actions, pages and recent projects */
    static List<Command> filter(List<Command> all, String query) {
        String q = query == null ? "" : query.trim();
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Command c = all.get(i);
            if (q.isEmpty()) {
                if (!c.group().equals(group("mods")) && !c.group().equals(group("settings"))) {
                    scored.add(new Scored(c, 0, i));
                }
                continue;
            }
            int s = Math.max(Fuzzy.score(q, c.title()), Fuzzy.score(q, c.keywords()) - 6);
            if (s > 0) {
                scored.add(new Scored(c, s, i));
            }
        }
        scored.sort(Comparator.comparingInt((Scored s) -> -s.score()).thenComparingInt(Scored::order));
        return scored.stream().limit(MAX_RESULTS).map(Scored::command).toList();
    }

    private static String group(String id) {
        return I18n.tr("ui.palette.group." + id);
    }

    private List<Command> commands() {
        List<Command> out = new ArrayList<>();
        Optional<Project> sel = ctx.selectedProject();
        String actions = group("actions");
        out.add(new Command(actions, I18n.tr("ui.palette.newProject"), null, Icons.PLUS, null, "create add instance",
            () -> ctx.newProject(null)));
        sel.ifPresent(p -> {
            PlayFlow.Phase phase = ctx.play().state(p.id).phase();
            boolean running = phase == PlayFlow.Phase.RUNNING;
            out.add(new Command(actions, I18n.tr(running ? "ui.palette.stop" : "ui.palette.play", p.name), null,
                running ? Icons.STOP : Icons.PLAY, null, "launch start game run", () -> ctx.play().play(p)));
            Preset active = p.preset == null || p.preset.active == null ? Preset.DEFAULT : p.preset.active;
            if (active != Preset.ULTRA_PERFORMANCE) {
                out.add(new Command(actions, I18n.tr("ui.palette.ultra"), p.name, Icons.BOLT, null,
                    "fps preset performance fast", () -> ProjectActions.applyPreset(ctx, p, Preset.ULTRA_PERFORMANCE)));
            }
            out.add(new Command(actions, I18n.tr("ui.palette.gameFolder"), p.name, Icons.FOLDER, null, "files directory",
                () -> ctx.openFolder(ctx.services().projects().gameDir(p))));
        });
        out.add(new Command(actions, I18n.tr("ui.palette.openLogs"), null, Icons.LOGS, null, "log files launcher",
            () -> ctx.openFolder(ctx.services().paths().logs())));
        out.add(new Command(actions, I18n.tr("ui.palette.diagnostics"), null, Icons.ARCHIVE, null, "crash report support zip",
            () -> PlayFlow.exportDiagnostics(ctx, sel.orElse(null))));
        boolean light = "light".equalsIgnoreCase(ctx.services().settings().get().appearance.theme);
        out.add(new Command(actions, I18n.tr(light ? "ui.palette.dark" : "ui.palette.light"), null, Icons.PALETTE, null,
            "theme appearance mode colour color", () -> setTheme(light ? "dark" : "light")));
        out.add(new Command(actions, I18n.tr(ctx.shell().isCollapsed() ? "ui.sidebar.expand" : "ui.sidebar.collapse"), null,
            Icons.SIDEBAR, "Ctrl+B", "navigation menu", () -> ctx.shell().setCollapsed(!ctx.shell().isCollapsed())));
        out.add(new Command(actions, I18n.tr("ui.account.add"), null, Icons.USER, null, "sign in login microsoft",
            () -> SignInDialog.show(ctx, null)));

        String pages = group("pages");
        int n = 1;
        for (String[] page : new String[][] {{"home", Icons.HOME}, {"library", Icons.LIBRARY}, {"discover", Icons.DISCOVER},
            {"shardlight", Icons.SHARD}, {"multiplayer", Icons.SERVERS}, {"downloads", Icons.DOWNLOAD},
            {"settings", Icons.GEAR}}) {
            out.add(new Command(pages, I18n.tr("ui.nav." + page[0]), null, page[1], "Ctrl+" + n++, page[0],
                () -> ctx.shell().navigate(page[0])));
        }
        out.add(new Command(pages, I18n.tr("ui.gallery.title"), I18n.tr("ui.gallery.subtitle"), Icons.GRID, null,
            "components design", () -> ctx.shell().navigate("gallery")));

        String projects = group("projects");
        List<Project> all = ctx.services().projects().list();
        for (Project p : all) {
            out.add(new Command(projects, p.name, p.minecraftVersion + "  ·  " + ProjectText.loader(p), Icons.CUBE, null,
                p.minecraftVersion + " " + ProjectText.loader(p), () -> ctx.openProject(p.id)));
        }

        String settings = group("settings");
        for (String id : SETTINGS) {
            out.add(new Command(settings, I18n.tr("ui.settings.section." + id), I18n.tr("ui.nav.settings"), Icons.GEAR, null,
                I18n.tr("ui.settings.section." + id + ".about"), () -> ctx.openSettings(id)));
        }

        String mods = group("mods");
        int count = 0;
        for (Project p : all) {
            for (ContentEntry c : p.content) {
                if (count++ >= MAX_MODS) {
                    break;
                }
                String title = c.title != null && !c.title.isBlank() ? c.title : c.fileName;
                out.add(new Command(mods, title, I18n.tr("ui.palette.modIn",
                    I18n.tr("ui.content.filter." + c.kind.name().toLowerCase(Locale.ROOT)), p.name), Icons.PACKAGE, null,
                    c.modId == null ? "" : c.modId, () -> ctx.openProjectTab(p.id, "content")));
            }
        }
        return out;
    }

    private void setTheme(String theme) {
        try {
            ctx.services().settings().update(s -> s.appearance.theme = theme);
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't save the theme", e);
            ctx.overlay().toast("danger", e.userMessage());
        }
    }

    private static final class Cell extends ListCell<Command> {
        @Override
        protected void updateItem(Command c, boolean empty) {
            super.updateItem(c, empty);
            setText(null);
            if (empty || c == null) {
                setGraphic(null);
                return;
            }
            Label title = new Label(c.title());
            title.getStyleClass().add("palette-title");
            VBox text = new VBox(title);
            if (c.subtitle() != null && !c.subtitle().isBlank()) {
                Label sub = new Label(c.subtitle());
                sub.getStyleClass().addAll("faint", "small");
                text.getChildren().add(sub);
            }
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            Label right = new Label(c.shortcut() != null ? c.shortcut() : c.group());
            right.getStyleClass().addAll(c.shortcut() != null ? "kbd" : "palette-group", "caption");
            HBox row = new HBox(Icons.of(c.icon(), 18), text, right);
            row.getStyleClass().add("palette-row");
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setAccessibleText(c.title() + (c.subtitle() == null ? "" : ", " + c.subtitle()));
        }
    }
}
