package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
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
    private CatalogInstall() {
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
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.install.planning", item.title()), true, (pr, c) -> {
            pr.stage("ui.install.planning.stage");
            CatalogVersion v = version != null ? version : best(provider, item, target, c)
                .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "services.noCompatibleVersion",
                    item.title(), target.minecraftVersion, target.loader.type.displayName()));
            return new Planned(v, ctx.services().planner().plan(target, provider, v, item.kind(), false, c));
        }, planned -> showPlan(ctx, provider, item, target, planned.version(), planned.plan(), false, after));
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

    private static void showPlan(AppContext ctx, CatalogProvider provider, CatalogProject item, Project target,
                                 CatalogVersion version, InstallPlan plan, boolean overridden, Runnable after) {
        VBox body = new VBox(10);
        body.setPrefWidth(640);
        body.getChildren().add(UiKit.label(I18n.tr("ui.install.into", target.name, target.minecraftVersion,
            target.loader.isVanilla() ? I18n.tr("ui.project.vanilla") : target.loader.type.displayName()), "muted"));
        long total = 0;
        VBox files = new VBox(6);
        for (PlannedFile f : plan.files()) {
            total += Math.max(0, f.file().size());
            Label title = UiKit.label(f.title() + "  " + f.version().versionNumber(), "project-name");
            Label sub = UiKit.label(f.file().fileName() + "  ·  " + UiKit.bytes(f.file().size())
                + (f.dependency() ? "  ·  " + I18n.tr("ui.install.requiredBy", f.requiredBy()) : ""), "faint", "small");
            VBox t = new VBox(2, title, sub);
            HBox.setHgrow(t, Priority.ALWAYS);
            HBox row = new HBox(10, UiKit.iconBox(Icons.of(f.dependency() ? Icons.PACKAGE : Icons.DOWNLOAD, 16), 32), t);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            files.getChildren().add(row);
        }
        if (!plan.files().isEmpty()) {
            body.getChildren().addAll(UiKit.label(I18n.tr("ui.install.willDownload", plan.files().size(), UiKit.bytes(total)), "h3"),
                files);
        }
        boolean anyOverridable = false;
        for (PlanIssue issue : plan.issues()) {
            String kind = switch (issue.severity().name()) {
                case "ERROR" -> "danger";
                case "WARNING" -> "warning";
                default -> "info";
            };
            Object[] args = issue.args() == null ? new Object[0] : issue.args().toArray();
            body.getChildren().add(UiKit.banner(kind, I18n.tr(issue.messageKey(), args)));
            anyOverridable |= issue.overridable() && "danger".equals(kind);
        }
        boolean restricted = plan.files().stream().map(PlannedFile::file).anyMatch(CatalogFile::distributionRestricted)
            || plan.issues().stream().anyMatch(i -> i.messageKey().contains("restricted"));
        if (!plan.optionalSuggestions().isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (CatalogDependency d : plan.optionalSuggestions()) {
                if (!b.isEmpty()) {
                    b.append(", ");
                }
                b.append(d.fileName() != null ? d.fileName() : d.projectId());
            }
            body.getChildren().add(UiKit.banner("info", I18n.tr("ui.install.optional", b.toString())));
        }
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
        cancel.setOnAction(e -> d.close());
        override.selectedProperty().addListener((o, a, b) -> {
            if (b) {
                d.close();
                Tasks.withProgress(ctx.overlay(), I18n.tr("ui.install.planning", item.title()), true,
                    (pr, c) -> ctx.services().planner().plan(target, provider, version, item.kind(), true, c),
                    p2 -> showPlan(ctx, provider, item, target, version, p2, true, after));
            }
        });
        install.setOnAction(e -> {
            d.close();
            Tasks.withProgress(ctx.overlay(), I18n.tr("ui.install.installing", item.title()), true,
                (pr, c) -> ctx.services().content().install(target, plan, pr, c), entries -> {
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
