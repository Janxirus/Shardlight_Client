package dev.shardlight.client.catalog;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.task.CancellationToken;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Works out what installing a catalog version into a project means: the root file (primary, else first),
 * its REQUIRED dependencies resolved recursively (a pinned version id is used as is; otherwise the newest
 * version for the project's Minecraft version and loader, preferring release over beta over alpha),
 * skipping what is already installed (same catalog project, or a mod id equal to the dependency's slug).
 * OPTIONAL dependencies become suggestions; installed INCOMPATIBLE ones are errors; a root version not made
 * for the project's Minecraft version or loader is an error unless the user accepts the risk
 * ({@code allowIncompatible}); files that can't be downloaded by the launcher are errors pointing to the
 * website. A cycle guard and size limits keep hostile metadata from looping. Thread-safe and stateless.
 *
 * <p>Metadata can't catch every runtime incompatibility; {@link InstallPlan#summary()} says so.
 */
public final class InstallPlanner {
    private static final Logger LOG = Logger.getLogger(InstallPlanner.class.getName());
    static final int MAX_FILES = 150;
    static final int MAX_DEPTH = 24;

    /** a planner */
    public InstallPlanner() {
    }

    /**
     * Plan installing {@code root} into {@code project}.
     *
     * @param kind              where the root goes (MOD, RESOURCE_PACK, SHADER_PACK)
     * @param allowIncompatible the user accepted installing a version not marked as compatible
     */
    public InstallPlan plan(Project project, CatalogProvider provider, CatalogVersion root, ContentKind kind,
                            boolean allowIncompatible, CancellationToken cancel) throws LauncherException {
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        return new Run(project, provider, kind == null ? ContentKind.MOD : kind, allowIncompatible, ct).plan(root);
    }

    /** one planning run */
    private static final class Run {
        final Project project;
        final CatalogProvider provider;
        final ContentKind kind;
        final boolean allowIncompatible;
        final CancellationToken cancel;
        final ContentSource source;
        final LoaderType loader;
        final String mc;
        final List<PlannedFile> files = new ArrayList<>();
        final List<PlanIssue> issues = new ArrayList<>();
        final List<CatalogDependency> suggestions = new ArrayList<>();
        final Set<String> planned = new HashSet<>();
        final Set<String> suggested = new HashSet<>();
        final Map<String, Optional<CatalogProject>> projects = new HashMap<>();
        final List<ContentEntry> installed = new ArrayList<>();

        Run(Project project, CatalogProvider provider, ContentKind kind, boolean allowIncompatible, CancellationToken cancel) {
            this.project = project;
            this.provider = provider;
            this.kind = kind;
            this.allowIncompatible = allowIncompatible;
            this.cancel = cancel;
            this.source = CatalogRegistry.sourceOf(provider.id());
            this.loader = project.loader == null || project.loader.type == null ? LoaderType.VANILLA : project.loader.type;
            this.mc = project.minecraftVersion;
            if (project.content != null) {
                for (ContentEntry e : project.content) {
                    if (e != null && !e.missing) {
                        installed.add(e);
                    }
                }
            }
        }

        InstallPlan plan(CatalogVersion root) throws LauncherException {
            if (kind != ContentKind.MOD && kind != ContentKind.RESOURCE_PACK && kind != ContentKind.SHADER_PACK) {
                issue(Severity.ERROR, "content.plan.kindUnsupported", false, kind.name());
                return result();
            }
            if (kind == ContentKind.MOD && loader == LoaderType.VANILLA) {
                issue(Severity.ERROR, "content.plan.vanillaNoMods", false);
            }
            Optional<CatalogProject> rootProject = projectInfo(root.projectId());
            String title = rootProject.map(CatalogProject::title).orElse(root.name() != null ? root.name() : root.versionNumber());
            checkCompatibility(root, title);
            ContentEntry existing = installedByProject(root.projectId(), kind);
            if (existing != null) {
                if (root.id() != null && root.id().equals(existing.versionId)) {
                    issue(Severity.INFO, "content.plan.sameVersion", false, title);
                } else {
                    issue(Severity.INFO, "content.plan.replaces", false, title, nz(existing.versionNumber));
                }
            } else {
                rootProject.map(CatalogProject::slug).flatMap(slug -> installedByModId(slug, null))
                    .ifPresent(e -> issue(Severity.WARNING, "content.plan.duplicateModId", false, title, label(e)));
            }
            if (!addFile(root, rootProject, title, kind, false, null)) {
                return result();
            }
            planned.add(key(root.projectId()));
            // breadth-first through the dependency graph; planned ids guard against cycles
            Deque<Object[]> queue = new ArrayDeque<>();
            queue.add(new Object[] {root, title, 0});
            while (!queue.isEmpty()) {
                cancel.check();
                Object[] item = queue.poll();
                CatalogVersion v = (CatalogVersion) item[0];
                String vTitle = (String) item[1];
                int depth = (Integer) item[2];
                for (CatalogDependency d : v.dependencies()) {
                    cancel.check();
                    switch (d.type()) {
                        case EMBEDDED -> {
                            // bundled inside the jar
                        }
                        case OPTIONAL -> suggest(d);
                        case INCOMPATIBLE -> incompatible(d, vTitle);
                        case REQUIRED -> required(d, v, vTitle, depth, queue);
                    }
                }
            }
            return result();
        }

        private InstallPlan result() {
            return new InstallPlan(files, issues, suggestions);
        }

        // -------------------------------------------------------------- checks

        private void checkCompatibility(CatalogVersion v, String title) {
            boolean mcOk = mc == null || v.gameVersions().isEmpty() || v.gameVersions().contains(mc);
            boolean loaderOk = kind != ContentKind.MOD || loader == LoaderType.VANILLA || v.loaders().isEmpty()
                || v.loaders().stream().anyMatch(this::loaderAccepts);
            if (kind != ContentKind.MOD) {
                if (!mcOk) {
                    // packs from other versions often still work; Minecraft marks them as incompatible
                    issue(Severity.WARNING, "content.plan.packVersionMismatch", false, title, String.join(", ", v.gameVersions()), mc);
                }
                return;
            }
            if (mcOk && loaderOk) {
                return;
            }
            Severity sev = allowIncompatible ? Severity.WARNING : Severity.ERROR;
            if (!loaderOk) {
                issue(sev, "content.plan.wrongLoader", true, title, String.join(", ", v.loaders()), loader.displayName());
            }
            if (!mcOk) {
                issue(sev, "content.plan.wrongMinecraft", true, title, String.join(", ", v.gameVersions()), mc);
            }
            if (allowIncompatible) {
                issue(Severity.WARNING, "content.plan.riskAccepted", true, title);
            }
        }

        private boolean loaderAccepts(String catalogLoader) {
            try {
                return loader.acceptsModsFor(LoaderType.parse(catalogLoader));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private boolean compatible(CatalogVersion v) {
            boolean mcOk = mc == null || v.gameVersions().isEmpty() || v.gameVersions().contains(mc);
            boolean loaderOk = loader == LoaderType.VANILLA || v.loaders().isEmpty() || v.loaders().stream().anyMatch(this::loaderAccepts)
                || v.loaders().stream().noneMatch(l -> isModLoader(l)); // packs: "minecraft", shaders: "iris"
            return mcOk && loaderOk;
        }

        private static boolean isModLoader(String l) {
            String s = l.toLowerCase(Locale.ROOT);
            return s.equals("fabric") || s.equals("quilt") || s.equals("forge") || s.equals("neoforge");
        }

        /** add the version's file to the plan; false when it has none */
        private boolean addFile(CatalogVersion v, Optional<CatalogProject> p, String title, ContentKind k, boolean dependency,
                                String requiredBy) {
            Optional<CatalogFile> file = v.primaryFile();
            if (file.isEmpty()) {
                issue(Severity.ERROR, "content.plan.noFiles", false, title);
                return false;
            }
            CatalogFile f = file.get();
            String page = p.map(CatalogProject::pageUrl).map(Object::toString).orElse("");
            if (!f.downloadable()) {
                issue(Severity.ERROR, "content.plan.restricted", false, title, page);
            }
            files.add(new PlannedFile(title, v, f, k, dependency, requiredBy, p.map(CatalogProject::pageUrl).orElse(null)));
            return true;
        }

        private void suggest(CatalogDependency d) {
            String id = d.projectId() != null ? d.projectId() : d.versionId();
            if (id == null || planned.contains(key(id)) || installedByProject(d.projectId(), null) != null || !suggested.add(id)) {
                return;
            }
            suggestions.add(d);
        }

        private void incompatible(CatalogDependency d, String vTitle) throws LauncherException {
            ContentEntry e = null;
            if (d.projectId() != null) {
                e = installedByProject(d.projectId(), null);
            }
            if (e == null && d.versionId() != null) {
                for (ContentEntry x : installed) {
                    if (x.source == source && d.versionId().equals(x.versionId)) {
                        e = x;
                        break;
                    }
                }
            }
            if (e == null && d.projectId() != null) {
                Optional<CatalogProject> p = projectInfo(d.projectId());
                if (p.isPresent() && p.get().slug() != null) {
                    e = installedByModId(p.get().slug(), null).orElse(null);
                }
            }
            if (e != null && e.enabled) {
                issue(Severity.ERROR, "content.plan.incompatibleInstalled", false, vTitle, label(e));
            } else if (d.projectId() != null && planned.contains(key(d.projectId()))) {
                String other = projectInfo(d.projectId()).map(CatalogProject::title).orElse(d.projectId());
                issue(Severity.ERROR, "content.plan.incompatibleInstalled", false, vTitle, other);
            }
        }

        private void required(CatalogDependency d, CatalogVersion parent, String parentTitle, int depth, Deque<Object[]> queue)
            throws LauncherException {
            String pid = d.projectId();
            CatalogVersion pinned = null;
            if (d.versionId() != null) {
                if (pid != null && planned.contains(key(pid))) {
                    return;
                }
                try {
                    pinned = provider.version(d.versionId(), cancel);
                } catch (LauncherException e) {
                    if (e.isCancelled() || e.code() == ErrorCode.API_KEY_MISSING) {
                        throw e;
                    }
                    issue(Severity.ERROR, "content.plan.dependencyUnavailable", false, nz(pid != null ? pid : d.versionId()),
                        parentTitle, nz(mc), loader.displayName());
                    if (pid != null) {
                        planned.add(key(pid));
                    }
                    return;
                }
                pid = pid != null ? pid : pinned.projectId();
            }
            if (pid == null) {
                if (d.fileName() != null) {
                    issue(Severity.WARNING, "content.plan.externalDependency", false, parentTitle, d.fileName());
                }
                return;
            }
            if (planned.contains(key(pid))) {
                return; // already planned (also breaks cycles)
            }
            ContentEntry have = installedByProject(pid, null);
            Optional<CatalogProject> p = projectInfo(pid);
            if (have == null && p.isPresent() && p.get().slug() != null) {
                have = installedByModId(p.get().slug(), pid).orElse(null);
            }
            String title = p.map(CatalogProject::title).orElse(pinned != null && pinned.name() != null ? pinned.name() : pid);
            if (have != null) {
                planned.add(key(pid));
                if (!have.enabled) {
                    issue(Severity.WARNING, "content.plan.dependencyDisabled", false, label(have), parentTitle);
                }
                return;
            }
            if (files.size() >= MAX_FILES || depth + 1 > MAX_DEPTH) {
                issue(Severity.ERROR, "content.plan.tooManyDependencies", false, parentTitle);
                planned.add(key(pid));
                return;
            }
            CatalogVersion v = pinned != null ? pinned : newest(pid);
            planned.add(key(pid));
            if (v == null) {
                issue(Severity.ERROR, "content.plan.dependencyUnavailable", false, title, parentTitle, nz(mc), loader.displayName());
                return;
            }
            if (pinned != null && !compatible(pinned)) {
                issue(Severity.WARNING, "content.plan.dependencyIncompatible", false, title, parentTitle);
            }
            ContentKind depKind = p.map(CatalogProject::kind)
                .filter(k -> k == ContentKind.MOD || k == ContentKind.RESOURCE_PACK || k == ContentKind.SHADER_PACK)
                .orElse(ContentKind.MOD);
            if (addFile(v, p, title, depKind, true, parent.projectId())) {
                queue.add(new Object[] {v, title, depth + 1});
            }
        }

        /** newest compatible version: release before beta before alpha, then most recent */
        private CatalogVersion newest(String projectId) throws LauncherException {
            List<CatalogVersion> all;
            try {
                all = provider.versions(projectId, mc, loader == LoaderType.VANILLA ? null : loader, cancel);
            } catch (LauncherException e) {
                if (e.isCancelled() || e.code() == ErrorCode.API_KEY_MISSING) {
                    throw e;
                }
                LOG.info("No versions for dependency " + projectId + ": " + e.getMessage());
                return null;
            }
            return all.stream()
                .filter(this::compatible)
                .filter(v -> v.primaryFile().isPresent())
                .min(Comparator.comparingInt(CatalogVersion::channelRank)
                    .thenComparing(CatalogVersion::published, Comparator.nullsLast(Comparator.reverseOrder())))
                .orElse(null);
        }

        // -------------------------------------------------------------- lookups

        private Optional<CatalogProject> projectInfo(String projectId) throws LauncherException {
            if (projectId == null) {
                return Optional.empty();
            }
            Optional<CatalogProject> cached = projects.get(projectId);
            if (cached != null) {
                return cached;
            }
            Optional<CatalogProject> p;
            try {
                p = Optional.ofNullable(provider.project(projectId, cancel));
            } catch (LauncherException e) {
                if (e.isCancelled() || e.code() == ErrorCode.API_KEY_MISSING) {
                    throw e;
                }
                LOG.fine(() -> "No project info for " + projectId + ": " + e.getMessage());
                p = Optional.empty();
            }
            projects.put(projectId, p);
            return p;
        }

        private ContentEntry installedByProject(String projectId, ContentKind k) {
            if (projectId == null) {
                return null;
            }
            for (ContentEntry e : installed) {
                if (e.source == source && projectId.equals(e.projectId) && (k == null || e.kind == k)) {
                    return e;
                }
            }
            return null;
        }

        /** an installed mod (not of {@code exceptProject}) whose mod id equals {@code slug} */
        private Optional<ContentEntry> installedByModId(String slug, String exceptProject) {
            if (slug == null) {
                return Optional.empty();
            }
            String s = slug.toLowerCase(Locale.ROOT);
            String alt = s.replace('-', '_');
            for (ContentEntry e : installed) {
                if (e.kind != ContentKind.MOD || e.modId == null) {
                    continue;
                }
                if (exceptProject != null && e.source == source && exceptProject.equals(e.projectId)) {
                    continue;
                }
                String m = e.modId.toLowerCase(Locale.ROOT);
                if (m.equals(s) || m.equals(alt)) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }

        private void issue(Severity severity, String key, boolean overridable, String... args) {
            List<String> a = new ArrayList<>();
            for (String x : args) {
                a.add(x == null ? "" : x);
            }
            issues.add(new PlanIssue(severity, key, a, overridable));
        }

        private static String key(String id) {
            return id == null ? "" : id;
        }

        private static String label(ContentEntry e) {
            return e.title != null && !e.title.isBlank() ? e.title : String.valueOf(e.fileName);
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }
}
