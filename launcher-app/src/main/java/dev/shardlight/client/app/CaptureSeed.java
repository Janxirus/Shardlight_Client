package dev.shardlight.client.app;

import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.JavaSettings;
import dev.shardlight.client.project.NewProjectRequest;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * --capture only: when the (throw-away) capture data folder has no projects, create two example projects
 * so the screenshots show filled pages. Nothing is downloaded or installed.
 */
final class CaptureSeed {
    private static final Logger LOG = Logger.getLogger(CaptureSeed.class.getName());

    private CaptureSeed() {
    }

    static void seed(AppContext ctx) {
        var projects = ctx.services().projects();
        if (!projects.list().isEmpty()) {
            return;
        }
        try {
            projects.create(new NewProjectRequest("Vanilla 26.3", "builtin:grass", "vanilla", "26.3",
                LoaderSpec.vanilla(), Set.of(), null, Preset.BALANCED, new JavaSettings()));
            var p = projects.create(new NewProjectRequest("Shardlight Survival", "builtin:shard", "shardlight-default",
                "26.3", new LoaderSpec(LoaderType.FABRIC, "0.19.5"), Set.of("ui", "map", "party", "companion"),
                "EMBER", Preset.BALANCED, new JavaSettings()));
            ctx.selectProject(p.id);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Couldn't create the capture example projects", e);
        }
    }
}
