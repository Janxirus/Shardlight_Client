package dev.shardlight.client.app.pages;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.app.ui.DesignTokens;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Fields;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Motion;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.auth.MicrosoftClientIds;
import dev.shardlight.client.discord.DiscordApplicationIds;
import dev.shardlight.client.discord.DiscordPresence;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.java.JavaInstall;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.settings.LauncherSettings;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.OsInfo;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Launcher-wide settings in eight sections. A search box jumps to any single setting, every change applies at
 * once and offers Undo, and Appearance shows a live preview of what the choices look like.
 */
public final class SettingsPage implements Page {
    private static final Logger LOG = Logger.getLogger(SettingsPage.class.getName());
    /** the section ids, in the order the list shows them (also used by the command palette and --capture) */
    public static final List<String> SECTIONS = List.of("accounts", "appearance", "java", "performance", "downloads",
        "storage", "advanced", "about");
    /** older section names, each now a part of Advanced */
    private static final List<String> ADVANCED_PARTS = List.of("content", "privacy", "discord", "updates");
    private static final List<String> ACCENTS = DesignTokens.ACCENT_PRESETS;

    /** one searchable setting: the section it lives in and the anchor the page scrolls to */
    private record Entry(String section, String anchor, String title, String about) {
    }

    private static final List<Entry> INDEX = List.of(
        new Entry("accounts", "accounts", "ui.settings.section.accounts", "ui.settings.section.accounts.about"),
        new Entry("accounts", "skins", "ui.skins.title", "ui.skins.about"),
        new Entry("appearance", "theme", "ui.settings.theme", "ui.settings.themeAbout"),
        new Entry("appearance", "accent", "ui.settings.accent", "ui.settings.accentAbout"),
        new Entry("appearance", "highContrast", "ui.settings.highContrast", "ui.settings.highContrastAbout"),
        new Entry("appearance", "reducedMotion", "ui.settings.reducedMotion", "ui.settings.reducedMotionAbout"),
        new Entry("appearance", "effects", "ui.settings.effects", "ui.settings.effectsAbout"),
        new Entry("appearance", "compact", "ui.settings.compact", "ui.settings.compactAbout"),
        new Entry("appearance", "scale", "ui.settings.scale", "ui.settings.scaleAbout"),
        new Entry("appearance", "language", "ui.settings.language", "ui.settings.languageAbout"),
        new Entry("java", "memory", "ui.settings.memory", "ui.settings.memoryAbout"),
        new Entry("java", "preferMojang", "ui.settings.preferMojang", "ui.settings.preferMojangAbout"),
        new Entry("java", "javaFound", "ui.settings.javaFound", "ui.settings.javaPerProject"),
        new Entry("performance", "defaultPreset", "ui.settings.defaultPreset", "ui.settings.defaultPresetAbout"),
        new Entry("downloads", "concurrent", "ui.settings.concurrent", "ui.settings.concurrentAbout"),
        new Entry("downloads", "retries", "ui.settings.retries", "ui.settings.retriesAbout"),
        new Entry("downloads", "notify", "ui.settings.notify", "ui.settings.notifyAbout"),
        new Entry("storage", "dataFolder", "ui.settings.dataFolder", "ui.settings.dataFolderHow"),
        new Entry("storage", "projectsFolder", "ui.settings.projectsFolder", "ui.settings.dataFolderHow"),
        new Entry("storage", "backupsFolder", "ui.settings.backupsFolder", "ui.settings.dataFolderHow"),
        new Entry("storage", "logsFolder", "ui.settings.logsFolder", "ui.settings.dataFolderHow"),
        new Entry("storage", "cacheFolder", "ui.settings.cacheFolder", "ui.settings.dataFolderHow"),
        new Entry("storage", "diskUse", "ui.storage.title", "ui.storage.cleanupAbout"),
        new Entry("storage", "cleanup", "ui.storage.cleanup", "ui.storage.cleanupAbout"),
        new Entry("storage", "diagnostics", "ui.settings.diagnostics", "ui.settings.diagnosticsAbout"),
        new Entry("advanced", "signin", "ui.settings.clientId", "ui.settings.clientIdAbout"),
        new Entry("advanced", "content", "ui.settings.section.content", "ui.settings.section.content.about"),
        new Entry("advanced", "curseKey", "ui.settings.curseKey", "ui.settings.curseKeyAbout"),
        new Entry("advanced", "defaultSource", "ui.settings.defaultSource", "ui.settings.defaultSourceAbout"),
        new Entry("advanced", "snapshots", "ui.settings.snapshots", "ui.settings.snapshotsAbout"),
        new Entry("advanced", "privacy", "ui.settings.section.privacy", "ui.settings.section.privacy.about"),
        new Entry("advanced", "discord", "ui.settings.section.discord", "ui.settings.section.discord.about"),
        new Entry("advanced", "discordApp", "ui.settings.discordApp", "ui.settings.discordAppAbout"),
        new Entry("advanced", "updates", "ui.settings.section.updates", "ui.settings.section.updates.about"),
        new Entry("advanced", "checkOnStart", "ui.settings.checkOnStart", "ui.settings.checkOnStartAbout"),
        new Entry("advanced", "channel", "ui.settings.channel", "ui.settings.channelAbout"),
        new Entry("about", "version", "ui.settings.version", "ui.settings.section.about.about"),
        new Entry("about", "license", "ui.settings.license", "ui.settings.licenseText"),
        new Entry("about", "thirdParty", "ui.settings.thirdParty", "ui.settings.thirdPartyText"));

    private final AppContext ctx;
    private final HBox root = new HBox(0);
    private final VBox nav = new VBox(2);
    private final VBox content = new VBox(16);
    private final ScrollPane scroll;
    private final Fields.SearchField search = new Fields.SearchField(I18n.tr("ui.settings.search"));
    private final Map<String, Node> anchors = new HashMap<>();
    private String section = "accounts";
    private String pendingAnchor;
    private boolean jumping;
    private List<JavaInstall> detectedJava;
    private boolean scanningJava;

    public SettingsPage(AppContext ctx) {
        this.ctx = ctx;
        nav.getStyleClass().add("settings-nav");
        nav.setPadding(new Insets(4, 12, 4, 0));
        VBox side = new VBox(12, search, nav);
        side.setMinWidth(ThemeManager.scaled(230));
        side.setPrefWidth(Region.USE_COMPUTED_SIZE);
        side.setMaxWidth(ThemeManager.scaled(320));
        side.setPadding(new Insets(0, 12, 0, 0));
        side.getStyleClass().add("settings-side");
        content.setPadding(new Insets(4, 8, 24, 16));
        content.setMaxWidth(ThemeManager.scaled(860));
        scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS);
        root.getChildren().addAll(side, scroll);
        search.field().textProperty().addListener((o, a, q) -> {
            if (!jumping) {
                render();
            }
        });
        search.field().setOnAction(e -> {
            List<Entry> hits = matches(search.field().getText());
            if (!hits.isEmpty()) {
                jump(hits.get(0));
            }
        });
        ctx.onAccountsChanged(() -> {
            if ("accounts".equals(section)) {
                render();
            }
        });
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.settings");
    }

    @Override
    public Region view() {
        return root;
    }

    @Override
    public boolean scrolls() {
        return false;
    }

    @Override
    public void onShow() {
        render();
    }

    /** show one section (used by other pages, e.g. "content" from Discover); the old part names open Advanced there */
    public void open(String id) {
        if (SECTIONS.contains(id)) {
            section = id;
            pendingAnchor = null;
        } else if (ADVANCED_PARTS.contains(id)) {
            section = "advanced";
            pendingAnchor = id;
        } else {
            return;
        }
        jumping = true;
        search.field().clear();
        jumping = false;
        render();
    }

    /** --capture only: show the Accounts section with this Skins card in place of the live one */
    public void previewSkins(Node skins) {
        open("accounts");
        int last = content.getChildren().size() - 1;
        if (last >= 0) {
            content.getChildren().set(last, skins);
        }
    }

    /** --capture only: type into the search box */
    public void previewSearch(String query) {
        search.field().setText(query);
    }

    /** --capture only: change one harmless setting so the Undo toast shows */
    public void previewUndo() {
        open("downloads");
        change(I18n.tr("ui.settings.notify"), I18n.tr("ui.settings.valueOff"), st -> st.downloads.notifyWhenDone,
            (st, v) -> st.downloads.notifyWhenDone = v, false);
        render();
    }

    private void jump(Entry e) {
        section = e.section();
        pendingAnchor = e.anchor();
        jumping = true;
        search.field().clear();
        jumping = false;
        render();
    }

    private static List<Entry> matches(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : INDEX) {
            String title = I18n.tr(e.title()).toLowerCase(Locale.ROOT);
            String about = I18n.tr(e.about()).toLowerCase(Locale.ROOT);
            if (title.contains(q) || about.contains(q)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt(e -> I18n.tr(e.title()).toLowerCase(Locale.ROOT).contains(q) ? 0 : 1));
        return out;
    }

    private void render() {
        anchors.clear();
        nav.getChildren().clear();
        String query = search.field().getText();
        boolean searching = query != null && !query.isBlank();
        for (String id : SECTIONS) {
            Button b = new Button(I18n.tr("ui.settings.section." + id));
            b.setGraphic(Icons.of(icon(id), 16));
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.getStyleClass().addAll("flat", "settings-nav-button");
            if (id.equals(section) && !searching) {
                b.getStyleClass().add("selected");
            }
            b.setOnAction(e -> open(id));
            nav.getChildren().add(b);
        }
        content.getChildren().clear();
        LauncherSettings s = ctx.services().settings().get();
        if (ctx.services().settings().isReadOnly()) {
            content.getChildren().add(UiKit.banner("danger", I18n.tr("ui.settings.readOnly")));
        }
        if (searching) {
            results(query);
            return;
        }
        switch (section) {
            case "accounts" -> accounts(s);
            case "appearance" -> appearance(s);
            case "java" -> java(s);
            case "performance" -> performance(s);
            case "downloads" -> downloads(s);
            case "storage" -> storage();
            case "advanced" -> advanced(s);
            default -> about();
        }
        String target = pendingAnchor;
        pendingAnchor = null;
        if (target != null) {
            Platform.runLater(() -> reveal(target));
        }
    }

    private void results(String query) {
        List<Entry> hits = matches(query);
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.searchResults", query.strip()), "h3"));
        if (hits.isEmpty()) {
            content.getChildren().add(UiKit.emptyState(Icons.SEARCH, I18n.tr("ui.settings.searchNone", query.strip()),
                I18n.tr("ui.settings.searchNoneAbout"), null));
            return;
        }
        VBox list = new VBox(8);
        for (Entry e : hits) {
            VBox text = new VBox(2, UiKit.label(I18n.tr(e.title()), "strong"), UiKit.label(I18n.tr(e.about()), "muted", "small"));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, text, UiKit.chip(I18n.tr("ui.settings.section." + e.section()), null),
                Icons.of(Icons.CHEVRON_RIGHT, 14));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().addAll("card", "flat", "setting-hit");
            row.setFocusTraversable(true);
            row.setOnMouseClicked(ev -> jump(e));
            row.setOnKeyPressed(ev -> {
                if (ev.getCode() == KeyCode.ENTER || ev.getCode() == KeyCode.SPACE) {
                    jump(e);
                    ev.consume();
                }
            });
            row.setAccessibleText(I18n.tr(e.title()) + ", " + I18n.tr("ui.settings.section." + e.section()));
            list.getChildren().add(row);
        }
        content.getChildren().add(list);
    }

    /** scroll to a setting and mark it for a moment */
    private void reveal(String anchor) {
        Node n = anchors.get(anchor);
        if (n == null) {
            return;
        }
        content.applyCss();
        content.layout();
        Bounds nb = n.localToScene(n.getBoundsInLocal());
        Bounds cb = content.localToScene(content.getBoundsInLocal());
        double max = content.getHeight() - scroll.getViewportBounds().getHeight();
        if (max > 0 && nb != null && cb != null) {
            scroll.setVvalue(Math.max(0, Math.min(1, (nb.getMinY() - cb.getMinY() - 24) / max)));
        }
        n.getStyleClass().add("setting-found");
        PauseTransition done = new PauseTransition(Duration.seconds(3));
        done.setOnFinished(e -> n.getStyleClass().remove("setting-found"));
        done.play();
    }

    private static String icon(String id) {
        return switch (id) {
            case "accounts" -> Icons.USER;
            case "appearance" -> Icons.PALETTE;
            case "java" -> Icons.CUBE;
            case "performance" -> Icons.GAUGE;
            case "downloads" -> Icons.DOWNLOAD;
            case "storage" -> Icons.FOLDER;
            case "advanced" -> Icons.SLIDERS;
            default -> Icons.INFO;
        };
    }

    private void heading(String id) {
        content.getChildren().add(reg(id, new VBox(4, UiKit.label(I18n.tr("ui.settings.section." + id), "h2"),
            UiKit.label(I18n.tr("ui.settings.section." + id + ".about"), "muted"))));
    }

    /** a heading for one part of the Advanced section */
    private void subheading(String id) {
        content.getChildren().add(reg(id, new VBox(2, UiKit.label(I18n.tr("ui.settings.section." + id), "h3"),
            UiKit.label(I18n.tr("ui.settings.section." + id + ".about"), "muted", "small"))));
    }

    private <N extends Node> N reg(String anchor, N node) {
        anchors.put(anchor, node);
        return node;
    }

    /** a setting row the search can scroll to */
    private HBox row(String anchor, String title, String about, Node control) {
        return reg(anchor, UiKit.settingRow(title, about, control));
    }

    /** save one change; the UI is re-rendered when it fails so controls show the real value */
    private boolean save(Consumer<LauncherSettings> change) {
        try {
            ctx.services().settings().update(change);
            return true;
        } catch (Exception e) {
            ctx.overlay().toast("danger", Tasks.message(e));
            render();
            return false;
        }
    }

    /** save one change and say so with an Undo button that puts the old value back */
    private <T> void change(String title, String shown, Function<LauncherSettings, T> read,
                            BiConsumer<LauncherSettings, T> write, T value) {
        T old = read.apply(ctx.services().settings().get());
        if (Objects.equals(old, value) || !save(st -> write.accept(st, value))) {
            return;
        }
        ctx.overlay().undo(I18n.tr("ui.settings.changed", title, shown), () -> {
            save(st -> write.accept(st, old));
            render();
        });
    }

    private ToggleSwitch flag(String title, Function<LauncherSettings, Boolean> read, BiConsumer<LauncherSettings, Boolean> write) {
        ToggleSwitch t = new ToggleSwitch(read.apply(ctx.services().settings().get()), title);
        t.selectedProperty().addListener((o, a, b) -> change(title, I18n.tr(b ? "ui.settings.valueOn" : "ui.settings.valueOff"),
            read, write, b));
        return t;
    }

    // ---- accounts ----

    private void accounts(LauncherSettings s) {
        heading("accounts");
        var accounts = ctx.services().accounts();
        if (!accounts.isSignInConfigured()) {
            content.getChildren().add(UiKit.banner("warning", I18n.tr("ui.settings.signInNotConfigured")));
        }
        List<Account> list = accounts.accounts();
        Optional<Account> selected = accounts.selected();
        VBox rows = new VBox(8);
        if (list.isEmpty()) {
            rows.getChildren().add(UiKit.emptyState(Icons.USER, I18n.tr("ui.settings.noAccountsTitle"),
                I18n.tr("ui.settings.noAccounts"), null));
        }
        for (Account a : list) {
            boolean isSelected = selected.map(x -> x.id.equals(a.id)).orElse(false);
            VBox text = new VBox(2, UiKit.label(a.username, "project-name"),
                UiKit.label(I18n.tr("ui.settings.accountSub", shortId(a.id)), "faint", "small"));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, UiKit.iconBox(Icons.of(Icons.USER, 20), 40), text);
            row.setAlignment(Pos.CENTER_LEFT);
            if (isSelected) {
                row.getChildren().add(UiKit.chip(I18n.tr("ui.settings.inUse"), "success", Icons.CHECK));
            } else {
                row.getChildren().add(UiKit.button(I18n.tr("ui.settings.useAccount"), null,
                    () -> Tasks.quiet(ctx.overlay(), () -> {
                        accounts.select(a.id);
                        return a;
                    }, x -> ctx.accountsChanged())));
            }
            if (!a.ownsGame) {
                row.getChildren().add(UiKit.chip(I18n.tr("ui.settings.noGame"), "warning"));
            }
            // whether the saved sign-in still works needs the OS secret store, which can be slow: fill it in later
            HBox signInState = new HBox(8);
            signInState.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(signInState);
            Fx.run(() -> accounts.hasSavedSignIn(a.id), saved -> {
                if (!saved && accounts.isSignInConfigured()) {
                    signInState.getChildren().setAll(UiKit.chip(I18n.tr("ui.settings.signInExpired"), "warning"),
                        UiKit.button(I18n.tr("ui.play.signInAgain"), null, () -> SignInDialog.show(ctx, x -> render())));
                }
            }, err -> { });
            row.getChildren().add(UiKit.button(I18n.tr("ui.settings.signOut"), null, () -> ctx.overlay().confirm(
                I18n.tr("ui.settings.signOutTitle", a.username), I18n.tr("ui.settings.signOutMessage"), List.of(),
                I18n.tr("ui.settings.signOut"), false, () -> Tasks.quiet(ctx.overlay(), () -> {
                    accounts.signOut(a.id);
                    return a;
                }, x -> {
                    ctx.overlay().toast("success", I18n.tr("ui.settings.signedOut", a.username));
                    ctx.accountsChanged();
                }))));
            row.getStyleClass().add("card");
            rows.getChildren().add(row);
        }
        Button add = UiKit.primary(I18n.tr(list.isEmpty() ? "ui.account.signIn" : "ui.settings.addAccount"), Icons.PLUS,
            () -> SignInDialog.show(ctx, a -> render()));
        content.getChildren().addAll(rows, new HBox(add));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.secretStore", accounts.secretStoreName()), "faint", "small"));
        content.getChildren().add(reg("skins", SkinsSection.build(ctx)));
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }

    // ---- appearance ----

    private void appearance(LauncherSettings s) {
        heading("appearance");
        LauncherSettings.Appearance a = s.appearance;
        content.getChildren().add(preview());

        HBox theme = UiKit.segmented(List.of(I18n.tr("ui.settings.dark"), I18n.tr("ui.settings.light")),
            "light".equals(a.theme) ? 1 : 0, i -> change(I18n.tr("ui.settings.theme"),
                I18n.tr(i == 1 ? "ui.settings.light" : "ui.settings.dark"), st -> st.appearance.theme,
                (st, v) -> st.appearance.theme = v, i == 1 ? "light" : "dark"));

        HBox swatches = new HBox(6);
        for (String hex : ACCENTS) {
            Button b = new Button();
            b.getStyleClass().add("accent-swatch");
            b.setStyle("-fx-background-color: " + hex + ";");
            b.setAccessibleText(I18n.tr("ui.settings.accentColour", hex));
            if (hex.equalsIgnoreCase(a.accent)) {
                b.getStyleClass().add("selected");
            }
            b.setOnAction(e -> {
                change(I18n.tr("ui.settings.accent"), hex, st -> st.appearance.accent, (st, v) -> st.appearance.accent = v, hex);
                render();
            });
            swatches.getChildren().add(b);
        }
        ColorPicker picker = new ColorPicker(ThemeManager.parse(a.accent, Color.web(ACCENTS.get(0))));
        picker.setAccessibleText(I18n.tr("ui.settings.accentCustom"));
        picker.setOnAction(e -> {
            String hex = ThemeManager.web(picker.getValue());
            change(I18n.tr("ui.settings.accent"), hex, st -> st.appearance.accent, (st, v) -> st.appearance.accent = v, hex);
            render();
        });
        swatches.getChildren().add(picker);
        swatches.setAlignment(Pos.CENTER_LEFT);

        Slider scale = new Slider(0.8, 1.6, a.scale <= 0 ? 1 : a.scale);
        scale.setMajorTickUnit(0.1);
        scale.setMinorTickCount(0);
        scale.setSnapToTicks(true);
        scale.setPrefWidth(ThemeManager.scaled(220));
        scale.setAccessibleText(I18n.tr("ui.settings.scale"));
        Label scaleValue = UiKit.label(Math.round(scale.getValue() * 100) + "%", "muted");
        scale.valueProperty().addListener((o, x, v) -> scaleValue.setText(Math.round(v.doubleValue() * 100) + "%"));
        scale.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) {
                double v = Math.round(scale.getValue() * 10) / 10.0;
                change(I18n.tr("ui.settings.scale"), Math.round(v * 100) + "%", st -> st.appearance.scale,
                    (st, x) -> st.appearance.scale = x, v);
            }
        });

        Node language = languagePicker(a);

        content.getChildren().add(UiKit.card(
            row("theme", I18n.tr("ui.settings.theme"), I18n.tr("ui.settings.themeAbout"), theme),
            row("accent", I18n.tr("ui.settings.accent"), I18n.tr("ui.settings.accentAbout"), swatches),
            row("highContrast", I18n.tr("ui.settings.highContrast"), I18n.tr("ui.settings.highContrastAbout"),
                flag(I18n.tr("ui.settings.highContrast"), st -> st.appearance.highContrast, (st, v) -> st.appearance.highContrast = v)),
            row("reducedMotion", I18n.tr("ui.settings.reducedMotion"), I18n.tr("ui.settings.reducedMotionAbout"),
                flag(I18n.tr("ui.settings.reducedMotion"), st -> st.appearance.reducedMotion, (st, v) -> st.appearance.reducedMotion = v)),
            row("effects", I18n.tr("ui.settings.effects"), I18n.tr("ui.settings.effectsAbout"),
                flag(I18n.tr("ui.settings.effects"), st -> st.appearance.backgroundEffects, (st, v) -> st.appearance.backgroundEffects = v)),
            row("compact", I18n.tr("ui.settings.compact"), I18n.tr("ui.settings.compactAbout"),
                flag(I18n.tr("ui.settings.compact"), st -> st.appearance.compact, (st, v) -> st.appearance.compact = v)),
            row("scale", I18n.tr("ui.settings.scale"), I18n.tr("ui.settings.scaleAbout"), new HBox(10, scale, scaleValue)),
            row("language", I18n.tr("ui.settings.language"), I18n.tr("ui.settings.languageAbout"), language)));
    }

    /**
     * The language picker: the system language, English and every translation file found, and a button that opens the
     * folder to drop new ones into. The two test languages show only when asked for (docs/LANGUAGES.md).
     */
    private Node languagePicker(LauncherSettings.Appearance a) {
        List<I18n.Language> found = new ArrayList<>(I18n.available());
        boolean tests = Boolean.getBoolean("shardlight.testLanguages") || System.getenv("SHARDLIGHT_TEST_LANGUAGES") != null
            || I18n.testLanguages().stream().anyMatch(l -> l.tag().equals(a.language));
        if (tests) {
            found.addAll(I18n.testLanguages());
        }
        Map<String, String> names = new HashMap<>();
        List<String> tags = new ArrayList<>();
        tags.add("");
        for (I18n.Language l : found) {
            tags.add(l.tag());
            names.put(l.tag(), l.name());
        }
        ComboBox<String> language = new ComboBox<>();
        language.getItems().setAll(tags);
        language.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String tag) {
                return tag == null || tag.isEmpty() ? I18n.tr("ui.settings.languageSystem") : names.getOrDefault(tag, tag);
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        language.setValue(tags.contains(a.language) ? a.language : "");
        language.setAccessibleText(I18n.tr("ui.settings.language"));
        language.setOnAction(e -> {
            String tag = language.getValue() == null ? "" : language.getValue();
            if (tag.equals(a.language == null ? "" : a.language)) {
                return;
            }
            change(I18n.tr("ui.settings.language"), language.getConverter().toString(tag), st -> st.appearance.language,
                (st, v) -> st.appearance.language = v, tag);
            ctx.overlay().toast("info", I18n.tr("ui.settings.languageRestart"),
                new Overlay.Action(I18n.tr("ui.settings.restartNow"), ctx::restart));
        });
        Button folder = UiKit.button(I18n.tr("ui.settings.languageAdd"), Icons.FOLDER, this::openLanguages);
        HBox box = new HBox(8, language, folder);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** make the folder translation files go in (with a README and the English files to copy) and show it */
    private void openLanguages() {
        Path dir = ctx.services().paths().languages();
        Fx.run(() -> {
            I18n.exportTemplate(dir);
            return dir;
        }, d -> ctx.openFolder(d), err -> ctx.overlay().toast("danger", Tasks.message(err)));
    }

    /** a small mock-up drawn with the live tokens, so theme, accent, size, motion and contrast show at once */
    private Node preview() {
        VBox side = new VBox(4);
        side.getStyleClass().add("preview-side");
        String[] names = {I18n.tr("ui.nav.home"), I18n.tr("ui.nav.library"), I18n.tr("ui.nav.discover")};
        for (int i = 0; i < names.length; i++) {
            Label pill = UiKit.label(names[i], "preview-pill");
            pill.setMaxWidth(Double.MAX_VALUE);
            if (i == 0) {
                pill.getStyleClass().add("selected");
            }
            side.getChildren().add(pill);
        }
        Region dot = new Region();
        dot.getStyleClass().add("preview-dot");
        Motion.pulse(dot, 0.35, 0);
        HBox chips = new HBox(6, UiKit.chip("Fabric", "accent"), UiKit.chip("1.21", null),
            UiKit.chip(I18n.tr("ui.settings.preview.ready"), "success", Icons.CHECK));
        Button play = UiKit.primary(I18n.tr("ui.settings.preview.play"), Icons.PLAY, () -> { });
        Button other = UiKit.button(I18n.tr("ui.settings.preview.settings"), null, () -> { });
        play.setFocusTraversable(false);
        other.setFocusTraversable(false);
        VBox card = UiKit.card(new HBox(8, dot, UiKit.label(I18n.tr("ui.settings.preview.project"), "project-name")),
            UiKit.label(I18n.tr("ui.settings.preview.body"), "muted", "small"), chips, UiKit.progress(0.62),
            new HBox(8, play, other));
        VBox main = new VBox(10, UiKit.label(I18n.tr("ui.settings.preview.title"), "h3"), card);
        main.getStyleClass().add("preview-main");
        HBox.setHgrow(main, Priority.ALWAYS);
        HBox frame = new HBox(side, main);
        frame.getStyleClass().add("appearance-preview");
        frame.setMouseTransparent(true);
        frame.setAccessibleText(I18n.tr("ui.settings.preview.about"));
        return UiKit.section(I18n.tr("ui.settings.preview"), frame);
    }

    // ---- Java ----

    private void java(LauncherSettings s) {
        heading("java");
        LauncherSettings.JavaDefaults j = s.java;
        long total = NewProjectWizard.totalMemoryMb();
        int maxSlider = (int) Math.max(2048, Math.min(32768, total > 0 ? total - 1024 : 16384));
        Slider mem = new Slider(1024, maxSlider, Math.max(1024, Math.min(j.maxMemoryMb, maxSlider)));
        mem.setMajorTickUnit(1024);
        mem.setMinorTickCount(1);
        mem.setSnapToTicks(true);
        mem.setBlockIncrement(512);
        mem.setPrefWidth(ThemeManager.scaled(260));
        mem.setAccessibleText(I18n.tr("ui.settings.memory"));
        Label memValue = UiKit.label(memText(j.maxMemoryMb), "muted");
        mem.valueProperty().addListener((o, a, v) -> memValue.setText(memText((int) (Math.round(v.doubleValue() / 512) * 512))));
        mem.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) {
                int v = (int) (Math.round(mem.getValue() / 512) * 512);
                change(I18n.tr("ui.settings.memory"), memText(v), st -> st.java.maxMemoryMb, (st, x) -> st.java.maxMemoryMb = x, v);
            }
        });
        Label memHint = UiKit.label(total > 0 ? I18n.tr("ui.settings.memoryHint", memText((int) total)) : "", "faint", "small");

        content.getChildren().add(UiKit.card(
            row("memory", I18n.tr("ui.settings.memory"), I18n.tr("ui.settings.memoryAbout"),
                new VBox(4, new HBox(10, mem, memValue), memHint)),
            row("preferMojang", I18n.tr("ui.settings.preferMojang"), I18n.tr("ui.settings.preferMojangAbout"),
                flag(I18n.tr("ui.settings.preferMojang"), st -> st.java.preferMojangRuntime, (st, v) -> st.java.preferMojangRuntime = v))));

        VBox found = new VBox(6);
        Button rescan = UiKit.button(I18n.tr("ui.settings.javaScan"), Icons.REFRESH, () -> scanJava(true));
        content.getChildren().add(reg("javaFound", UiKit.section(I18n.tr("ui.settings.javaFound"), found, rescan)));
        if (scanningJava) {
            found.getChildren().add(UiKit.label(I18n.tr("ui.settings.javaScanning"), "muted"));
        } else if (detectedJava == null) {
            scanJava(false);
            found.getChildren().add(UiKit.label(I18n.tr("ui.settings.javaScanning"), "muted"));
        } else if (detectedJava.isEmpty()) {
            found.getChildren().add(UiKit.label(I18n.tr("ui.settings.javaNone"), "muted"));
        } else {
            for (JavaInstall install : detectedJava) {
                VBox text = new VBox(2, UiKit.label(I18n.tr("ui.settings.javaVersion", install.majorVersion(),
                    install.version(), install.vendor() == null ? "" : install.vendor()), "project-name"),
                    UiKit.label(install.executable().toString(), "faint", "small", "mono"));
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox row = new HBox(10, UiKit.iconBox(Icons.of(Icons.CUBE, 18), 36), text);
                if (install.managed()) {
                    row.getChildren().add(UiKit.chip(I18n.tr("ui.settings.javaManaged"), "accent"));
                }
                if (install.arch() != null) {
                    row.getChildren().add(UiKit.chip(install.arch(), null));
                }
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().addAll("card", "flat");
                found.getChildren().add(row);
            }
        }
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.javaPerProject"), "faint", "small"));
    }

    private static String memText(int mb) {
        return mb >= 1024 ? String.format(I18n.locale(), "%.1f GB", mb / 1024.0) : mb + " MB";
    }

    private void scanJava(boolean force) {
        if (scanningJava || (!force && detectedJava != null)) {
            return;
        }
        scanningJava = true;
        Fx.run(() -> ctx.services().java().detect(), list -> {
            scanningJava = false;
            detectedJava = list;
            if ("java".equals(section)) {
                render();
            }
        }, err -> {
            scanningJava = false;
            detectedJava = List.of();
            LOG.log(Level.FINE, "Java scan failed", err);
            if ("java".equals(section)) {
                render();
            }
        });
        if (force && "java".equals(section)) {
            render();
        }
    }

    // ---- performance defaults ----

    /** the preset a new project starts with when its template does not choose one */
    static Preset defaultPreset(LauncherSettings s) {
        try {
            Preset p = Preset.valueOf(s.performance.defaultPreset);
            return p == Preset.CUSTOM ? Preset.DEFAULT : p;
        } catch (RuntimeException e) {
            return Preset.DEFAULT;
        }
    }

    private void performance(LauncherSettings s) {
        heading("performance");
        List<Preset> presets = List.of(Preset.ULTRA_PERFORMANCE, Preset.PERFORMANCE, Preset.BALANCED, Preset.QUALITY,
            Preset.ULTRA_QUALITY);
        Preset current = defaultPreset(s);
        ComboBox<Preset> pick = new ComboBox<>();
        pick.getItems().setAll(presets);
        pick.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Preset p) {
                return p == null ? "" : I18n.tr(p.nameKey());
            }

            @Override
            public Preset fromString(String string) {
                return Preset.DEFAULT;
            }
        });
        pick.setValue(current);
        pick.setAccessibleText(I18n.tr("ui.settings.defaultPreset"));
        pick.setOnAction(e -> {
            Preset p = pick.getValue();
            if (p != null) {
                change(I18n.tr("ui.settings.defaultPreset"), I18n.tr(p.nameKey()), st -> st.performance.defaultPreset,
                    (st, v) -> st.performance.defaultPreset = v, p.name());
                render();
            }
        });
        content.getChildren().add(UiKit.card(row("defaultPreset", I18n.tr("ui.settings.defaultPreset"),
            I18n.tr("ui.settings.defaultPresetAbout"), pick)));
        VBox list = new VBox(8);
        for (Preset p : presets) {
            VBox text = new VBox(2, UiKit.label(I18n.tr(p.nameKey()), "strong"),
                UiKit.label(I18n.tr(p.descriptionKey()), "muted", "small"));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox r = new HBox(12, text);
            r.setAlignment(Pos.CENTER_LEFT);
            if (p == current) {
                r.getChildren().add(UiKit.chip(I18n.tr("ui.settings.presetDefault"), "accent", Icons.CHECK));
            }
            r.getStyleClass().addAll("card", "flat");
            list.getChildren().add(r);
        }
        content.getChildren().add(UiKit.section(I18n.tr("ui.settings.presetsTitle"), list));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.presetsNote"), "faint", "small"));
    }

    // ---- downloads ----

    private void downloads(LauncherSettings s) {
        heading("downloads");
        Spinner<Integer> concurrent = new Spinner<>(1, 16, Math.max(1, Math.min(16, s.downloads.maxConcurrent)));
        concurrent.setEditable(true);
        concurrent.setPrefWidth(ThemeManager.scaled(100));
        concurrent.setAccessibleText(I18n.tr("ui.settings.concurrent"));
        concurrent.valueProperty().addListener((o, a, v) -> change(I18n.tr("ui.settings.concurrent"), String.valueOf(v),
            st -> st.downloads.maxConcurrent, (st, x) -> st.downloads.maxConcurrent = x, v));
        Spinner<Integer> retries = new Spinner<>(0, 10, Math.max(0, Math.min(10, s.downloads.maxRetries)));
        retries.setEditable(true);
        retries.setPrefWidth(ThemeManager.scaled(100));
        retries.setAccessibleText(I18n.tr("ui.settings.retries"));
        retries.valueProperty().addListener((o, a, v) -> change(I18n.tr("ui.settings.retries"), String.valueOf(v),
            st -> st.downloads.maxRetries, (st, x) -> st.downloads.maxRetries = x, v));
        content.getChildren().add(UiKit.card(
            row("concurrent", I18n.tr("ui.settings.concurrent"), I18n.tr("ui.settings.concurrentAbout"), concurrent),
            row("retries", I18n.tr("ui.settings.retries"), I18n.tr("ui.settings.retriesAbout"), retries),
            row("notify", I18n.tr("ui.settings.notify"), I18n.tr("ui.settings.notifyAbout"),
                flag(I18n.tr("ui.settings.notify"), st -> st.downloads.notifyWhenDone, (st, v) -> st.downloads.notifyWhenDone = v))));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.downloadsSafety"), "faint", "small"));
    }

    // ---- storage and diagnostics ----

    private void storage() {
        heading("storage");
        var paths = ctx.services().paths();
        content.getChildren().add(UiKit.card(
            folderRow("dataFolder", I18n.tr("ui.settings.dataFolder"), paths.root()),
            folderRow("projectsFolder", I18n.tr("ui.settings.projectsFolder"), paths.projects()),
            folderRow("backupsFolder", I18n.tr("ui.settings.backupsFolder"), paths.backups()),
            folderRow("logsFolder", I18n.tr("ui.settings.logsFolder"), paths.logs()),
            folderRow("cacheFolder", I18n.tr("ui.settings.cacheFolder"), paths.cache())));
        List<Node> usage = StorageSection.build(ctx);
        content.getChildren().add(reg("diskUse", usage.get(0)));
        content.getChildren().add(reg("cleanup", usage.get(1)));
        if (ctx.services().settings().recoveredFrom() != null) {
            content.getChildren().add(UiKit.banner("warning", I18n.tr("ui.settings.recovered",
                ctx.services().settings().recoveredFrom().getFileName())));
        }
        content.getChildren().add(reg("diagnostics", UiKit.section(I18n.tr("ui.settings.diagnostics"),
            UiKit.label(I18n.tr("ui.settings.diagnosticsAbout"), "muted"),
            new HBox(8, UiKit.button(I18n.tr("ui.settings.exportDiagnostics"), Icons.ARCHIVE,
                () -> PlayFlow.exportDiagnostics(ctx, null))))));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.dataFolderHow"), "faint", "small"));
    }

    private Node folderRow(String anchor, String title, java.nio.file.Path dir) {
        Button open = UiKit.button(I18n.tr("ui.settings.open"), Icons.FOLDER, () -> ctx.openFolder(dir));
        return row(anchor, title, dir.toAbsolutePath().toString(), open);
    }

    // ---- advanced: sign-in app, content sources, privacy, updates ----

    private void advanced(LauncherSettings s) {
        heading("advanced");
        signInApp(s);
        contentSources(s);
        privacy();
        discord(s);
        updates(s);
    }

    private void signInApp(LauncherSettings s) {
        TextField id = new TextField(s.auth.microsoftClientId == null ? "" : s.auth.microsoftClientId);
        id.setPromptText(MicrosoftClientIds.buildDefault().isEmpty() ? "00000000-0000-0000-0000-000000000000"
            : I18n.tr("ui.settings.clientIdBuiltIn"));
        id.setPrefColumnCount(30);
        id.setAccessibleText(I18n.tr("ui.settings.clientId"));
        Label error = UiKit.label("", "danger-text", "small");
        Button saveId = UiKit.button(I18n.tr("common.save"), null, () -> {
            String v = id.getText() == null ? "" : id.getText().trim();
            if (!v.isEmpty() && !MicrosoftClientIds.looksValid(v)) {
                error.setText(I18n.tr("ui.signin.clientIdInvalid"));
                return;
            }
            error.setText("");
            change(I18n.tr("ui.settings.clientId"), v.isEmpty() ? I18n.tr("ui.settings.clientIdBuiltIn") : v,
                st -> st.auth.microsoftClientId, (st, x) -> st.auth.microsoftClientId = x, v);
            ctx.accountsChanged();
        });
        VBox clientId = new VBox(6, new HBox(8, id, saveId), error);
        subheading("signin");
        content.getChildren().add(UiKit.card(
            row("signin", I18n.tr("ui.settings.clientId"), I18n.tr("ui.settings.clientIdAbout"), clientId),
            UiKit.button(I18n.tr("ui.settings.signInHelp"), Icons.EXTERNAL, () -> ctx.openUrl("https://aka.ms/mce-reviewappid"))));
    }

    private void contentSources(LauncherSettings s) {
        subheading("content");
        boolean hasKey = ctx.services().hasCurseForgeKey();
        ToggleSwitch curseOn = new ToggleSwitch(s.catalog.curseForgeEnabled && hasKey, I18n.tr("ui.settings.curseEnabled"));
        curseOn.selectedProperty().addListener((o, a, b) -> change("CurseForge", I18n.tr(b ? "ui.settings.valueOn" : "ui.settings.valueOff"),
            st -> st.catalog.curseForgeEnabled, (st, v) -> st.catalog.curseForgeEnabled = v, b));
        curseOn.setDisable(!hasKey);

        PasswordField key = new PasswordField();
        key.setPromptText(I18n.tr(hasKey ? "ui.settings.curseKeyStored" : "ui.settings.curseKeyPrompt"));
        key.setPrefColumnCount(28);
        key.setAccessibleText(I18n.tr("ui.settings.curseKey"));
        Button saveKey = UiKit.button(I18n.tr("common.save"), Icons.KEY, () -> {
            String v = key.getText() == null ? "" : key.getText().trim();
            if (v.isEmpty()) {
                return;
            }
            Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().setCurseForgeKey(v);
                ctx.services().settings().update(st -> st.catalog.curseForgeEnabled = true);
                return Boolean.TRUE;
            }, x -> {
                key.clear();
                ctx.overlay().toast("success", I18n.tr("ui.settings.curseKeySaved"));
                render();
            });
        });
        HBox keyRow = new HBox(8, key, saveKey);
        if (hasKey) {
            keyRow.getChildren().add(UiKit.button(I18n.tr("ui.settings.curseKeyRemove"), Icons.TRASH,
                () -> ctx.overlay().confirm(I18n.tr("ui.settings.curseKeyRemoveTitle"), I18n.tr("ui.settings.curseKeyRemoveMessage"),
                    List.of(), I18n.tr("ui.settings.curseKeyRemove"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                        ctx.services().setCurseForgeKey(null);
                        ctx.services().settings().update(st -> st.catalog.curseForgeEnabled = false);
                        return Boolean.TRUE;
                    }, x -> render()))));
        }
        keyRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> source = new ComboBox<>();
        source.getItems().setAll("modrinth", "curseforge");
        source.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String v) {
                return "curseforge".equals(v) ? "CurseForge" : "Modrinth";
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        source.setValue("curseforge".equals(s.catalog.defaultSource) && hasKey ? "curseforge" : "modrinth");
        source.setAccessibleText(I18n.tr("ui.settings.defaultSource"));
        source.setOnAction(e -> change(I18n.tr("ui.settings.defaultSource"),
            "curseforge".equals(source.getValue()) ? "CurseForge" : "Modrinth", st -> st.catalog.defaultSource,
            (st, v) -> st.catalog.defaultSource = v, source.getValue()));

        content.getChildren().add(UiKit.card(
            UiKit.settingRow("Modrinth", I18n.tr("ui.settings.modrinthAbout"), UiKit.chip(I18n.tr("ui.settings.alwaysOn"), "success")),
            UiKit.settingRow("CurseForge", I18n.tr("ui.settings.curseAbout"), curseOn),
            row("curseKey", I18n.tr("ui.settings.curseKey"), I18n.tr("ui.settings.curseKeyAbout"), keyRow),
            row("defaultSource", I18n.tr("ui.settings.defaultSource"), I18n.tr("ui.settings.defaultSourceAbout"), source),
            row("snapshots", I18n.tr("ui.settings.snapshots"), I18n.tr("ui.settings.snapshotsAbout"),
                flag(I18n.tr("ui.settings.snapshots"), st -> st.catalog.showSnapshotVersions, (st, v) -> st.catalog.showSnapshotVersions = v))));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.contentLegal"), "faint", "small"));
    }

    private void privacy() {
        subheading("privacy");
        VBox list = new VBox(8);
        for (String k : List.of("signin", "game", "catalog", "github", "servers", "updates", "images")) {
            HBox row = new HBox(10, UiKit.iconBox(Icons.of(Icons.INFO, 14), 24),
                UiKit.label(I18n.tr("ui.settings.privacy." + k)));
            row.setAlignment(Pos.TOP_LEFT);
            list.getChildren().add(row);
        }
        content.getChildren().addAll(UiKit.banner("success", I18n.tr("ui.settings.noTelemetry")),
            UiKit.section(I18n.tr("ui.settings.privacyConnections"), list),
            UiKit.label(I18n.tr("ui.settings.privacyLogs"), "faint", "small"));
    }

    /** Discord Rich Presence: one switch, off by default, and the application id it needs */
    private void discord(LauncherSettings s) {
        subheading("discord");
        String builtIn = DiscordApplicationIds.buildDefault();
        String shownId = DiscordApplicationIds.fromSettings(ctx.services().settings()).get();
        boolean ready = !shownId.isEmpty();
        ToggleSwitch on = flag(I18n.tr("ui.settings.discord"), st -> st.privacy.discordRichPresence,
            (st, v) -> st.privacy.discordRichPresence = v);
        StackPane onBox = new StackPane(on);
        onBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        if (!ready) {
            on.setDisable(true);
            UiKit.tooltip(onBox, I18n.tr("ui.settings.discordNotReady"));
        }
        TextField id = new TextField(s.privacy.discordApplicationId == null ? "" : s.privacy.discordApplicationId);
        id.setPromptText(builtIn.isEmpty() ? "123456789012345678" : I18n.tr("ui.settings.discordIdBuiltIn"));
        id.setPrefColumnCount(22);
        id.setAccessibleText(I18n.tr("ui.settings.discordApp"));
        Label error = UiKit.label("", "danger-text", "small");
        Button save = UiKit.button(I18n.tr("common.save"), null, () -> {
            String v = id.getText() == null ? "" : id.getText().trim();
            if (!v.isEmpty() && !DiscordApplicationIds.looksValid(v)) {
                error.setText(I18n.tr("ui.settings.discordIdInvalid"));
                return;
            }
            error.setText("");
            change(I18n.tr("ui.settings.discordApp"), v.isEmpty() ? I18n.tr("ui.settings.discordIdBuiltIn") : v,
                st -> st.privacy.discordApplicationId, (st, x) -> st.privacy.discordApplicationId = x, v);
            render();
        });
        VBox appId = new VBox(6, new HBox(8, id, save), error);
        List<Node> parts = new ArrayList<>();
        if (!ready) {
            parts.add(UiKit.banner("info", I18n.tr("ui.settings.discordSetup")));
        }
        parts.add(row("discord", I18n.tr("ui.settings.discord"), I18n.tr("ui.settings.discordAbout"), onBox));
        Optional<Project> sample = ctx.selectedProject();
        if (sample.isPresent() && ready) {
            Project p = sample.get();
            DiscordPresence.Activity a = DiscordPresence.activityFor(p.name, p.minecraftVersion, java.time.Instant.now());
            VBox example = new VBox(2, UiKit.label(I18n.tr("ui.settings.discordExample"), "caption", "faint"),
                UiKit.label(a.details(), "strong"), UiKit.label(a.state(), "muted"));
            example.getStyleClass().add("discord-example");
            parts.add(example);
        }
        parts.add(row("discordApp", I18n.tr("ui.settings.discordApp"), I18n.tr("ui.settings.discordAppAbout"), appId));
        parts.add(UiKit.label(I18n.tr("ui.settings.discordPrivacy"), "faint", "small"));
        content.getChildren().add(UiKit.card(parts.toArray(new Node[0])));
    }

    private void updates(LauncherSettings s) {
        subheading("updates");
        var updates = ctx.services().updates();
        content.getChildren().add(row("version", I18n.tr("ui.settings.version"), null, UiKit.label(BuildInfo.VERSION, "mono")));
        if (!updates.isConfigured()) {
            content.getChildren().add(UiKit.banner("info", I18n.tr("ui.settings.updatesNotConfigured")));
            return;
        }
        HBox channel = UiKit.segmented(List.of(I18n.tr("ui.settings.stable"), I18n.tr("ui.settings.beta")),
            "beta".equals(s.updates.channel) ? 1 : 0, i -> change(I18n.tr("ui.settings.channel"),
                I18n.tr(i == 1 ? "ui.settings.beta" : "ui.settings.stable"), st -> st.updates.channel,
                (st, v) -> st.updates.channel = v, i == 1 ? "beta" : "stable"));
        VBox result = new VBox(8);
        Button check = UiKit.primary(I18n.tr("ui.settings.checkNow"), Icons.REFRESH, () -> checkUpdates(result));
        content.getChildren().addAll(UiKit.card(
            row("checkOnStart", I18n.tr("ui.settings.checkOnStart"), I18n.tr("ui.settings.checkOnStartAbout"),
                flag(I18n.tr("ui.settings.checkOnStart"), st -> st.updates.checkOnStart, (st, v) -> st.updates.checkOnStart = v)),
            row("channel", I18n.tr("ui.settings.channel"), I18n.tr("ui.settings.channelAbout"), channel)),
            new HBox(check), result,
            UiKit.label(I18n.tr("ui.settings.updatesSafety"), "faint", "small"));
    }

    private void checkUpdates(VBox result) {
        result.getChildren().setAll(UiKit.label(I18n.tr("ui.settings.checking"), "muted"));
        String channel = ctx.services().settings().get().updates.channel;
        Fx.run(() -> ctx.services().updates().check(channel, new CancellationToken()), info -> {
            result.getChildren().clear();
            if (info.isEmpty()) {
                result.getChildren().add(UiKit.banner("success", I18n.tr("ui.settings.upToDate", BuildInfo.VERSION)));
                return;
            }
            var u = info.get();
            VBox body = new VBox(6, UiKit.label(I18n.tr("ui.settings.updateAvailable", u.version()), "h3"));
            if (u.changelog() != null && !u.changelog().isBlank()) {
                Label log = UiKit.label(CatalogProjectPage.plain(u.changelog()), "small");
                log.setWrapText(true);
                body.getChildren().add(log);
            }
            HBox actions = new HBox(8);
            actions.getChildren().add(UiKit.primary(I18n.tr("ui.settings.downloadUpdate"), Icons.DOWNLOAD, () ->
                Tasks.withProgress(ctx.overlay(), I18n.tr("ui.settings.downloadingUpdate", u.version()), true, (p, c) ->
                    ctx.services().updates().download(u, platform(), ctx.services().paths().cache().resolve("updates"), p, c),
                    file -> {
                        ctx.overlay().message(I18n.tr("ui.settings.updateDownloaded"), I18n.tr("ui.settings.updateDownloadedMessage",
                            file.getFileName()));
                        ctx.openFolder(file.getParent());
                    })));
            if (u.notesUrl() != null) {
                actions.getChildren().add(UiKit.button(I18n.tr("ui.settings.releaseNotes"), Icons.EXTERNAL,
                    () -> ctx.openUrl(u.notesUrl().toString())));
            }
            actions.getChildren().add(UiKit.button(I18n.tr("ui.settings.skipVersion"), null, () -> {
                change(I18n.tr("ui.settings.skipVersion"), u.version(), st -> st.updates.skippedVersion,
                    (st, v) -> st.updates.skippedVersion = v, u.version());
                result.getChildren().setAll(UiKit.label(I18n.tr("ui.settings.skipped", u.version()), "muted"));
            }));
            body.getChildren().add(actions);
            result.getChildren().add(UiKit.card(body));
        }, err -> result.getChildren().setAll(UiKit.banner("danger", Tasks.message(err),
            UiKit.button(I18n.tr("ui.state.retry"), Icons.REFRESH, () -> checkUpdates(result)))));
    }

    /** the update manifest's platform name for this computer */
    static String platform() {
        OsInfo os = OsInfo.current();
        String arch = os.arch() == OsInfo.Arch.ARM64 ? "arm64" : "x64";
        return switch (os.os()) {
            case WINDOWS -> "windows-" + arch;
            case MACOS -> "macos-" + arch;
            default -> "linux-" + arch;
        };
    }

    // ---- about ----

    private void about() {
        heading("about");
        content.getChildren().add(UiKit.card(
            row("version", I18n.tr("ui.settings.version"), null, UiKit.label(BuildInfo.VERSION, "mono")),
            UiKit.settingRow(I18n.tr("ui.settings.runtime"), null, UiKit.label(System.getProperty("java.vendor", "") + " "
                + Runtime.version() + "  ·  JavaFX " + System.getProperty("javafx.runtime.version", "?"), "mono", "small")),
            UiKit.settingRow(I18n.tr("ui.settings.system"), null, UiKit.label(System.getProperty("os.name", "") + " "
                + System.getProperty("os.version", "") + " (" + System.getProperty("os.arch", "") + ")", "mono", "small")),
            UiKit.settingRow(I18n.tr("ui.settings.dataFolder"), null, UiKit.label(ctx.services().paths().root().toString(),
                "mono", "small"))));
        content.getChildren().addAll(
            reg("license", UiKit.section(I18n.tr("ui.settings.license"), UiKit.label(I18n.tr("ui.settings.licenseText"), "muted"))),
            reg("thirdParty", UiKit.section(I18n.tr("ui.settings.thirdParty"), UiKit.label(I18n.tr("ui.settings.thirdPartyText"), "muted"))),
            UiKit.banner("info", I18n.tr("ui.settings.notAffiliated")));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.contact", BuildInfo.CONTACT), "faint", "small"));
    }
}
