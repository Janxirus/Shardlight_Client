package dev.shardlight.client.storage;

import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.NewProjectRequest;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Counting disk use, and the rules that decide what clean-up may delete. */
class DiskUsageTest {
    @TempDir
    Path dir;
    AppPaths paths;
    ProjectService projects;
    DiskUsage usage;

    @BeforeEach
    void setUp() throws Exception {
        paths = new AppPaths(dir.resolve("data"));
        paths.createDirectories();
        projects = new ProjectService(paths);
        usage = new DiskUsage(paths, projects, projects::list);
        version("26.3", null, "java-runtime-epsilon", 1000);
        version("fabric-loader-0.19.5-26.3", "26.3", null, 100);
        version("1.20.1", null, "java-runtime-gamma", 700);
        version("1.16.5", null, null, 500);
        runtime("java-runtime-epsilon", 3000);
        runtime("java-runtime-gamma", 2000);
        runtime("jre-legacy", 1000);
    }

    void version(String id, String inherits, String runtime, int bytes) throws IOException {
        Path v = Files.createDirectories(paths.versions().resolve(id));
        String json = "{\"id\":\"" + id + "\"" + (inherits == null ? "" : ",\"inheritsFrom\":\"" + inherits + "\"")
            + (runtime == null ? "" : ",\"javaVersion\":{\"component\":\"" + runtime + "\",\"majorVersion\":21}")
            + ",\"mainClass\":\"x\"}";
        Files.writeString(v.resolve(id + ".json"), json);
        if (inherits == null) {
            Files.write(v.resolve(id + ".jar"), new byte[bytes]);
        }
    }

    void runtime(String component, int bytes) throws IOException {
        Path r = Files.createDirectories(paths.runtimes().resolve(component).resolve("linux"));
        Files.write(r.resolve("libjvm.so"), new byte[bytes]);
    }

    Project project(String name, String mc, LoaderType loader, String installed) throws Exception {
        Project p = projects.create(new NewProjectRequest(name, null, null, mc,
            loader == LoaderType.VANILLA ? LoaderSpec.vanilla() : new LoaderSpec(loader, "0.19.5"), null, null, null, null));
        if (installed != null) {
            p = projects.update(p.id, x -> x.installedVersionId = installed);
        }
        return p;
    }

    Set<String> names(DiskUsage.CleanupPlan plan, DiskUsage.Kind kind) {
        return plan.items().stream().filter(i -> i.kind() == kind).map(DiskUsage.CleanupItem::name).collect(Collectors.toSet());
    }

    // ---- counting ---------------------------------------------------------------------------------------

    @Test
    void reportCountsEachProjectAndEachSharedFolder() throws Exception {
        Project a = project("Alpha", "26.3", LoaderType.FABRIC, "fabric-loader-0.19.5-26.3");
        Files.write(projects.gameDir(a).resolve("mods/m.jar"), new byte[4000]);
        Path drive = Files.createDirectories(dir.resolve("drive"));
        Project b = projects.create(NewProjectRequest.vanilla("Beta", "26.3"), drive);
        Files.write(projects.gameDir(b).resolve("saves/big.dat"), new byte[9000]);
        Files.write(paths.assets().resolve("obj"), new byte[123]);
        Files.createDirectories(paths.libraries());
        Files.write(paths.libraries().resolve("lib.jar"), new byte[77]);
        Files.write(paths.files().resolve("blob"), new byte[55]);
        Files.write(paths.cache().resolve("tmp"), new byte[5]);

        DiskUsage.Report r = usage.report(null);

        assertEquals(2, r.projects().size());
        assertEquals("Beta", r.projects().get(0).name(), "largest first");
        assertEquals(9000, r.projects().get(0).externalBytes());
        assertEquals(drive.resolve("beta").toString(), r.projects().get(0).externalPath());
        assertTrue(r.projects().get(0).dataBytes() < 9000, "the folder outside is not counted twice");
        assertTrue(r.projects().get(1).dataBytes() >= 4000);
        assertEquals(0, r.projects().get(1).externalBytes());
        assertEquals(123, bytes(r, DiskUsage.Cache.ASSETS));
        assertEquals(77, bytes(r, DiskUsage.Cache.LIBRARIES));
        assertEquals(60, bytes(r, DiskUsage.Cache.DOWNLOADS), "downloads are the file cache plus the metadata cache");
        assertTrue(bytes(r, DiskUsage.Cache.VERSIONS) >= 2200);
        assertEquals(6000, bytes(r, DiskUsage.Cache.RUNTIMES));
        long sum = r.projects().stream().mapToLong(DiskUsage.ProjectUsage::totalBytes).sum() + r.caches().stream().mapToLong(DiskUsage.CacheUsage::bytes).sum();
        assertEquals(sum, r.totalBytes());
        assertTrue(r.freeBytes() > 0);
    }

    private static long bytes(DiskUsage.Report r, DiskUsage.Cache c) {
        return r.caches().stream().filter(x -> x.cache() == c).findFirst().orElseThrow().bytes();
    }

    // ---- clean-up rules ---------------------------------------------------------------------------------

    @Test
    void withNoProjectsEveryVersionAndRuntimeIsUnused() throws Exception {
        DiskUsage.CleanupPlan plan = usage.planCleanup();
        assertEquals(Set.of("26.3", "fabric-loader-0.19.5-26.3", "1.20.1", "1.16.5"), names(plan, DiskUsage.Kind.VERSION));
        assertEquals(Set.of("java-runtime-epsilon", "java-runtime-gamma", "jre-legacy"), names(plan, DiskUsage.Kind.RUNTIME));
    }

    @Test
    void anInstalledProjectKeepsItsVersionWhatItBuildsOnAndItsRuntime() throws Exception {
        project("Alpha", "26.3", LoaderType.FABRIC, "fabric-loader-0.19.5-26.3");
        DiskUsage.CleanupPlan plan = usage.planCleanup();
        assertEquals(Set.of("1.20.1", "1.16.5"), names(plan, DiskUsage.Kind.VERSION), "26.3 stays because the loader version builds on it");
        assertEquals(Set.of("java-runtime-gamma", "jre-legacy"), names(plan, DiskUsage.Kind.RUNTIME),
            "the loader version has no javaVersion, so it takes the one of 26.3");
        assertFalse(plan.runtimesKept());
        assertTrue(plan.totalBytes() >= 700 + 500 + 2000 + 1000);
    }

    @Test
    void aProjectNotInstalledYetStillKeepsTheVersionItNames() throws Exception {
        project("Fresh", "1.20.1", LoaderType.VANILLA, null);
        DiskUsage.CleanupPlan plan = usage.planCleanup();
        assertFalse(names(plan, DiskUsage.Kind.VERSION).contains("1.20.1"));
        assertFalse(names(plan, DiskUsage.Kind.RUNTIME).contains("java-runtime-gamma"));
    }

    @Test
    void aLoaderVersionThatBuildsOnAProjectsMinecraftVersionIsKept() throws Exception {
        project("Pending", "26.3", LoaderType.FABRIC, null);
        Set<String> removable = names(usage.planCleanup(), DiskUsage.Kind.VERSION);
        assertFalse(removable.contains("26.3"));
        assertFalse(removable.contains("fabric-loader-0.19.5-26.3"));
        project("Other loader", "26.3", LoaderType.FORGE, null);
        assertFalse(names(usage.planCleanup(), DiskUsage.Kind.VERSION).contains("26.3"));
    }

    @Test
    void aProjectsOwnJavaInsideTheRuntimesFolderKeepsThatRuntime() throws Exception {
        Project p = project("Custom java", "26.3", LoaderType.VANILLA, "26.3");
        projects.update(p.id, x -> x.java.javaPath = paths.runtimes().resolve("jre-legacy").resolve("linux").resolve("bin").resolve("java").toString());
        Set<String> removable = names(usage.planCleanup(), DiskUsage.Kind.RUNTIME);
        assertFalse(removable.contains("jre-legacy"));
        assertFalse(removable.contains("java-runtime-epsilon"));
        assertTrue(removable.contains("java-runtime-gamma"));
    }

    @Test
    void ifAKeptVersionCannotBeReadNoRuntimeIsOfferedForDeletion() throws Exception {
        project("Alpha", "26.3", LoaderType.VANILLA, "26.3");
        Files.writeString(paths.versions().resolve("26.3/26.3.json"), "{ this is not json");
        DiskUsage.CleanupPlan plan = usage.planCleanup();
        assertTrue(plan.runtimesKept());
        assertTrue(names(plan, DiskUsage.Kind.RUNTIME).isEmpty(), "can't tell what it needs, so nothing is offered");
        assertFalse(names(plan, DiskUsage.Kind.VERSION).contains("26.3"));
    }

    @Test
    void cleanupDeletesOnlyThePlannedUnusedItems() throws Exception {
        project("Alpha", "26.3", LoaderType.FABRIC, "fabric-loader-0.19.5-26.3");
        DiskUsage.CleanupPlan plan = usage.planCleanup();
        DiskUsage.CleanupResult result = usage.cleanup(plan, null, null);
        assertTrue(result.failed().isEmpty());
        assertEquals(plan.totalBytes(), result.freedBytes());
        assertFalse(Files.exists(paths.versions().resolve("1.20.1")));
        assertFalse(Files.exists(paths.versions().resolve("1.16.5")));
        assertFalse(Files.exists(paths.runtimes().resolve("java-runtime-gamma")));
        assertFalse(Files.exists(paths.runtimes().resolve("jre-legacy")));
        assertTrue(Files.isRegularFile(paths.versions().resolve("26.3/26.3.jar")));
        assertTrue(Files.isRegularFile(paths.versions().resolve("fabric-loader-0.19.5-26.3/fabric-loader-0.19.5-26.3.json")));
        assertTrue(Files.isRegularFile(paths.runtimes().resolve("java-runtime-epsilon/linux/libjvm.so")));
        assertTrue(usage.planCleanup().items().isEmpty(), "nothing more to remove");
    }

    @Test
    void aProjectInstalledAfterThePlanWasMadeIsNotHarmed() throws Exception {
        DiskUsage.CleanupPlan stale = usage.planCleanup(); // everything looks unused
        project("Late", "1.20.1", LoaderType.VANILLA, "1.20.1");
        DiskUsage.CleanupResult result = usage.cleanup(stale, null, null);
        assertTrue(Files.isRegularFile(paths.versions().resolve("1.20.1/1.20.1.jar")), "now used, so skipped");
        assertTrue(Files.isRegularFile(paths.runtimes().resolve("java-runtime-gamma/linux/libjvm.so")));
        assertTrue(result.failed().isEmpty(), "skipping isn't a failure");
        assertFalse(Files.exists(paths.versions().resolve("1.16.5")));
        assertFalse(result.removed().stream().anyMatch(i -> i.name().equals("1.20.1")));
    }

    @Test
    void cleanupNeverDeletesAnythingOutsideTheVersionsAndRuntimesFolders() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("data/projects/precious"));
        Files.writeString(outside.resolve("world.dat"), "keep me");
        DiskUsage.CleanupPlan forged = new DiskUsage.CleanupPlan(List.of(
            new DiskUsage.CleanupItem(DiskUsage.Kind.VERSION, "..", outside, 7),
            new DiskUsage.CleanupItem(DiskUsage.Kind.VERSION, "precious", outside, 7)), false);
        DiskUsage.CleanupResult r = usage.cleanup(forged, null, null);
        assertTrue(Files.isRegularFile(outside.resolve("world.dat")));
        assertTrue(r.removed().isEmpty());
    }
}
