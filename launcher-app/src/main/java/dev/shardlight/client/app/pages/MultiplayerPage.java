package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Disclosure;
import dev.shardlight.client.app.ui.Fields;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.MotdText;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.PingBars;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.LaunchOptions;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ServerEntry;
import dev.shardlight.client.project.ServerStatus;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Each project's own server list (its servers.dat) as cards: icon, message of the day in the server's colours,
 * ping bars, players and version, with Join using the chosen project. Adding a server is a short dialog that
 * pings the address as you type.
 */
public final class MultiplayerPage implements Page {
    private static final java.time.Duration PING_TIMEOUT = java.time.Duration.ofSeconds(5);
    /** a server icon is a 64x64 PNG; anything far bigger is not one */
    private static final int MAX_ICON_BYTES = 64 * 1024;
    private static final int SKELETONS = 3;

    private final AppContext ctx;
    private final VBox root = new VBox();
    private final StackPane view = new StackPane(root);
    private final VBox list = new VBox();
    private final ComboBox<Project> target = new ComboBox<>();
    /** latest ping result per address; null value = ping in progress */
    private final Map<String, ServerStatus> status = new HashMap<>();
    private boolean previewing;
    private List<ServerEntry> servers = List.of();
    private String loadedFor;

    public MultiplayerPage(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().addAll("multiplayer", "page-column", "stack");
        StackPane.setAlignment(root, Pos.TOP_CENTER);
        list.getStyleClass().add("server-list");
        target.setCellFactory(l -> ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setButtonCell(ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setPrefWidth(ThemeManager.em(21));
        target.setAccessibleText(I18n.tr("ui.multiplayer.project"));
        target.setOnAction(e -> {
            Project p = target.getValue();
            if (p != null && !p.id.equals(loadedFor)) {
                ctx.selectProject(p.id);
                load(p, true);
            }
        });
        ctx.play().onStateChanged(() -> {
            if (loadedFor != null && ctx.shell().current() == this) {
                project().ifPresent(p -> load(p, false));
            }
        });
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.multiplayer");
    }

    @Override
    public Region view() {
        return view;
    }

    @Override
    public Node actions() {
        HBox box = new HBox(target,
            UiKit.iconButton(Icons.REFRESH, I18n.tr("ui.multiplayer.refresh"), this::refreshAll),
            UiKit.primary(I18n.tr("ui.multiplayer.add"), Icons.PLUS, () -> project().ifPresent(p -> edit(p, -1))));
        box.getStyleClass().add("button-row");
        return box;
    }

    @Override
    public void onShow() {
        List<Project> projects = ctx.services().projects().list();
        target.getItems().setAll(projects);
        Optional<Project> sel = ctx.selectedProject();
        target.setValue(sel.flatMap(s -> projects.stream().filter(p -> p.id.equals(s.id)).findFirst()).orElse(null));
        sel.ifPresentOrElse(p -> load(p, !p.id.equals(loadedFor)), () -> {
            loadedFor = null;
            root.getChildren().setAll(UiKit.emptyState(Icons.SERVERS, I18n.tr("ui.multiplayer.noProjectTitle"),
                I18n.tr("ui.multiplayer.noProjectBody"), UiKit.primary(I18n.tr("ui.library.new"), Icons.PLUS,
                    () -> ctx.newProject(null))));
        });
    }

    private Optional<Project> project() {
        return loadedFor == null ? Optional.empty() : ctx.services().projects().get(loadedFor);
    }

    /** --capture only: show made-up answers for the example servers instead of pinging them */
    public void previewServers() {
        previewing = true;
        fillPreview();
        rerender();
    }

    private void fillPreview() {
        String[] motds = {"§6§lExample Network§r §7- §aSurvival, Creative and Skyblock", "§bFriends only§r\n§7Be nice."};
        int i = 0;
        for (ServerEntry s : servers) {
            ServerStatus st = i < motds.length
                ? new ServerStatus(true, i == 0 ? 28 : 3, i == 0 ? 100 : 20, "", i == 0 ? "Paper 26.3" : "Fabric 26.3", 780,
                    i == 0 ? 34 : 118, null, null, dev.shardlight.client.project.MotdSpan.fromLegacy(motds[i]))
                : ServerStatus.offline(I18n.tr("ui.multiplayer.previewOffline"));
            status.put(key(s.address()), st);
            i++;
        }
    }

    /** --capture only: open the add-server dialog */
    public void previewAdd() {
        project().ifPresent(p -> edit(p, -1));
    }

    private void load(Project p, boolean ping) {
        loadedFor = p.id;
        if (servers.isEmpty() || ping) {
            root.getChildren().setAll(header(p));
            for (int i = 0; i < SKELETONS; i++) {
                root.getChildren().add(UiKit.skeletonCard());
            }
        }
        Fx.run(() -> ctx.services().servers().load(p), loaded -> {
            servers = loaded;
            if (previewing) {
                fillPreview();
            }
            render(p);
            if (ping && !previewing) {
                refreshAll();
            }
        }, err -> {
            servers = List.of();
            root.getChildren().setAll(header(p), UiKit.errorState(Tasks.message(err), () -> load(p, true)));
        });
    }

    private Node header(Project p) {
        VBox intro = new VBox(UiKit.label(I18n.tr("ui.multiplayer.introTitle", p.name), "h2"),
            UiKit.label(I18n.tr("ui.multiplayer.intro"), "muted"));
        intro.getStyleClass().addAll("stack-tight", "page-intro");
        return intro;
    }

    private void render(Project p) {
        root.getChildren().clear();
        root.getChildren().add(header(p));
        if (ctx.play().isRunning(p.id)) {
            root.getChildren().add(UiKit.banner("warning", I18n.tr("ui.multiplayer.running")));
        }
        if (servers.isEmpty()) {
            root.getChildren().add(UiKit.emptyState(Icons.SERVERS, I18n.tr("ui.multiplayer.emptyTitle"),
                I18n.tr("ui.multiplayer.emptyBody"), UiKit.primary(I18n.tr("ui.multiplayer.add"), Icons.PLUS,
                    () -> edit(p, -1))));
            return;
        }
        list.getChildren().clear();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).favourite()) {
                order.add(i);
            }
        }
        for (int i = 0; i < servers.size(); i++) {
            if (!servers.get(i).favourite()) {
                order.add(i);
            }
        }
        for (int index : order) {
            list.getChildren().add(row(p, index));
        }
        root.getChildren().add(list);
        root.getChildren().add(UiKit.label(I18n.tr("ui.multiplayer.pingNote"), "faint", "small"));
    }

    // ------------------------------------------------------------------ a server card

    private Node row(Project p, int index) {
        ServerEntry s = servers.get(index);
        ServerStatus st = status.get(key(s.address()));
        boolean pinging = status.containsKey(key(s.address())) && st == null;
        boolean online = st != null && st.online();

        HBox name = new HBox();
        name.getStyleClass().add("button-row");
        if (s.favourite()) {
            Node star = Icons.of(Icons.STAR, 14);
            star.getStyleClass().add("warning-icon");
            name.getChildren().add(star);
        }
        name.getChildren().add(UiKit.label(display(s), "h3"));
        VBox text = new VBox(name, UiKit.label(s.address(), "mono", "faint", "small"));
        text.getStyleClass().add("server-text");
        if (online) {
            text.getChildren().add(motd(st));
        } else if (pinging) {
            text.getChildren().add(UiKit.skeleton("line"));
        } else if (st != null) {
            Label why = UiKit.label(st.error() == null || st.error().isBlank() ? I18n.tr("ui.multiplayer.noAnswer") : st.error(),
                "muted", "small");
            text.getChildren().add(why);
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        VBox stats = stats(p, st, pinging);
        Button join = UiKit.primary(I18n.tr("ui.multiplayer.join"), Icons.PLAY, () -> join(p, s));
        join.setDisable(ctx.play().isRunning(p.id));
        join.setAccessibleText(I18n.tr("ui.multiplayer.joinAccessible", display(s), p.name));
        UiKit.tooltip(join, I18n.tr("ui.multiplayer.joinWith", p.name));
        Button fav = UiKit.iconButton(Icons.STAR, I18n.tr(s.favourite() ? "ui.multiplayer.unfavourite" : "ui.multiplayer.favourite"),
            () -> update(p, index, new ServerEntry(s.name(), s.address(), s.iconBase64(), s.acceptTextures(), !s.favourite())));
        if (s.favourite()) {
            fav.getStyleClass().add("accent-icon");
        }
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.common.more"), () -> { });
        more.setOnAction(e -> menu(p, index).show(more, Side.BOTTOM, 0, 4));
        HBox actions = new HBox(fav, more, join);
        actions.getStyleClass().add("button-row");

        HBox row = new HBox(icon(s, st), text, stats, actions);
        row.getStyleClass().addAll("card", "server-card-row");
        if (st != null && !online) {
            row.getStyleClass().add("server-offline");
        }
        row.setAccessibleText(display(s) + ", " + (online ? I18n.tr("ui.multiplayer.players", st.playersOnline(), st.playersMax())
            : pinging ? I18n.tr("ui.multiplayer.pinging") : I18n.tr("ui.multiplayer.offline")));
        return row;
    }

    private static Node motd(ServerStatus st) {
        Node flow = MotdText.of(st.motdSpans(), st.motd());
        VBox box = new VBox(flow);
        box.getStyleClass().add("motd-box");
        return box;
    }

    /** ping bars with the latency, players and the server's version (flagged when it is not the project's) */
    private VBox stats(Project p, ServerStatus st, boolean pinging) {
        VBox v = new VBox();
        v.getStyleClass().add("server-stats");
        if (pinging) {
            v.getChildren().add(UiKit.chip(I18n.tr("ui.multiplayer.pinging"), null));
            return v;
        }
        if (st == null) {
            return v;
        }
        if (!st.online()) {
            HBox ping = new HBox(new PingBars(-1, false), UiKit.chip(I18n.tr("ui.multiplayer.offline"), "danger"));
            ping.getStyleClass().add("server-ping");
            v.getChildren().add(ping);
            return v;
        }
        HBox ping = new HBox(new PingBars(st.latencyMs(), true),
            UiKit.label(st.latencyMs() < 0 ? "?" : I18n.tr("ui.multiplayer.latency", st.latencyMs()), "tabular", "small", "muted"));
        ping.getStyleClass().add("server-ping");
        v.getChildren().addAll(ping, UiKit.chip(I18n.tr("ui.multiplayer.players", st.playersOnline(), st.playersMax()),
            null, Icons.USER));
        if (st.versionName() != null && !st.versionName().isBlank()) {
            boolean matches = st.versionName().contains(p.minecraftVersion);
            Label version = UiKit.chip(st.versionName(), matches ? null : "warning");
            version.setWrapText(false);
            UiKit.tooltip(version, matches ? st.versionName() : I18n.tr("ui.multiplayer.versionMismatch", p.minecraftVersion));
            v.getChildren().add(version);
        }
        return v;
    }

    private ContextMenu menu(Project p, int index) {
        ServerEntry s = servers.get(index);
        MenuItem edit = new MenuItem(I18n.tr("ui.multiplayer.edit"));
        edit.setOnAction(e -> edit(p, index));
        MenuItem copy = new MenuItem(I18n.tr("ui.multiplayer.copyAddress"));
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(s.address());
            Clipboard.getSystemClipboard().setContent(c);
            ctx.overlay().toast("success", I18n.tr("ui.multiplayer.copied"));
        });
        MenuItem up = new MenuItem(I18n.tr("ui.multiplayer.moveUp"));
        up.setDisable(index == 0);
        up.setOnAction(e -> move(p, index, -1));
        MenuItem down = new MenuItem(I18n.tr("ui.multiplayer.moveDown"));
        down.setDisable(index == servers.size() - 1);
        down.setOnAction(e -> move(p, index, 1));
        MenuItem remove = new MenuItem(I18n.tr("ui.multiplayer.remove"));
        remove.getStyleClass().add("danger");
        remove.setOnAction(e -> ctx.overlay().confirm(I18n.tr("ui.multiplayer.removeTitle", display(s)),
            I18n.tr("ui.multiplayer.removeMessage"), List.of(s.address()), I18n.tr("ui.multiplayer.remove"), true, () -> {
                List<ServerEntry> next = new ArrayList<>(servers);
                next.remove(index);
                save(p, next);
            }));
        return new ContextMenu(edit, copy, up, down, remove);
    }

    private static String display(ServerEntry s) {
        return s.name() == null || s.name().isBlank() ? s.address() : s.name();
    }

    private Node icon(ServerEntry s, ServerStatus st) {
        String b64 = st != null && st.faviconBase64() != null ? st.faviconBase64() : s.iconBase64();
        Image img = decodeIcon(b64);
        if (img != null) {
            ImageView view = new ImageView(img);
            view.setFitWidth(ThemeManager.em(4.571));
            view.setFitHeight(ThemeManager.em(4.571));
            view.setSmooth(false);
            return UiKit.iconBox(view, ThemeManager.em(4.857));
        }
        return UiKit.iconBox(Icons.of(Icons.SERVERS, 24), ThemeManager.em(4.857));
    }

    /** a server icon from base64 (with or without a data: prefix); null when absent or not a small image */
    static Image decodeIcon(String b64) {
        if (b64 == null || b64.isBlank()) {
            return null;
        }
        String s = b64.strip();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(s);
            if (bytes.length == 0 || bytes.length > MAX_ICON_BYTES) {
                return null;
            }
            Image img = new Image(new ByteArrayInputStream(bytes));
            return img.isError() || img.getWidth() > 256 || img.getHeight() > 256 ? null : img;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void join(Project p, ServerEntry s) {
        ctx.play().play(p, new LaunchOptions(null, s.address(), false));
    }

    private void move(Project p, int index, int delta) {
        List<ServerEntry> next = new ArrayList<>(servers);
        ServerEntry e = next.remove(index);
        next.add(Math.max(0, Math.min(next.size(), index + delta)), e);
        save(p, next);
    }

    private void update(Project p, int index, ServerEntry entry) {
        List<ServerEntry> next = new ArrayList<>(servers);
        next.set(index, entry);
        save(p, next);
    }

    private void save(Project p, List<ServerEntry> next) {
        if (ctx.play().isRunning(p.id)) {
            ctx.overlay().message(I18n.tr("ui.multiplayer.runningTitle"), I18n.tr("ui.multiplayer.running"));
            return;
        }
        Tasks.quiet(ctx.overlay(), () -> {
            ctx.services().servers().save(p, next);
            return ctx.services().servers().load(p);
        }, saved -> {
            servers = saved;
            render(p);
        });
    }

    // ------------------------------------------------------------------ add and edit

    /** add (index -1) or edit a server; the dialog pings the address as it is typed */
    private void edit(Project p, int index) {
        ServerEntry old = index < 0 ? null : servers.get(index);
        TextField name = new TextField(old == null ? "" : old.name());
        name.setPromptText(I18n.tr("ui.multiplayer.namePrompt"));
        TextField address = new TextField(old == null ? "" : old.address());
        address.setPromptText("play.example.net");
        Fields.Field nameField = new Fields.Field(I18n.tr("ui.multiplayer.name"), name, null);
        Fields.Field addressField = new Fields.Field(I18n.tr("ui.multiplayer.address"), address, I18n.tr("ui.multiplayer.addressHelp"));
        ComboBox<String> packs = new ComboBox<>();
        packs.getItems().addAll(I18n.tr("ui.multiplayer.packsPrompt"), I18n.tr("ui.multiplayer.packsEnabled"),
            I18n.tr("ui.multiplayer.packsDisabled"));
        Boolean accept = old == null ? null : old.acceptTextures();
        packs.getSelectionModel().select(accept == null ? 0 : accept ? 1 : 2);
        packs.setMaxWidth(Double.MAX_VALUE);
        Disclosure more = new Disclosure(I18n.tr("ui.multiplayer.moreOptions"), false,
            new Fields.Field(I18n.tr("ui.multiplayer.packs"), packs, null));

        VBox result = new VBox();
        result.getStyleClass().add("ping-result");
        int[] generation = {0};
        PauseTransition debounce = new PauseTransition(Duration.millis(600));
        Runnable test = () -> {
            String addr = address.getText() == null ? "" : address.getText().strip();
            int mine = ++generation[0];
            if (addr.isEmpty() || validateAddress(addr) != null) {
                result.getChildren().setAll(UiKit.label(I18n.tr("ui.multiplayer.testHint"), "faint", "small"));
                return;
            }
            result.getChildren().setAll(UiKit.label(I18n.tr("ui.multiplayer.testing"), "muted", "small"), UiKit.skeleton("block"));
            Fx.run(() -> ctx.services().servers().ping(addr, PING_TIMEOUT), st -> {
                if (mine == generation[0]) {
                    showTest(result, p, st);
                }
            }, err -> {
                if (mine == generation[0]) {
                    showTest(result, p, ServerStatus.offline(Tasks.message(err)));
                }
            });
        };
        debounce.setOnFinished(e -> test.run());
        address.textProperty().addListener((o, a, b) -> {
            addressField.setError(null);
            debounce.playFromStart();
        });
        VBox body = new VBox(addressField, nameField, result, more);
        body.getStyleClass().addAll("add-server");
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button ok = UiKit.primary(I18n.tr(old == null ? "ui.multiplayer.add" : "common.save"), null, () -> { });
        ok.setDefaultButton(true);
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr(old == null ? "ui.multiplayer.addTitle"
            : "ui.multiplayer.editTitle"), body, cancel, ok), true, false);
        cancel.setOnAction(e -> d.close());
        ok.setOnAction(e -> {
            String addr = address.getText() == null ? "" : address.getText().strip();
            String problem = validateAddress(addr);
            if (problem != null) {
                addressField.setError(problem);
                address.requestFocus();
                return;
            }
            String n = name.getText() == null || name.getText().isBlank() ? I18n.tr("ui.multiplayer.defaultName")
                : name.getText().strip();
            int choice = packs.getSelectionModel().getSelectedIndex();
            Boolean acceptTextures = choice == 1 ? Boolean.TRUE : choice == 2 ? Boolean.FALSE : null;
            ServerEntry entry = new ServerEntry(n, addr, old == null ? null : old.iconBase64(), acceptTextures,
                old != null && old.favourite());
            d.close();
            List<ServerEntry> next = new ArrayList<>(servers);
            if (old == null) {
                next.add(entry);
            } else {
                next.set(index, entry);
            }
            save(p, next);
            ping(entry.address());
        });
        if (old != null) {
            test.run();
        } else {
            result.getChildren().setAll(UiKit.label(I18n.tr("ui.multiplayer.testHint"), "faint", "small"));
        }
        (old == null ? address : name).requestFocus();
    }

    /** what the live test found: the server's message and ping, or why it did not answer (adding still works) */
    private void showTest(VBox result, Project p, ServerStatus st) {
        if (!st.online()) {
            result.getChildren().setAll(UiKit.banner("warning", st.error() == null || st.error().isBlank()
                ? I18n.tr("ui.multiplayer.noAnswer") : I18n.tr("ui.multiplayer.testFailed", st.error())));
            return;
        }
        HBox facts = new HBox(new PingBars(st.latencyMs(), true),
            UiKit.label(st.latencyMs() < 0 ? "?" : I18n.tr("ui.multiplayer.latency", st.latencyMs()), "tabular", "small", "muted"),
            UiKit.chip(I18n.tr("ui.multiplayer.players", st.playersOnline(), st.playersMax()), null, Icons.USER));
        facts.getStyleClass().add("ping-result-row");
        result.getChildren().setAll(motd(st), facts);
        if (st.versionName() != null && !st.versionName().isBlank()) {
            boolean matches = st.versionName().contains(p.minecraftVersion);
            result.getChildren().add(matches
                ? UiKit.banner("success", I18n.tr("ui.multiplayer.testMatches", st.versionName(), p.name))
                : UiKit.banner("warning", I18n.tr("ui.multiplayer.versionMismatch", p.minecraftVersion)));
        }
    }

    /** null when the address looks usable: host, host:port or [ipv6]:port */
    static String validateAddress(String addr) {
        if (addr.isEmpty()) {
            return I18n.tr("ui.multiplayer.addressEmpty");
        }
        if (addr.chars().anyMatch(Character::isWhitespace) || addr.contains("/") || addr.contains("@")) {
            return I18n.tr("ui.multiplayer.addressInvalid");
        }
        String port = null;
        if (addr.startsWith("[")) {
            int end = addr.indexOf(']');
            if (end < 0) {
                return I18n.tr("ui.multiplayer.addressInvalid");
            }
            if (end + 1 < addr.length()) {
                if (addr.charAt(end + 1) != ':') {
                    return I18n.tr("ui.multiplayer.addressInvalid");
                }
                port = addr.substring(end + 2);
            }
        } else if (addr.indexOf(':') >= 0 && addr.indexOf(':') == addr.lastIndexOf(':')) {
            port = addr.substring(addr.indexOf(':') + 1);
            if (addr.indexOf(':') == 0) {
                return I18n.tr("ui.multiplayer.addressInvalid");
            }
        }
        if (port != null) {
            try {
                int n = Integer.parseInt(port);
                if (n < 1 || n > 65535) {
                    return I18n.tr("ui.multiplayer.portInvalid");
                }
            } catch (NumberFormatException e) {
                return I18n.tr("ui.multiplayer.portInvalid");
            }
        }
        return null;
    }

    private static String key(String address) {
        return address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
    }

    private void refreshAll() {
        servers.stream().map(ServerEntry::address).distinct().forEach(this::ping);
    }

    private void ping(String address) {
        if (previewing) {
            return;
        }
        String k = key(address);
        if (status.containsKey(k) && status.get(k) == null) {
            return;
        }
        status.put(k, null);
        rerender();
        Fx.run(() -> ctx.services().servers().ping(address, PING_TIMEOUT), st -> {
            status.put(k, st);
            rerender();
        }, err -> {
            status.put(k, ServerStatus.offline(Tasks.message(err)));
            rerender();
        });
    }

    private void rerender() {
        project().ifPresent(p -> {
            if (!servers.isEmpty() && ctx.shell().current() == this) {
                render(p);
            }
        });
    }
}
