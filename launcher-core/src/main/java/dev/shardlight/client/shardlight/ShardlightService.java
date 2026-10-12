package dev.shardlight.client.shardlight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.game.OptionsTxt;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Installs the optional Shardlight modules and theme packs into a project. Everything here is optional: a
 * project with no Shardlight selection is valid and untouched, and theme packs (plain resource packs) work
 * even on vanilla. Module jars come only from the content library; Fabric API, their one real dependency,
 * is reported in {@link ApplyResult#catalogDependenciesNeeded()} for the integrator to install from
 * Modrinth. Blocking and thread-safe; work on one project is serialized with {@link ProjectLocks}.
 */
public final class ShardlightService {
    /** Fabric API's Modrinth project id and slug, and its CurseForge id */
    public static final String FABRIC_API_MODRINTH_ID = "P7dR8mSH";
    public static final String FABRIC_API_SLUG = "fabric-api";
    public static final String FABRIC_API_CURSEFORGE_ID = "306612";
    /** the UI module, which owns the {@code theme} key */
    public static final String UI_MODULE = "ui";

    private static final Logger LOG = Logger.getLogger(ShardlightService.class.getName());
    private static final String OPTIONS_PACKS = "resourcePacks";
    private static final String OPTIONS_INCOMPATIBLE = "incompatibleResourcePacks";

    private final AppPaths paths;
    private final ProjectAccess projects;
    private final ShardlightCatalog catalog;
    private final ShardlightContentLibrary library;

    public ShardlightService(AppPaths paths, ProjectAccess projects, ShardlightCatalog catalog, ShardlightContentLibrary library) {
        this.paths = Objects.requireNonNull(paths);
        this.projects = Objects.requireNonNull(projects);
        this.catalog = Objects.requireNonNull(catalog);
        this.library = Objects.requireNonNull(library);
    }

    public ShardlightCatalog catalog() {
        return catalog;
    }

    public ShardlightContentLibrary library() {
        return library;
    }

    // ---------------------------------------------------------------- check

    /**
     * Can these modules go into this project? Errors for a loader other than Fabric (Quilt is a warning:
     * may work, untested), an unsupported Minecraft version or a too old Fabric Loader; lists modules whose
     * jar isn't in the library, the catalog dependencies still to install, the Java version needed and
     * "works better with" hints. An empty selection is always valid. Never throws.
     */
    public CompatibilityReport check(Project project, Set<String> moduleIds) {
        Objects.requireNonNull(project, "project");
        List<CompatibilityReport.Message> msgs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, ShardlightModule.Dependency> deps = new LinkedHashMap<>();
        List<ShardlightModule> sel = new ArrayList<>();
        for (String id : moduleIds == null ? Set.<String>of() : moduleIds) {
            Optional<ShardlightModule> m = catalog.module(id);
            if (m.isEmpty()) {
                msgs.add(msg(CompatibilityReport.Severity.ERROR, "shardlight.check.unknownModule", String.valueOf(id)));
            }
        }
        for (ShardlightModule m : catalog.modules()) {
            if (moduleIds != null && moduleIds.stream().anyMatch(id -> m.id().equalsIgnoreCase(id))) {
                sel.add(m);
            }
        }
        if (sel.isEmpty()) {
            msgs.add(msg(CompatibilityReport.Severity.INFO, "shardlight.check.none"));
            return new CompatibilityReport(msgs, missing, List.of(), 0);
        }
        LoaderType loader = project.loader == null || project.loader.type == null ? LoaderType.VANILLA : project.loader.type;
        String loaderVersion = project.loader == null ? null : project.loader.version;
        switch (loader) {
            case VANILLA -> msgs.add(msg(CompatibilityReport.Severity.ERROR, "shardlight.check.needsFabric"));
            case FORGE, NEOFORGE -> msgs.add(msg(CompatibilityReport.Severity.ERROR, "shardlight.check.wrongLoader", loader.displayName()));
            case QUILT -> msgs.add(msg(CompatibilityReport.Severity.WARNING, "shardlight.check.quiltUntested"));
            case FABRIC -> {
                String min = sel.stream().map(ShardlightModule::minLoaderVersion).max(ModVersions::compare).orElse("0");
                if (loaderVersion == null || loaderVersion.isBlank()) {
                    msgs.add(msg(CompatibilityReport.Severity.WARNING, "shardlight.check.loaderVersionUnknown", min));
                } else if (ModVersions.compare(loaderVersion, min) < 0) {
                    msgs.add(msg(CompatibilityReport.Severity.ERROR, "shardlight.check.loaderTooOld", loaderVersion, min));
                }
            }
            default -> msgs.add(msg(CompatibilityReport.Severity.ERROR, "shardlight.check.wrongLoader", loader.displayName()));
        }
        int java = 0;
        boolean libraryTrouble = false;
        for (ShardlightModule m : sel) {
            String name = I18n.tr(m.nameKey());
            if (!m.supportsMinecraft(project.minecraftVersion)) {
                msgs.add(msg(CompatibilityReport.Severity.ERROR, "shardlight.check.minecraftUnsupported", name,
                    String.valueOf(project.minecraftVersion), String.join(", ", m.minecraftVersions())));
            }
            try {
                if (library.moduleJar(m.id(), project.minecraftVersion).isEmpty()) {
                    missing.add(m.id());
                    msgs.add(msg(CompatibilityReport.Severity.WARNING, "shardlight.check.missingJar", name));
                }
            } catch (LauncherException e) {
                missing.add(m.id());
                libraryTrouble = true;
                LOG.warning("Couldn't read the Shardlight library: " + e.getMessage());
            }
            java = Math.max(java, m.javaMajor());
            for (ShardlightModule.Dependency d : m.requires()) {
                Optional<ContentEntry> have = findDependency(project, d);
                if (have.isEmpty()) {
                    deps.putIfAbsent(d.coordinate(), d);
                } else if (!have.get().enabled) {
                    String warn = "shardlight.check.dependencyDisabled";
                    if (!containsKey(msgs, warn)) {
                        msgs.add(msg(CompatibilityReport.Severity.WARNING, warn, d.project()));
                    }
                }
            }
            for (ContentEntry other : otherCopies(project, m)) {
                msgs.add(msg(CompatibilityReport.Severity.WARNING, "shardlight.check.otherCopy", name, String.valueOf(other.fileName)));
            }
            for (String w : m.worksBetterWith()) {
                if (sel.stream().noneMatch(s -> s.id().equals(w))) {
                    catalog.module(w).ifPresent(o -> msgs.add(msg(CompatibilityReport.Severity.INFO,
                        "shardlight.check.worksBetterWith", name, I18n.tr(o.nameKey()))));
                }
            }
        }
        if (libraryTrouble) {
            msgs.add(msg(CompatibilityReport.Severity.WARNING, "shardlight.check.libraryUnreadable"));
        }
        for (ShardlightModule.Dependency d : deps.values()) {
            msgs.add(msg(CompatibilityReport.Severity.INFO, "shardlight.check.dependency", d.project(), d.source(),
                d.reasonKey() == null || d.reasonKey().isBlank() ? "" : I18n.tr(d.reasonKey())));
        }
        msgs.add(msg(CompatibilityReport.Severity.INFO, "shardlight.check.java", Integer.toString(java)));
        return new CompatibilityReport(msgs, missing, new ArrayList<>(deps.keySet()), java);
    }

    private static boolean containsKey(List<CompatibilityReport.Message> msgs, String key) {
        return msgs.stream().anyMatch(m -> m.key().equals(key));
    }

    private static CompatibilityReport.Message msg(CompatibilityReport.Severity s, String key, String... args) {
        return new CompatibilityReport.Message(s, key, List.of(args));
    }

    /** the project's entry that provides a catalog dependency (Fabric API by mod id, Modrinth id/slug or CurseForge id) */
    static Optional<ContentEntry> findDependency(Project project, ShardlightModule.Dependency d) {
        if (project.content == null) {
            return Optional.empty();
        }
        boolean fabricApi = FABRIC_API_SLUG.equalsIgnoreCase(d.project()) || FABRIC_API_MODRINTH_ID.equals(d.project());
        for (ContentEntry e : project.content) {
            if (e == null || (e.kind != null && e.kind != ContentKind.MOD)) {
                continue;
            }
            boolean match = d.project().equalsIgnoreCase(String.valueOf(e.projectId)) || d.project().equals(e.modId);
            if (fabricApi) {
                match |= FABRIC_API_SLUG.equals(e.modId) || FABRIC_API_MODRINTH_ID.equals(e.projectId)
                    || FABRIC_API_SLUG.equalsIgnoreCase(String.valueOf(e.projectId))
                    || (e.source == ContentSource.CURSEFORGE && FABRIC_API_CURSEFORGE_ID.equals(e.projectId));
            }
            if (match) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** the same mod installed from somewhere else (Modrinth, a local file) */
    private static List<ContentEntry> otherCopies(Project project, ShardlightModule m) {
        List<ContentEntry> out = new ArrayList<>();
        if (project.content == null) {
            return out;
        }
        for (ContentEntry e : project.content) {
            if (e != null && e.source != ContentSource.SHARDLIGHT && (e.kind == null || e.kind == ContentKind.MOD)
                && (m.modId().equals(e.modId) || (m.modrinthSlug() != null && m.modrinthSlug().equalsIgnoreCase(String.valueOf(e.projectId))))) {
                out.add(e);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- apply

    /**
     * Make the project's Shardlight content match the selection: copy each selected module's newest library
     * jar into {@code mods/} (replacing an older Shardlight jar of that module), delete the jars of
     * deselected modules so their code can't load on the next launch, install the theme's UI pack (and sound
     * pack when the library has one) at the top of {@code options.txt}'s resource packs while removing
     * previously applied Shardlight theme packs ({@code themeId == null} removes them), set {@code theme} in
     * {@code config/shardlight.json} when the UI module is selected, and save the project's
     * {@code shardlightModules}/{@code shardlightTheme}. Throws when the selection fails {@link #check} or a
     * selected module's jar isn't in the library. The library always keeps its originals.
     */
    public ApplyResult apply(Project project, Set<String> moduleIds, String themeId, ProgressListener progress,
                             CancellationToken cancel) throws LauncherException {
        Objects.requireNonNull(project, "project");
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        Set<String> selection = new LinkedHashSet<>();
        for (String id : moduleIds == null ? Set.<String>of() : moduleIds) {
            selection.add(catalog.module(id).orElseThrow(() ->
                new LauncherException(ErrorCode.NOT_FOUND, "shardlight.module.unknown", String.valueOf(id))).id());
        }
        List<ShardlightModule> sel = catalog.modules().stream().filter(m -> selection.contains(m.id())).toList();
        ShardlightTheme theme = null;
        if (themeId != null && !themeId.isBlank()) {
            theme = catalog.theme(themeId).orElseThrow(() ->
                new LauncherException(ErrorCode.NOT_FOUND, "shardlight.theme.unknown", themeId));
        }
        Set<String> selIds = new LinkedHashSet<>();
        sel.forEach(m -> selIds.add(m.id()));

        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            Project proj = projects.get(project.id).orElse(project);
            CompatibilityReport report = check(proj, selIds);
            if (!report.ok()) {
                throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "shardlight.apply.incompatible",
                    report.errors().get(0).text());
            }
            if (!report.missingLibraryItems().isEmpty()) {
                List<String> names = report.missingLibraryItems().stream()
                    .map(id -> catalog.module(id).map(m -> I18n.tr(m.nameKey())).orElse(id)).toList();
                throw new LauncherException(ErrorCode.DEPENDENCY_MISSING, "shardlight.apply.missingJar", String.join(", ", names));
            }
            if (proj.content == null) {
                proj.content = new ArrayList<>();
            }
            Path game = projects.gameDir(proj);
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            report.warnings().forEach(w -> warnings.add(w.text()));
            boolean done = false;
            try {
                Files.createDirectories(game.resolve(GameDirs.MODS));
                Files.createDirectories(game.resolve(GameDirs.RESOURCE_PACKS));
                p.stage("shardlight.stage.modules");
                applyModules(proj, game, sel, added, removed, warnings, p, c);
                p.stage("shardlight.stage.theme");
                applyTheme(proj, game, theme, added, removed, warnings, c);
                if (theme != null && selIds.contains(UI_MODULE)) {
                    setUiTheme(game, theme.id(), warnings);
                }
                proj.shardlightModules = new LinkedHashSet<>(selIds);
                proj.shardlightTheme = theme == null ? null : theme.id();
                done = true;
            } catch (IOException e) {
                throw LauncherException.io(e, game.toString());
            } finally {
                // project.json must describe what is on disk, also after a failure half-way
                Project saved = projects.save(proj);
                project.content = saved.content;
                project.shardlightModules = saved.shardlightModules;
                project.shardlightTheme = saved.shardlightTheme;
                if (!done) {
                    LOG.warning("Shardlight apply for " + proj.id + " stopped part-way; the project lists what was installed");
                }
            }
            LOG.info("Shardlight for " + proj.id + ": modules " + selIds + ", theme " + proj.shardlightTheme
                + "; added " + added + ", removed " + removed);
            return new ApplyResult(added, removed, report.catalogDependencies(), warnings);
        } finally {
            lock.unlock();
        }
    }

    private void applyModules(Project proj, Path game, List<ShardlightModule> sel, List<String> added, List<String> removed,
                              List<String> warnings, ProgressListener progress, CancellationToken cancel) throws LauncherException {
        Path mods = game.resolve(GameDirs.MODS);
        // 1. jars of modules that are no longer selected (or that the catalog no longer knows)
        for (ContentEntry e : List.copyOf(proj.content)) {
            if (!isOurs(e, ContentKind.MOD)) {
                continue;
            }
            Optional<ShardlightModule> m = moduleOf(e);
            if (m.isEmpty() || sel.stream().noneMatch(s -> s.id().equals(m.get().id()))) {
                deleteEntryFiles(mods, e);
                proj.content.remove(e);
                removed.add(e.fileName);
            }
        }
        // 2. the selected modules
        int i = 0;
        for (ShardlightModule m : sel) {
            cancel.check();
            progress.progress("shardlight.stage.modules", I18n.tr(m.nameKey()), i++, sel.size());
            List<ContentEntry> others = otherCopies(proj, m);
            if (!others.isEmpty()) {
                continue; // the check's otherCopy warning already says that copy is kept
            }
            LibraryItem jar = library.moduleJar(m.id(), proj.minecraftVersion).orElseThrow(() ->
                new LauncherException(ErrorCode.DEPENDENCY_MISSING, "shardlight.apply.missingJar", I18n.tr(m.nameKey())));
            List<ContentEntry> ours = proj.content.stream()
                .filter(e -> isOurs(e, ContentKind.MOD) && moduleOf(e).map(x -> x.id().equals(m.id())).orElse(false))
                .toList();
            if (ours.size() == 1 && isCurrent(mods, ours.get(0), jar)) {
                continue;
            }
            for (ContentEntry e : ours) {
                deleteEntryFiles(mods, e);
                proj.content.remove(e);
                removed.add(e.fileName);
            }
            Path target = freeTarget(mods, jar);
            FileOps.copyAtomic(jar.file(), target);
            ContentEntry e = entry(ContentKind.MOD, m.id(), m.modId(), I18n.english(m.nameKey()), jar, target, m.pageUrl());
            proj.content.add(e);
            added.add(e.fileName);
        }
        progress.progress("shardlight.stage.modules", "", sel.size(), sel.size());
    }

    private void applyTheme(Project proj, Path game, ShardlightTheme theme, List<String> added, List<String> removed,
                            List<String> warnings, CancellationToken cancel) throws LauncherException {
        Path packsDir = game.resolve(GameDirs.RESOURCE_PACKS);
        List<LibraryItem> wanted = new ArrayList<>();
        if (theme != null) {
            Optional<LibraryItem> ui = library.themePack(theme.id(), false);
            if (ui.isPresent()) {
                wanted.add(ui.get());
            } else {
                warnings.add(I18n.tr("shardlight.apply.packMissing", theme.displayName(), theme.uiPackFile()));
            }
            library.themePack(theme.id(), true).ifPresent(wanted::add);
        }
        Path optionsFile = game.resolve(GameDirs.OPTIONS);
        ReentrantLock optionsLock = AtomicFiles.lockFor(optionsFile);
        optionsLock.lock();
        try {
            OptionsTxt options = OptionsTxt.read(optionsFile);
            boolean hadList = options.get(OPTIONS_PACKS).isPresent();
            boolean hadIncompatible = options.get(OPTIONS_INCOMPATIBLE).isPresent();
            List<String> packs = options.getList(OPTIONS_PACKS);
            List<String> incompatible = options.getList(OPTIONS_INCOMPATIBLE);
            List<String> packsBefore = List.copyOf(packs);
            List<String> incompatibleBefore = List.copyOf(incompatible);
            // library item id -> file name in resourcepacks/, for the packs that end up installed
            Map<String, String> installed = new LinkedHashMap<>();
            for (ContentEntry e : List.copyOf(proj.content)) {
                if (!isOurs(e, ContentKind.RESOURCE_PACK)) {
                    continue;
                }
                Optional<LibraryItem> same = wanted.stream()
                    .filter(w -> !installed.containsKey(w.id()) && isCurrent(packsDir, e, w)).findFirst();
                if (same.isPresent()) {
                    installed.put(same.get().id(), e.fileName);
                    continue;
                }
                deleteEntryFiles(packsDir, e);
                proj.content.remove(e);
                removed.add(e.fileName);
                packs.remove("file/" + e.fileName);
                incompatible.remove("file/" + e.fileName);
            }
            for (LibraryItem item : wanted) {
                if (installed.containsKey(item.id())) {
                    continue;
                }
                cancel.check();
                Path target = freeTarget(packsDir, item);
                FileOps.copyAtomic(item.file(), target);
                String name = target.getFileName().toString();
                String title = name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
                ContentEntry e = entry(ContentKind.RESOURCE_PACK, item.moduleOrThemeId(), null, title, item, target, null);
                proj.content.add(e);
                added.add(name);
                installed.put(item.id(), name);
            }
            // the last entry has the highest priority: the UI pack, then its sound pack, go on top
            for (LibraryItem w : wanted) {
                String n = installed.get(w.id());
                packs.remove("file/" + n);
                packs.add("file/" + n);
            }
            if (!hadList && !packs.isEmpty() && !packs.contains("vanilla")) {
                packs.add(0, "vanilla");
            }
            if (!packs.equals(packsBefore) || !incompatible.equals(incompatibleBefore)) {
                options.setList(OPTIONS_PACKS, packs);
                if (hadIncompatible) {
                    options.setList(OPTIONS_INCOMPATIBLE, incompatible);
                }
                options.write(optionsFile);
            }
        } finally {
            optionsLock.unlock();
        }
    }

    /** set only {@code "theme"} in the UI mod's config, keeping every other key; a broken file is left alone */
    private void setUiTheme(Path game, String themeId, List<String> warnings) throws LauncherException {
        ShardlightModule ui = catalog.module(UI_MODULE).orElseThrow();
        Path file = PathSafety.resolveInside(game, ui.configFile());
        ReentrantLock l = AtomicFiles.lockFor(file);
        l.lock();
        try {
            JsonObject o;
            try {
                o = ModConfigFiles.read(file);
            } catch (LauncherException e) {
                if (e.code() != ErrorCode.INVALID_DATA) {
                    throw e;
                }
                warnings.add(I18n.tr("shardlight.apply.configUnreadable", ui.configFile()));
                return;
            }
            JsonElement cur = o.get("theme");
            if (cur == null || !cur.isJsonPrimitive() || !themeId.equals(cur.getAsString())) {
                o.addProperty("theme", themeId);
                ModConfigFiles.write(file, o);
            }
        } finally {
            l.unlock();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isOurs(ContentEntry e, ContentKind kind) {
        return e != null && e.source == ContentSource.SHARDLIGHT && e.kind == kind;
    }

    private Optional<ShardlightModule> moduleOf(ContentEntry e) {
        Optional<ShardlightModule> m = catalog.module(e.projectId);
        return m.isPresent() ? m : catalog.moduleByModId(e.modId);
    }

    /** the entry is this library item, enabled, and its file is intact */
    private static boolean isCurrent(Path dir, ContentEntry e, LibraryItem item) {
        if (!e.enabled || e.fileName == null || !item.id().equals(e.versionId) || !PathSafety.isSafeFileName(e.fileName)) {
            return false;
        }
        return Hashing.matches(dir.resolve(e.fileName), HashAlgo.SHA1, e.sha1);
    }

    /** a target path for a library file that doesn't overwrite anything the user put there */
    private static Path freeTarget(Path dir, LibraryItem item) throws LauncherException {
        String name = item.fileName();
        Path target = PathSafety.resolveInside(dir, name);
        if (Files.exists(target) || Files.exists(dir.resolve(name + GameDirs.DISABLED_SUFFIX))) {
            int dot = name.lastIndexOf('.');
            String suffix = "-shardlight-" + item.sha256().substring(0, 8);
            name = dot > 0 ? name.substring(0, dot) + suffix + name.substring(dot) : name + suffix;
            target = PathSafety.resolveInside(dir, name);
        }
        return target;
    }

    /** delete an entry's file (and its disabled variant); an unsafe recorded name deletes nothing */
    private static void deleteEntryFiles(Path dir, ContentEntry e) throws LauncherException {
        if (e.fileName == null || !PathSafety.isSafeFileName(e.fileName)) {
            LOG.warning("Not deleting a Shardlight entry with an unsafe file name: " + e.fileName);
            return;
        }
        Path f = PathSafety.resolveInside(dir, e.fileName);
        FileOps.delete(f);
        FileOps.delete(f.resolveSibling(f.getFileName() + GameDirs.DISABLED_SUFFIX));
    }

    private static ContentEntry entry(ContentKind kind, String projectId, String modId, String title, LibraryItem item,
                                      Path file, String pageUrl) throws LauncherException {
        ContentEntry e = new ContentEntry();
        e.id = UUID.randomUUID().toString();
        e.kind = kind;
        e.source = ContentSource.SHARDLIGHT;
        e.projectId = projectId;
        e.versionId = item.id();
        e.versionNumber = item.version();
        e.title = title;
        e.modId = modId;
        e.fileName = file.getFileName().toString();
        try {
            e.sha1 = Hashing.hash(file, HashAlgo.SHA1);
            e.sha512 = Hashing.hash(file, HashAlgo.SHA512);
            e.size = Files.size(file);
            e.modifiedAt = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            throw LauncherException.io(ex, file.toString());
        }
        e.enabled = true;
        e.installedAt = Instant.now().toString();
        e.pageUrl = pageUrl;
        return e;
    }

    /** the module ids of a project's selection that the catalog knows, in catalog order */
    public List<String> knownModules(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (ShardlightModule m : catalog.modules()) {
            if (ids != null && ids.stream().anyMatch(id -> m.id().equalsIgnoreCase(id))) {
                out.add(m.id());
            }
        }
        return out;
    }

    /** the launcher's data folders (for callers that need the content root) */
    public AppPaths paths() {
        return paths;
    }
}
