package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.modpack.MrpackExportOptions;
import dev.shardlight.client.project.Project;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/** Export a project as a Modrinth .mrpack or a CurseForge modpack zip. */
final class ModpackExportDialog {
    private ModpackExportDialog() {
    }

    static void show(AppContext ctx, Project p) {
        HBox format = UiKit.segmented(List.of(I18n.tr("ui.modpack.modrinth"), I18n.tr("ui.modpack.curseforge")), 0, i -> { });
        TextField name = new TextField(p.name);
        name.setAccessibleText(I18n.tr("ui.modpack.name"));
        TextField version = new TextField("1.0.0");
        version.setAccessibleText(I18n.tr("ui.modpack.version"));
        TextField summary = new TextField();
        summary.setAccessibleText(I18n.tr("ui.modpack.summary"));
        CheckBox config = new CheckBox(I18n.tr("ui.modpack.includeConfig"));
        config.setSelected(true);
        CheckBox packs = new CheckBox(I18n.tr("ui.modpack.includePacks"));
        packs.setSelected(true);
        CheckBox shaders = new CheckBox(I18n.tr("ui.modpack.includeShaders"));
        shaders.setSelected(true);
        CheckBox local = new CheckBox(I18n.tr("ui.modpack.includeLocal"));
        local.setWrapText(true);
        VBox body = new VBox(8, format,
            UiKit.label(I18n.tr("ui.modpack.name"), "small"), name,
            UiKit.label(I18n.tr("ui.modpack.version"), "small"), version,
            UiKit.label(I18n.tr("ui.modpack.summary"), "small"), summary,
            config, packs, shaders, local,
            UiKit.label(I18n.tr("ui.modpack.localHint"), "faint", "small"),
            UiKit.label(I18n.tr("ui.modpack.curseHint"), "faint", "small"));
        body.setPrefWidth(500);
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button export = UiKit.primary(I18n.tr("ui.modpack.export"), null, () -> { });
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.modpack.title", p.name), body, cancel, export), true, false);
        cancel.setOnAction(e -> d.close());
        export.setOnAction(e -> {
            boolean curse = format.getChildren().get(1) instanceof javafx.scene.control.ToggleButton t && t.isSelected();
            String n = name.getText().isBlank() ? p.name : name.getText().trim();
            String v = version.getText().isBlank() ? "1.0.0" : version.getText().trim();
            Path out = Dialogs.saveFile(ctx.stage(), I18n.tr("ui.modpack.title", p.name),
                n.replaceAll("[^A-Za-z0-9._-]+", "-") + "-" + v + (curse ? ".zip" : ".mrpack"),
                I18n.tr(curse ? "ui.modpack.curseforge" : "ui.modpack.modrinth"), curse ? "*.zip" : "*.mrpack");
            if (out == null) {
                return;
            }
            d.close();
            MrpackExportOptions opts = new MrpackExportOptions(n, v, summary.getText().trim(), config.isSelected(),
                packs.isSelected(), shaders.isSelected(), local.isSelected());
            Tasks.withProgress(ctx.overlay(), I18n.tr("ui.modpack.exporting"), false, (pr, c) -> curse
                ? ctx.services().modpacks().exportCurseForge(p, n, v, out)
                : ctx.services().modpacks().exportMrpack(p, opts, out), file -> {
                    ctx.overlay().toast("success", I18n.tr("ui.modpack.exported", file.getFileName()));
                    ctx.openFolder(file.getParent());
                });
        });
    }
}
