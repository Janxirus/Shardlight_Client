package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.app.flows.ProjectActions;
import dev.shardlight.client.app.ui.Cards;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Fields;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.Severity;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.BackupInfo;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.shardlight.ShardlightModule;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The first tab of a project: how big it is, how many mods are on, when it was last backed up, anything wrong with
 * its content, and a switch for each Shardlight module. Disk size and the last backup are read in the background
 * and fill in when they are known.
 */
final class OverviewTab {
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox();

    OverviewTab(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
        root.getStyleClass().add("stack");
    }

    VBox view() {
        return root;
    }

    void refresh() {
        root.getChildren().clear();
        Optional<Project> found = ctx.services().projects().get(projectId);
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        long modsOn = ProjectText.count(p, ContentKind.MOD, true);
        long mods = ProjectText.count(p, ContentKind.MOD, false);
        HBox disk = Cards.stat(Icons.ARCHIVE, "…", I18n.tr("ui.overview.disk"));
        HBox backup = Cards.stat(Icons.CLOCK, "…", I18n.tr("ui.overview.lastBackup"));
        FlowPane stats = new FlowPane(disk, Cards.stat(Icons.PACKAGE, I18n.tr("ui.overview.modsOf", modsOn, mods),
            I18n.tr("ui.overview.mods")), backup,
            Cards.stat(Icons.CLOCK, p.lastPlayedAt > 0 ? UiKit.duration(p.totalPlayMillis) : I18n.tr("ui.project.never"),
                I18n.tr("ui.overview.playTime")));
        stats.getStyleClass().add("stat-row");
        root.getChildren().add(stats);
        Fx.run(() -> diskSize(ctx.services().projects().projectDir(p.id)), bytes -> setValue(disk, UiKit.bytes(bytes)),
            err -> setValue(disk, I18n.tr("common.unknown")));
        Fx.run(() -> ctx.services().backups().list(p.id), list -> setValue(backup, list.stream()
            .filter(b -> !b.isPreRestore()).map(BackupInfo::createdAt).max(Comparator.naturalOrder())
            .map(ProjectText::ago).orElse(I18n.tr("ui.overview.noBackup"))), err -> setValue(backup, I18n.tr("common.unknown")));

        VBox warnings = new VBox();
        warnings.getStyleClass().add("stack-tight");
        root.getChildren().add(warnings);
        Fx.run(() -> ctx.services().content().warnings(p), list -> {
            for (var w : list) {
                warnings.getChildren().add(UiKit.banner(w.severity() == Severity.ERROR ? "danger" : "warning", w.message()));
            }
        }, err -> { });

        root.getChildren().add(modules(p));

        TextArea notes = Fields.tabLeaves(new TextArea(p.notes == null ? "" : p.notes));
        notes.setPrefRowCount(3);
        notes.setWrapText(true);
        notes.setPromptText(I18n.tr("ui.projectPage.notesPrompt"));
        notes.setAccessibleText(I18n.tr("ui.projectPage.notes"));
        notes.focusedProperty().addListener((o, a, b) -> {
            if (!b && !notes.getText().equals(p.notes)) {
                String text = notes.getText();
                Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().update(p.id, x -> x.notes = text), x -> { });
            }
        });
        root.getChildren().add(UiKit.section(I18n.tr("ui.projectPage.notes"), notes));

        FlowPane quick = new FlowPane(
            UiKit.button(I18n.tr("ui.home.findMods"), Icons.DISCOVER, () -> {
                ctx.selectProject(p.id);
                ctx.shell().navigate("discover");
            }),
            UiKit.button(I18n.tr("ui.backup.now"), Icons.ARCHIVE, () -> ProjectActions.backupNow(ctx, p)),
            UiKit.button(I18n.tr("ui.projectPage.openMods"), Icons.FOLDER,
                () -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve("mods"))),
            UiKit.button(I18n.tr("ui.diagnostics.export"), Icons.LOGS, () -> PlayFlow.exportDiagnostics(ctx, p)));
        quick.getStyleClass().add("button-row");
        root.getChildren().add(UiKit.section(I18n.tr("ui.home.shortcuts"), quick));
    }

    private static void setValue(HBox stat, String value) {
        ((Label) ((VBox) stat.getChildren().get(1)).getChildren().get(0)).setText(value);
    }

    private static long diskSize(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    // ------------------------------------------------------------------ Shardlight modules

    private Node modules(Project p) {
        VBox card = new VBox();
        card.getStyleClass().addAll("card", "modules-card");
        card.getChildren().addAll(UiKit.label(I18n.tr("ui.overview.modules"), "h3"),
            UiKit.label(I18n.tr("ui.overview.modulesHint"), "muted", "small"));
        for (ShardlightModule m : ctx.services().shardlightCatalog().modules()) {
            ToggleSwitch on = new ToggleSwitch(p.shardlightModules.contains(m.id()), I18n.tr(m.nameKey()));
            on.selectedProperty().addListener((o, was, now) -> toggle(p, m, now, on));
            card.getChildren().add(UiKit.settingRow(I18n.tr(m.nameKey()), I18n.tr(m.descriptionKey()), on));
        }
        return card;
    }

    /** add or remove one module the way the Shardlight page does: check, then download and place it */
    private void toggle(Project p, ShardlightModule m, boolean now, ToggleSwitch on) {
        Set<String> chosen = new LinkedHashSet<>(p.shardlightModules);
        if (now) {
            chosen.add(m.id());
        } else {
            chosen.remove(m.id());
        }
        if (ctx.play().isRunning(p.id)) {
            ctx.overlay().message(I18n.tr("ui.shardlight.apply"), I18n.tr("ui.project.closeGameFirst"));
            refresh();
            return;
        }
        try {
            var report = ctx.services().shardlight().check(p, Set.copyOf(chosen));
            if (!report.canApply()) {
                ctx.overlay().toast("warning", report.errors().isEmpty() ? I18n.tr("ui.overview.moduleBlocked")
                    : report.errors().get(0).text());
                refresh();
                return;
            }
        } catch (Exception e) {
            ctx.overlay().toast("danger", Tasks.message(e));
            refresh();
            return;
        }
        String theme = chosen.contains("ui") ? p.shardlightTheme : null;
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.shardlight.applying", p.name), true,
            (pr, c) -> ctx.services().setup().applyShardlight(p, Set.copyOf(chosen), theme, pr, c), outcome -> {
                ctx.overlay().toast("success", I18n.tr(now ? "ui.overview.moduleOn" : "ui.overview.moduleOff", I18n.tr(m.nameKey())));
                for (String w : outcome.warnings()) {
                    ctx.overlay().toast("warning", w);
                }
                ctx.projectsChanged();
                refresh();
            }, err -> {
                if (!Tasks.isCancelled(err)) {
                    ctx.overlay().toast("danger", Tasks.message(err));
                }
                refresh();
            });
    }
}
