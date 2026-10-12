package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.ProjectIcons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.Project;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/** The dashboard: the selected project with a big Play button, alerts, recent projects and shortcuts. */
public final class HomePage implements Page {
    private final AppContext ctx;
    private final VBox root = new VBox(18);

    public HomePage(AppContext ctx) {
        this.ctx = ctx;
        ctx.onProjectsChanged(this::refreshIfShown);
        ctx.onAccountsChanged(this::refreshIfShown);
        ctx.play().onStateChanged(this::refreshIfShown);
    }

    @Override
    public String title() {
        return I18n.tr("ui.nav.home");
    }

    @Override
    public Region view() {
        return root;
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
        root.getChildren().addAll(alerts());
        if (selected.isEmpty()) {
            root.getChildren().add(welcome());
            return;
        }
        Project p = selected.get();
        root.getChildren().add(hero(p, projects));
        if (projects.size() > 1) {
            root.getChildren().add(recent(projects, p));
        }
        root.getChildren().add(shortcuts(p));
    }

    private List<Node> alerts() {
        var accounts = ctx.services().accounts();
        java.util.ArrayList<Node> out = new java.util.ArrayList<>();
        if (accounts.selected().isEmpty()) {
            if (!accounts.isSignInConfigured()) {
                out.add(UiKit.banner("warning", I18n.tr("ui.home.signInNotConfigured"),
                    UiKit.button(I18n.tr("ui.home.howToSetUp"), null, () -> SignInDialog.show(ctx, null))));
            } else {
                out.add(UiKit.banner("info", I18n.tr("ui.home.signInToPlay"),
                    UiKit.primary(I18n.tr("ui.account.signIn"), Icons.USER, () -> SignInDialog.show(ctx, null))));
            }
        } else if (!accounts.remembersSignIn()) {
            out.add(UiKit.banner("warning", I18n.tr("ui.signin.notRemembered")));
        }
        if (ctx.services().settings().recoveredFrom() != null) {
            out.add(UiKit.banner("warning", I18n.tr("ui.home.settingsRecovered",
                ctx.services().settings().recoveredFrom().getFileName())));
        }
        for (String problem : ctx.services().projects().problems()) {
            out.add(UiKit.banner("warning", problem));
        }
        return out;
    }

    private Node welcome() {
        VBox v = new VBox(14);
        v.getStyleClass().addAll("card", "hero");
        v.setPadding(new Insets(28));
        Label t = UiKit.label(I18n.tr("ui.home.welcomeTitle"), "h1");
        Label s = UiKit.label(I18n.tr("ui.home.welcomeBody"), "muted");
        s.setMaxWidth(640);
        FlowPane quick = new FlowPane(10, 10);
        quick.getChildren().addAll(
            UiKit.primary(I18n.tr("ui.home.createProject"), Icons.PLUS, () -> ctx.newProject(null)),
            UiKit.button(I18n.tr("ui.template.vanilla"), Icons.CUBE, () -> ctx.newProject("vanilla")),
            UiKit.button(I18n.tr("ui.template.balanced"), Icons.GAUGE, () -> ctx.newProject("balanced")),
            UiKit.button(I18n.tr("ui.template.ultraPerformance"), Icons.BOLT, () -> ctx.newProject("ultra-performance")),
            UiKit.button(I18n.tr("ui.template.shardlightDefault"), Icons.SHARD, () -> ctx.newProject("shardlight-default")));
        v.getChildren().addAll(UiKit.label(I18n.tr("ui.home.eyebrow"), "eyebrow"), t, s, quick,
            UiKit.label(I18n.tr("ui.home.shardlightOptional"), "faint", "small"));
        return v;
    }

    private Node hero(Project p, List<Project> projects) {
        Node icon = ProjectIcons.tile(p.icon, ctx.services().projects().projectDir(p.id), 76);
        Label name = UiKit.label(p.name, "h1");
        FlowPane chips = new FlowPane(8, 8,
            UiKit.chip(I18n.tr("ui.project.minecraft", p.minecraftVersion), null, Icons.CUBE),
            UiKit.chip(ProjectText.loader(p), null, Icons.PACKAGE),
            UiKit.chip(ProjectText.java(p), null, Icons.GEAR),
            UiKit.chip(ProjectText.memory(p), null, Icons.GAUGE),
            UiKit.chip(I18n.tr("ui.home.presetChip", ProjectText.preset(p)),
                p.preset != null && p.preset.active == Preset.ULTRA_PERFORMANCE ? "warning" : "accent", Icons.BOLT));
        Label content = UiKit.label(ProjectText.contentSummary(p), "muted");
        Label shard = UiKit.label(I18n.tr("ui.home.shardlightLine", ProjectText.modules(p)), "muted");
        Label played = UiKit.label(ProjectText.lastPlayed(p) + "  ·  "
            + I18n.tr("ui.project.playTime", UiKit.duration(p.totalPlayMillis)), "faint", "small");
        VBox info = new VBox(8, name, chips, content, shard, played);
        HBox.setHgrow(info, Priority.ALWAYS);

        boolean running = ctx.play().isRunning(p.id);
        Button play = new Button(I18n.tr(running ? "ui.play.running" : "ui.play.play"));
        play.setGraphic(Icons.of(running ? Icons.STOP : Icons.PLAY, 24));
        play.getStyleClass().add("play-button");
        if (running) {
            play.getStyleClass().add("running");
            dev.shardlight.client.app.ui.UiKit.tooltip(play, I18n.tr("ui.play.stopTip"));
        }
        play.setDefaultButton(true);
        play.setMaxWidth(Double.MAX_VALUE);
        play.setAccessibleText(I18n.tr(running ? "ui.play.stopAccessible" : "ui.play.playAccessible", p.name));
        play.setOnAction(e -> ctx.play().play(p));

        ComboBox<Project> picker = new ComboBox<>();
        picker.getItems().setAll(projects);
        picker.setValue(projects.stream().filter(x -> x.id.equals(p.id)).findFirst().orElse(null));
        picker.setCellFactory(l -> ProjectText.cell(null));
        picker.setButtonCell(ProjectText.cell(null));
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setAccessibleText(I18n.tr("ui.home.changeProject"));
        picker.setOnAction(e -> {
            Project chosen = picker.getValue();
            if (chosen != null && !chosen.id.equals(p.id)) {
                ctx.selectProject(chosen.id);
            }
        });
        Button details = UiKit.button(I18n.tr("ui.home.openProject"), Icons.LIBRARY, () -> ctx.openProject(p.id));
        details.setMaxWidth(Double.MAX_VALUE);
        VBox right = new VBox(10, play, picker, details);
        right.setMinWidth(250);
        right.setMaxWidth(280);
        right.setAlignment(Pos.TOP_CENTER);

        HBox top = new HBox(20, icon, info, right);
        top.setAlignment(Pos.TOP_LEFT);
        VBox hero = new VBox(top);
        hero.getStyleClass().addAll("card", "hero");
        hero.setPadding(new Insets(24));
        return hero;
    }


    private Node recent(List<Project> projects, Project selected) {
        FlowPane row = new FlowPane(12, 12);
        projects.stream().filter(p -> !p.id.equals(selected.id)).limit(4).forEach(p -> {
            VBox tile = new VBox(8,
                new HBox(10, ProjectIcons.tile(p.icon, ctx.services().projects().projectDir(p.id), 40),
                    new VBox(2, UiKit.label(p.name, "project-name"),
                        UiKit.label(p.minecraftVersion + "  ·  " + ProjectText.loader(p), "muted", "small"))),
                UiKit.label(ProjectText.lastPlayed(p), "faint", "small"));
            tile.getStyleClass().add("project-tile");
            tile.setFocusTraversable(true);
            tile.setAccessibleText(I18n.tr("ui.home.selectProject", p.name));
            tile.setOnMouseClicked(e -> {
                if (e.getClickCount() >= 2) {
                    ctx.play().play(p);
                } else {
                    ctx.selectProject(p.id);
                }
            });
            tile.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    ctx.selectProject(p.id);
                }
            });
            UiKit.tooltip(tile, I18n.tr("ui.home.tileTip"));
            row.getChildren().add(tile);
        });
        return UiKit.section(I18n.tr("ui.home.recent"), row);
    }

    private Node shortcuts(Project p) {
        FlowPane row = new FlowPane(10, 10,
            UiKit.button(I18n.tr("ui.home.newProject"), Icons.PLUS, () -> ctx.newProject(null)),
            UiKit.button(I18n.tr("ui.home.findMods"), Icons.DISCOVER, () -> ctx.shell().navigate("discover")),
            UiKit.button(I18n.tr("ui.home.presets"), Icons.BOLT, () -> ctx.shell().navigate("shardlight")),
            UiKit.button(I18n.tr("ui.home.openFolder"), Icons.FOLDER,
                () -> ctx.openFolder(ctx.services().projects().gameDir(p))));
        return UiKit.section(I18n.tr("ui.home.shortcuts"), row);
    }
}
