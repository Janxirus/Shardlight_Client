package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.modpack.ModpackInfo;
import dev.shardlight.client.project.ArchivedProject;
import dev.shardlight.client.project.BackupScope;
import dev.shardlight.client.project.CloneOptions;
import dev.shardlight.client.project.ExportOptions;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.RemovalPlan;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Project actions shared by the Library and the project page; every destructive one asks first. */
public final class ProjectActions {
    private ProjectActions() {
    }

    /** null when the name is fine, else why not */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return I18n.tr("ui.project.nameEmpty");
        }
        if (name.length() > 64) {
            return I18n.tr("ui.project.nameTooLong");
        }
        if (!name.codePoints().anyMatch(Character::isLetterOrDigit)) {
            return I18n.tr("ui.project.nameNoLetters");
        }
        return null;
    }

    public static void rename(AppContext ctx, Project p) {
        Dialogs.text(ctx.overlay(), I18n.tr("ui.project.renameTitle"), I18n.tr("ui.project.nameLabel"), p.name,
            I18n.tr("ui.project.rename"), ProjectActions::validateName,
            name -> Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().rename(p.id, name),
                r -> ctx.overlay().toast("success", I18n.tr("ui.project.renamed", r.name))));
    }

    public static void duplicate(AppContext ctx, Project p) {
        Dialogs.options(ctx.overlay(), I18n.tr("ui.project.duplicateTitle", p.name), I18n.tr("ui.project.duplicateIntro"),
            List.of(new Dialogs.Option(I18n.tr("ui.project.copyWorlds"), I18n.tr("ui.project.copyWorldsHint"), true),
                new Dialogs.Option(I18n.tr("ui.project.copyScreenshots"), null, false),
                new Dialogs.Option(I18n.tr("ui.project.copyLogs"), null, false)),
            I18n.tr("ui.project.duplicate"), false, v -> {
                String newName = I18n.tr("ui.project.copyName", p.name);
                Tasks.withProgress(ctx.overlay(), I18n.tr("ui.project.duplicating", p.name), false,
                    (pr, c) -> ctx.services().projects().duplicate(p.id, newName.length() > 64 ? newName.substring(0, 64) : newName,
                        new CloneOptions(v.get(0), v.get(1), v.get(2))),
                    copy -> {
                        ctx.overlay().toast("success", I18n.tr("ui.project.duplicated", copy.name));
                        ctx.openProject(copy.id);
                    });
            });
    }

    public static void export(AppContext ctx, Project p) {
        Dialogs.options(ctx.overlay(), I18n.tr("ui.project.exportTitle", p.name), I18n.tr("ui.project.exportIntro"),
            List.of(new Dialogs.Option(I18n.tr("ui.project.exportMods"), null, true),
                new Dialogs.Option(I18n.tr("ui.project.exportConfig"), null, true),
                new Dialogs.Option(I18n.tr("ui.project.exportResourcePacks"), null, true),
                new Dialogs.Option(I18n.tr("ui.project.exportShaderPacks"), null, true),
                new Dialogs.Option(I18n.tr("ui.project.exportWorlds"), I18n.tr("ui.project.exportWorldsHint"), false),
                new Dialogs.Option(I18n.tr("ui.project.exportScreenshots"), null, false)),
            I18n.tr("ui.project.exportChoose"), false, v -> {
                Path out = Dialogs.saveFile(ctx.stage(), I18n.tr("ui.project.exportTitle", p.name),
                    p.id + ".shardlight.zip", I18n.tr("ui.project.exportFilter"), "*.zip");
                if (out == null) {
                    return;
                }
                ExportOptions opts = new ExportOptions(v.get(4), v.get(5), v.get(0), v.get(1), v.get(2), v.get(3));
                Tasks.withProgress(ctx.overlay(), I18n.tr("ui.project.exporting", p.name), false,
                    (pr, c) -> ctx.services().projects().exportProject(p.id, out, opts),
                    file -> {
                        ctx.overlay().toast("success", I18n.tr("ui.project.exported", file.getFileName()));
                        ctx.openFolder(file.getParent());
                    });
            });
    }

    public static void backupNow(AppContext ctx, Project p) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.backup.creating", p.name), true,
            (pr, c) -> ctx.services().backups().backupProject(p.id, I18n.tr("ui.backup.manualLabel"),
                BackupScope.EVERYTHING, pr, c),
            b -> ctx.overlay().toast("success", I18n.tr("ui.backup.created", UiKit.bytes(b.sizeBytes()))));
    }

    /** move to the launcher's trash (reversible), after showing exactly what is inside */
    public static void moveToTrash(AppContext ctx, Project p, Runnable after) {
        if (ctx.play().isRunning(p.id)) {
            ctx.overlay().message(I18n.tr("ui.project.trashTitle", p.name), I18n.tr("ui.project.closeGameFirst"));
            return;
        }
        Tasks.quiet(ctx.overlay(), () -> ctx.services().projects().planRemoval(p.id), (RemovalPlan plan) -> {
            List<String> details = new ArrayList<>();
            details.add(I18n.tr("ui.project.removeWorlds", plan.worlds()));
            details.add(I18n.tr("ui.project.removeScreenshots", plan.screenshots()));
            details.add(I18n.tr("ui.project.removeMods", plan.mods(), plan.resourcePacks()));
            details.add(I18n.tr("ui.project.removeSize", UiKit.bytes(plan.totalBytes())));
            if (p.gameDirLocation != null) {
                details.add(I18n.tr("ui.project.removeOutside", p.gameDirLocation));
            }
            ctx.overlay().confirm(I18n.tr("ui.project.trashTitle", p.name), I18n.tr("ui.project.trashMessage"), details,
                I18n.tr("ui.project.trash"), true, () -> Tasks.quiet(ctx.overlay(),
                    () -> ctx.services().projects().archive(p.id), (ArchivedProject a) -> {
                        ctx.overlay().toast("success", I18n.tr("ui.project.trashed", a.name()));
                        if (p.id.equals(ctx.services().settings().get().selectedProjectId)) {
                            ctx.services().projects().list().stream().findFirst()
                                .ifPresentOrElse(n -> ctx.selectProject(n.id), () -> ctx.selectProject(null));
                        }
                        if (after != null) {
                            after.run();
                        }
                    }));
        });
    }

    /** permanently delete a trashed project: needs the project name typed in, since it can't be undone */
    public static void deletePermanently(AppContext ctx, ArchivedProject a, Runnable after) {
        TextField confirmName = new TextField();
        confirmName.setAccessibleText(I18n.tr("ui.trash.typeName", a.name()));
        VBox body = new VBox(10, UiKit.label(I18n.tr("ui.trash.deleteMessage", a.name(), UiKit.bytes(a.sizeBytes()))));
        ctx.services().projects().archivedGameDir(a.trashId()).ifPresent(outside ->
            body.getChildren().add(UiKit.banner("warning", I18n.tr("ui.trash.deleteOutside", outside.toString()))));
        body.getChildren().addAll(UiKit.label(I18n.tr("ui.trash.typeName", a.name()), "muted", "small"), confirmName);
        body.setPrefWidth(ThemeManager.scaled(460));
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button delete = new Button(I18n.tr("ui.trash.deleteForever"));
        delete.getStyleClass().add("danger");
        delete.setDisable(true);
        confirmName.textProperty().addListener((o, x, n) -> delete.setDisable(n == null || !n.trim().equals(a.name())));
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.trash.deleteTitle"), body, cancel, delete), true, false);
        cancel.setOnAction(e -> d.close());
        delete.setOnAction(e -> {
            d.close();
            Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().projects().deletePermanently(a.trashId());
                return a;
            }, x -> {
                ctx.overlay().toast("success", I18n.tr("ui.trash.deleted", a.name()));
                if (after != null) {
                    after.run();
                }
            });
        });
        cancel.requestFocus();
    }

    /** import a .shardlight project export, a Modrinth .mrpack or a CurseForge modpack zip */
    public static void importFile(AppContext ctx) {
        Path file = Dialogs.openFile(ctx.stage(), I18n.tr("ui.import.title"), I18n.tr("ui.import.filter"),
            "*.zip", "*.mrpack", "*.shardlight");
        if (file == null) {
            return;
        }
        importDropped(ctx, file);
    }

    /** the same import for a file that is already chosen (picked in the dialog or dropped on the window) */
    public static void importDropped(AppContext ctx, Path file) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Tasks.quiet(ctx.overlay(), () -> {
            try {
                return ctx.services().modpacks().inspect(file);
            } catch (Exception e) {
                if (lower.endsWith(".mrpack")) {
                    throw e;
                }
                return null;
            }
        }, (ModpackInfo info) -> {
            if (info == null) {
                importProjectExport(ctx, file);
            } else {
                confirmModpack(ctx, file, info);
            }
        });
    }

    private static void importProjectExport(AppContext ctx, Path file) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.import.importing", file.getFileName()), false,
            (pr, c) -> ctx.services().projects().importProject(file, null),
            p -> {
                ctx.overlay().toast("success", I18n.tr("ui.import.done", p.name));
                ctx.openProject(p.id);
            });
    }

    private static void confirmModpack(AppContext ctx, Path file, ModpackInfo info) {
        List<String> details = new ArrayList<>();
        details.add(I18n.tr("ui.import.packVersion", info.version() == null ? "-" : info.version()));
        details.add(I18n.tr("ui.import.packGame", info.minecraftVersion(), info.loader() == null ? "-" : info.loader().toString()));
        details.add(I18n.tr("ui.import.packFiles", info.fileCount()));
        if (info.problems() != null) {
            details.addAll(info.problems());
        }
        boolean curse = info.format() != null && info.format().toLowerCase(Locale.ROOT).contains("curse");
        ctx.overlay().confirm(I18n.tr("ui.import.packTitle", info.name()),
            I18n.tr(curse ? "ui.import.packIntroCurse" : "ui.import.packIntro"), details, I18n.tr("ui.import.packInstall"),
            false, () -> Tasks.withProgress(ctx.overlay(), I18n.tr("ui.import.importing", info.name()), true,
                (pr, c) -> curse ? ctx.services().modpacks().importCurseForge(file, info.name(), pr, c)
                    : ctx.services().modpacks().importMrpack(file, info.name(), pr, c),
                p -> {
                    ctx.overlay().toast("success", I18n.tr("ui.import.done", p.name));
                    ctx.projectsChanged();
                    ctx.openProject(p.id);
                }));
    }

    /** apply a performance preset to {@code p} and say what changed (the palette's Switch to Ultra Performance) */
    public static void applyPreset(AppContext ctx, Project p, dev.shardlight.client.preset.Preset target) {
        String name = I18n.tr(target.nameKey());
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.preset.applying", name), false,
            (pr, c) -> ctx.services().setup().applyPreset(p, target), r -> {
                ctx.projectsChanged();
                ctx.overlay().toast("success", r.applied().isEmpty() ? I18n.tr("ui.preset.appliedNothing", name)
                    : I18n.tr("ui.preset.applied", name, r.applied().size()));
                for (String w : r.warnings()) {
                    ctx.overlay().toast("warning", w);
                }
            });
    }
}
