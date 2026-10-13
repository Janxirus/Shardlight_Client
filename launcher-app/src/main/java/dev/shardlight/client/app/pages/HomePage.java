package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.app.ui.Art;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.PlayButton;
import dev.shardlight.client.app.ui.ProjectArt;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.Severity;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.Project;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Home: the selected project as a hero with its Play button, a row of recent projects to jump back into, and an
 * alerts strip that only appears when something needs attention. Last played and play time come from real launch
 * records only. There is no news card: nothing to show until a signed update manifest is configured.
 */
public final class HomePage implements Page {
    private static final long LOW_DISK_BYTES = 5L << 30;
    private static final int JUMP_BACK_SHOWN = 6;

    /** one thing that needs attention, with the button that fixes it */
    record Alert(String kind, String text, String fixLabel, Runnable fix) {
    }

    private final AppContext ctx;
    private final VBox root = new VBox();
    private final StackPane view = new StackPane(root);

    public HomePage(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().addAll("home", "page-column");
        StackPane.setAlignment(root, Pos.TOP_CENTER);
        view.getChildren().add(0, new Backdrop());
        ctx.onProjectsChanged(this::refreshIfShown);
        ctx.onAccountsChanged(this::refreshIfShown);
        ctx.play().onStateChanged(this::refreshIfShown);
    }

    /**
     * Soft facets behind the top of the page. The picture is transparent, so it follows the dark and light window; it
     * is as wide as the page, keeps its shape, and takes no room of its own.
     */
    private static final class Backdrop extends Region {
        private final ImageView picture = new ImageView(Art.hero());
        private final Region fade = new Region();

        Backdrop() {
            picture.setPreserveRatio(true);
            picture.setSmooth(true);
            fade.getStyleClass().add("home-backdrop-fade");
            getStyleClass().add("home-backdrop");
            getChildren().addAll(picture, fade);
            setMouseTransparent(true);
            setFocusTraversable(false);
        }

        @Override
        protected double computePrefWidth(double height) {
            return 0;
        }

        @Override
        protected double computePrefHeight(double width) {
            return 0;
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            picture.setFitWidth(w);
            double h = picture.getLayoutBounds().getHeight();
            picture.relocate(0, 0);
            fade.resizeRelocate(0, 0, w, h);
        }
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.home");
    }

    @Override
    public Region view() {
        return view;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refreshIfShown() {
        if (ctx.shell().current() == this) {
            refresh();
        }
    }

    private void refresh() {
        root.getChildren().clear();
        List<Project> projects = ctx.services().projects().list();
        Optional<Project> selected = ctx.selectedProject();
        VBox strip = new VBox();
        strip.getStyleClass().add("alert-strip");
        fillAlerts(strip, selected.orElse(null));
        root.getChildren().add(strip);
        if (selected.isEmpty()) {
            root.getChildren().add(welcome());
            return;
        }
        Project p = selected.get();
        root.getChildren().add(hero(p));
        if (projects.size() > 1) {
            root.getChildren().add(jumpBack(projects, p));
        }
    }

    // ------------------------------------------------------------------ alerts

    /** everything that needs attention now (the slow checks join the strip when they finish) */
    private void fillAlerts(VBox strip, Project selected) {
        List<Alert> now = new ArrayList<>(quickAlerts(selected));
        show(strip, now);
        if (selected != null) {
            Fx.run(() -> ctx.services().content().warnings(selected), warnings -> {
                long errors = warnings.stream().filter(w -> w.severity() == Severity.ERROR).count();
                if (errors > 0) {
                    List<Alert> all = new ArrayList<>(now);
                    all.add(new Alert("danger", I18n.tr("ui.home.alert.brokenMods", selected.name, errors),
                        I18n.tr("ui.home.alert.fixMods"), () -> ctx.openProjectTab(selected.id, "content")));
                    show(strip, all);
                }
            }, err -> { });
        }
    }

    private static void show(VBox strip, List<Alert> alerts) {
        strip.getChildren().clear();
        for (Alert a : alerts.stream().limit(4).toList()) {
            Button fix = UiKit.button(a.fixLabel(), null, a.fix());
            strip.getChildren().add(UiKit.banner(a.kind(), a.text(), fix));
        }
    }

    private List<Alert> quickAlerts(Project selected) {
        List<Alert> out = new ArrayList<>();
        var accounts = ctx.services().accounts();
        if (accounts.selected().isEmpty()) {
            if (!accounts.isSignInConfigured()) {
                out.add(new Alert("warning", I18n.tr("ui.home.signInNotConfigured"), I18n.tr("ui.home.howToSetUp"),
                    () -> SignInDialog.show(ctx, null)));
            } else {
                out.add(new Alert("info", I18n.tr("ui.home.signInToPlay"), I18n.tr("ui.account.signIn"),
                    () -> SignInDialog.show(ctx, null)));
            }
        } else if (!accounts.remembersSignIn()) {
            out.add(new Alert("warning", I18n.tr("ui.signin.notRemembered"), I18n.tr("ui.home.alert.details"),
                () -> ctx.openSettings("accounts")));
        }
        ctx.play().availableUpdate().ifPresent(v -> out.add(new Alert("info", I18n.tr("ui.update.available", v),
            I18n.tr("ui.home.alert.update"), () -> ctx.openSettings("updates"))));
        if (selected != null && selected.java != null && selected.java.javaPath != null && !selected.java.javaPath.isBlank()
            && !Files.isRegularFile(java.nio.file.Path.of(selected.java.javaPath))) {
            out.add(new Alert("danger", I18n.tr("ui.home.alert.javaMissing", selected.name, selected.java.javaPath),
                I18n.tr("ui.home.alert.chooseJava"), () -> ctx.openProjectTab(selected.id, "settings")));
        }
        long free = freeBytes();
        if (free >= 0 && free < LOW_DISK_BYTES) {
            out.add(new Alert("warning", I18n.tr("ui.home.alert.lowDisk", UiKit.bytes(free)),
                I18n.tr("ui.home.alert.manageStorage"), () -> ctx.openSettings("storage")));
        }
        if (ctx.services().settings().recoveredFrom() != null) {
            out.add(new Alert("warning", I18n.tr("ui.home.settingsRecovered",
                ctx.services().settings().recoveredFrom().getFileName()), I18n.tr("ui.home.alert.details"),
                () -> ctx.openSettings("storage")));
        }
        for (String problem : ctx.services().projects().problems()) {
            out.add(new Alert("warning", problem, I18n.tr("ui.home.alert.openLibrary"), () -> ctx.shell().navigate("library")));
        }
        return out;
    }

    /** free space where the launcher keeps its data, or -1 when it can't be read */
    private long freeBytes() {
        try {
            return Files.getFileStore(ctx.services().paths().root()).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ welcome

    private Node welcome() {
        VBox v = new VBox();
        v.getStyleClass().addAll("card", "welcome");
        FlowPane quick = new FlowPane(
            UiKit.primary(I18n.tr("ui.home.createProject"), Icons.PLUS, () -> ctx.newProject(null)),
            UiKit.button(I18n.tr("ui.template.vanilla"), Icons.CUBE, () -> ctx.newProject("vanilla")),
            UiKit.button(I18n.tr("ui.template.balanced"), Icons.GAUGE, () -> ctx.newProject("balanced")),
            UiKit.button(I18n.tr("ui.template.ultraPerformance"), Icons.BOLT, () -> ctx.newProject("ultra-performance")),
            UiKit.button(I18n.tr("ui.template.shardlightDefault"), Icons.SHARD, () -> ctx.newProject("shardlight-default")));
        quick.getStyleClass().add("button-row");
        v.getChildren().addAll(UiKit.label(I18n.tr("ui.home.eyebrow"), "eyebrow"),
            UiKit.label(I18n.tr("ui.home.welcomeTitle"), "h1"), UiKit.label(I18n.tr("ui.home.welcomeBody"), "muted"), quick,
            UiKit.label(I18n.tr("ui.home.shardlightOptional"), "faint", "small"));
        return v;
    }

    // ------------------------------------------------------------------ hero

    private Node hero(Project p) {
        var dir = ctx.services().projects().projectDir(p.id);
        ProjectArt art = ProjectArt.of(p, dir, 0, ThemeManager.em(15.7), 1);
        Region scrim = new Region();
        scrim.getStyleClass().add("hero-scrim");
        scrim.setMouseTransparent(true);

        Label eyebrow = UiKit.label(I18n.tr("ui.home.selected"), "caption", "on-art");
        Label name = UiKit.label(p.name, "display", "on-art");
        name.setWrapText(false);
        FlowPane chips = new FlowPane(
            artChip(I18n.tr("ui.project.minecraft", p.minecraftVersion), Icons.CUBE),
            artChip(ProjectText.loader(p), Icons.PACKAGE),
            artChip(ProjectText.preset(p), p.preset != null && p.preset.active == Preset.ULTRA_PERFORMANCE ? Icons.BOLT : Icons.GAUGE),
            UiKit.chipCapped(p.hasShardlight() ? ProjectText.modules(p) : I18n.tr("ui.home.noModules"), "on-art", Icons.SHARD, 24));
        chips.getStyleClass().add("chip-row");
        // real launch records only: nothing is shown for a project that was never played
        Label played = UiKit.label(p.lastPlayedAt > 0 ? ProjectText.lastPlayed(p) + "   ·   "
            + I18n.tr("ui.project.playTime", UiKit.duration(p.totalPlayMillis)) : I18n.tr("ui.project.neverPlayed"),
            "small", "on-art");
        VBox info = new VBox(eyebrow, name, chips, played);
        info.getStyleClass().add("hero-info");

        Button play = PlayButton.of(ctx, p, "large");
        Button open = UiKit.button(I18n.tr("ui.home.openProject"), Icons.LIBRARY, () -> ctx.openProject(p.id), "on-art");
        VBox actions = new VBox(play, open);
        actions.getStyleClass().add("hero-actions");

        HBox content = new HBox(info, UiKit.spacer(), actions);
        content.getStyleClass().add("hero-content");
        StackPane hero = new StackPane(art, scrim, content);
        hero.getStyleClass().add("hero-panel");
        StackPane.setAlignment(content, Pos.BOTTOM_LEFT);
        hero.setAccessibleText(p.name);
        return hero;
    }

    private static Label artChip(String text, String icon) {
        return UiKit.chip(text, "on-art", icon);
    }

    // ------------------------------------------------------------------ jump back in

    private Node jumpBack(List<Project> projects, Project selected) {
        List<String> recent = ctx.services().settings().get().recentProjects;
        Comparator<Project> order = Comparator.comparingInt((Project p) -> {
            int i = recent.indexOf(p.id);
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(Comparator.comparingLong((Project p) -> p.lastPlayedAt).reversed());
        FlowPane row = new FlowPane();
        row.getStyleClass().add("jump-row");
        projects.stream().sorted(order).limit(JUMP_BACK_SHOWN).forEach(p -> row.getChildren().add(jumpCard(p, p.id.equals(selected.id))));
        return UiKit.section(I18n.tr("ui.home.recent"), row);
    }

    private Node jumpCard(Project p, boolean selected) {
        var dir = ctx.services().projects().projectDir(p.id);
        ProjectArt art = ProjectArt.of(p, dir, 0, ThemeManager.em(7.1), 0.714);
        Node icon = ProjectIcons.tile(p.icon, dir, ThemeManager.em(2.571));
        StackPane top = new StackPane(art, icon);
        top.getStyleClass().add("jump-art");
        StackPane.setAlignment(icon, Pos.BOTTOM_LEFT);
        StackPane.setMargin(icon, new Insets(0, 0, ThemeManager.em(0.571), ThemeManager.em(0.714)));
        Label name = UiKit.label(p.name, "project-name");
        name.setWrapText(false);
        Label meta = UiKit.label(p.minecraftVersion + "  ·  " + ProjectText.loader(p), "muted", "small");
        meta.setWrapText(false);
        Label played = UiKit.label(ProjectText.lastPlayed(p), "faint", "small");
        played.setWrapText(false);
        VBox text = new VBox(name, meta, played);
        text.getStyleClass().add("card-body");
        VBox card = new VBox(top, text);
        card.getStyleClass().addAll("card", "jump-card");
        if (selected) {
            card.getStyleClass().add("selected");
        }
        dev.shardlight.client.app.ui.Motion.lift(card);
        card.getStyleClass().add("interactive");
        card.setFocusTraversable(true);
        card.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (e.getClickCount() >= 2) {
                ctx.play().play(p);
            } else {
                ctx.selectProject(p.id);
            }
        });
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                ctx.selectProject(p.id);
                e.consume();
            }
        });
        card.setAccessibleText(I18n.tr("ui.home.selectProject", p.name));
        UiKit.tooltip(card, I18n.tr("ui.home.tileTip"));
        return card;
    }
}
