package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.storage.DiskUsage;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/**
 * The Storage section of Settings: how much room each project and each shared folder takes, and a clean-up that offers
 * only the Minecraft versions and Java runtimes no project uses, after listing them and asking.
 */
public final class StorageSection {
    private final AppContext ctx;
    private final VBox usageBody = new VBox();
    private final VBox cleanupBody = new VBox();

    private StorageSection(AppContext ctx) {
        this.ctx = ctx;
        usageBody.getStyleClass().add("stack");
        cleanupBody.getStyleClass().add("stack-tight");
    }

    /** the disk-use card and the clean-up card; counting runs in the background */
    public static List<Node> build(AppContext ctx) {
        StorageSection s = new StorageSection(ctx);
        s.recount();
        return List.of(s.usageCard(), s.cleanupCard());
    }

    /** with the numbers already known (the screenshots) */
    public static List<Node> buildWith(AppContext ctx, DiskUsage.Report report) {
        StorageSection s = new StorageSection(ctx);
        s.show(report);
        return List.of(s.usageCard(), s.cleanupCard());
    }

    private Node usageCard() {
        Button recount = UiKit.iconButton(Icons.REFRESH, I18n.tr("ui.storage.recount"), this::recount);
        HBox head = new HBox(8, UiKit.label(I18n.tr("ui.storage.title"), "h3"), UiKit.spacer(), recount);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = UiKit.card(head, usageBody);
        card.getStyleClass().add("stack");
        return card;
    }

    private Node cleanupCard() {
        Button find = UiKit.button(I18n.tr("ui.storage.cleanupFind"), Icons.TRASH, this::findUnused);
        cleanupBody.getChildren().setAll(UiKit.settingRow(I18n.tr("ui.storage.cleanup"), I18n.tr("ui.storage.cleanupAbout"), find));
        return UiKit.card(cleanupBody);
    }

    private void recount() {
        usageBody.getChildren().setAll(UiKit.skeleton("line"), UiKit.skeleton("line"), UiKit.skeleton("short"));
        Fx.run(() -> ctx.services().diskUsage().report(null), this::show,
            err -> usageBody.getChildren().setAll(UiKit.errorState(I18n.tr("ui.storage.failed") + ": " + Tasks.message(err), this::recount)));
    }

    private void show(DiskUsage.Report r) {
        HBox stats = new HBox(32,
            UiKit.stat(UiKit.bytes(r.totalBytes()), I18n.tr("ui.storage.total")),
            UiKit.stat(r.freeBytes() < 0 ? I18n.tr("ui.storage.unknown") : UiKit.bytes(r.freeBytes()), I18n.tr("ui.storage.free")));
        stats.getStyleClass().add("stat-row");
        VBox projects = new VBox(10, UiKit.label(I18n.tr("ui.storage.projects"), "strong"));
        long max = Math.max(1, r.projects().stream().mapToLong(DiskUsage.ProjectUsage::totalBytes).max().orElse(1));
        if (r.projects().isEmpty()) {
            projects.getChildren().add(UiKit.label(I18n.tr("ui.storage.noProjects"), "muted"));
        }
        for (DiskUsage.ProjectUsage u : r.projects()) {
            projects.getChildren().add(projectRow(u, max));
        }
        VBox shared = new VBox(10, UiKit.label(I18n.tr("ui.storage.shared"), "strong"));
        long maxShared = Math.max(1, r.caches().stream().mapToLong(DiskUsage.CacheUsage::bytes).max().orElse(1));
        for (DiskUsage.CacheUsage c : r.caches()) {
            shared.getChildren().add(barRow(UiKit.label(I18n.tr("ui.storage.cache." + c.cache().name())), c.bytes(), maxShared, null));
        }
        usageBody.getChildren().setAll(stats, projects, shared);
    }

    private Node projectRow(DiskUsage.ProjectUsage u, long max) {
        VBox name = new VBox(2, UiKit.label(u.name(), "strong"));
        Button show = null;
        if (u.externalPath() != null) {
            Label outside = UiKit.label(I18n.tr("ui.storage.outside", u.externalPath()), "faint", "small", "mono");
            name.getChildren().add(outside);
            Path dir = Path.of(u.externalPath());
            show = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.storage.show"), () -> ctx.openFolder(dir));
        }
        return barRow(name, u.totalBytes(), max, show);
    }

    /** a label, a size and a thin bar showing the share of the largest row */
    private static Node barRow(Node label, long bytes, long max, Button action) {
        Label size = UiKit.label(UiKit.bytes(bytes), "tabular");
        HBox top = new HBox(8, label, UiKit.spacer(), size);
        if (action != null) {
            top.getChildren().add(action);
        }
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(label, Priority.NEVER);
        ProgressBar bar = UiKit.progress(max <= 0 ? 0 : (double) bytes / max);
        bar.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, top, bar);
    }

    // ---- clean-up ------------------------------------------------------------------------------------

    private void findUnused() {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.storage.cleanupFind"), false,
            (pr, c) -> ctx.services().diskUsage().planCleanup(), this::confirmCleanup);
    }

    /** list what would go, then ask; the red panel says what is lost */
    public Overlay.Dialog confirmCleanup(DiskUsage.CleanupPlan plan) {
        if (plan.items().isEmpty()) {
            ctx.overlay().message(I18n.tr("ui.storage.cleanup"), I18n.tr("ui.storage.cleanupNone"));
            return null;
        }
        VBox rows = new VBox(6);
        for (DiskUsage.CleanupItem item : plan.items()) {
            Label name = UiKit.label(item.name(), "mono", "small");
            Label kind = UiKit.label(I18n.tr("ui.storage.kind." + item.kind().name()), "faint", "small");
            Label size = UiKit.label(UiKit.bytes(item.bytes()), "tabular", "small");
            HBox row = new HBox(10, name, kind, UiKit.spacer(), size);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        Label intro = UiKit.label(I18n.tr("ui.storage.cleanupIntro"));
        intro.setWrapText(true);
        VBox body = new VBox(10, intro, rows);
        if (plan.runtimesKept()) {
            body.getChildren().add(UiKit.banner("info", I18n.tr("ui.storage.cleanupKept")));
        }
        body.getStyleClass().add("modal-lost");
        body.setPrefWidth(520);
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button ok = new Button(I18n.tr("ui.storage.cleanupRun", UiKit.bytes(plan.totalBytes())));
        ok.getStyleClass().add("danger");
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.storage.cleanupTitle"), body, cancel, ok), true, false);
        cancel.setOnAction(e -> d.close());
        ok.setOnAction(e -> {
            d.close();
            run(plan);
        });
        cancel.requestFocus();
        return d;
    }

    private void run(DiskUsage.CleanupPlan plan) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.storage.cleanupRunning"), true,
            (pr, c) -> ctx.services().diskUsage().cleanup(plan, pr, c), result -> {
                ctx.overlay().toast(result.failed().isEmpty() ? "success" : "warning",
                    result.failed().isEmpty() ? I18n.tr("ui.storage.cleanupDone", UiKit.bytes(result.freedBytes()))
                        : I18n.tr("ui.storage.cleanupFailed", UiKit.bytes(result.freedBytes()), result.failed().size()));
                recount();
            });
    }
}
