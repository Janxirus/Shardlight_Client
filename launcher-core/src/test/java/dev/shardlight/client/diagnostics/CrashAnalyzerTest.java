package dev.shardlight.client.diagnostics;

import dev.shardlight.client.i18n.I18n;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashAnalyzerTest {
    final CrashAnalyzer analyzer = new CrashAnalyzer();

    Finding first(String text) {
        List<Finding> all = analyzer.analyze(text);
        assertFalse(all.isEmpty(), "nothing recognised in: " + text);
        return all.get(0);
    }

    @Test
    void fabricMissingDependency() {
        Finding f = first("""
            net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!
            More details:
            \t - Mod 'Sodium Extra' (sodium-extra) 0.5.1 requires version 0.5.0 or later of mod 'Sodium' (sodium), which is missing!
            """);
        assertEquals("diagnostics.missing.title", f.titleKey());
        assertEquals(List.of("Sodium Extra", "Sodium", "version 0.5.0 or later"), f.args());
        assertEquals("sodium", f.modId());
        assertEquals(Severity.ERROR, f.severity());
    }

    @Test
    void fabricMissingDependencyKnownOnlyById() {
        Finding f = first(" - Mod 'Iris' (iris) 1.7.0 requires any version of fabric-api, which is missing!");
        assertEquals("diagnostics.missing.title", f.titleKey());
        assertEquals(List.of("Iris", "fabric-api", "any version"), f.args());
        assertEquals("fabric-api", f.modId());
    }

    @Test
    void fabricWrongMinecraftVersion() {
        Finding f = first(" - Mod 'Fabric API' (fabric-api) 0.92.2+1.20.1 requires version 1.20.1 of 'Minecraft' (minecraft), "
            + "but only the wrong version is present: 1.21.4!");
        assertEquals("diagnostics.wrong.title", f.titleKey());
        assertEquals(List.of("Fabric API", "Minecraft", "version 1.20.1", "1.21.4"), f.args());
        assertEquals("fabric-api", f.modId(), "the mod to update or remove");
    }

    @Test
    void fabricIncompatibleMods() {
        Finding f = first(" - Mod 'Sodium' (sodium) 0.5.3 is incompatible with any version of mod 'OptiFabric' (optifabric), "
            + "but a matching version is present: 1.13.0!");
        assertEquals("diagnostics.incompatible.title", f.titleKey());
        assertEquals(List.of("Sodium", "OptiFabric"), f.args());
    }

    @Test
    void fabricNeedsNewerLoaderOrJava() {
        List<Finding> all = analyzer.analyze("""
             - Mod 'Shardlight Map' (shardlight_map) 1.5.0 requires version 0.19.5 or later of 'Fabric Loader' (fabricloader), but only the wrong version is present: 0.16.10!
             - Mod 'Shardlight UI' (softglass) 4.1.0 requires version 25 or later of 'Java' (java), but only the wrong version is present: 21!
            """);
        assertEquals("diagnostics.loader.title", all.get(0).titleKey());
        assertEquals(List.of("Shardlight Map", "Fabric Loader", "version 0.19.5 or later", "0.16.10"), all.get(0).args());
        assertEquals("diagnostics.java.title", all.get(1).titleKey());
        assertEquals(List.of("Shardlight UI", "25", "21"), all.get(1).args());
    }

    @Test
    void forgeMissingAndWrongDependencies() {
        List<Finding> all = analyzer.analyze("""
            Missing or unsupported mandatory dependencies:
            \tMod ID: 'geckolib', Requested by: 'mowziesmobs', Expected range: '[4.4.9,)', Actual version: '[MISSING]'
            \tMod ID: 'minecraft', Requested by: 'create', Expected range: '[1.20.1,1.20.2)', Actual version: '1.21.1'
            """);
        assertEquals("diagnostics.missing.title", all.get(0).titleKey());
        assertEquals(List.of("mowziesmobs", "geckolib", "4.4.9+"), all.get(0).args());
        assertEquals("diagnostics.wrong.title", all.get(1).titleKey());
        assertEquals(List.of("create", "minecraft", "[1.20.1,1.20.2)", "1.21.1"), all.get(1).args());
    }

    @Test
    void javaTooOld() {
        Finding f = first("Exception in thread \"main\" java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main "
            + "has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the "
            + "Java Runtime only recognizes class file versions up to 61.0");
        assertEquals("diagnostics.java.title", f.titleKey());
        assertEquals(List.of("21", "17"), f.args());
    }

    @Test
    void memoryProblems() {
        assertEquals("diagnostics.memory.title", first("java.lang.OutOfMemoryError: Java heap space").titleKey());
        assertEquals("diagnostics.heap.title", first("""
            Error occurred during initialization of VM
            Could not reserve enough space for 8388608KB object heap
            """).titleKey());
        assertEquals("diagnostics.heap.title", first("Invalid maximum heap size: -Xmx99999999999G\n"
            + "Error: Could not create the Java Virtual Machine.").titleKey());
    }

    @Test
    void badJavaOption() {
        Finding f = first("Unrecognized option: --illegal-access=permit\nError: Could not create the Java Virtual Machine.");
        assertEquals("diagnostics.jvmOption.title", f.titleKey());
        assertEquals(List.of("--illegal-access=permit"), f.args());
        Finding g = first("Unrecognized VM option 'UseConcMarkSweepGC'\nError: Could not create the Java Virtual Machine.");
        assertEquals(List.of("UseConcMarkSweepGC"), g.args());
    }

    @Test
    void mixinFailures() {
        Finding f = first("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error "
            + "was encountered\nCaused by: org.spongepowered.asm.mixin.throwables.MixinApplyError: Mixin [sodium.mixins.json:"
            + "core.MinecraftMixin] from phase [DEFAULT] in config [sodium.mixins.json] FAILED during APPLY");
        assertEquals("diagnostics.mixin.title", f.titleKey());
        assertEquals(List.of("sodium"), f.args());
        Finding g = first("Mixin apply for mod lithium failed lithium.mixins.json:ai.MobMixin from mod lithium");
        assertEquals(List.of("lithium"), g.args());
        assertEquals("lithium", g.modId());
    }

    @Test
    void damagedJar() {
        Finding f = first("Error reading mods/broken-mod-1.0.jar\njava.util.zip.ZipException: zip END header not found");
        assertEquals("diagnostics.damaged.title", f.titleKey());
        assertEquals(List.of("broken-mod-1.0.jar"), f.args());
    }

    @Test
    void graphicsDriver() {
        assertEquals("diagnostics.graphics.title", first("GLFW error 65542: WGL: The driver does not appear to support OpenGL")
            .titleKey());
        assertEquals("diagnostics.graphics.title", first("""
            # A fatal error has been detected by the Java Runtime Environment:
            #
            #  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffb1c2a8f4d, pid=1234, tid=5678
            #
            # Problematic frame:
            # C  [atio6axx.dll+0x1a8f4d]
            """).titleKey());
    }

    @Test
    void nativeCrashOutsideTheDriver() {
        Finding f = first("""
            # A fatal error has been detected by the Java Runtime Environment:
            # Problematic frame:
            # C  [SomeOverlay64.dll+0x1234]
            """);
        assertEquals("diagnostics.nativeCrash.title", f.titleKey());
        assertEquals(List.of("SomeOverlay64.dll"), f.args());
        List<Finding> byCode = analyzer.analyze(-1073741819, null, List.of("Loading..."));
        assertEquals("diagnostics.nativeCrash.title", byCode.get(0).titleKey());
    }

    @Test
    void missingFabricApiClasses() {
        Finding f = first("java.lang.NoClassDefFoundError: net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents");
        assertEquals("diagnostics.fabricApi.title", f.titleKey());
        assertEquals("fabric-api", f.modId());
        Finding g = first("Caused by: java.lang.ClassNotFoundException: com.example.lib.Thing");
        assertEquals("diagnostics.missingClass.title", g.titleKey());
        assertEquals(Severity.WARNING, g.severity());
    }

    @Test
    void crashReportDescriptionAndSuspects() {
        List<Finding> all = analyzer.analyze("""
            ---- Minecraft Crash Report ----
            // Who set us up the TNT?

            Description: Rendering overlay

            java.lang.NullPointerException: Cannot invoke "Object.toString()" because "x" is null
            \tat com.example.Mod.render(Mod.java:10)

            Suspected Mods:
            \tExample Mod (examplemod), Version: 1.0
            Suspected Mod: Example Mod (examplemod), Version: 1.0
            """);
        assertEquals("diagnostics.suspected.title", all.get(0).titleKey());
        assertEquals(Severity.WARNING, all.get(0).severity());
        Finding report = all.get(all.size() - 1);
        assertEquals("diagnostics.report.title", report.titleKey());
        assertEquals("Rendering overlay", report.args().get(0));
        assertTrue(report.args().get(1).startsWith("java.lang.NullPointerException"));
    }

    @Test
    void unknownCrashSaysSoWithTheExitCode() {
        List<Finding> all = analyzer.analyze(255, null, List.of("[Render thread/INFO]: Loading", "something odd"));
        assertEquals(1, all.size());
        assertEquals("diagnostics.unknown.title", all.get(0).titleKey());
        assertEquals(List.of("255"), all.get(0).args());
        assertTrue(analyzer.analyze(0, null, List.of("Stopping!")).isEmpty(), "a normal exit has nothing to explain");
    }

    @Test
    void ordinaryLogsProduceNothing() {
        assertTrue(analyzer.analyze("""
            [12:00:00] [Render thread/INFO]: Setting user: Steve
            [12:00:01] [Render thread/INFO]: Backend library: LWJGL version 3.3.3
            [12:00:02] [Render thread/INFO]: Checking for duplicate mods is not a thing here
            [12:00:05] [Render thread/INFO]: Stopping!
            """).isEmpty());
    }

    @Test
    void mostSeriousFirstAndCapped() {
        StringBuilder text = new StringBuilder("Description: Boom\njava.lang.RuntimeException: boom\n");
        for (int i = 0; i < 20; i++) {
            text.append(" - Mod 'A").append(i).append("' (a").append(i).append(") 1.0 requires any version of mod 'B")
                .append(i).append("' (b").append(i).append("), which is missing!\n");
        }
        List<Finding> all = analyzer.analyze(text.toString());
        assertEquals(CrashAnalyzer.MAX_FINDINGS, all.size());
        assertTrue(all.stream().allMatch(f -> f.severity() == Severity.ERROR));
    }

    @Test
    void everyFindingHasEnglishText() {
        String[] samples = {
            " - Mod 'A' (a) 1 requires any version of mod 'B' (b), which is missing!",
            "java.lang.OutOfMemoryError: Java heap space",
            "Unrecognized option: -Xfoo",
            "Error: Could not create the Java Virtual Machine.",
            "MixinApplyError: something",
            "Found duplicate mods: Mod ID: 'x' from mod files: a.jar, b.jar",
            "java.util.zip.ZipException: zip END header not found",
            "Pixel format not accelerated",
            "Description: Ticking entity\njava.lang.IllegalStateException: no",
        };
        for (String s : samples) {
            for (Finding f : analyzer.analyze(s)) {
                assertTrue(I18n.has(f.titleKey()), f.titleKey());
                assertTrue(I18n.has(f.detailKey()), f.detailKey());
                assertFalse(I18n.english(f.detailKey(), f.argArray()).contains("{"), f.detailKey());
            }
        }
    }
}
