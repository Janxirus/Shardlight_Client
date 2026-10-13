package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.CatalogDependency;
import dev.shardlight.client.catalog.CatalogFile;
import dev.shardlight.client.catalog.CatalogProject;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.InstallPlan;
import dev.shardlight.client.catalog.PlanIssue;
import dev.shardlight.client.catalog.PlannedFile;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Installing something from Modrinth or CurseForge into a project: pick a compatible version, plan its
 * dependencies, show exactly what will be downloaded and any problems, then install after confirmation.
 * Modpacks become a new project instead.
 */
public final class CatalogInstall {
    private static final ObservableSet<String> BUSY = FXCollections.observableSet();

    private CatalogInstall() {
    }

    /** keys of the items being planned or installed right now, so buttons can show "Installing" */
    public static ObservableSet<String> busy() {
        return BUSY;
    }

    /** the key {@link #busy()} uses for an item */
    public static String key(CatalogProvider provider, CatalogProject item) {
        return provider.id() + ":" + item.id();
    }

    /** install {@code item} into {@code target}; {@code version} null = newest compatible version */
    public static void install(AppContext ctx, CatalogProvider provider, CatalogProject item, CatalogVersion version,
                               Project target, Runnable after) {
        if (item.kind() == ContentKind.MODPACK) {
            installModpack(ctx, provider, item, version, after);
            return;
        }
        if (target == null) {
            ctx.overlay().message(I18n.tr("ui.install.noProjectTitle"), I18n.tr("ui.install.noProject"));
            return;
        }
        String key = key(provider, item);
        BUSY.add(key);
        Runnable release = () -> BUSY.remove(key);
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.install.planning", item.title()), true, (pr, c) -> {
            try {
                pr.stage("ui.install.planning.stage");
                CatalogVersion v = version != null ? version : best(provider, item, target, c)
                    .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "services.noCompatibleVersion",
                        item.title(), target.minecraftVersion, target.loader.type.displayName()));
                return new Planned(v, ctx.services().planner().plan(target, provider, v, item.kind(), false, c));
            } catch (Exception | Error e) {
                Fx.ui(release);
                throw e;
            }
        }, planned -> showPlan(ctx, provider, item, target, planned.version(), planned.plan(), false, after, release));
    }

    private record Planned(CatalogVersion version, InstallPlan plan) {
    }

    /** newest version for the project's Minecraft version and loader, release before beta before alpha */
    public static Optional<CatalogVersion> best(CatalogProvider provider, CatalogProject item, Project target,
                                                dev.shardlight.client.task.CancellationToken c) throws LauncherException {
        var loader = item.kind() == ContentKind.MOD ? target.loader.type : null;
        List<CatalogVersion> versions = provider.versions(item.id(), target.minecraftVersion, loader, c);
        return versions.stream().min(Comparator.comparingInt((CatalogVersion v) -> rank(v.channel()))
            .thenComparing(CatalogVersion::published, Comparator.nullsLast(Comparator.reverseOrder())));
    }

    private static int rank(String channel) {
        return channel == null ? 3 : switch (channel) {
            case "release" -> 0;
            case "beta" -> 1;
            case "alpha" -> 2;
            default -> 3;
        };
    }

    /** "N files, size" and one row per file to download, dependencies marked; empty when there is nothing to download */
    public static VBox files(InstallPlan plan) {
        VBox box = new VBox();
        box.getStyleClass().add("stack-tight");
        if (plan.files().isEmpty()) {
            return box;
        }
        long total = 0;
        VBox rows = new VBox();
        rows.getStyleClass().add("stack-tight");
        for (PlannedFile f : plan.files()) {
            total += Math.max(0, f.file().size());
            HBox title = new HBox(UiKit.label(f.title() + "  " + f.version().versionNumber(), "project-name"));
            title.getStyleClass().add("card-title-row");
            if (f.dependency()) {
                title.getChildren().add(UiKit.chip(I18n.tr("ui.install.dependency"), "info"));
            }
            Label sub = UiKit.label(f.file().fileName() + "  ·  " + UiKit.bytes(f.file().size())
                + (f.dependency() ? "  ·  " + I18n.tr("ui.install.requiredBy", f.requiredBy()) : ""), "faint", "small");
            VBox t = new VBox(title, sub);
            t.getStyleClass().add("row-text");
            HBox.setHgrow(t, Priority.ALWAYS);
            HBox row = new HBox(UiKit.iconBox(Icons.of(f.dependency() ? Icons.PACKAGE : Icons.DOWNLOAD, 16), 32), t);
            row.getStyleClass().addAll("card", "flat", "plan-row");
            rows.getChildren().add(row);
        }
        HBox head = new HBox(UiKit.label(I18n.tr("ui.install.willDownload", plan.files().size(), UiKit.bytes(total)), "h3"));
        head.getStyleClass().add("card-title-row");
        box.getChildren().addAll(head, rows);
        return box;
    }

    /** a banner for every problem the plan found, and one for the optional dependencies it suggests */
    public static List<javafx.scene.Node> issues(InstallPlan plan) {
        List<javafx.scene.Node> out = new java.util.ArrayList<>();
        for (PlanIssue issue : plan.issues()) {
            String kind = switch (issue.severity().name()) {
                case "ERROR" -> "danger";
                case "WARNING" -> "warning";
                default -> "info";
            };
            Object[] args = issue.args() == null ? new Object[0] : issue.args().toArray();
            out.add(UiKit.banner(kind, I18n.tr(issue.messageKey(), args)));
        }
        if (!plan.optionalSuggestions().isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (CatalogDependency d : plan.optionalSuggestions()) {
                if (!b.isEmpty()) {
                    b.append(", ");
                }
                b.append(d.fileName() != null ? d.fileName() : d.projectId());
            }
            out.add(UiKit.banner("info", I18n.tr("ui.install.optional", b.toString())));
        }
        return out;
    }

    private static void showPlan(AppContext ctx, CatalogProvider provider, CatalogProject item, Project target,
                                 CatalogVersion version, InstallPlan plan, boolean overridden, Runnable after,
                                 Runnable release) {
        VBox body = new VBox();
        body.getStyleClass().add("stack-tight");
        body.setPrefWidth(ThemeManager.scaled(640));
        body.getChildren().add(UiKit.label(I18n.tr("ui.install.into", target.name, target.minecraftVersion,
            target.loader.isVanilla() ? I18n.tr("ui.project.vanilla") : target.loader.type.displayName()), "muted"));
        boolean anyOverridable = plan.issues().stream()
            .anyMatch(i -> i.overridable() && i.severity() == dev.shardlight.client.catalog.Severity.ERROR);
        body.getChildren().addAll(issues(plan));
        body.getChildren().add(files(plan));
        boolean restricted = plan.files().stream().map(PlannedFile::file).anyMatch(CatalogFile::distributionRestricted)
            || plan.issues().stream().anyMatch(i -> i.messageKey().contains("restricted"));
        body.getChildren().add(UiKit.label(I18n.tr("ui.install.disclaimer"), "faint", "small"));

        CheckBox override = new CheckBox(I18n.tr("ui.install.override"));
        override.setWrapText(true);
        if (anyOverridable && !overridden) {
            body.getChildren().add(override);
        }
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button website = UiKit.button(I18n.tr("ui.install.openWebsite"), Icons.EXTERNAL,
            () -> ctx.openUrl(item.pageUrl() == null ? null : item.pageUrl().toString()));
        website.setVisible(restricted);
        website.setManaged(restricted);
        Button install = UiKit.primary(I18n.tr("ui.install.install"), Icons.DOWNLOAD, () -> { });
        install.setDisable(!plan.canInstall());
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.install.title", item.title()), body, cancel, website,
            install), true, true);
        java.util.concurrent.atomic.AtomicBoolean proceeding = new java.util.concurrent.atomic.AtomicBoolean();
        d.onClose(() -> {
            if (!proceeding.get()) {
                release.run();
            }
        });
        cancel.setOnAction(e -> d.close());
        override.selectedProperty().addListener((o, a, b) -> {
            if (b) {
                proceeding.set(true);
                d.close();
                Tasks.withProgress(ctx.overlay(), I18n.tr("ui.install.planning", item.title()), true,
                    (pr, c) -> {
                        try {
                            return ctx.services().planner().plan(target, provider, version, item.kind(), true, c);
                        } catch (Exception | Error ex) {
                            Fx.ui(release);
                            throw ex;
                        }
                    },
                    p2 -> showPlan(ctx, provider, item, target, version, p2, true, after, release));
            }
        });
        install.setOnAction(e -> {
            proceeding.set(true);
            d.close();
            Tasks.withProgress(ctx.overlay(), I18n.tr("ui.install.installing", item.title()), true,
                (pr, c) -> {
                    try {
                        return ctx.services().content().install(target, plan, pr, c);
                    } catch (Exception | Error ex) {
                        Fx.ui(release);
                        throw ex;
                    }
                }, entries -> {
                    release.run();
                    ctx.overlay().toast("success", I18n.tr("ui.install.done", item.title(), target.name));
                    ctx.projectsChanged();
                    if (after != null) {
                        after.run();
                    }
                });
        });
    }

    /** download a modpack and turn it into a new project */
    private static void installModpack(AppContext ctx, CatalogProvider provider, CatalogProject item, CatalogVersion version,
                                       Runnable after) {
        ctx.overlay().confirm(I18n.tr("ui.install.modpackTitle", item.title()), I18n.tr("ui.install.modpackMessage"),
            List.of(I18n.tr("ui.install.modpackIsolated")), I18n.tr("ui.install.modpackCreate"), false,
            () -> Tasks.withProgress(ctx.overlay(), I18n.tr("ui.install.installing", item.title()), true, (pr, c) -> {
                CatalogVersion v = version;
                if (v == null) {
                    v = provider.versions(item.id(), null, null, c).stream()
                        .min(Comparator.comparingInt((CatalogVersion x) -> rank(x.channel()))
                            .thenComparing(CatalogVersion::published, Comparator.nullsLast(Comparator.reverseOrder())))
                        .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "services.noCompatibleVersion",
                            item.title(), "-", "-"));
                }
                List<CatalogFile> files = v.files();
                CatalogFile f = files.stream().filter(CatalogFile::primary).findFirst()
                    .or(() -> files.stream().findFirst())
                    .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "services.noFile", item.title()));
                if (f.distributionRestricted() || f.url() == null) {
                    throw new LauncherException(ErrorCode.DISTRIBUTION_RESTRICTED, "services.restricted", item.title());
                }
                Path dir = ctx.services().paths().cache().resolve("modpacks");
                Files.createDirectories(dir);
                Path file = dir.resolve(dev.shardlight.client.util.PathSafety.sanitizeFileName(f.fileName()));
                DownloadRequest req = DownloadRequest.of(f.url(), file).withLabel(item.title());
                if (f.sha1() != null) {
                    req = req.withHash(dev.shardlight.client.util.HashAlgo.SHA1, f.sha1());
                }
                ctx.services().downloads().downloadAll(item.title(), List.of(req), "ui.install.downloadingPack", pr, c);
                var info = ctx.services().modpacks().inspect(file);
                boolean curse = info.format() != null && info.format().toLowerCase(java.util.Locale.ROOT).contains("curse");
                return curse ? ctx.services().modpacks().importCurseForge(file, item.title(), pr, c)
                    : ctx.services().modpacks().importMrpack(file, item.title(), pr, c);
            }, p -> {
                ctx.overlay().toast("success", I18n.tr("ui.import.done", p.name));
                ctx.projectsChanged();
                ctx.selectProject(p.id);
                ctx.openProject(p.id);
                if (after != null) {
                    after.run();
                }
            }));
    }
}
