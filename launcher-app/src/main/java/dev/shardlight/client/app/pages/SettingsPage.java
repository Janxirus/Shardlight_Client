package dev.shardlight.client.app.pages;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.auth.MicrosoftClientIds;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.java.JavaInstall;
import dev.shardlight.client.settings.LauncherSettings;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.OsInfo;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Launcher-wide settings, in sections: accounts, appearance, Java, downloads, content sources, privacy, updates, storage, about. */
public final class SettingsPage implements Page {
    private static final Logger LOG = Logger.getLogger(SettingsPage.class.getName());
    private static final List<String> SECTIONS = List.of("accounts", "appearance", "java", "downloads", "content", "privacy",
        "updates", "storage", "about");
    /** languages with a full translation; "" follows the system */
    private static final List<String> LANGUAGES = List.of("", "en");
    private static final List<String> ACCENTS = List.of("#7C6CF2", "#4F8DF7", "#2BB3A3", "#4CAF50", "#E0A030", "#E8663D",
        "#E04F7A", "#B45CE0");

    private final AppContext ctx;
    private final HBox root = new HBox(0);
    private final VBox nav = new VBox(2);
    private final VBox content = new VBox(16);
    private String section = "accounts";
    private List<JavaInstall> detectedJava;
    private boolean scanningJava;

    public SettingsPage(AppContext ctx) {
        this.ctx = ctx;
        nav.getStyleClass().add("settings-nav");
        nav.setPadding(new Insets(4, 12, 4, 0));
        nav.setMinWidth(190);
        nav.setPrefWidth(190);
        content.setPadding(new Insets(4, 8, 24, 16));
        content.setMaxWidth(860);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS);
        root.getChildren().addAll(nav, scroll);
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

    /** show one section (used by other pages, e.g. "content" from Discover) */
    public void open(String id) {
        if (SECTIONS.contains(id)) {
            section = id;
            render();
        }
    }

    private void render() {
        nav.getChildren().clear();
        for (String id : SECTIONS) {
            Button b = new Button(I18n.tr("ui.settings.section." + id));
            b.setGraphic(Icons.of(icon(id), 16));
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.getStyleClass().addAll("flat", "settings-nav-button");
            if (id.equals(section)) {
                b.getStyleClass().add("selected");
            }
            b.setOnAction(e -> {
                section = id;
                render();
            });
            nav.getChildren().add(b);
        }
        content.getChildren().clear();
        LauncherSettings s = ctx.services().settings().get();
        if (ctx.services().settings().isReadOnly()) {
            content.getChildren().add(UiKit.banner("danger", I18n.tr("ui.settings.readOnly")));
        }
        switch (section) {
            case "accounts" -> accounts(s);
            case "appearance" -> appearance(s);
            case "java" -> java(s);
            case "downloads" -> downloads(s);
            case "content" -> contentSources(s);
            case "privacy" -> privacy();
            case "updates" -> updates(s);
            case "storage" -> storage();
            default -> about();
        }
    }

    private static String icon(String id) {
        return switch (id) {
            case "accounts" -> Icons.USER;
            case "appearance" -> Icons.PALETTE;
            case "java" -> Icons.CUBE;
            case "downloads" -> Icons.DOWNLOAD;
            case "content" -> Icons.DISCOVER;
            case "privacy" -> Icons.EYE_OFF;
            case "updates" -> Icons.UPDATE;
            case "storage" -> Icons.FOLDER;
            default -> Icons.INFO;
        };
    }

    private void heading(String id) {
        content.getChildren().add(new VBox(4, UiKit.label(I18n.tr("ui.settings.section." + id), "h2"),
            UiKit.label(I18n.tr("ui.settings.section." + id + ".about"), "muted")));
    }

    /** save one change; the UI is re-rendered when it fails so controls show the real value */
    private void save(Consumer<LauncherSettings> change) {
        try {
            ctx.services().settings().update(change);
        } catch (Exception e) {
            ctx.overlay().toast("danger", Tasks.message(e));
            render();
        }
    }

    private ToggleSwitch toggle(boolean value, String name, Consumer<Boolean> onChange) {
        ToggleSwitch t = new ToggleSwitch(value, name);
        t.selectedProperty().addListener((o, a, b) -> onChange.accept(b));
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
            rows.getChildren().add(UiKit.label(I18n.tr("ui.settings.noAccounts"), "muted"));
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

        // the Azure application id
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
            save(st -> st.auth.microsoftClientId = v);
            ctx.overlay().toast("success", I18n.tr("ui.settings.saved"));
            ctx.accountsChanged();
        });
        VBox clientId = new VBox(6, new HBox(8, id, saveId), error);
        content.getChildren().add(UiKit.section(I18n.tr("ui.settings.advanced"),
            UiKit.settingRow(I18n.tr("ui.settings.clientId"), I18n.tr("ui.settings.clientIdAbout"), clientId),
            UiKit.button(I18n.tr("ui.settings.signInHelp"), Icons.EXTERNAL,
                () -> ctx.openUrl("https://aka.ms/mce-reviewappid"))));
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }

    // ---- appearance ----

    private void appearance(LauncherSettings s) {
        heading("appearance");
        LauncherSettings.Appearance a = s.appearance;
        HBox theme = UiKit.segmented(List.of(I18n.tr("ui.settings.dark"), I18n.tr("ui.settings.light")),
            "light".equals(a.theme) ? 1 : 0, i -> save(st -> st.appearance.theme = i == 1 ? "light" : "dark"));

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
                save(st -> st.appearance.accent = hex);
                render();
            });
            swatches.getChildren().add(b);
        }
        ColorPicker picker = new ColorPicker(ThemeManager.parse(a.accent, Color.web(ACCENTS.get(0))));
        picker.setAccessibleText(I18n.tr("ui.settings.accentCustom"));
        picker.setOnAction(e -> {
            String hex = ThemeManager.web(picker.getValue());
            save(st -> st.appearance.accent = hex);
            render();
        });
        swatches.getChildren().add(picker);
        swatches.setAlignment(Pos.CENTER_LEFT);

        Slider scale = new Slider(0.8, 1.6, a.scale <= 0 ? 1 : a.scale);
        scale.setMajorTickUnit(0.1);
        scale.setMinorTickCount(0);
        scale.setSnapToTicks(true);
        scale.setPrefWidth(220);
        scale.setAccessibleText(I18n.tr("ui.settings.scale"));
        Label scaleValue = UiKit.label(Math.round(scale.getValue() * 100) + "%", "muted");
        scale.valueProperty().addListener((o, x, v) -> scaleValue.setText(Math.round(v.doubleValue() * 100) + "%"));
        scale.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) {
                double v = Math.round(scale.getValue() * 10) / 10.0;
                save(st -> st.appearance.scale = v);
            }
        });

        ComboBox<String> language = new ComboBox<>();
        language.getItems().setAll(LANGUAGES);
        language.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String tag) {
                return tag == null || tag.isEmpty() ? I18n.tr("ui.settings.languageSystem")
                    : Locale.forLanguageTag(tag).getDisplayName(Locale.forLanguageTag(tag));
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        language.setValue(LANGUAGES.contains(a.language) ? a.language : "");
        language.setAccessibleText(I18n.tr("ui.settings.language"));
        language.setOnAction(e -> {
            String tag = language.getValue() == null ? "" : language.getValue();
            save(st -> st.appearance.language = tag);
            ctx.overlay().toast("info", I18n.tr("ui.settings.languageRestart"));
        });

        content.getChildren().add(UiKit.card(
            UiKit.settingRow(I18n.tr("ui.settings.theme"), I18n.tr("ui.settings.themeAbout"), theme),
            UiKit.settingRow(I18n.tr("ui.settings.accent"), I18n.tr("ui.settings.accentAbout"), swatches),
            UiKit.settingRow(I18n.tr("ui.settings.highContrast"), I18n.tr("ui.settings.highContrastAbout"),
                toggle(a.highContrast, I18n.tr("ui.settings.highContrast"), v -> save(st -> st.appearance.highContrast = v))),
            UiKit.settingRow(I18n.tr("ui.settings.reducedMotion"), I18n.tr("ui.settings.reducedMotionAbout"),
                toggle(a.reducedMotion, I18n.tr("ui.settings.reducedMotion"), v -> save(st -> st.appearance.reducedMotion = v))),
            UiKit.settingRow(I18n.tr("ui.settings.effects"), I18n.tr("ui.settings.effectsAbout"),
                toggle(a.backgroundEffects, I18n.tr("ui.settings.effects"), v -> save(st -> st.appearance.backgroundEffects = v))),
            UiKit.settingRow(I18n.tr("ui.settings.compact"), I18n.tr("ui.settings.compactAbout"),
                toggle(a.compact, I18n.tr("ui.settings.compact"), v -> save(st -> st.appearance.compact = v))),
            UiKit.settingRow(I18n.tr("ui.settings.scale"), I18n.tr("ui.settings.scaleAbout"), new HBox(10, scale, scaleValue)),
            UiKit.settingRow(I18n.tr("ui.settings.language"), I18n.tr("ui.settings.languageAbout"), language)));
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
        mem.setPrefWidth(260);
        mem.setAccessibleText(I18n.tr("ui.settings.memory"));
        Label memValue = UiKit.label(memText(j.maxMemoryMb), "muted");
        mem.valueProperty().addListener((o, a, v) -> memValue.setText(memText((int) (Math.round(v.doubleValue() / 512) * 512))));
        mem.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) {
                int v = (int) (Math.round(mem.getValue() / 512) * 512);
                save(st -> st.java.maxMemoryMb = v);
            }
        });
        Label memHint = UiKit.label(total > 0 ? I18n.tr("ui.settings.memoryHint", memText((int) total)) : "", "faint", "small");

        content.getChildren().add(UiKit.card(
            UiKit.settingRow(I18n.tr("ui.settings.memory"), I18n.tr("ui.settings.memoryAbout"),
                new VBox(4, new HBox(10, mem, memValue), memHint)),
            UiKit.settingRow(I18n.tr("ui.settings.preferMojang"), I18n.tr("ui.settings.preferMojangAbout"),
                toggle(j.preferMojangRuntime, I18n.tr("ui.settings.preferMojang"),
                    v -> save(st -> st.java.preferMojangRuntime = v)))));

        VBox found = new VBox(6);
        Button rescan = UiKit.button(I18n.tr("ui.settings.javaScan"), Icons.REFRESH, () -> scanJava(true));
        content.getChildren().add(UiKit.section(I18n.tr("ui.settings.javaFound"), found, rescan));
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

    // ---- downloads ----

    private void downloads(LauncherSettings s) {
        heading("downloads");
        Spinner<Integer> concurrent = new Spinner<>(1, 16, Math.max(1, Math.min(16, s.downloads.maxConcurrent)));
        concurrent.setEditable(true);
        concurrent.setPrefWidth(100);
        concurrent.setAccessibleText(I18n.tr("ui.settings.concurrent"));
        concurrent.valueProperty().addListener((o, a, v) -> save(st -> st.downloads.maxConcurrent = v));
        Spinner<Integer> retries = new Spinner<>(0, 10, Math.max(0, Math.min(10, s.downloads.maxRetries)));
        retries.setEditable(true);
        retries.setPrefWidth(100);
        retries.setAccessibleText(I18n.tr("ui.settings.retries"));
        retries.valueProperty().addListener((o, a, v) -> save(st -> st.downloads.maxRetries = v));
        content.getChildren().add(UiKit.card(
            UiKit.settingRow(I18n.tr("ui.settings.concurrent"), I18n.tr("ui.settings.concurrentAbout"), concurrent),
            UiKit.settingRow(I18n.tr("ui.settings.retries"), I18n.tr("ui.settings.retriesAbout"), retries),
            UiKit.settingRow(I18n.tr("ui.settings.notify"), I18n.tr("ui.settings.notifyAbout"),
                toggle(s.downloads.notifyWhenDone, I18n.tr("ui.settings.notify"), v -> save(st -> st.downloads.notifyWhenDone = v)))));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.downloadsSafety"), "faint", "small"));
    }

    // ---- content sources ----

    private void contentSources(LauncherSettings s) {
        heading("content");
        boolean hasKey = ctx.services().hasCurseForgeKey();
        ToggleSwitch curseOn = toggle(s.catalog.curseForgeEnabled && hasKey, I18n.tr("ui.settings.curseEnabled"),
            v -> save(st -> st.catalog.curseForgeEnabled = v));
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
        source.setOnAction(e -> save(st -> st.catalog.defaultSource = source.getValue()));

        content.getChildren().add(UiKit.card(
            UiKit.settingRow("Modrinth", I18n.tr("ui.settings.modrinthAbout"), UiKit.chip(I18n.tr("ui.settings.alwaysOn"), "success")),
            UiKit.settingRow("CurseForge", I18n.tr("ui.settings.curseAbout"), curseOn),
            UiKit.settingRow(I18n.tr("ui.settings.curseKey"), I18n.tr("ui.settings.curseKeyAbout"), keyRow),
            UiKit.settingRow(I18n.tr("ui.settings.defaultSource"), I18n.tr("ui.settings.defaultSourceAbout"), source),
            UiKit.settingRow(I18n.tr("ui.settings.snapshots"), I18n.tr("ui.settings.snapshotsAbout"),
                toggle(s.catalog.showSnapshotVersions, I18n.tr("ui.settings.snapshots"),
                    v -> save(st -> st.catalog.showSnapshotVersions = v)))));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.contentLegal"), "faint", "small"));
    }

    // ---- privacy ----

    private void privacy() {
        heading("privacy");
        VBox list = new VBox(8);
        for (String k : List.of("signin", "game", "catalog", "servers", "updates", "images")) {
            HBox row = new HBox(10, UiKit.iconBox(Icons.of(Icons.INFO, 14), 24),
                UiKit.label(I18n.tr("ui.settings.privacy." + k)));
            row.setAlignment(Pos.TOP_LEFT);
            list.getChildren().add(row);
        }
        content.getChildren().addAll(UiKit.banner("success", I18n.tr("ui.settings.noTelemetry")),
            UiKit.section(I18n.tr("ui.settings.privacyConnections"), list),
            UiKit.label(I18n.tr("ui.settings.privacyLogs"), "faint", "small"));
    }

    // ---- updates ----

    private void updates(LauncherSettings s) {
        heading("updates");
        var updates = ctx.services().updates();
        content.getChildren().add(UiKit.settingRow(I18n.tr("ui.settings.version"), null,
            UiKit.label(BuildInfo.VERSION, "mono")));
        if (!updates.isConfigured()) {
            content.getChildren().add(UiKit.banner("info", I18n.tr("ui.settings.updatesNotConfigured")));
            return;
        }
        HBox channel = UiKit.segmented(List.of(I18n.tr("ui.settings.stable"), I18n.tr("ui.settings.beta")),
            "beta".equals(s.updates.channel) ? 1 : 0, i -> save(st -> st.updates.channel = i == 1 ? "beta" : "stable"));
        VBox result = new VBox(8);
        Button check = UiKit.primary(I18n.tr("ui.settings.checkNow"), Icons.REFRESH, () -> checkUpdates(result));
        content.getChildren().addAll(UiKit.card(
            UiKit.settingRow(I18n.tr("ui.settings.checkOnStart"), I18n.tr("ui.settings.checkOnStartAbout"),
                toggle(s.updates.checkOnStart, I18n.tr("ui.settings.checkOnStart"), v -> save(st -> st.updates.checkOnStart = v))),
            UiKit.settingRow(I18n.tr("ui.settings.channel"), I18n.tr("ui.settings.channelAbout"), channel)),
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
                save(st -> st.updates.skippedVersion = u.version());
                result.getChildren().setAll(UiKit.label(I18n.tr("ui.settings.skipped", u.version()), "muted"));
            }));
            body.getChildren().add(actions);
            result.getChildren().add(UiKit.card(body));
        }, err -> result.getChildren().setAll(UiKit.banner("danger", Tasks.message(err))));
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

    // ---- storage and diagnostics ----

    private void storage() {
        heading("storage");
        var paths = ctx.services().paths();
        content.getChildren().add(UiKit.card(
            folderRow(I18n.tr("ui.settings.dataFolder"), paths.root()),
            folderRow(I18n.tr("ui.settings.projectsFolder"), paths.projects()),
            folderRow(I18n.tr("ui.settings.backupsFolder"), paths.backups()),
            folderRow(I18n.tr("ui.settings.logsFolder"), paths.logs()),
            folderRow(I18n.tr("ui.settings.cacheFolder"), paths.cache())));
        if (ctx.services().settings().recoveredFrom() != null) {
            content.getChildren().add(UiKit.banner("warning", I18n.tr("ui.settings.recovered",
                ctx.services().settings().recoveredFrom().getFileName())));
        }
        content.getChildren().add(UiKit.section(I18n.tr("ui.settings.diagnostics"),
            UiKit.label(I18n.tr("ui.settings.diagnosticsAbout"), "muted"),
            new HBox(8, UiKit.button(I18n.tr("ui.settings.exportDiagnostics"), Icons.ARCHIVE,
                () -> PlayFlow.exportDiagnostics(ctx, null)))));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.dataFolderHow"), "faint", "small"));
    }

    private Node folderRow(String title, java.nio.file.Path dir) {
        Button open = UiKit.button(I18n.tr("ui.settings.open"), Icons.FOLDER, () -> ctx.openFolder(dir));
        return UiKit.settingRow(title, dir.toAbsolutePath().toString(), open);
    }

    // ---- about ----

    private void about() {
        heading("about");
        content.getChildren().add(UiKit.card(
            UiKit.settingRow(I18n.tr("ui.settings.version"), null, UiKit.label(BuildInfo.VERSION, "mono")),
            UiKit.settingRow(I18n.tr("ui.settings.runtime"), null, UiKit.label(System.getProperty("java.vendor", "") + " "
                + Runtime.version() + "  ·  JavaFX " + System.getProperty("javafx.runtime.version", "?"), "mono", "small")),
            UiKit.settingRow(I18n.tr("ui.settings.system"), null, UiKit.label(System.getProperty("os.name", "") + " "
                + System.getProperty("os.version", "") + " (" + System.getProperty("os.arch", "") + ")", "mono", "small")),
            UiKit.settingRow(I18n.tr("ui.settings.dataFolder"), null, UiKit.label(ctx.services().paths().root().toString(),
                "mono", "small"))));
        content.getChildren().addAll(
            UiKit.section(I18n.tr("ui.settings.license"), UiKit.label(I18n.tr("ui.settings.licenseText"), "muted")),
            UiKit.section(I18n.tr("ui.settings.thirdParty"), UiKit.label(I18n.tr("ui.settings.thirdPartyText"), "muted")),
            UiKit.banner("info", I18n.tr("ui.settings.notAffiliated")));
        content.getChildren().add(UiKit.label(I18n.tr("ui.settings.contact", BuildInfo.CONTACT), "faint", "small"));
    }
}
