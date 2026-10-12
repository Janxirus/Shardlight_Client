package dev.shardlight.client.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.OptionsTxt;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.preset.SettingDef.Kind;
import dev.shardlight.client.preset.SettingDef.Target;
import dev.shardlight.client.project.PresetState;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ProjectAccess;
import dev.shardlight.client.shardlight.FileOps;
import dev.shardlight.client.shardlight.ModConfigFiles;
import dev.shardlight.client.shardlight.ProjectLocks;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The FPS-first preset engine. A preset is a table of values for the settings in {@link PresetCatalog};
 * applying it writes only those keys into the config file of each Shardlight module the project has
 * selected (and, with the project's opt-in, Minecraft's video options in {@code options.txt}). Every other
 * key, JVM arguments and the mods themselves are never touched. Each written file is backed up first to
 * {@code config/shardlight/backups/<file>.<stamp>.bak} (newest 10 kept) and every written key is recorded
 * with its previous value in {@code config/shardlight/launcher-preset.json}, so {@link #revertLast} can put
 * things back.
 *
 * <p>No frame-rate numbers have been measured for these presets yet; see {@code docs/PRESETS.md} for the
 * measurement method. Blocking and thread-safe; work on one project is serialized with
 * {@link ProjectLocks}.
 */
public final class PresetEngine {
    /** the launcher-preset.json format this launcher writes (see schemas/launcher-preset.schema.json) */
    public static final int RECORD_SCHEMA_VERSION = 1;
    /** the record of the last apply, relative to the game directory */
    public static final String RECORD_FILE = "config/shardlight/launcher-preset.json";
    /** where config files are backed up before a preset writes them */
    public static final String BACKUP_DIR = "config/shardlight/backups";
    /** backups kept per config file */
    public static final int KEEP_BACKUPS = 10;
    /** i18n key of the Ultra Quality overhead warning */
    public static final String ULTRA_QUALITY_WARNING_KEY = "preset.ultra_quality.warning";

    private static final Logger LOG = Logger.getLogger(PresetEngine.class.getName());
    /** backup stamps in UTC, so names sort by time even across daylight-saving changes */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    /** the last stamp handed out; stamps only grow, so backups made in the same millisecond still sort */
    private static final AtomicLong LAST_STAMP = new AtomicLong();

    private final ProjectAccess projects;

    public PresetEngine(ProjectAccess projects) {
        this.projects = Objects.requireNonNull(projects);
    }

    // ---------------------------------------------------------------- catalog

    /** every setting a preset can control */
    public List<SettingDef> settings() {
        return PresetCatalog.settings();
    }

    /** one setting by key */
    public Optional<SettingDef> setting(String key) {
        return PresetCatalog.setting(key);
    }

    /** the settings that apply to this project: those of its selected modules, plus vanilla video when opted in */
    public List<SettingDef> activeSettings(Project project) {
        List<Target> targets = activeTargets(project);
        return settings().stream().filter(d -> targets.contains(d.target())).toList();
    }

    /** the full value table of a preset; CUSTOM without a project is BALANCED */
    public Map<String, Object> values(Preset preset) {
        return PresetCatalog.values(preset);
    }

    /** the full value table of a preset for a project: CUSTOM is BALANCED overlaid with the project's overrides */
    public Map<String, Object> values(Project project, Preset preset) {
        return values(project, preset, new ArrayList<>());
    }

    /** the warning to show before applying a preset (Ultra Quality's overhead), if any */
    public Optional<String> warningKey(Preset preset) {
        return preset == Preset.ULTRA_QUALITY ? Optional.of(ULTRA_QUALITY_WARNING_KEY) : Optional.empty();
    }

    private Map<String, Object> values(Project project, Preset preset, List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>(PresetCatalog.values(preset));
        if (preset == Preset.CUSTOM && project != null) {
            for (Map.Entry<String, Object> e : state(project).customOverrides.entrySet()) {
                Optional<SettingDef> d = setting(e.getKey());
                if (d.isPresent() && d.get().accepts(e.getValue())) {
                    out.put(e.getKey(), d.get().normalize(e.getValue()));
                } else {
                    warnings.add(I18n.tr("preset.custom.ignoredOverride", e.getKey()));
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    // ---------------------------------------------------------------- preview / apply

    /**
     * Exactly what applying {@code target} would change: one entry per setting whose value in effect differs,
     * for the project's selected modules (and vanilla video when opted in). Empty for a project without
     * Shardlight modules and without the vanilla opt-in. A config file that can't be read is left out, as
     * {@link #apply} would leave it alone.
     */
    public List<SettingChange> preview(Project project, Preset target) throws LauncherException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(target, "target");
        Map<String, Object> vals = values(project, target, new ArrayList<>());
        Path game = projects.gameDir(project);
        List<SettingChange> out = new ArrayList<>();
        for (Target t : activeTargets(project)) {
            Path file = game.resolve(t.file());
            if (t == Target.VANILLA) {
                if (!Files.exists(file)) {
                    continue;
                }
                OptionsTxt opts = OptionsTxt.read(file);
                for (SettingDef d : defsOf(t)) {
                    Object current = opts.get(d.jsonKey()).map(r -> parseOption(d, r)).orElse(d.modDefault());
                    Object proposed = vals.get(d.key());
                    if (!d.same(current, proposed)) {
                        out.add(new SettingChange(d, current, proposed));
                    }
                }
            } else {
                JsonObject o;
                try {
                    o = ModConfigFiles.read(file);
                } catch (LauncherException e) {
                    if (e.code() == ErrorCode.INVALID_DATA) {
                        continue;
                    }
                    throw e;
                }
                for (SettingDef d : defsOf(t)) {
                    Object current = effective(o.get(d.jsonKey()), d);
                    Object proposed = vals.get(d.key());
                    if (!d.same(current, proposed)) {
                        out.add(new SettingChange(d, current, proposed));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Write a preset: only its controlled keys, into each selected module's own config file (created with
     * just those keys when absent; the mod fills in the rest), plus the vanilla video options when opted in.
     * Backs up every file first, records what was written in {@code launcher-preset.json}, and stores the
     * preset as the project's active/last applied one. A config file that isn't valid JSON is left alone
     * with a warning. Never touches JVM arguments, mods or unrelated settings.
     */
    public ApplyReport apply(Project project, Preset target) throws LauncherException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(target, "target");
        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            PresetState state = state(project);
            List<String> warnings = new ArrayList<>();
            Map<String, Object> vals = values(project, target, warnings);
            warningKey(target).ifPresent(k -> warnings.add(I18n.tr(k)));
            if (state.adaptiveQuality) {
                warnings.add(I18n.tr(AdaptiveQuality.NOT_IMPLEMENTED_KEY));
            }
            Path game = projects.gameDir(project);
            List<Target> targets = activeTargets(project);
            List<SettingChange> applied = new ArrayList<>();
            List<String> files = new ArrayList<>();
            long now = System.currentTimeMillis();
            if (targets.isEmpty()) {
                warnings.add(I18n.tr("preset.apply.nothingToApply"));
            } else {
                String stamp = stamp(now);
                List<RecordChange> changes = new ArrayList<>();
                for (Target t : targets) {
                    Map<String, Object> wanted = new LinkedHashMap<>();
                    for (SettingDef d : defsOf(t)) {
                        wanted.put(d.key(), vals.get(d.key()));
                    }
                    writeTarget(game, t, wanted, stamp, applied, files, changes, warnings);
                }
                writeRecord(game, target, state.lastApplied, project, changes, now, null);
                files.add(RECORD_FILE);
                state.lastApplied = target;
                state.lastAppliedAt = now;
            }
            state.active = target;
            savePresetState(project);
            LOG.info("Preset " + target + " for " + project.id + ": " + applied.size() + " settings changed in " + files);
            return new ApplyReport(target, applied, files, warnings);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Put back the values recorded in {@code launcher-preset.json} by the last apply (or revert, so calling it
     * twice redoes). Only the controlled keys are restored; a key that didn't exist before is removed again.
     */
    public ApplyReport revertLast(Project project) throws LauncherException {
        Objects.requireNonNull(project, "project");
        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            PresetState state = state(project);
            Path game = projects.gameDir(project);
            Path recordFile = game.resolve(RECORD_FILE);
            if (!Files.exists(recordFile)) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "preset.revert.nothing");
            }
            JsonObject rec;
            try {
                rec = ModConfigFiles.read(recordFile);
            } catch (LauncherException e) {
                throw new LauncherException(ErrorCode.INVALID_DATA, e, "preset.revert.unreadable", RECORD_FILE);
            }
            if (!String.valueOf(RECORD_SCHEMA_VERSION).equals(string(rec, "schemaVersion"))) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "preset.revert.unreadable", RECORD_FILE);
            }
            Preset recorded = presetOf(rec.get("preset"));
            Preset before = presetOf(rec.get("previousPreset"));
            List<String> warnings = new ArrayList<>();
            // what to put back, per target, in file order; only known, controlled keys of known files
            Map<Target, Map<String, JsonElement>> restore = new EnumMap<>(Target.class);
            JsonArray changes = rec.has("changes") && rec.get("changes").isJsonArray() ? rec.getAsJsonArray("changes") : new JsonArray();
            for (JsonElement el : changes) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                Optional<SettingDef> d = setting(string(c, "key"));
                if (d.isEmpty() || !d.get().target().file().equals(string(c, "file")) || !d.get().jsonKey().equals(string(c, "jsonKey"))) {
                    warnings.add(I18n.tr("preset.revert.skipped", String.valueOf(string(c, "key"))));
                    continue;
                }
                JsonElement prev = c.get("previous");
                restore.computeIfAbsent(d.get().target(), k -> new LinkedHashMap<>())
                    .put(d.get().key(), prev == null || prev.isJsonNull() ? null : prev);
            }
            List<SettingChange> applied = new ArrayList<>();
            List<String> files = new ArrayList<>();
            List<RecordChange> undo = new ArrayList<>();
            long now = System.currentTimeMillis();
            String stamp = stamp(now);
            for (Map.Entry<Target, Map<String, JsonElement>> e : restore.entrySet()) {
                restoreTarget(game, e.getKey(), e.getValue(), stamp, applied, files, undo, warnings);
            }
            String revertOf = rec.has("appliedAt") && rec.get("appliedAt").isJsonPrimitive() ? rec.get("appliedAt").getAsString() : null;
            writeRecord(game, before, recorded, project, undo, now, revertOf == null ? "" : revertOf);
            files.add(RECORD_FILE);
            if (before != null) {
                state.active = before;
            }
            state.lastApplied = before;
            state.lastAppliedAt = now;
            savePresetState(project);
            LOG.info("Reverted preset " + recorded + " for " + project.id + "; now " + before);
            return new ApplyReport(before, applied, files, warnings);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- custom overrides and options

    /**
     * Remember the user's own value for one setting and switch the project to CUSTOM. Overrides are kept
     * when other presets are applied, so returning to CUSTOM restores them exactly. Apply CUSTOM to write.
     */
    public void setCustomOverride(Project project, String key, Object value) throws LauncherException {
        Objects.requireNonNull(project, "project");
        SettingDef d = setting(key).orElseThrow(() -> new LauncherException(ErrorCode.NOT_FOUND, "preset.setting.unknown", String.valueOf(key)));
        Object v;
        try {
            v = d.normalize(value);
        } catch (IllegalArgumentException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "preset.setting.badValue", I18n.tr(d.labelKey()), String.valueOf(value));
        }
        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            PresetState s = state(project);
            s.customOverrides.put(d.key(), v);
            s.active = Preset.CUSTOM;
            savePresetState(project);
        } finally {
            lock.unlock();
        }
    }

    /** forget the user's value for one setting (it follows Balanced again in CUSTOM); the project stays CUSTOM */
    public void clearCustomOverride(Project project, String key) throws LauncherException {
        Objects.requireNonNull(project, "project");
        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            PresetState s = state(project);
            s.customOverrides.remove(key);
            s.active = Preset.CUSTOM;
            savePresetState(project);
        } finally {
            lock.unlock();
        }
    }

    /** forget every custom value; the project stays CUSTOM (which is then the same as Balanced) */
    public void clearCustomOverrides(Project project) throws LauncherException {
        Objects.requireNonNull(project, "project");
        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            PresetState s = state(project);
            s.customOverrides.clear();
            s.active = Preset.CUSTOM;
            savePresetState(project);
        } finally {
            lock.unlock();
        }
    }

    /** the project's custom values, normalized ({@code setting key -> value}) */
    public Map<String, Object> customOverrides(Project project) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : state(project).customOverrides.entrySet()) {
            setting(e.getKey()).filter(d -> d.accepts(e.getValue())).ifPresent(d -> out.put(d.key(), d.normalize(e.getValue())));
        }
        return out;
    }

    /** turn Minecraft video options on or off for this project's presets (off by default) */
    public void setApplyVanillaVideoSettings(Project project, boolean on) throws LauncherException {
        Objects.requireNonNull(project, "project");
        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            state(project).applyVanillaVideoSettings = on;
            savePresetState(project);
        } finally {
            lock.unlock();
        }
    }

    /** the project's adaptive quality choice; the in-game side isn't implemented yet */
    public AdaptiveQuality adaptiveQuality(Project project) {
        PresetState s = state(project);
        return new AdaptiveQuality(s.adaptiveQuality, clampFps(s.adaptiveTargetFps), false);
    }

    /**
     * Store the adaptive quality choice (recorded in launcher-preset.json on the next apply). It stays off
     * unless the user turns it on; no mod acts on it yet.
     */
    public void setAdaptiveQuality(Project project, boolean enabled, int targetFps) throws LauncherException {
        Objects.requireNonNull(project, "project");
        if (targetFps < AdaptiveQuality.MIN_FPS || targetFps > AdaptiveQuality.MAX_FPS) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "preset.adaptive.badFps", targetFps,
                AdaptiveQuality.MIN_FPS, AdaptiveQuality.MAX_FPS);
        }
        ReentrantLock lock = ProjectLocks.lockFor(project.id);
        lock.lock();
        try {
            PresetState s = state(project);
            s.adaptiveQuality = enabled;
            s.adaptiveTargetFps = targetFps;
            savePresetState(project);
        } finally {
            lock.unlock();
        }
    }

    private static int clampFps(int fps) {
        return Math.max(AdaptiveQuality.MIN_FPS, Math.min(AdaptiveQuality.MAX_FPS, fps));
    }

    // ---------------------------------------------------------------- file work

    private void writeTarget(Path game, Target t, Map<String, Object> wanted, String stamp, List<SettingChange> applied,
                             List<String> files, List<RecordChange> changes, List<String> warnings) throws LauncherException {
        Path file = PathSafety.resolveInside(game, t.file());
        ReentrantLock fl = AtomicFiles.lockFor(file);
        fl.lock();
        try {
            if (t == Target.VANILLA) {
                if (!Files.exists(file)) {
                    // without Minecraft's own options.txt (and its data version) a partial file could be
                    // misread by the game's option upgrades: wait until the game has created it
                    warnings.add(I18n.tr("preset.apply.optionsMissing"));
                    return;
                }
                OptionsTxt opts = OptionsTxt.read(file);
                boolean changed = false;
                for (Map.Entry<String, Object> w : wanted.entrySet()) {
                    SettingDef d = setting(w.getKey()).orElseThrow();
                    Optional<String> raw = opts.get(d.jsonKey());
                    Object current = raw.map(r -> parseOption(d, r)).orElse(d.modDefault());
                    String next = encodeOption(d, w.getValue());
                    if (raw.isEmpty() || !raw.get().equals(next)) {
                        opts.set(d.jsonKey(), next);
                        changed = true;
                    }
                    changes.add(new RecordChange(d, raw.<JsonElement>map(JsonPrimitive::new).orElse(null), new JsonPrimitive(next)));
                    if (!d.same(current, w.getValue())) {
                        applied.add(new SettingChange(d, current, w.getValue()));
                    }
                }
                if (changed) {
                    backup(game, file, stamp);
                    FileOps.write(file, opts.text().getBytes(StandardCharsets.UTF_8));
                    files.add(t.file());
                }
                return;
            }
            JsonObject o;
            try {
                o = ModConfigFiles.read(file);
            } catch (LauncherException e) {
                if (e.code() != ErrorCode.INVALID_DATA) {
                    throw e;
                }
                warnings.add(I18n.tr("preset.apply.configUnreadable", t.file()));
                LOG.warning("Left " + t.file() + " alone: " + e.getMessage());
                return;
            }
            boolean existed = Files.exists(file);
            boolean changed = !existed;
            for (Map.Entry<String, Object> w : wanted.entrySet()) {
                SettingDef d = setting(w.getKey()).orElseThrow();
                JsonElement prev = o.get(d.jsonKey());
                Object current = effective(prev, d);
                JsonElement next = toJson(w.getValue());
                if (prev == null || !prev.equals(next)) {
                    o.add(d.jsonKey(), next);
                    changed = true;
                }
                changes.add(new RecordChange(d, prev == null || prev.isJsonNull() ? null : prev.deepCopy(), next));
                if (!d.same(current, w.getValue())) {
                    applied.add(new SettingChange(d, current, w.getValue()));
                }
            }
            if (changed) {
                if (existed) {
                    backup(game, file, stamp);
                }
                ModConfigFiles.write(file, o);
                files.add(t.file());
            }
        } finally {
            fl.unlock();
        }
    }

    private void restoreTarget(Path game, Target t, Map<String, JsonElement> restore, String stamp, List<SettingChange> applied,
                               List<String> files, List<RecordChange> undo, List<String> warnings) throws LauncherException {
        Path file = PathSafety.resolveInside(game, t.file());
        ReentrantLock fl = AtomicFiles.lockFor(file);
        fl.lock();
        try {
            if (t == Target.VANILLA) {
                if (!Files.exists(file)) {
                    warnings.add(I18n.tr("preset.revert.fileGone", t.file()));
                    return;
                }
                OptionsTxt opts = OptionsTxt.read(file);
                boolean changed = false;
                for (Map.Entry<String, JsonElement> r : restore.entrySet()) {
                    SettingDef d = setting(r.getKey()).orElseThrow();
                    Optional<String> raw = opts.get(d.jsonKey());
                    String back = r.getValue() == null || !r.getValue().isJsonPrimitive() ? null : r.getValue().getAsString();
                    Object current = raw.map(x -> parseOption(d, x)).orElse(d.modDefault());
                    Object restored = back == null ? d.modDefault() : parseOption(d, back);
                    if (back == null ? raw.isPresent() : !raw.map(back::equals).orElse(false)) {
                        if (back == null) {
                            opts.remove(d.jsonKey());
                        } else {
                            opts.set(d.jsonKey(), back);
                        }
                        changed = true;
                    }
                    undo.add(new RecordChange(d, raw.<JsonElement>map(JsonPrimitive::new).orElse(null),
                        back == null ? null : new JsonPrimitive(back)));
                    if (!d.same(current, restored)) {
                        applied.add(new SettingChange(d, current, restored));
                    }
                }
                if (changed) {
                    backup(game, file, stamp);
                    FileOps.write(file, opts.text().getBytes(StandardCharsets.UTF_8));
                    files.add(t.file());
                }
                return;
            }
            JsonObject o;
            try {
                o = ModConfigFiles.read(file);
            } catch (LauncherException e) {
                if (e.code() != ErrorCode.INVALID_DATA) {
                    throw e;
                }
                warnings.add(I18n.tr("preset.apply.configUnreadable", t.file()));
                return;
            }
            boolean existed = Files.exists(file);
            boolean changed = false;
            for (Map.Entry<String, JsonElement> r : restore.entrySet()) {
                SettingDef d = setting(r.getKey()).orElseThrow();
                JsonElement cur = o.get(d.jsonKey());
                JsonElement back = r.getValue();
                Object current = effective(cur, d);
                Object restored = effective(back, d);
                if (back == null ? cur != null : !back.equals(cur)) {
                    if (back == null) {
                        o.remove(d.jsonKey());
                    } else {
                        o.add(d.jsonKey(), back.deepCopy());
                    }
                    changed = true;
                }
                undo.add(new RecordChange(d, cur == null || cur.isJsonNull() ? null : cur.deepCopy(), back == null ? null : back.deepCopy()));
                if (!d.same(current, restored)) {
                    applied.add(new SettingChange(d, current, restored));
                }
            }
            if (changed) {
                if (existed) {
                    backup(game, file, stamp);
                }
                ModConfigFiles.write(file, o);
                files.add(t.file());
            }
        } finally {
            fl.unlock();
        }
    }

    /** a backup stamp for this moment, later than every stamp handed out before in this launcher */
    private static String stamp(long now) {
        long t = LAST_STAMP.updateAndGet(prev -> Math.max(prev + 1, now));
        return STAMP.format(Instant.ofEpochMilli(t));
    }

    /** copy a file to config/shardlight/backups/&lt;name&gt;.&lt;stamp&gt;.bak and keep the newest {@value #KEEP_BACKUPS} */
    private static void backup(Path game, Path file, String stamp) throws LauncherException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Path dir = game.resolve(BACKUP_DIR);
        String base = file.getFileName().toString();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(base + "." + stamp + ".bak");
            for (int i = 1; Files.exists(target); i++) {
                target = dir.resolve(base + "." + stamp + "-" + String.format("%02d", i) + ".bak");
            }
            FileOps.copyAtomic(file, target);
            List<Path> all;
            try (Stream<Path> s = Files.list(dir)) {
                all = s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(base + ".") && n.endsWith(".bak") && Files.isRegularFile(p);
                }).sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList();
            }
            for (int i = KEEP_BACKUPS; i < all.size(); i++) {
                FileOps.delete(all.get(i));
            }
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
    }

    private void writeRecord(Path game, Preset preset, Preset previous, Project project, List<RecordChange> changes,
                             long now, String revertOf) throws LauncherException {
        PresetState s = state(project);
        JsonObject r = new JsonObject();
        r.addProperty("schemaVersion", RECORD_SCHEMA_VERSION);
        r.add("preset", preset == null ? JsonNull.INSTANCE : new JsonPrimitive(preset.name()));
        r.add("previousPreset", previous == null ? JsonNull.INSTANCE : new JsonPrimitive(previous.name()));
        r.addProperty("appliedAt", Instant.ofEpochMilli(now).toString());
        r.addProperty("appliedAtMillis", now);
        r.addProperty("launcherVersion", BuildInfo.VERSION);
        if (revertOf != null) {
            r.addProperty("revertOf", revertOf);
        }
        JsonArray modules = new JsonArray();
        for (Target t : activeTargets(project)) {
            if (t.moduleId() != null) {
                modules.add(t.moduleId());
            }
        }
        r.add("modules", modules);
        r.addProperty("vanillaVideoSettings", s.applyVanillaVideoSettings);
        JsonObject adaptive = new JsonObject();
        adaptive.addProperty("enabled", s.adaptiveQuality);
        adaptive.addProperty("targetFps", clampFps(s.adaptiveTargetFps));
        adaptive.addProperty("implementedInGame", false);
        r.add("adaptiveQuality", adaptive);
        JsonArray list = new JsonArray();
        for (RecordChange c : changes) {
            JsonObject o = new JsonObject();
            o.addProperty("key", c.def().key());
            o.addProperty("target", c.def().target().name());
            o.addProperty("file", c.def().target().file());
            o.addProperty("jsonKey", c.def().jsonKey());
            if (c.previous() != null) {
                o.add("previous", c.previous());
            }
            if (c.value() != null) {
                o.add("value", c.value());
            }
            list.add(o);
        }
        r.add("changes", list);
        Path file = PathSafety.resolveInside(game, RECORD_FILE);
        ModConfigFiles.write(file, r);
    }

    /** one written key: what was there before (null = absent) and what is there now (null = removed) */
    private record RecordChange(SettingDef def, JsonElement previous, JsonElement value) {
    }

    // ---------------------------------------------------------------- values

    private static List<SettingDef> defsOf(Target t) {
        return PresetCatalog.settings().stream().filter(d -> d.target() == t).toList();
    }

    /** the targets of the project's selected modules (catalog order), plus VANILLA with the opt-in */
    private static List<Target> activeTargets(Project project) {
        Set<Target> out = new LinkedHashSet<>();
        Set<String> selected = project.shardlightModules == null ? Set.of() : project.shardlightModules;
        for (Target t : Target.values()) {
            if (t.moduleId() != null && selected.stream().anyMatch(id -> t.moduleId().equalsIgnoreCase(id))) {
                out.add(t);
            }
        }
        if (state(project).applyVanillaVideoSettings) {
            out.add(Target.VANILLA);
        }
        return List.copyOf(out);
    }

    private static PresetState state(Project project) {
        if (project.preset == null) {
            project.preset = new PresetState();
        }
        if (project.preset.customOverrides == null) {
            project.preset.customOverrides = new LinkedHashMap<>();
        }
        if (project.preset.active == null) {
            project.preset.active = Preset.DEFAULT;
        }
        return project.preset;
    }

    /** save the preset state on top of the latest stored project, so other changes to it are kept */
    private void savePresetState(Project project) throws LauncherException {
        Project fresh = projects.get(project.id).orElse(project);
        if (fresh != project) {
            fresh.preset = Json.copy(project.preset, PresetState.class);
        }
        projects.save(fresh);
    }

    /** the value in effect for a JSON config entry: the mod's default when absent or null */
    private static Object effective(JsonElement el, SettingDef d) {
        if (el == null || el.isJsonNull()) {
            return d.modDefault();
        }
        Object raw = javaValue(el);
        try {
            return d.normalize(raw);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private static Object javaValue(JsonElement el) {
        if (!el.isJsonPrimitive()) {
            return el.toString();
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            try {
                BigDecimal b = new BigDecimal(p.getAsString());
                if (b.stripTrailingZeros().scale() <= 0 && b.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0
                    && !p.getAsString().contains(".") && !p.getAsString().toLowerCase(Locale.ROOT).contains("e")) {
                    return b.intValueExact();
                }
                return b.doubleValue();
            } catch (NumberFormatException | ArithmeticException e) {
                return p.getAsDouble();
            }
        }
        return p.getAsString();
    }

    private static JsonElement toJson(Object v) {
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Integer i) {
            return new JsonPrimitive(i);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n.doubleValue());
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    /** an options.txt value as written by Minecraft: booleans and numbers bare, strings as JSON strings */
    static String encodeOption(SettingDef d, Object value) {
        Object v = d.normalize(value);
        return switch (d.kind()) {
            case BOOLEAN -> v.toString();
            case INT -> Integer.toString((Integer) v);
            case DOUBLE -> Double.toString((Double) v);
            case STRING -> Json.COMPACT.toJson((String) v);
        };
    }

    /** parse an options.txt value for a setting; the raw text when it doesn't fit the setting */
    static Object parseOption(SettingDef d, String raw) {
        String s = raw.trim();
        Object v = s;
        if (d.kind() == Kind.STRING && s.startsWith("\"")) {
            try {
                JsonElement e = JsonParser.parseString(s);
                if (e.isJsonPrimitive()) {
                    v = e.getAsString();
                }
            } catch (JsonParseException e) {
                return raw;
            }
        }
        try {
            return d.normalize(v);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private static Preset presetOf(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return Preset.valueOf(e.getAsString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
