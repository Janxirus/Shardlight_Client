package dev.shardlight.client.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads crash reports and game output and recognises the common reasons Minecraft fails to start or crashes:
 * missing or wrong-version mods (Fabric, Quilt, Forge and NeoForge wording), mods that can't be used together,
 * a Java that is too old, running out of memory, bad Java options, mods that fail to patch the game (Mixin),
 * duplicate and damaged files, graphics driver problems and native crashes. Every finding names a next step.
 * Nothing here guesses: when no known pattern matches, the result says so instead of inventing a cause.
 */
public final class CrashAnalyzer {
    /** findings beyond this are left out (the first ones are the most useful) */
    public static final int MAX_FINDINGS = 8;
    /** only the end of very large logs is read */
    static final int MAX_READ_BYTES = 2 * 1024 * 1024;

    private static final String MOD_REF = "(?:mod )?(?:'([^']+)' \\(([^)]+)\\)|([A-Za-z0-9_.\\-]+))";
    private static final Pattern FABRIC_MISSING = Pattern.compile(
        "Mod '([^']+)' \\(([^)]+)\\) \\S+ requires (.+?) of " + MOD_REF + ", which is missing");
    private static final Pattern FABRIC_WRONG = Pattern.compile(
        "Mod '([^']+)' \\(([^)]+)\\) \\S+ requires (.+?) of " + MOD_REF + ", but only the wrong version is present: ([^!\\r\\n]+)");
    private static final Pattern FABRIC_INCOMPATIBLE = Pattern.compile(
        "Mod '([^']+)' \\(([^)]+)\\) \\S+ is incompatible with .+? of " + MOD_REF + ", but a matching version is present");
    private static final Pattern FABRIC_OLD_MISSING = Pattern.compile(
        "Could not find required mod: (\\S+) requires \\{(\\S+) @ \\[([^\\]]*)\\]\\}");
    private static final Pattern FORGE_DEPENDENCY = Pattern.compile(
        "Mod ID: '([^']+)', Requested by: '([^']+)', Expected range: '([^']*)', Actual version: '([^']*)'");
    private static final Pattern CLASS_VERSION = Pattern.compile(
        "class file version (\\d+)(?:\\.\\d+)?\\), this version of the Java Runtime only recognizes class file versions up to (\\d+)");
    private static final Pattern OUT_OF_MEMORY = Pattern.compile("java\\.lang\\.OutOfMemoryError(?::\\s*([^\\r\\n]+))?");
    private static final Pattern HEAP_RESERVE = Pattern.compile(
        "Could not reserve enough space for .*?object heap|Invalid (?:maximum|initial) heap size|Initial heap size set to a larger value than the maximum heap size");
    private static final Pattern BAD_JVM_OPTION = Pattern.compile(
        "Unrecognized option: (\\S+)|Unrecognized VM option '([^']+)'|Improperly specified VM option '([^']+)'");
    private static final Pattern JVM_NOT_CREATED = Pattern.compile("Could not create the Java Virtual Machine");
    private static final Pattern MIXIN_FROM_MOD = Pattern.compile(
        "(?:Mixin apply for mod ([A-Za-z0-9_\\-]+) failed|Mixin \\[[^\\]]+\\] from mod ([A-Za-z0-9_\\-]+)(?: failed| FAILED)?)");
    private static final Pattern MIXIN_CONFIG = Pattern.compile(
        "(?:MixinApplyError|MixinTransformerError|InvalidInjectionException|InvalidMixinException|MixinPreProcessorException)"
            + "[^\\r\\n]*?(?:Mixin \\[|in config \\[|from config \\[|@Mixin target in |mixin config )?([A-Za-z0-9_\\-]+)\\.mixins?\\.json");
    private static final Pattern MIXIN_ANY = Pattern.compile(
        "MixinApplyError|MixinTransformerError|InvalidInjectionException|InvalidMixinException");
    private static final Pattern DUPLICATE = Pattern.compile(
        "(?i)[^\\r\\n]*(?:found duplicate mods?|duplicate mods? (?:found|detected)|multiple versions of the same mod"
            + "|is (?:provided|loaded) (?:by|from) multiple)[^\\r\\n]*");
    private static final Pattern DAMAGED_ZIP = Pattern.compile(
        "zip END header not found|invalid LOC header|error in opening zip file|ZipException: (?:invalid|zip file is empty)"
            + "|Zip file is empty|invalid CEN header|java\\.util\\.zip\\.ZipError");
    private static final Pattern JAR_NAME = Pattern.compile("([A-Za-z0-9_.+\\-\\[\\]]+\\.jar)");
    private static final Pattern GRAPHICS = Pattern.compile(
        "Pixel format not accelerated|GLFW error 6554[23]|WGL: The driver does not appear to support OpenGL"
            + "|Couldn't set pixel format|No OpenGL context found in the current thread|OpenGL \\d\\.\\d is not supported"
            + "|Failed to create the GLFW window|GLX: Failed to create context|Failed to initialize OpenGL");
    private static final Pattern FATAL_JVM = Pattern.compile("A fatal error has been detected by the Java Runtime Environment");
    private static final Pattern PROBLEMATIC_FRAME = Pattern.compile("Problematic frame:\\s*\\r?\\n#\\s*\\S+\\s+\\[([^+\\]]+)");
    private static final Pattern DRIVER_LIBRARY = Pattern.compile(
        "(?i)atio6axx|atioglxx|amdvlk|aticfx|nvoglv|nvd3dum|libnvidia|ig\\d*icd|igxelp|iris_dri|radeonsi|i965_dri|swrast_dri|opengl32");
    private static final Pattern MISSING_CLASS = Pattern.compile(
        "(?:NoClassDefFoundError|ClassNotFoundException): ([A-Za-z_$][\\w$]*(?:[./][A-Za-z_$][\\w$]*)+)");
    private static final Pattern SUSPECTED = Pattern.compile("Suspected Mods?:\\s*([^\\r\\n]+)");
    private static final Pattern DESCRIPTION = Pattern.compile("(?m)^Description: ([^\\r\\n]+)");
    private static final Pattern EXCEPTION_LINE = Pattern.compile("(?m)^([A-Za-z_$][\\w$.]*(?:Exception|Error)(?::[^\\r\\n]*)?)$");

    /** Windows exit codes that mean the Java process itself crashed (access violation, stack buffer overrun) */
    private static final List<Integer> NATIVE_CRASH_CODES = List.of(-1073741819, -1073740791, -1073741571, -1073740940);

    /**
     * Analyse how a game session ended.
     *
     * @param exitCode    the process exit code
     * @param crashReport the crash report or Java fatal error log of this session, or null
     * @param output      the last lines the game printed (may be empty)
     * @return findings, most serious first; when the game crashed and nothing was recognised, one finding
     *         that says so with the exit code
     */
    public List<Finding> analyze(int exitCode, String crashReport, List<String> output) {
        StringBuilder text = new StringBuilder();
        if (crashReport != null) {
            text.append(crashReport).append('\n');
        }
        if (output != null) {
            for (String line : output) {
                text.append(line).append('\n');
            }
        }
        Map<String, Finding> found = new LinkedHashMap<>();
        collect(text.toString(), found);
        if (crashReport != null) {
            describe(crashReport, found);
        }
        if (NATIVE_CRASH_CODES.contains(exitCode) && !found.containsKey("graphics") && !found.containsKey("nativeCrash")) {
            add(found, "nativeCrash", new Finding(Severity.ERROR, "diagnostics.nativeCrash.title",
                "diagnostics.nativeCrash.detail", List.of(), null));
        }
        boolean explained = found.values().stream().anyMatch(f -> f.severity() != Severity.INFO);
        if (!explained && exitCode != 0) {
            add(found, "exitCode", new Finding(Severity.INFO, "diagnostics.unknown.title", "diagnostics.unknown.detail",
                List.of(Integer.toString(exitCode)), null));
        }
        return sorted(found);
    }

    /** analyse any log or crash report text */
    public List<Finding> analyze(String text) {
        Map<String, Finding> found = new LinkedHashMap<>();
        if (text != null) {
            collect(text, found);
            describe(text, found);
        }
        return sorted(found);
    }

    /** analyse a log or crash report file (only its last {@value #MAX_READ_BYTES} bytes when it is larger) */
    public List<Finding> analyze(Path file) throws IOException {
        return analyze(readTail(file));
    }

    /** the end of a text file, at most {@value #MAX_READ_BYTES} bytes, decoded leniently as UTF-8 */
    public static String readTail(Path file) throws IOException {
        long size = Files.size(file);
        try (InputStream in = Files.newInputStream(file)) {
            if (size > MAX_READ_BYTES) {
                in.skipNBytes(size - MAX_READ_BYTES);
            }
            return new String(in.readNBytes(MAX_READ_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static void collect(String text, Map<String, Finding> found) {
        Matcher m = FABRIC_MISSING.matcher(text);
        while (m.find()) {
            String mod = m.group(1);
            String depName = m.group(4) != null ? m.group(4) : m.group(6);
            String depId = m.group(5) != null ? m.group(5) : m.group(6);
            dependency(found, mod, m.group(2), depName, depId, m.group(3), null);
        }
        m = FABRIC_WRONG.matcher(text);
        while (m.find()) {
            String depName = m.group(4) != null ? m.group(4) : m.group(6);
            String depId = m.group(5) != null ? m.group(5) : m.group(6);
            dependency(found, m.group(1), m.group(2), depName, depId, m.group(3), m.group(7).trim());
        }
        m = FABRIC_INCOMPATIBLE.matcher(text);
        while (m.find()) {
            String other = m.group(3) != null ? m.group(3) : m.group(5);
            add(found, "incompatible|" + m.group(2) + "|" + other, new Finding(Severity.ERROR,
                "diagnostics.incompatible.title", "diagnostics.incompatible.detail", List.of(m.group(1), other), m.group(2)));
        }
        m = FABRIC_OLD_MISSING.matcher(text);
        while (m.find()) {
            dependency(found, m.group(1), m.group(1), m.group(2), m.group(2), rangeText(m.group(3)), null);
        }
        m = FORGE_DEPENDENCY.matcher(text);
        while (m.find()) {
            String actual = m.group(4);
            boolean missing = actual.isBlank() || actual.contains("MISSING");
            dependency(found, m.group(2), m.group(2), m.group(1), m.group(1), rangeText(m.group(3)), missing ? null : actual);
        }
        m = CLASS_VERSION.matcher(text);
        if (m.find()) {
            int needed = Integer.parseInt(m.group(1)) - 44;
            int current = Integer.parseInt(m.group(2)) - 44;
            add(found, "java", new Finding(Severity.ERROR, "diagnostics.java.title", "diagnostics.java.detail",
                List.of(Integer.toString(needed), Integer.toString(current)), null));
        }
        m = OUT_OF_MEMORY.matcher(text);
        if (m.find()) {
            String kind = m.group(1) == null ? "" : m.group(1).trim();
            add(found, "memory", new Finding(Severity.ERROR, "diagnostics.memory.title", "diagnostics.memory.detail",
                List.of(kind), null));
        }
        if (HEAP_RESERVE.matcher(text).find()) {
            add(found, "heap", new Finding(Severity.ERROR, "diagnostics.heap.title", "diagnostics.heap.detail", List.of(), null));
        }
        m = BAD_JVM_OPTION.matcher(text);
        if (m.find()) {
            String option = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            add(found, "jvmOption", new Finding(Severity.ERROR, "diagnostics.jvmOption.title", "diagnostics.jvmOption.detail",
                List.of(option), null));
        } else if (JVM_NOT_CREATED.matcher(text).find() && !found.containsKey("heap")) {
            add(found, "jvm", new Finding(Severity.ERROR, "diagnostics.jvm.title", "diagnostics.jvm.detail", List.of(), null));
        }
        mixins(text, found);
        m = DUPLICATE.matcher(text);
        if (m.find()) {
            add(found, "duplicate", new Finding(Severity.ERROR, "diagnostics.duplicate.title", "diagnostics.duplicate.detail",
                List.of(shorten(m.group().trim())), null));
        }
        m = DAMAGED_ZIP.matcher(text);
        if (m.find()) {
            String jar = nearbyJar(text, m.start());
            add(found, "damaged", jar == null
                ? new Finding(Severity.ERROR, "diagnostics.damaged.title", "diagnostics.damaged.detailUnknown", List.of(), null)
                : new Finding(Severity.ERROR, "diagnostics.damaged.title", "diagnostics.damaged.detail", List.of(jar), null));
        }
        boolean graphics = GRAPHICS.matcher(text).find();
        if (FATAL_JVM.matcher(text).find()) {
            Matcher frame = PROBLEMATIC_FRAME.matcher(text);
            String library = frame.find() ? frame.group(1).trim() : null;
            if (library != null && DRIVER_LIBRARY.matcher(library).find()) {
                graphics = true;
            } else {
                add(found, "nativeCrash", library == null
                    ? new Finding(Severity.ERROR, "diagnostics.nativeCrash.title", "diagnostics.nativeCrash.detail", List.of(), null)
                    : new Finding(Severity.ERROR, "diagnostics.nativeCrash.title", "diagnostics.nativeCrash.detailLibrary",
                        List.of(library), null));
            }
        }
        if (graphics) {
            add(found, "graphics", new Finding(Severity.ERROR, "diagnostics.graphics.title", "diagnostics.graphics.detail",
                List.of(), null));
        }
        m = MISSING_CLASS.matcher(text);
        while (m.find()) {
            String cls = m.group(1).replace('/', '.');
            if (cls.startsWith("net.fabricmc.fabric.")) {
                if (!found.containsKey("missing|fabric-api")) {
                    add(found, "missingClass|fabric-api", new Finding(Severity.ERROR, "diagnostics.fabricApi.title",
                        "diagnostics.fabricApi.detail", List.of(), "fabric-api"));
                }
            } else if (!cls.startsWith("java.") && !cls.startsWith("javax.") && !found.containsKey("missingClass")) {
                add(found, "missingClass", new Finding(Severity.WARNING, "diagnostics.missingClass.title",
                    "diagnostics.missingClass.detail", List.of(cls), null));
            }
        }
        m = SUSPECTED.matcher(text);
        if (m.find()) {
            String mods = m.group(1).trim();
            String lower = mods.toLowerCase(Locale.ROOT);
            if (!mods.isEmpty() && !lower.equals("none") && !lower.equals("minecraft (minecraft)") && !lower.equals("minecraft")) {
                add(found, "suspected", new Finding(Severity.WARNING, "diagnostics.suspected.title",
                    "diagnostics.suspected.detail", List.of(shorten(mods)), null));
            }
        }
    }

    /** a requirement of {@code mod} on {@code depId} that is missing ({@code present == null}) or the wrong version */
    private static void dependency(Map<String, Finding> found, String mod, String modId, String depName, String depId,
                                   String requirement, String present) {
        String id = depId.toLowerCase(Locale.ROOT);
        String req = requirement == null ? "" : requirement.trim();
        if (id.equals("java")) {
            String needed = firstNumber(req);
            add(found, "java", new Finding(Severity.ERROR, "diagnostics.java.title", "diagnostics.javaMod.detail",
                List.of(mod, needed == null ? req : needed, present == null ? "" : present), modId));
            return;
        }
        if (id.equals("fabricloader") || id.equals("quilt_loader") || id.equals("forge") || id.equals("neoforge")) {
            add(found, "loader|" + modId, new Finding(Severity.ERROR, "diagnostics.loader.title", "diagnostics.loader.detail",
                List.of(mod, depName, req, present == null ? "" : present), modId));
            return;
        }
        if (present == null) {
            add(found, "missing|" + id, new Finding(Severity.ERROR, "diagnostics.missing.title", "diagnostics.missing.detail",
                List.of(mod, depName, req), depId));
        } else {
            add(found, "wrong|" + modId + "|" + id, new Finding(Severity.ERROR, "diagnostics.wrong.title",
                "diagnostics.wrong.detail", List.of(mod, depName, req, present), modId));
        }
    }

    private static void mixins(String text, Map<String, Finding> found) {
        Matcher m = MIXIN_FROM_MOD.matcher(text);
        while (m.find()) {
            String mod = m.group(1) != null ? m.group(1) : m.group(2);
            int lineEnd = text.indexOf('\n', m.end());
            String line = text.substring(m.start(), lineEnd < 0 ? text.length() : lineEnd);
            if (m.group(1) != null || line.toLowerCase(Locale.ROOT).contains("fail")) {
                add(found, "mixin|" + mod, new Finding(Severity.ERROR, "diagnostics.mixin.title", "diagnostics.mixin.detail",
                    List.of(mod), mod));
                return;
            }
        }
        m = MIXIN_CONFIG.matcher(text);
        if (m.find()) {
            add(found, "mixin|" + m.group(1), new Finding(Severity.ERROR, "diagnostics.mixin.title", "diagnostics.mixin.detail",
                List.of(m.group(1)), null));
        } else if (MIXIN_ANY.matcher(text).find()) {
            add(found, "mixin", new Finding(Severity.ERROR, "diagnostics.mixin.title", "diagnostics.mixin.detailUnknown",
                List.of(), null));
        }
    }

    /** the crash report's own one-line description and its first exception, as background */
    private static void describe(String report, Map<String, Finding> found) {
        Matcher d = DESCRIPTION.matcher(report);
        if (!d.find()) {
            return;
        }
        Matcher e = EXCEPTION_LINE.matcher(report);
        String exception = e.find(d.end()) ? shorten(e.group(1).trim()) : "";
        add(found, "description", new Finding(Severity.INFO, "diagnostics.report.title", "diagnostics.report.detail",
            List.of(shorten(d.group(1).trim()), exception), null));
    }

    private static String nearbyJar(String text, int at) {
        int from = text.lastIndexOf('\n', Math.max(0, at - 1));
        from = text.lastIndexOf('\n', Math.max(0, from - 1));
        int to = at;
        for (int i = 0; i < 3 && to >= 0; i++) {
            to = text.indexOf('\n', to + 1);
        }
        String around = text.substring(Math.max(0, from), to < 0 ? text.length() : to);
        Matcher j = JAR_NAME.matcher(around);
        return j.find() ? j.group(1) : null;
    }

    /** Forge-style version ranges as text ({@code [1.2,)} is "1.2 or newer") */
    private static String rangeText(String range) {
        String r = range == null ? "" : range.trim();
        Matcher open = Pattern.compile("^\\[([^,\\]]+),\\s*\\)$").matcher(r);
        if (open.matches()) {
            return open.group(1) + "+";
        }
        return r.isEmpty() || r.equals("*") ? "*" : r;
    }

    private static String firstNumber(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static void add(Map<String, Finding> found, String key, Finding f) {
        if (found.size() < MAX_FINDINGS * 2) {
            found.putIfAbsent(key, f);
        }
    }

    private static List<Finding> sorted(Map<String, Finding> found) {
        List<Finding> all = new ArrayList<>(found.values());
        all.sort(Comparator.comparingInt(f -> -f.severity().ordinal()));
        return List.copyOf(all.subList(0, Math.min(all.size(), MAX_FINDINGS)));
    }
}
