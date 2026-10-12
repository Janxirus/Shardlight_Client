package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Disclosure;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.diagnostics.Finding;
import dev.shardlight.client.diagnostics.Severity;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.launch.GameExit;
import dev.shardlight.client.project.Project;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the player sees after the game crashed: the most likely cause in plain words, a button that goes to
 * where it can be fixed, and the log and diagnostics export. The causes come from the crash analysis; a
 * fix button only appears for causes the launcher knows where to fix.
 */
public final class CrashScreen {
    /** where a cause is fixed: the project tab and the label of the button that opens it */
    record Fix(String tab, String labelKey, String icon) {
    }

    private CrashScreen() {
    }

    static Fix fixFor(Finding f) {
        String id = f.titleKey().replaceFirst("^diagnostics\\.", "").replaceFirst("\\.title$", "");
        return switch (id) {
            case "java" -> new Fix("settings", "ui.crash.fix.java", Icons.CUBE);
            case "memory", "heap" -> new Fix("settings", "ui.crash.fix.memory", Icons.MEMORY);
            case "jvmOption", "jvm" -> new Fix("settings", "ui.crash.fix.jvm", Icons.SLIDERS);
            case "graphics", "nativeCrash" -> new Fix("performance", "ui.crash.fix.graphics", Icons.GAUGE);
            case "missing", "wrong", "incompatible", "loader", "duplicate", "mixin", "fabricApi", "missingClass",
                 "suspected", "damaged" -> new Fix("content", "ui.crash.fix.mods", Icons.PACKAGE);
            default -> null;
        };
    }

    public static void show(AppContext ctx, Project project, GameExit exit) {
        List<Finding> findings = new ArrayList<>(exit.findings());
        findings.sort((a, b) -> b.severity().compareTo(a.severity()));
        Finding likely = findings.isEmpty() || findings.get(0).severity() == Severity.INFO ? null : findings.get(0);

        VBox body = new VBox(14);
        body.getStyleClass().add("crash-screen");
        body.setPrefWidth(620);

        Overlay.Dialog[] dialog = new Overlay.Dialog[1];
        Label what = UiKit.label(I18n.tr("ui.crash.what", exit.exitCode()), "muted");
        what.setWrapText(true);
        body.getChildren().add(what);

        VBox cause = new VBox(8);
        cause.getStyleClass().addAll("crash-cause", likely == null ? "unknown" : "known");
        cause.getChildren().add(UiKit.label(I18n.tr(likely == null ? "ui.crash.likelyUnknown" : "ui.crash.likely"), "eyebrow"));
        if (likely != null) {
            cause.getChildren().addAll(finding(likely), fixButton(ctx, project, likely, dialog, true));
        } else {
            Label none = UiKit.label(I18n.tr("ui.play.noFindings"), "muted");
            none.setWrapText(true);
            cause.getChildren().add(none);
        }
        body.getChildren().add(cause);

        List<Finding> others = findings.stream().filter(f -> f != likely).toList();
        if (!others.isEmpty()) {
            VBox list = new VBox(10);
            for (Finding f : others) {
                list.getChildren().addAll(finding(f), fixButton(ctx, project, f, dialog, false));
            }
            body.getChildren().add(new Disclosure(I18n.tr("ui.crash.others", others.size()), false, list));
        }
        Label hint = UiKit.label(I18n.tr("ui.play.crashHint"), "faint", "small");
        hint.setWrapText(true);
        body.getChildren().add(hint);

        Button close = new Button(I18n.tr("common.close"));
        close.setCancelButton(true);
        Button log = UiKit.button(I18n.tr("ui.crash.openLog"), Icons.LOGS, () -> {
            dialog[0].close();
            ctx.openProjectTab(project.id, "logs");
        });
        Button report = UiKit.button(I18n.tr("ui.play.openCrashReport"), Icons.LOGS,
            () -> exit.crashReport().map(Path::getParent).ifPresent(ctx::openFolder));
        report.setDisable(exit.crashReport().isEmpty());
        Button export = UiKit.primary(I18n.tr("ui.diagnostics.export"), Icons.ARCHIVE, () -> {
            dialog[0].close();
            PlayFlow.exportDiagnostics(ctx, project);
        });
        dialog[0] = ctx.overlay().show(Overlay.modal(I18n.tr("ui.play.crashedTitle", project.name), body, close, log, report,
            export), true, true);
        close.setOnAction(e -> dialog[0].close());
    }

    /**
     * The findings of a log or crash report analysis, most serious first: a likely cause with its fix button, then
     * the other findings, each with the tab that fixes it. Findings that are only notes are listed without a cause.
     */
    public static VBox analysis(AppContext ctx, Project project, List<Finding> found, Overlay.Dialog[] dialog) {
        List<Finding> findings = new ArrayList<>(found);
        findings.sort((a, b) -> b.severity().compareTo(a.severity()));
        Finding likely = findings.isEmpty() || findings.get(0).severity() == Severity.INFO ? null : findings.get(0);
        VBox body = new VBox(14);
        body.setPrefWidth(620);
        if (likely != null) {
            VBox cause = new VBox(8);
            cause.getStyleClass().addAll("crash-cause", "known");
            cause.getChildren().addAll(UiKit.label(I18n.tr("ui.crash.likely"), "eyebrow"), finding(likely),
                fixButton(ctx, project, likely, dialog, true));
            body.getChildren().add(cause);
        }
        VBox list = new VBox(10);
        for (Finding f : findings) {
            if (f != likely) {
                list.getChildren().addAll(finding(f), fixButton(ctx, project, f, dialog, false));
            }
        }
        if (!list.getChildren().isEmpty()) {
            body.getChildren().add(likely == null ? list : new Disclosure(I18n.tr("ui.crash.others", findings.size() - 1), true, list));
        }
        return body;
    }

    /** a cause: a short title and what it means, in the analysis' own words */
    private static Node finding(Finding f) {
        Object[] args = f.argArray();
        Label title = UiKit.label(I18n.tr(f.titleKey(), args), "h3");
        Label detail = UiKit.label(I18n.tr(f.detailKey(), args), "muted");
        detail.setWrapText(true);
        VBox text = new VBox(2, title, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        String kind = switch (f.severity()) {
            case ERROR -> "danger";
            case WARNING -> "warning";
            default -> "info";
        };
        HBox row = new HBox(12, UiKit.chip(I18n.tr("ui.crash.severity." + f.severity().name().toLowerCase()), kind), text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Node fixButton(AppContext ctx, Project project, Finding f, Overlay.Dialog[] dialog, boolean main) {
        Fix fix = fixFor(f);
        if (fix == null) {
            return new HBox();
        }
        Runnable go = () -> {
            dialog[0].close();
            ctx.openProjectTab(project.id, fix.tab());
        };
        Button b = main ? UiKit.primary(I18n.tr(fix.labelKey()), fix.icon(), go) : UiKit.button(I18n.tr(fix.labelKey()), fix.icon(), go);
        return new HBox(b);
    }
}
