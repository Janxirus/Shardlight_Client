package dev.shardlight.client.services;

import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.catalog.InstallPlan;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.ApplyReport;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.shardlight.ApplyResult;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Steps that need several services together: installing a project's game files, switching Shardlight
 * modules on or off (with Fabric API from Modrinth when a module needs it), and applying presets.
 */
public final class ProjectSetup {
    private static final Logger LOG = Logger.getLogger(ProjectSetup.class.getName());
    private final LauncherServices s;

    ProjectSetup(LauncherServices services) {
        this.s = services;
    }

    /** what {@link #applyShardlight} did */
    public record ShardlightOutcome(ApplyResult result, List<String> installedDependencies, ApplyReport preset,
                                    List<String> warnings) {
    }

    /**
     * Install the loader profile and every game file of a project (incremental: files already present and
     * verified are skipped). Returns the version id that will be launched.
     */
    public String installGame(Project project, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        String versionId = s.loaders().ensureInstalled(project.minecraftVersion, project.loader, progress, cancel);
        s.installer().install(versionId, progress, cancel);
        Project fresh = s.projects().update(project.id, p -> p.installedVersionId = versionId);
        project.installedVersionId = fresh.installedVersionId;
        return versionId;
    }

    /**
     * Switch the project's Shardlight modules and theme. Deselected modules' jars are removed from the
     * project so they can't load on the next launch. Fabric API comes from Modrinth when a module needs it
     * and the project doesn't have it. When modules are selected, the project's preset is written into
     * their config files afterwards.
     */
    public ShardlightOutcome applyShardlight(Project project, Set<String> modules, String themeId,
                                            ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        ApplyResult result = s.shardlight().apply(project, modules, themeId, progress, cancel);
        Project current = reload(project);
        List<String> installed = new ArrayList<>();
        List<String> warnings = new ArrayList<>(result.warnings());
        for (String dep : result.catalogDependenciesNeeded()) {
            cancel.check();
            int colon = dep.indexOf(':');
            String source = colon < 0 ? "modrinth" : dep.substring(0, colon);
            String projectId = colon < 0 ? dep : dep.substring(colon + 1);
            installed.addAll(installLatest(current, source, projectId, ContentKind.MOD, progress, cancel));
            current = reload(current);
        }
        ApplyReport preset = null;
        if (!modules.isEmpty()) {
            preset = s.presets().apply(current, current.preset.active == null ? Preset.DEFAULT : current.preset.active);
        }
        return new ShardlightOutcome(result, installed, preset, warnings);
    }

    /**
     * Install the newest version of a catalog project that fits the project's Minecraft version and loader
     * (release before beta before alpha), with its required dependencies. Returns the titles installed.
     */
    public List<String> installLatest(Project project, String source, String catalogProjectId, ContentKind kind,
                                      ProgressListener progress, CancellationToken cancel) throws LauncherException {
        CatalogProvider provider = s.catalogs().get(source)
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "services.catalogMissing", source));
        if (!provider.isAvailable()) {
            throw new LauncherException(ErrorCode.API_KEY_MISSING, provider.unavailableReasonKey());
        }
        LoaderType loader = kind == ContentKind.MOD ? project.loader.type() : null;
        List<CatalogVersion> versions = provider.versions(catalogProjectId, project.minecraftVersion, loader, cancel);
        CatalogVersion best = versions.stream()
            .min(Comparator.comparingInt((CatalogVersion v) -> channelRank(v.channel()))
                .thenComparing(CatalogVersion::published, Comparator.nullsLast(Comparator.reverseOrder())))
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "services.noCompatibleVersion",
                catalogProjectId, project.minecraftVersion, project.loader.type().displayName()));
        InstallPlan plan = s.planner().plan(project, provider, best, kind, false, cancel);
        if (!plan.canInstall()) {
            throw new LauncherException(ErrorCode.CONFLICT, "services.planBlocked", catalogProjectId);
        }
        var entries = s.content().install(project, plan, progress, cancel);
        List<String> titles = new ArrayList<>();
        entries.forEach(e -> titles.add(e.title == null ? e.fileName : e.title));
        LOG.info("Installed " + titles + " into " + project.id);
        return titles;
    }

    /** apply a preset to the project's Shardlight config files (and the vanilla video settings when opted in) */
    public ApplyReport applyPreset(Project project, Preset preset) throws LauncherException {
        return s.presets().apply(reload(project), preset);
    }

    private Project reload(Project p) throws LauncherException {
        return s.projects().get(p.id)
            .orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "services.projectGone", p.name));
    }

    private static int channelRank(String channel) {
        if (channel == null) {
            return 3;
        }
        return switch (channel) {
            case "release" -> 0;
            case "beta" -> 1;
            case "alpha" -> 2;
            default -> 3;
        };
    }
}
