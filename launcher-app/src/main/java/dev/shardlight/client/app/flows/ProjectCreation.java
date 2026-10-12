package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.NewProjectRequest;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.SuggestedContent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creating a project and getting it ready to play: the new-project wizard and the first-run welcome both end here.
 * The project is made first, so it exists even when the downloads that follow fail or are cancelled; Play finishes
 * whatever is missing.
 */
public final class ProjectCreation {
    private ProjectCreation() {
    }

    private record Created(Project project, List<String> notes) {
    }

    /**
     * Create the project from {@code req}, then (when {@code install}) install the game, the chosen Shardlight
     * modules and the suggested content. Selects the new project and opens it.
     */
    public static void run(AppContext ctx, NewProjectRequest req, boolean vanillaVideo, Path pictured, boolean install,
                           List<SuggestedContent> suggestions) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.wizard.creating", req.name()), true, (progress, c) -> {
            var projects = ctx.services().projects();
            Project p = projects.create(req);
            if (vanillaVideo) {
                p = projects.update(p.id, x -> x.preset.applyVanillaVideoSettings = true);
            }
            if (pictured != null) {
                p = projects.setIconFromFile(p.id, pictured);
            }
            List<String> notes = new ArrayList<>();
            if (install) {
                ctx.services().setup().installGame(p, progress, c);
                if (!req.shardlightModules().isEmpty()) {
                    var report = ctx.services().shardlight().check(p, req.shardlightModules());
                    if (report.canApply()) {
                        var outcome = ctx.services().setup().applyShardlight(p, req.shardlightModules(), req.shardlightTheme(),
                            progress, c);
                        notes.addAll(outcome.warnings());
                    } else {
                        notes.add(I18n.tr("ui.wizard.shardlightLater"));
                    }
                }
                for (SuggestedContent s : suggestions) {
                    try {
                        ctx.services().setup().installLatest(p, s.source(), s.projectSlug(),
                            s.kind() == null ? ContentKind.MOD : s.kind(), progress, c);
                    } catch (Exception e) {
                        if (Tasks.isCancelled(e)) {
                            throw e;
                        }
                        notes.add(I18n.tr("ui.wizard.suggestionFailed", s.projectSlug(), Tasks.message(e)));
                    }
                }
            }
            return new Created(p, notes);
        }, created -> {
            ctx.selectProject(created.project().id);
            ctx.projectsChanged();
            ctx.overlay().toast("success", I18n.tr("ui.wizard.created", created.project().name));
            ctx.openProject(created.project().id);
            if (!created.notes().isEmpty()) {
                ctx.overlay().message(I18n.tr("ui.wizard.createdTitle"), String.join("\n\n", created.notes()));
            }
        });
    }
}
