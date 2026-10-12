package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.LaunchOptions;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ServerEntry;
import dev.shardlight.client.project.ServerStatus;
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
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Each project's own server list (its servers.dat), with live status from the standard server list ping,
 * favourites, editing, and joining a server directly from the launcher.
 */
public final class MultiplayerPage implements Page {
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(5);
    /** a server icon is a 64x64 PNG; anything far bigger is not one */
    private static final int MAX_ICON_BYTES = 64 * 1024;

    private final AppContext ctx;
    private final VBox root = new VBox(14);
    private final VBox list = new VBox(8);
    private final ComboBox<Project> target = new ComboBox<>();
    /** latest ping result per address; null value = ping in progress */
    private final Map<String, ServerStatus> status = new HashMap<>();
    private List<ServerEntry> servers = List.of();
    private String loadedFor;

    public MultiplayerPage(AppContext ctx) {
        this.ctx = ctx;
        target.setCellFactory(l -> ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setButtonCell(ProjectText.cell(I18n.tr("ui.discover.noProject")));
        target.setPrefWidth(300);
        target.setAccessibleText(I18n.tr("ui.multiplayer.project"));
        target.setOnAction(e -> {
            Project p = target.getValue();
            if (p != null && !p.id.equals(loadedFor)) {
                ctx.selectProject(p.id);
                load(p, true);
            }
        });
        ctx.play().onStateChanged(() -> {
            if (loadedFor != null) {
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
        return root;
    }

    @Override
    public Node actions() {
        HBox box = new HBox(8, UiKit.label(I18n.tr("ui.multiplayer.project"), "muted"), target,
            UiKit.iconButton(Icons.REFRESH, I18n.tr("ui.multiplayer.refresh"), this::refreshAll),
            UiKit.primary(I18n.tr("ui.multiplayer.add"), Icons.PLUS, () -> project().ifPresent(p -> edit(p, -1))));
        box.setAlignment(Pos.CENTER_LEFT);
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

    private void load(Project p, boolean ping) {
        loadedFor = p.id;
        Fx.run(() -> ctx.services().servers().load(p), loaded -> {
            servers = loaded;
            render(p);
            if (ping) {
                refreshAll();
            }
        }, err -> {
            servers = List.of();
            render(p);
            root.getChildren().add(1, UiKit.banner("danger", Tasks.message(err)));
        });
    }

    private void render(Project p) {
        root.getChildren().clear();
        VBox intro = new VBox(4, UiKit.label(I18n.tr("ui.multiplayer.introTitle", p.name), "h2"),
            UiKit.label(I18n.tr("ui.multiplayer.intro"), "muted"));
        intro.setMaxWidth(860);
        root.getChildren().add(intro);
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

    private Node row(Project p, int index) {
        ServerEntry s = servers.get(index);
        ServerStatus st = status.get(key(s.address()));
        boolean pinging = status.containsKey(key(s.address())) && st == null;

        FlowPane meta = new FlowPane(6, 4);
        if (pinging) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.multiplayer.pinging"), null));
        } else if (st != null && st.online()) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.multiplayer.players", st.playersOnline(), st.playersMax()),
                "success", Icons.USER));
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.multiplayer.latency", st.latencyMs()),
                st.latencyMs() > 250 ? "warning" : null));
            if (st.versionName() != null && !st.versionName().isBlank()) {
                boolean matches = st.versionName().contains(p.minecraftVersion);
                Label v = UiKit.chip(st.versionName(), matches ? null : "warning");
                if (!matches) {
                    UiKit.tooltip(v, I18n.tr("ui.multiplayer.versionMismatch", p.minecraftVersion));
                }
                meta.getChildren().add(v);
            }
        } else if (st != null) {
            Label off = UiKit.chip(I18n.tr("ui.multiplayer.offline"), "danger");
            if (st.error() != null) {
                UiKit.tooltip(off, st.error());
            }
            meta.getChildren().add(off);
        }

        VBox text = new VBox(3);
        HBox name = new HBox(6, UiKit.label(s.name() == null || s.name().isBlank() ? s.address() : s.name(), "project-name"));
        name.setAlignment(Pos.CENTER_LEFT);
        if (s.favourite()) {
            name.getChildren().add(0, UiKit.iconBox(Icons.of(Icons.STAR, 14), 18));
            name.getChildren().get(0).getStyleClass().add("warning-icon");
        }
        text.getChildren().addAll(name, UiKit.label(s.address(), "muted", "small"));
        if (st != null && st.online() && st.motd() != null && !st.motd().isBlank()) {
            Label motd = UiKit.label(st.motd().strip(), "faint", "small");
            motd.setWrapText(true);
            text.getChildren().add(motd);
        }
        text.getChildren().add(meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button join = UiKit.primary(I18n.tr("ui.multiplayer.join"), Icons.PLAY, () -> join(p, s));
        join.setDisable(ctx.play().isRunning(p.id));
        Button fav = UiKit.iconButton(Icons.STAR, I18n.tr(s.favourite() ? "ui.multiplayer.unfavourite" : "ui.multiplayer.favourite"),
            () -> update(p, index, new ServerEntry(s.name(), s.address(), s.iconBase64(), s.acceptTextures(), !s.favourite())));
        if (s.favourite()) {
            fav.getStyleClass().add("accent-icon");
        }
        Button more = UiKit.iconButton(Icons.MORE, I18n.tr("ui.common.more"), () -> { });
        more.setOnAction(e -> menu(p, index).show(more, Side.BOTTOM, 0, 4));

        HBox row = new HBox(14, icon(s, st), text, fav, more, join);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("card", "server-row");
        return row;
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
            view.setFitWidth(48);
            view.setFitHeight(48);
            view.setSmooth(false);
            return UiKit.iconBox(view, 52);
        }
        return UiKit.iconBox(Icons.of(Icons.SERVERS, 22), 52);
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

    /** add (index -1) or edit a server */
    private void edit(Project p, int index) {
        ServerEntry old = index < 0 ? null : servers.get(index);
        TextField name = new TextField(old == null ? "" : old.name());
        name.setPromptText(I18n.tr("ui.multiplayer.namePrompt"));
        name.setAccessibleText(I18n.tr("ui.multiplayer.name"));
        TextField address = new TextField(old == null ? "" : old.address());
        address.setPromptText("play.example.net");
        address.setAccessibleText(I18n.tr("ui.multiplayer.address"));
        ComboBox<String> packs = new ComboBox<>();
        packs.getItems().addAll(I18n.tr("ui.multiplayer.packsPrompt"), I18n.tr("ui.multiplayer.packsEnabled"),
            I18n.tr("ui.multiplayer.packsDisabled"));
        Boolean accept = old == null ? null : old.acceptTextures();
        packs.getSelectionModel().select(accept == null ? 0 : accept ? 1 : 2);
        packs.setAccessibleText(I18n.tr("ui.multiplayer.packs"));
        Label error = UiKit.label("", "danger-text", "small");
        VBox body = new VBox(8, UiKit.label(I18n.tr("ui.multiplayer.name"), "small"), name,
            UiKit.label(I18n.tr("ui.multiplayer.address"), "small"), address,
            UiKit.label(I18n.tr("ui.multiplayer.packs"), "small"), packs, error);
        body.setPrefWidth(460);
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
                error.setText(problem);
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
        (old == null ? address : name).requestFocus();
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
            status.put(k, new ServerStatus(false, 0, 0, null, null, -1, -1, null, Tasks.message(err)));
            rerender();
        });
    }

    private void rerender() {
        project().ifPresent(p -> {
            if (!servers.isEmpty()) {
                render(p);
            }
        });
    }
}
