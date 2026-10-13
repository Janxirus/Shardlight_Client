package dev.shardlight.client.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How much disk the launcher uses, and what can safely be removed. Space is counted per project (its folder in the
 * data folder plus a game directory kept elsewhere) and per shared cache. Clean-up only ever offers two things:
 * Minecraft versions and Java runtimes that no project needs. What a project needs is worked out from each project's
 * installed version, its Minecraft version and loader, what those versions inherit from, and the Java runtime each
 * kept version asks for; whenever that can't be worked out for sure, the item is kept.
 */
public final class DiskUsage {
    private static final Logger LOG = Logger.getLogger(DiskUsage.class.getName());
    /** the runtime component Mojang's launcher assumes when a version doesn't name one */
    static final String LEGACY_RUNTIME = "jre-legacy";

    /** the shared folders that are counted */
    public enum Cache { ASSETS, LIBRARIES, VERSIONS, RUNTIMES, DOWNLOADS, SHARDLIGHT_CONTENT, BACKUPS, TRASH, LOGS }

    public record ProjectUsage(String projectId, String name, long dataBytes, long externalBytes, String externalPath) {
        public long totalBytes() {
            return dataBytes + externalBytes;
        }
    }

    public record CacheUsage(Cache cache, long bytes) {
    }

    /**
     * @param projects   largest first
     * @param caches     every shared folder, in the order of {@link Cache}
     * @param freeBytes  free space on the drive holding the data folder, or -1 when unknown
     */
    public record Report(List<ProjectUsage> projects, List<CacheUsage> caches, long totalBytes, long freeBytes) {
    }

    public enum Kind { VERSION, RUNTIME }

    public record CleanupItem(Kind kind, String name, Path path, long bytes) {
    }

    /**
     * @param items          what would be deleted
     * @param runtimesKept   true when Java runtimes were left alone because a kept version's needs couldn't be read
     */
    public record CleanupPlan(List<CleanupItem> items, boolean runtimesKept) {
        public CleanupPlan {
            items = List.copyOf(items);
        }

        public long totalBytes() {
            return items.stream().mapToLong(CleanupItem::bytes).sum();
        }
    }

    public record CleanupResult(List<CleanupItem> removed, List<CleanupItem> failed) {
        public long freedBytes() {
            return removed.stream().mapToLong(CleanupItem::bytes).sum();
        }
    }

    private final AppPaths paths;
    private final ProjectAccess access;
    private final Supplier<List<Project>> projects;

    public DiskUsage(AppPaths paths, ProjectAccess access, Supplier<List<Project>> projects) {
        this.paths = paths;
        this.access = access;
        this.projects = projects;
    }

    // ================================================================== counting

    public Report report(CancellationToken cancel) throws LauncherException {
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        List<ProjectUsage> usage = new ArrayList<>();
        for (Project p : projects.get()) {
            ct.check();
            long data = sizeOf(paths.project(p.id), ct);
            long ext = 0;
            String where = null;
            if (p.gameDirLocation != null && !p.gameDirLocation.isBlank()) {
                ext = sizeOf(access.gameDir(p), ct);
                where = p.gameDirLocation;
            }
            usage.add(new ProjectUsage(p.id, p.name, data, ext, where));
        }
        usage.sort(Comparator.comparingLong(ProjectUsage::totalBytes).reversed().thenComparing(ProjectUsage::name, String.CASE_INSENSITIVE_ORDER));
        List<CacheUsage> caches = new ArrayList<>();
        long total = 0;
        for (Cache c : Cache.values()) {
            ct.check();
            long b = switch (c) {
                case ASSETS -> sizeOf(paths.assets(), ct);
                case LIBRARIES -> sizeOf(paths.libraries(), ct);
                case VERSIONS -> sizeOf(paths.versions(), ct);
                case RUNTIMES -> sizeOf(paths.runtimes(), ct);
                case DOWNLOADS -> sizeOf(paths.files(), ct) + sizeOf(paths.cache(), ct);
                case SHARDLIGHT_CONTENT -> sizeOf(paths.content(), ct);
                case BACKUPS -> sizeOf(paths.backups(), ct);
                case TRASH -> sizeOf(paths.trash(), ct);
                case LOGS -> sizeOf(paths.logs(), ct);
            };
            caches.add(new CacheUsage(c, b));
            total += b;
        }
        for (ProjectUsage u : usage) {
            total += u.totalBytes();
        }
        return new Report(usage, caches, total, freeBytes());
    }

    private long freeBytes() {
        Path probe = paths.root();
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        try {
            return probe == null ? -1 : Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    /** size of a folder or file in bytes, without following links; missing = 0 */
    static long sizeOf(Path root, CancellationToken ct) throws LauncherException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long[] sum = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        sum[0] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    try {
                        ct.check();
                    } catch (LauncherException e) {
                        throw new CancelledWalk(e);
                    }
                    return attrs.isSymbolicLink() || attrs.isOther() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (CancelledWalk c) {
            throw c.cause;
        } catch (IOException e) {
            LOG.log(Level.FINE, "Couldn't count " + root, e);
        }
        return sum[0];
    }

    private static final class CancelledWalk extends IOException {
        final LauncherException cause;

        CancelledWalk(LauncherException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    // ================================================================== clean-up

    /** what could be removed right now; changes nothing */
    public CleanupPlan planCleanup() throws LauncherException {
        List<Project> all = projects.get();
        TreeMap<String, JsonObject> versions = readVersions();
        Set<String> keep = usedVersions(all, versions);
        List<CleanupItem> items = new ArrayList<>();
        for (String id : versions.keySet()) {
            if (!keep.contains(id.toLowerCase(Locale.ROOT))) {
                Path dir = paths.versions().resolve(id);
                items.add(new CleanupItem(Kind.VERSION, id, dir, sizeOf(dir, CancellationToken.NONE)));
            }
        }
        boolean runtimesKept = false;
        Set<String> neededRuntimes = new HashSet<>();
        for (String id : keep) {
            JsonObject json = versions.get(id);
            if (json == null) {
                // a kept version that isn't installed yet: its runtime is fetched when it is installed
                continue;
            }
            String component = runtimeOf(json, versions);
            if (component == null) {
                runtimesKept = true;
            } else {
                neededRuntimes.add(component);
            }
        }
        if (!runtimesKept) {
            for (Project p : all) {
                String javaPath = p.java == null ? null : p.java.javaPath;
                if (javaPath != null) {
                    Path jp = Path.of(javaPath).toAbsolutePath().normalize();
                    for (Path rt : children(paths.runtimes())) {
                        if (jp.startsWith(rt.toAbsolutePath().normalize())) {
                            neededRuntimes.add(rt.getFileName().toString());
                        }
                    }
                }
            }
            for (Path rt : children(paths.runtimes())) {
                String name = rt.getFileName().toString();
                if (Files.isDirectory(rt, LinkOption.NOFOLLOW_LINKS) && !neededRuntimes.contains(name)) {
                    items.add(new CleanupItem(Kind.RUNTIME, name, rt, sizeOf(rt, CancellationToken.NONE)));
                }
            }
        }
        items.sort(Comparator.comparing(CleanupItem::kind).thenComparing(CleanupItem::name, String.CASE_INSENSITIVE_ORDER));
        return new CleanupPlan(items, runtimesKept);
    }

    /**
     * Delete what {@code plan} lists, but only the items a fresh look still finds unused (a project may have been
     * installed since the plan was made). Items that can't be deleted (a file in use) are reported, not retried.
     */
    public CleanupResult cleanup(CleanupPlan plan, ProgressListener progress, CancellationToken cancel) throws LauncherException {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        CancellationToken ct = cancel == null ? CancellationToken.NONE : cancel;
        Set<Path> stillUnused = new HashSet<>();
        for (CleanupItem i : planCleanup().items()) {
            stillUnused.add(i.path().toAbsolutePath().normalize());
        }
        List<CleanupItem> removed = new ArrayList<>();
        List<CleanupItem> failed = new ArrayList<>();
        int n = 0;
        for (CleanupItem item : plan.items()) {
            ct.check();
            pl.progress("storage.cleanup.stage", item.name(), n++, plan.items().size());
            Path p = item.path().toAbsolutePath().normalize();
            if (!stillUnused.contains(p)) {
                continue; // in use now: skipped on purpose, not a failure
            }
            Path base = item.kind() == Kind.VERSION ? paths.versions() : paths.runtimes();
            try {
                deleteChild(base, p);
                removed.add(item);
            } catch (LauncherException e) {
                LOG.log(Level.WARNING, "Couldn't delete " + p, e);
                failed.add(item);
            }
        }
        return new CleanupResult(removed, failed);
    }

    /** delete {@code target}, which must be a direct child folder of {@code base}; links are removed, never followed */
    private static void deleteChild(Path base, Path target) throws LauncherException {
        Path b = base.toAbsolutePath().normalize();
        if (!b.equals(target.getParent())) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", target.toString());
        }
        try {
            deleteRecursive(target);
        } catch (IOException e) {
            throw LauncherException.io(e, target.toString());
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteRecursive(c);
                }
            }
        }
        try {
            Files.deleteIfExists(p);
        } catch (java.nio.file.AccessDeniedException e) {
            p.toFile().setWritable(true);
            Files.deleteIfExists(p);
        }
    }

    // ---- what is in use -----------------------------------------------------------------------------------

    private TreeMap<String, JsonObject> readVersions() {
        TreeMap<String, JsonObject> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Path dir : children(paths.versions())) {
            String id = dir.getFileName().toString();
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            JsonObject json = null;
            Path file = dir.resolve(id + ".json");
            try {
                if (Files.isRegularFile(file)) {
                    JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                    json = e.isJsonObject() ? e.getAsJsonObject() : null;
                }
            } catch (IOException | RuntimeException e) {
                LOG.fine("Unreadable version file " + file + ": " + e.getMessage());
            }
            out.put(id, json == null ? new JsonObject() : json);
        }
        return out;
    }

    /**
     * The versions to keep: those a project installed, those named like a project's Minecraft version, a loader's
     * version that builds on a project's Minecraft version, and everything those inherit from.
     */
    private Set<String> usedVersions(List<Project> all, TreeMap<String, JsonObject> versions) {
        Set<String> keep = new HashSet<>();
        for (Project p : all) {
            addIfInstalled(keep, versions, p.installedVersionId);
            addIfInstalled(keep, versions, p.minecraftVersion);
            if (p.minecraftVersion != null && p.loader != null && !p.loader.isVanilla()) {
                String loader = p.loader.type.name().toLowerCase(Locale.ROOT);
                for (var e : versions.entrySet()) {
                    String inherits = str(e.getValue(), "inheritsFrom");
                    if (p.minecraftVersion.equalsIgnoreCase(inherits) && e.getKey().toLowerCase(Locale.ROOT).contains(loader)) {
                        keep.add(e.getKey().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String id : new ArrayList<>(keep)) {
                String parent = str(versions.get(id), "inheritsFrom");
                if (parent != null && versions.containsKey(parent) && keep.add(parent.toLowerCase(Locale.ROOT))) {
                    grew = true;
                }
            }
        }
        return keep;
    }

    private static void addIfInstalled(Set<String> keep, TreeMap<String, JsonObject> versions, String id) {
        if (id != null && versions.containsKey(id)) {
            keep.add(id.toLowerCase(Locale.ROOT));
        }
    }

    /** the Mojang runtime component a version needs, following inheritance; null when it can't be told */
    private static String runtimeOf(JsonObject json, TreeMap<String, JsonObject> versions) {
        JsonObject cur = json;
        for (int depth = 0; depth < 8 && cur != null; depth++) {
            JsonElement jv = cur.get("javaVersion");
            if (jv != null && jv.isJsonObject()) {
                String c = str(jv.getAsJsonObject(), "component");
                if (c != null) {
                    return c;
                }
            }
            String parent = str(cur, "inheritsFrom");
            if (parent == null) {
                // a complete version file without javaVersion is an old one
                return cur.size() == 0 ? null : LEGACY_RUNTIME;
            }
            cur = versions.get(parent);
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException e) {
            LOG.fine("Couldn't list " + dir + ": " + e.getMessage());
        }
        return out;
    }
}
