package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Disclosure;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.modpack.UpdateAction;
import dev.shardlight.client.modpack.UpdateItem;
import dev.shardlight.client.modpack.UpdatePlan;
import dev.shardlight.client.project.Project;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Updating a project made from a modpack: pick the new .mrpack, look at exactly what would change, then apply it. The
 * preview names every file the update will touch and every one of the player's changes that stays.
 */
public final class ModpackUpdateFlow {
    /** most files listed per group; the rest are counted */
    private static final int LIST_LIMIT = 200;

    private ModpackUpdateFlow() {
    }

    /** the Settings button: pick a file, compare, preview */
    public static void start(AppContext ctx, Project p) {
        if (ctx.play().isRunning(p.id)) {
            ctx.overlay().message(I18n.tr("ui.update.cardTitle"), I18n.tr("ui.update.running"));
            return;
        }
        Path file = Dialogs.openFile(ctx.stage(), I18n.tr("ui.update.chooseTitle"), I18n.tr("ui.update.filter"), "*.mrpack");
        if (file != null) {
            compare(ctx, p, file);
        }
    }

    static void compare(AppContext ctx, Project p, Path file) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.update.comparing", file.getFileName()), true,
            (pr, c) -> ctx.services().modpackUpdater().plan(p, file, c),
            plan -> preview(ctx, p, file, plan));
    }

    /** the preview dialog; also used by the screenshots */
    public static Overlay.Dialog preview(AppContext ctx, Project p, Path file, UpdatePlan plan) {
        VBox body = new VBox(12);
        body.setPrefWidth(620);
        body.getChildren().add(header(plan));
        CheckBox sure = null;
        if (!plan.samePack()) {
            body.getChildren().add(UiKit.banner("warning", I18n.tr("ui.update.otherPack", plan.packName(), plan.fromName())));
            sure = new CheckBox(I18n.tr("ui.update.otherPackConfirm"));
            body.getChildren().add(sure);
        }
        if (plan.changesGame()) {
            body.getChildren().add(UiKit.banner("info", I18n.tr("ui.update.gameChanges", gameText(plan))));
        }
        HBox stats = new HBox(24,
            UiKit.stat(String.valueOf(plan.count(UpdateAction.UPDATE)), I18n.tr("ui.update.stat.update")),
            UiKit.stat(String.valueOf(plan.count(UpdateAction.ADD)), I18n.tr("ui.update.stat.add")),
            UiKit.stat(String.valueOf(plan.count(UpdateAction.REMOVE)), I18n.tr("ui.update.stat.remove")),
            UiKit.stat(String.valueOf(kept(plan)), I18n.tr("ui.update.stat.keep")));
        stats.getStyleClass().add("stat-row");
        body.getChildren().add(stats);

        VBox lists = new VBox(8);
        group(lists, "ui.update.section.update", plan, i -> i.action() == UpdateAction.UPDATE, true);
        group(lists, "ui.update.section.add", plan, i -> i.action() == UpdateAction.ADD, true);
        group(lists, "ui.update.section.remove", plan, i -> i.action() == UpdateAction.REMOVE, true);
        VBox keep = new VBox(8);
        group(keep, "ui.update.keep.added", plan, i -> i.action() == UpdateAction.KEEP_ADDED, false);
        group(keep, "ui.update.keep.edited", plan, i -> i.action() == UpdateAction.KEEP_EDITED, false);
        group(keep, "ui.update.keep.conflict", plan, i -> i.action() == UpdateAction.KEEP_CONFLICT, false);
        group(keep, "ui.update.keep.removed", plan, i -> i.action() == UpdateAction.KEEP_REMOVED, false);
        if (!keep.getChildren().isEmpty()) {
            lists.getChildren().add(new Disclosure(I18n.tr("ui.update.section.keep") + "  (" + kept(plan) + ")", false, keep));
        }
        if (lists.getChildren().isEmpty()) {
            lists.getChildren().add(UiKit.label(I18n.tr("ui.update.nothing"), "muted"));
        }
        ScrollPane scroll = new ScrollPane(lists);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        scroll.setPrefViewportHeight(250);
        scroll.setMaxHeight(300);
        body.getChildren().add(scroll);
        body.getChildren().add(UiKit.label(I18n.tr("ui.update.safety"), "faint", "small"));

        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button apply = UiKit.primary(I18n.tr("ui.update.apply"), Icons.UPDATE, () -> { });
        boolean nothing = !plan.changesFiles() && !plan.changesGame();
        if (nothing) {
            UiKit.disable(apply, I18n.tr("ui.update.nothing"));
        } else if (sure != null) {
            apply.setDisable(true);
            sure.selectedProperty().addListener((o, a, b) -> apply.setDisable(!b));
        }
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.update.title", plan.packName()), body, cancel, apply), true, true);
        cancel.setOnAction(e -> d.close());
        apply.setOnAction(e -> {
            d.close();
            apply(ctx, p, file, plan);
        });
        return d;
    }

    private static void apply(AppContext ctx, Project p, Path file, UpdatePlan plan) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.update.applying", plan.packName()), true,
            (pr, c) -> ctx.services().modpackUpdater().apply(p, file, pr, c),
            result -> {
                long kept = kept(result.plan());
                ctx.overlay().toast("success", kept > 0 ? I18n.tr("ui.update.doneKept", plan.packName(), kept)
                    : I18n.tr("ui.update.done", plan.packName()));
                for (String w : result.warnings()) {
                    ctx.overlay().toast("warning", w);
                }
                ctx.projectsChanged();
            });
    }

    private static Node header(UpdatePlan plan) {
        String versions = plan.fromVersion() == null || plan.toVersion() == null ? I18n.tr("ui.update.versionsUnknown")
            : I18n.tr("ui.update.versions", plan.fromVersion(), plan.toVersion());
        Label l = UiKit.label(versions, "h3");
        l.setWrapText(true);
        return l;
    }

    private static String gameText(UpdatePlan plan) {
        List<String> parts = new ArrayList<>();
        if (!java.util.Objects.equals(plan.fromMinecraft(), plan.toMinecraft())) {
            parts.add(I18n.tr("ui.update.game", plan.fromMinecraft(), plan.toMinecraft()));
        }
        if (!String.valueOf(plan.fromLoader()).equals(String.valueOf(plan.toLoader()))) {
            parts.add(I18n.tr("ui.update.loader", plan.fromLoader(), plan.toLoader()));
        }
        return String.join(", ", parts);
    }

    private static long kept(UpdatePlan plan) {
        return plan.items().stream().filter(i -> !i.action().writes()).count();
    }

    /** a collapsible group of paths; groups with nothing in them are left out */
    private static void group(VBox into, String titleKey, UpdatePlan plan, Predicate<UpdateItem> match, boolean openByDefault) {
        List<UpdateItem> items = plan.items().stream().filter(match).toList();
        if (items.isEmpty()) {
            return;
        }
        VBox paths = new VBox(2);
        int shown = 0;
        for (UpdateItem item : items) {
            if (shown++ >= LIST_LIMIT) {
                paths.getChildren().add(UiKit.label(I18n.tr("ui.update.more", items.size() - LIST_LIMIT), "faint", "small"));
                break;
            }
            Label name = UiKit.label(item.path(), "mono", "small");
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            HBox row = new HBox(8, name);
            row.setAlignment(Pos.CENTER_LEFT);
            if (item.disabled()) {
                row.getChildren().add(UiKit.chip(I18n.tr("ui.update.off"), "warning"));
            }
            paths.getChildren().add(row);
        }
        into.getChildren().add(new Disclosure(I18n.tr(titleKey) + "  (" + items.size() + ")", openByDefault && items.size() <= 12, paths));
    }
}
