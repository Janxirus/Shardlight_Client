package dev.shardlight.client.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.OptionsTxt;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.SettingDef.Cost;
import dev.shardlight.client.preset.SettingDef.Kind;
import dev.shardlight.client.preset.SettingDef.Target;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.testutil.TestProjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetEngineTest {
    @TempDir
    Path dir;
    TestProjects projects;
    PresetEngine engine;

    /**
     * The defaults in the mods' config classes (SoftGlassConfig 4.1, MapConfig, PartyConfig, CompanionConfig),
     * copied by hand from their field initializers. Balanced must equal these.
     */
    static final Map<String, Object> MOD_DEFAULTS = new LinkedHashMap<>();

    static {
        Map<String, Object> m = MOD_DEFAULTS;
        m.put("ui.performanceMode", false);
        m.put("ui.reducedEffects", false);
        m.put("ui.menuEmbers", true);
        m.put("ui.themedParticles", true);
        m.put("ui.clickFx", true);
        m.put("ui.buttonFx", true);
        m.put("ui.damageNumbers", true);
        m.put("ui.themeFrames", true);
        m.put("ui.themeDecor", true);
        m.put("ui.rarityTooltips", true);
        m.put("ui.itemRarityBorders", false);
        m.put("ui.reducedMotion", false);
        m.put("ui.animationIntensity", 1.0);
        m.put("ui.screenAnimation", true);
        m.put("ui.screenAnimMillis", 220);
        m.put("ui.hoverAnim", "pop");
        m.put("ui.hoverAnimMillis", 110);
        m.put("ui.hoverAnimIntensity", 1.0);
        m.put("ui.smoothHotbar", true);
        m.put("ui.smoothScroll", true);
        m.put("ui.buttonLift", true);
        m.put("ui.tooltipEaseIn", true);
        m.put("ui.chatSmoothLines", true);
        m.put("ui.joinSlideIn", true);
        m.put("ui.pickupPopups", true);
        m.put("ui.lowHealthVignette", true);
        m.put("ui.durabilityHud", true);
        m.put("ui.armorHud", true);
        m.put("ui.toolWarning", true);
        m.put("ui.elytraWarning", true);
        m.put("ui.foodAirWarnings", true);
        m.put("map.scanSpeed", 6);
        m.put("map.radarRange", 48);
        m.put("map.terrainShading", true);
        m.put("map.biomeColors", true);
        m.put("map.trail", true);
        m.put("map.smoothZoom", true);
        m.put("map.nightTint", true);
        m.put("map.beacons", true);
        m.put("party.shareEvery", 15);
        m.put("party.worldMarkers", true);
        m.put("companion.zoomSmooth", true);
        m.put("companion.centreGuides", true);
    }

    @BeforeEach
    void setUp() {
        projects = new TestProjects(new AppPaths(dir));
        engine = new PresetEngine(projects);
    }

    private Project project(String... modules) throws LauncherException {
        Project p = projects.create("p" + System.nanoTime(), "26.3", LoaderType.FABRIC, "0.19.5");
        p.shardlightModules = new LinkedHashSet<>(List.of(modules));
        return projects.save(p);
    }

    private Path game(Project p) {
        return projects.gameDir(p);
    }

    private JsonObject readJson(Path f) throws Exception {
        return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
    }

    private Project reload(Project p) {
        return projects.get(p.id).orElseThrow();
    }

    // ------------------------------------------------------------------ the value table

    @Test
    void everyPresetValueIsWithinTheModsRange() {
        for (Preset preset : Preset.values()) {
            Map<String, Object> values = engine.values(preset);
            assertEquals(engine.settings().size(), values.size(), preset.name());
            for (SettingDef d : engine.settings()) {
                Object v = values.get(d.key());
                assertTrue(d.accepts(v), d.key() + "=" + v + " in " + preset);
                assertEquals(v, d.normalize(v), "canonical type for " + d.key());
                switch (d.kind()) {
                    case BOOLEAN -> assertTrue(v instanceof Boolean, d.key());
                    case INT -> assertTrue(v instanceof Integer, d.key());
                    case DOUBLE -> assertTrue(v instanceof Double, d.key());
                    case STRING -> assertTrue(v instanceof String, d.key());
                }
            }
        }
        // the sanitize ranges of the mods themselves
        assertRange("ui.screenAnimMillis", 80, 500);
        assertRange("ui.hoverAnimMillis", 40, 400);
        assertRange("ui.animationIntensity", 0, 2);
        assertRange("ui.hoverAnimIntensity", 0, 2);
        assertRange("map.scanSpeed", 1, 32);
        assertRange("map.radarRange", 16, 128);
        assertRange("party.shareEvery", 10, 120);
    }

    private void assertRange(String key, double min, double max) {
        SettingDef d = engine.setting(key).orElseThrow();
        assertEquals(min, d.constraint().min(), key);
        assertEquals(max, d.constraint().max(), key);
        assertFalse(d.accepts(min - 1), key);
        assertFalse(d.accepts(max + 1), key);
    }

    @Test
    void ultraPerformanceTurnsOffEveryHighCostEffectButNoEssential() {
        Map<String, Object> up = engine.values(Preset.ULTRA_PERFORMANCE);
        Map<String, Object> balanced = engine.values(Preset.BALANCED);
        int highCost = 0;
        for (SettingDef d : engine.settings()) {
            if (d.cost() == Cost.HIGH) {
                highCost++;
                if (d.kind() == Kind.BOOLEAN) {
                    assertEquals(false, up.get(d.key()), d.key());
                } else {
                    assertTrue(((Number) up.get(d.key())).doubleValue() < ((Number) balanced.get(d.key())).doubleValue(), d.key());
                }
            }
            if (d.essential()) {
                for (Preset p : Preset.values()) {
                    assertEquals(true, engine.values(p).get(d.key()), d.key() + " must stay on in " + p);
                }
            }
        }
        assertTrue(highCost >= 2);
        List<String> essentials = engine.settings().stream().filter(SettingDef::essential).map(SettingDef::key).toList();
        assertTrue(essentials.containsAll(List.of("ui.lowHealthVignette", "ui.durabilityHud", "ui.armorHud", "ui.toolWarning",
            "ui.elytraWarning", "ui.foodAirWarnings")), essentials.toString());
        // cheapest functional visuals
        assertEquals(true, up.get("ui.performanceMode"));
        assertEquals(true, up.get("ui.reducedMotion"));
        assertEquals("off", up.get("ui.hoverAnim"));
        assertEquals(false, up.get("ui.themeDecor"));
        assertEquals(false, up.get("ui.themeFrames"));
        assertEquals(false, up.get("ui.menuEmbers"));
        assertEquals(false, up.get("map.smoothZoom"));
        assertEquals(false, up.get("map.terrainShading"));
        assertTrue((Integer) up.get("map.scanSpeed") < (Integer) balanced.get("map.scanSpeed"));
        assertTrue((Integer) up.get("party.shareEvery") > (Integer) balanced.get("party.shareEvery"), "share less often");
        // presets move monotonically in cost for the numeric map settings
        assertTrue((Integer) engine.values(Preset.ULTRA_QUALITY).get("map.scanSpeed") >= (Integer) engine.values(Preset.QUALITY).get("map.scanSpeed"));
    }

    @Test
    void balancedEqualsTheModsDefaults() {
        Map<String, Object> balanced = engine.values(Preset.BALANCED);
        Set<String> modKeys = new LinkedHashSet<>();
        for (SettingDef d : engine.settings()) {
            if (d.target() != Target.VANILLA) {
                modKeys.add(d.key());
                assertTrue(MOD_DEFAULTS.containsKey(d.key()), "no recorded default for " + d.key());
                assertEquals(MOD_DEFAULTS.get(d.key()), balanced.get(d.key()), d.key());
                assertEquals(MOD_DEFAULTS.get(d.key()), d.modDefault(), d.key());
            }
        }
        assertEquals(MOD_DEFAULTS.keySet(), modKeys);
        // CUSTOM without overrides is Balanced
        assertEquals(balanced, engine.values(Preset.CUSTOM));
    }

    /**
     * When the mods' sources are next to this checkout (or SHARDLIGHT_MOD_SOURCES points at their parent
     * folder), check every controlled key against the real config classes: the field must exist with the
     * same type and default. Skipped elsewhere.
     */
    @Test
    void controlledKeysExistInTheModsConfigClasses() throws Exception {
        Path root = modSources();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "Shardlight mod sources not available");
        Map<Target, Path> files = Map.of(
            Target.UI, root.resolve("Shardlight_ui/src/client/java/dev/softglass/SoftGlassConfig.java"),
            Target.MAP, root.resolve("Shardlight_map/src/client/java/dev/shardmap/MapConfig.java"),
            Target.PARTY, root.resolve("Shardlight_party/src/client/java/dev/shardparty/PartyConfig.java"),
            Target.COMPANION, root.resolve("Shardlight_Companion/src/client/java/dev/shardcompanion/CompanionConfig.java"));
        for (SettingDef d : engine.settings()) {
            if (d.target() == Target.VANILLA) {
                continue;
            }
            String src = Files.readString(files.get(d.target()));
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("public\\s+(boolean|int|double|String)\\s+" + d.jsonKey() + "\\s*=\\s*([^;]+);").matcher(src);
            assertTrue(m.find(), d.key() + " not found in " + files.get(d.target()).getFileName());
            String type = m.group(1);
            String lit = m.group(2).trim();
            Object def = switch (type) {
                case "boolean" -> Boolean.parseBoolean(lit);
                case "int" -> Integer.parseInt(lit);
                case "double" -> Double.parseDouble(lit);
                default -> lit.substring(1, lit.length() - 1);
            };
            Kind expected = switch (type) {
                case "boolean" -> Kind.BOOLEAN;
                case "int" -> Kind.INT;
                case "double" -> Kind.DOUBLE;
                default -> Kind.STRING;
            };
            assertEquals(expected, d.kind(), d.key());
            assertTrue(d.same(def, d.modDefault()), d.key() + ": mod default " + def + " vs " + d.modDefault());
        }
    }

    static Path modSources() {
        String env = System.getenv("SHARDLIGHT_MOD_SOURCES");
        if (env != null && !env.isBlank() && Files.isDirectory(Path.of(env, "Shardlight_ui"))) {
            return Path.of(env);
        }
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("Shardlight_ui/src/client/java/dev/softglass"))) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ preview / apply

    @Test
    void previewWithoutModulesIsEmptyAndApplyWritesNothing() throws Exception {
        Project p = project();
        for (Preset preset : Preset.values()) {
            assertTrue(engine.preview(p, preset).isEmpty(), preset.name());
        }
        ApplyReport r = engine.apply(p, Preset.ULTRA_PERFORMANCE);
        assertTrue(r.applied().isEmpty());
        assertTrue(r.filesWritten().isEmpty());
        assertTrue(r.warnings().contains(I18n.tr("preset.apply.nothingToApply")), r.warnings().toString());
        assertFalse(Files.exists(game(p).resolve("config/shardlight")));
        assertEquals(Preset.ULTRA_PERFORMANCE, reload(p).preset.active);
        assertNull(reload(p).preset.lastApplied);
    }

    @Test
    void previewListsOnlyDifferencesAndWritesNothing() throws Exception {
        Project p = project("ui");
        assertTrue(engine.preview(p, Preset.BALANCED).isEmpty(), "an absent file means the mod defaults, which Balanced is");
        List<SettingChange> up = engine.preview(p, Preset.ULTRA_PERFORMANCE);
        assertFalse(up.isEmpty());
        assertTrue(up.stream().allMatch(c -> c.def().target() == Target.UI));
        SettingChange embers = up.stream().filter(c -> c.def().key().equals("ui.menuEmbers")).findFirst().orElseThrow();
        assertEquals(true, embers.current());
        assertEquals(false, embers.proposed());
        assertTrue(up.stream().noneMatch(c -> c.def().essential()));
        assertFalse(Files.exists(game(p).resolve("config/shardlight.json")));
    }

    @Test
    void applyWritesOnlyControlledKeysAndPreservesTheRest() throws Exception {
        Project p = project("ui", "map");
        Path ui = game(p).resolve("config/shardlight.json");
        Files.writeString(ui, """
            {
              "configVersion": 4,
              "theme": "FROSTBOUND",
              "chatBoxWidth": 300,
              "menuEmbers": true,
              "mentionWords": "Steve, Alex",
              "futureThing": {"nested": [1, 2.5, "x"], "flag": null}
            }
            """);
        ApplyReport r = engine.apply(p, Preset.ULTRA_PERFORMANCE);
        assertEquals(Preset.ULTRA_PERFORMANCE, r.preset());
        JsonObject o = readJson(ui);
        assertEquals("FROSTBOUND", o.get("theme").getAsString());
        assertEquals(300, o.get("chatBoxWidth").getAsInt());
        assertEquals(4, o.get("configVersion").getAsInt());
        assertEquals("Steve, Alex", o.get("mentionWords").getAsString());
        assertEquals(JsonParser.parseString("{\"nested\": [1, 2.5, \"x\"], \"flag\": null}"), o.get("futureThing"));
        assertFalse(o.get("menuEmbers").getAsBoolean());
        assertTrue(o.get("lowHealthVignette").getAsBoolean());
        assertEquals("off", o.get("hoverAnim").getAsString());
        assertEquals(0.0, o.get("animationIntensity").getAsDouble());
        // every controlled UI key, plus the 6 that were there
        long uiDefs = engine.settings().stream().filter(d -> d.target() == Target.UI).count();
        assertEquals(uiDefs + 5, o.size());

        // the map file didn't exist: created with only the controlled keys
        JsonObject map = readJson(game(p).resolve("config/shardmap.json"));
        assertEquals(engine.settings().stream().filter(d -> d.target() == Target.MAP).count(), map.size());
        assertEquals(2, map.get("scanSpeed").getAsInt());
        // modules not selected and vanilla options are not touched
        assertFalse(Files.exists(game(p).resolve("config/shardparty.json")));
        assertFalse(Files.exists(game(p).resolve("config/shardcompanion.json")));
        assertFalse(Files.exists(game(p).resolve("options.txt")));
        assertTrue(r.filesWritten().containsAll(List.of("config/shardlight.json", "config/shardmap.json", PresetEngine.RECORD_FILE)));

        // a backup of the existing file only
        try (Stream<Path> s = Files.list(game(p).resolve(PresetEngine.BACKUP_DIR))) {
            List<String> names = s.map(x -> x.getFileName().toString()).toList();
            assertEquals(1, names.size(), names.toString());
            assertTrue(names.get(0).startsWith("shardlight.json.") && names.get(0).endsWith(".bak"));
        }

        // the record lists every key with its previous value
        JsonObject rec = readJson(game(p).resolve(PresetEngine.RECORD_FILE));
        assertEquals(1, rec.get("schemaVersion").getAsInt());
        assertEquals("ULTRA_PERFORMANCE", rec.get("preset").getAsString());
        assertTrue(rec.get("previousPreset").isJsonNull());
        assertTrue(rec.has("appliedAt") && rec.has("launcherVersion"));
        JsonObject embers = null;
        for (JsonElement e : rec.getAsJsonArray("changes")) {
            if (e.getAsJsonObject().get("key").getAsString().equals("ui.menuEmbers")) {
                embers = e.getAsJsonObject();
            }
        }
        assertTrue(embers != null);
        assertTrue(embers.get("previous").getAsBoolean());
        assertFalse(embers.get("value").getAsBoolean());
        assertEquals("config/shardlight.json", embers.get("file").getAsString());

        Project stored = reload(p);
        assertEquals(Preset.ULTRA_PERFORMANCE, stored.preset.active);
        assertEquals(Preset.ULTRA_PERFORMANCE, stored.preset.lastApplied);
        assertTrue(stored.preset.lastAppliedAt > 0);
        assertTrue(r.applied().stream().anyMatch(c -> c.def().key().equals("ui.menuEmbers")));
        assertTrue(r.applied().stream().noneMatch(c -> c.def().essential()));
    }

    @Test
    void applyKeepsOtherProjectChanges() throws Exception {
        Project p = project("ui");
        Project other = reload(p);
        other.notes = "edited elsewhere";
        projects.save(other);
        engine.apply(p, Preset.QUALITY);
        assertEquals("edited elsewhere", reload(p).notes);
        assertEquals(Preset.QUALITY, reload(p).preset.active);
    }

    @Test
    void revertLastRestoresAndCanRedo() throws Exception {
        Project p = project("ui");
        Path ui = game(p).resolve("config/shardlight.json");
        String original = "{\n  \"menuEmbers\": true,\n  \"hoverAnim\": \"glow\",\n  \"chatBoxWidth\": 280\n}\n";
        Files.writeString(ui, original);
        engine.apply(p, Preset.ULTRA_PERFORMANCE);
        assertFalse(readJson(ui).get("menuEmbers").getAsBoolean());

        ApplyReport back = engine.revertLast(p);
        assertNull(back.preset(), "nothing was applied before");
        assertEquals(JsonParser.parseString(original), readJson(ui), "only the original keys, with their values");
        assertFalse(back.applied().isEmpty());
        assertTrue(back.filesWritten().contains("config/shardlight.json"));

        // reverting the revert puts the preset back
        ApplyReport redo = engine.revertLast(p);
        assertEquals(Preset.ULTRA_PERFORMANCE, redo.preset());
        assertFalse(readJson(ui).get("menuEmbers").getAsBoolean());
        assertEquals("off", readJson(ui).get("hoverAnim").getAsString());
        assertEquals(280, readJson(ui).get("chatBoxWidth").getAsInt());

        // after a second apply, revert goes back to the first preset
        engine.apply(p, Preset.QUALITY);
        ApplyReport r = engine.revertLast(p);
        assertEquals(Preset.ULTRA_PERFORMANCE, r.preset());
        assertEquals(Preset.ULTRA_PERFORMANCE, reload(p).preset.active);
        assertFalse(readJson(ui).get("menuEmbers").getAsBoolean());
    }

    @Test
    void revertWithoutApplyIsNotFound() throws Exception {
        Project p = project("ui");
        LauncherException e = assertThrows(LauncherException.class, () -> engine.revertLast(p));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
        assertEquals("preset.revert.nothing", e.messageKey());
    }

    @Test
    void tamperedRecordCannotWriteOutsideControlledKeys() throws Exception {
        Project p = project("ui");
        Path rec = game(p).resolve(PresetEngine.RECORD_FILE);
        Files.createDirectories(rec.getParent());
        Files.writeString(rec, """
            {"schemaVersion": 1, "preset": "BALANCED", "previousPreset": null, "appliedAt": "x",
             "changes": [
               {"key": "ui.menuEmbers", "target": "UI", "file": "../../../evil.json", "jsonKey": "menuEmbers", "previous": true},
               {"key": "ui.menuEmbers", "target": "UI", "file": "config/shardlight.json", "jsonKey": "javaArgs", "previous": "-Xmx64g"},
               {"key": "evil.key", "target": "UI", "file": "config/shardlight.json", "jsonKey": "x", "previous": 1},
               {"key": "ui.hoverAnim", "target": "UI", "file": "config/shardlight.json", "jsonKey": "hoverAnim", "previous": "lift"},
               "garbage"
             ]}
            """);
        ApplyReport r = engine.revertLast(p);
        assertEquals(3, r.warnings().size(), r.warnings().toString());
        JsonObject ui = readJson(game(p).resolve("config/shardlight.json"));
        assertEquals(1, ui.size(), ui.toString());
        assertEquals("lift", ui.get("hoverAnim").getAsString());
        assertFalse(Files.exists(dir.resolve("evil.json")));
        assertFalse(Files.exists(game(p).getParent().getParent().resolve("evil.json")));

        Files.writeString(rec, "{\"schemaVersion\": \"two\"}");
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> engine.revertLast(p)).code());
        Files.writeString(rec, "not json");
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> engine.revertLast(p)).code());
    }

    @Test
    void balancedUltraPerformanceBalancedCustomRoundTripKeepsOverrides() throws Exception {
        Project p = project("ui", "map");
        Path ui = game(p).resolve("config/shardlight.json");
        Path map = game(p).resolve("config/shardmap.json");
        engine.setCustomOverride(p, "ui.menuEmbers", false);
        engine.setCustomOverride(p, "map.scanSpeed", 3);
        engine.setCustomOverride(p, "ui.hoverAnim", "lift");
        assertEquals(Preset.CUSTOM, reload(p).preset.active);
        assertFalse(Files.exists(ui), "overrides are stored, not written, until CUSTOM is applied");

        engine.apply(p, Preset.BALANCED);
        assertTrue(readJson(ui).get("menuEmbers").getAsBoolean());
        assertEquals(6, readJson(map).get("scanSpeed").getAsInt());
        engine.apply(p, Preset.ULTRA_PERFORMANCE);
        assertEquals(2, readJson(map).get("scanSpeed").getAsInt());
        engine.apply(p, Preset.BALANCED);
        // work from a fresh copy, as the UI would after a restart
        Project fresh = reload(p);
        assertEquals(Map.of("ui.menuEmbers", false, "map.scanSpeed", 3, "ui.hoverAnim", "lift"), engine.customOverrides(fresh));
        ApplyReport custom = engine.apply(fresh, Preset.CUSTOM);
        assertEquals(Preset.CUSTOM, custom.preset());
        assertFalse(readJson(ui).get("menuEmbers").getAsBoolean());
        assertEquals("lift", readJson(ui).get("hoverAnim").getAsString());
        assertEquals(3, readJson(map).get("scanSpeed").getAsInt());
        assertTrue(readJson(ui).get("themeDecor").getAsBoolean(), "the rest follows Balanced");
        assertEquals(Preset.CUSTOM, reload(p).preset.active);
        assertEquals(3, engine.customOverrides(reload(p)).size());
        assertEquals(engine.values(fresh, Preset.CUSTOM).get("map.scanSpeed"), 3);

        engine.clearCustomOverride(fresh, "map.scanSpeed");
        assertEquals(6, engine.values(reload(p), Preset.CUSTOM).get("map.scanSpeed"));
        engine.clearCustomOverrides(fresh);
        assertEquals(engine.values(Preset.BALANCED), engine.values(reload(p), Preset.CUSTOM));
    }

    @Test
    void invalidOverridesAreRefused() throws Exception {
        Project p = project("ui", "map");
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class, () -> engine.setCustomOverride(p, "ui.nope", true)).code());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class, () -> engine.setCustomOverride(p, "../config", true)).code());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class, () -> engine.setCustomOverride(p, null, true)).code());
        for (Object[] bad : new Object[][]{{"map.scanSpeed", 99}, {"map.scanSpeed", 0}, {"map.scanSpeed", 2.5}, {"map.scanSpeed", "fast"},
            {"ui.hoverAnim", "spin"}, {"ui.menuEmbers", "yes"}, {"ui.menuEmbers", 1}, {"ui.animationIntensity", Double.NaN},
            {"ui.animationIntensity", 3.0}, {"ui.hoverAnim", null}}) {
            LauncherException e = assertThrows(LauncherException.class, () -> engine.setCustomOverride(p, (String) bad[0], bad[1]),
                bad[0] + "=" + bad[1]);
            assertEquals(ErrorCode.INVALID_DATA, e.code());
            assertEquals("preset.setting.badValue", e.messageKey());
        }
        assertTrue(engine.customOverrides(reload(p)).isEmpty());
        // a value hand-edited into project.json that is no longer valid is ignored with a warning
        Project q = reload(p);
        q.preset.customOverrides.put("map.scanSpeed", 500);
        q.preset.customOverrides.put("ui.gone", true);
        projects.save(q);
        ApplyReport r = engine.apply(reload(p), Preset.CUSTOM);
        assertEquals(2, r.warnings().size(), r.warnings().toString());
        assertEquals(6, readJson(game(p).resolve("config/shardmap.json")).get("scanSpeed").getAsInt());
    }

    @Test
    void vanillaVideoSettingsOnlyWithOptIn() throws Exception {
        Project p = project("ui");
        Path options = game(p).resolve("options.txt");
        String original = "version:4189\nrenderDistance:16\ngraphicsPreset:\"fancy\"\nlang:en_us\nresourcePacks:[\"vanilla\"]\n";
        Files.writeString(options, original);
        engine.apply(p, Preset.ULTRA_PERFORMANCE);
        assertEquals(original, Files.readString(options), "untouched without the opt-in");

        engine.setApplyVanillaVideoSettings(p, true);
        assertTrue(reload(p).preset.applyVanillaVideoSettings);
        Project q = reload(p);
        assertTrue(engine.preview(q, Preset.ULTRA_PERFORMANCE).stream().anyMatch(c -> c.def().key().equals("vanilla.renderDistance")));
        ApplyReport r = engine.apply(q, Preset.ULTRA_PERFORMANCE);
        assertTrue(r.filesWritten().contains("options.txt"));
        OptionsTxt o = OptionsTxt.read(options);
        assertEquals("8", o.get("renderDistance").orElseThrow());
        assertEquals("\"custom\"", o.get("graphicsPreset").orElseThrow());
        assertEquals("\"false\"", o.get("renderClouds").orElseThrow());
        assertEquals("0.75", o.get("entityDistanceScaling").orElseThrow());
        assertEquals("false", o.get("enableVsync").orElseThrow());
        assertEquals("en_us", o.get("lang").orElseThrow());
        assertEquals("4189", o.get("version").orElseThrow());
        assertEquals(List.of("vanilla"), o.getList("resourcePacks"));

        engine.revertLast(q);
        OptionsTxt back = OptionsTxt.read(options);
        assertEquals("16", back.get("renderDistance").orElseThrow());
        assertEquals("\"fancy\"", back.get("graphicsPreset").orElseThrow());
        assertTrue(back.get("maxFps").isEmpty(), "keys that weren't there are removed again");
        assertEquals("en_us", back.get("lang").orElseThrow());
    }

    @Test
    void vanillaVideoSettingsAreIgnoredForVersionsWhoseKeysWereNotChecked() throws Exception {
        Project p = projects.create("old" + System.nanoTime(), "1.21.4", LoaderType.FABRIC, "0.16.10");
        Path options = game(p).resolve("options.txt");
        Files.createDirectories(options.getParent());
        String original = "version:4189\nrenderDistance:16\n";
        Files.writeString(options, original);
        engine.setApplyVanillaVideoSettings(p, true);
        assertFalse(engine.vanillaVideoAvailable(reload(p)));
        assertTrue(engine.preview(reload(p), Preset.ULTRA_PERFORMANCE).isEmpty());
        engine.apply(reload(p), Preset.ULTRA_PERFORMANCE);
        assertEquals(original, Files.readString(options), "options.txt of an unchecked version is never touched");
    }

    @Test
    void vanillaSettingsWaitForTheGamesOwnOptionsFile() throws Exception {
        Project p = project();
        engine.setApplyVanillaVideoSettings(p, true);
        ApplyReport r = engine.apply(reload(p), Preset.PERFORMANCE);
        assertTrue(r.warnings().contains(I18n.tr("preset.apply.optionsMissing")), r.warnings().toString());
        assertFalse(Files.exists(game(p).resolve("options.txt")));
        assertTrue(engine.preview(reload(p), Preset.PERFORMANCE).isEmpty());
    }

    @Test
    void unreadableConfigIsLeftAlone() throws Exception {
        Project p = project("ui", "party");
        Path ui = game(p).resolve("config/shardlight.json");
        Files.writeString(ui, "{ \"menuEmbers\": tru");
        ApplyReport r = engine.apply(p, Preset.ULTRA_PERFORMANCE);
        assertEquals("{ \"menuEmbers\": tru", Files.readString(ui));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("config/shardlight.json")), r.warnings().toString());
        assertEquals(30, readJson(game(p).resolve("config/shardparty.json")).get("shareEvery").getAsInt());
        assertTrue(engine.preview(p, Preset.QUALITY).stream().noneMatch(c -> c.def().target() == Target.UI));
        // a file that isn't UTF-8 is not rewritten either
        Files.write(ui, new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'});
        engine.apply(p, Preset.QUALITY);
        assertEquals(10, Files.size(ui));
    }

    @Test
    void keepsTheNewestTenBackups() throws Exception {
        Project p = project("ui");
        Files.writeString(game(p).resolve("config/shardlight.json"), "{}");
        Preset[] cycle = {Preset.ULTRA_PERFORMANCE, Preset.QUALITY};
        for (int i = 0; i < 14; i++) {
            engine.apply(p, cycle[i % 2]);
        }
        try (Stream<Path> s = Files.list(game(p).resolve(PresetEngine.BACKUP_DIR))) {
            List<Path> backups = s.toList();
            assertEquals(PresetEngine.KEEP_BACKUPS, backups.size());
            for (Path b : backups) {
                assertTrue(JsonParser.parseString(Files.readString(b)).isJsonObject());
            }
        }
    }

    @Test
    void ultraQualityCarriesAnOverheadWarning() throws Exception {
        assertEquals(Optional.of(PresetEngine.ULTRA_QUALITY_WARNING_KEY), engine.warningKey(Preset.ULTRA_QUALITY));
        for (Preset p : Preset.values()) {
            if (p != Preset.ULTRA_QUALITY) {
                assertTrue(engine.warningKey(p).isEmpty(), p.name());
            }
        }
        ApplyReport r = engine.apply(project("ui"), Preset.ULTRA_QUALITY);
        assertTrue(r.warnings().contains(I18n.tr(PresetEngine.ULTRA_QUALITY_WARNING_KEY)));
    }

    @Test
    void adaptiveQualityIsOffByDefaultAndNotImplementedInGame() throws Exception {
        Project p = project("ui");
        AdaptiveQuality a = engine.adaptiveQuality(p);
        assertFalse(a.enabled());
        assertFalse(a.implementedInGame());
        assertEquals(60, a.targetFps());
        assertTrue(engine.apply(p, Preset.BALANCED).warnings().isEmpty());
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> engine.setAdaptiveQuality(p, true, 5)).code());
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> engine.setAdaptiveQuality(p, true, 1000)).code());
        engine.setAdaptiveQuality(p, true, 90);
        Project q = reload(p);
        assertEquals(new AdaptiveQuality(true, 90, false), engine.adaptiveQuality(q));
        ApplyReport r = engine.apply(q, Preset.BALANCED);
        assertTrue(r.warnings().contains(I18n.tr(AdaptiveQuality.NOT_IMPLEMENTED_KEY)));
        JsonObject rec = readJson(game(p).resolve(PresetEngine.RECORD_FILE));
        assertTrue(rec.getAsJsonObject("adaptiveQuality").get("enabled").getAsBoolean());
        assertFalse(rec.getAsJsonObject("adaptiveQuality").get("implementedInGame").getAsBoolean());

        // the file the mod will read, as documented in docs/ADAPTIVE_QUALITY.md
        JsonObject file = readJson(game(p).resolve(PresetEngine.ADAPTIVE_FILE));
        assertEquals(1, file.get("schemaVersion").getAsInt());
        assertTrue(file.get("enabled").getAsBoolean());
        assertEquals(90, file.get("targetFps").getAsInt());
        assertTrue(file.get("writtenBy").getAsString().startsWith("Shardlight Client "));
        engine.setAdaptiveQuality(reload(p), false, 90);
        assertFalse(readJson(game(p).resolve(PresetEngine.ADAPTIVE_FILE)).get("enabled").getAsBoolean(),
            "turning it off updates the file the mod reads");
    }

    @Test
    void adaptiveQualityLeavesNoFileWhileItHasNeverBeenTurnedOn() throws Exception {
        Project p = project("ui");
        engine.setAdaptiveQuality(p, false, 60);
        assertFalse(Files.exists(game(p).resolve(PresetEngine.ADAPTIVE_FILE)));
    }

    @Test
    void concurrentAppliesLeaveAConsistentFile() throws Exception {
        Project p = project("ui", "map");
        Files.writeString(game(p).resolve("config/shardlight.json"), "{\"chatBoxWidth\": 250}");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        Preset[] presets = {Preset.ULTRA_PERFORMANCE, Preset.PERFORMANCE, Preset.QUALITY, Preset.ULTRA_QUALITY};
        for (int i = 0; i < 8; i++) {
            Preset preset = presets[i % presets.length];
            Project copy = reload(p);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    engine.apply(copy, preset);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(errors.isEmpty(), errors.toString());
        JsonObject ui = readJson(game(p).resolve("config/shardlight.json"));
        assertEquals(250, ui.get("chatBoxWidth").getAsInt());
        Preset last = reload(p).preset.lastApplied;
        for (SettingDef d : engine.settings()) {
            if (d.target() == Target.UI) {
                assertTrue(d.same(engine.values(last).get(d.key()), PresetEngineTest.value(ui.get(d.jsonKey()))), d.key());
            }
        }
    }

    private static Object value(JsonElement e) {
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return e.getAsDouble();
        }
        return e.getAsString();
    }

    @Test
    void optionsTxtEncoding() {
        SettingDef clouds = engine.setting("vanilla.renderClouds").orElseThrow();
        assertEquals("\"fast\"", PresetEngine.encodeOption(clouds, "fast"));
        assertEquals("fast", PresetEngine.parseOption(clouds, "\"fast\""));
        assertEquals("fast", PresetEngine.parseOption(clouds, "fast"));
        assertEquals("\"broken", PresetEngine.parseOption(clouds, "\"broken"));
        SettingDef scale = engine.setting("vanilla.entityDistanceScaling").orElseThrow();
        assertEquals("1.25", PresetEngine.encodeOption(scale, 1.25));
        assertEquals(1.25, PresetEngine.parseOption(scale, "1.25"));
        SettingDef ao = engine.setting("vanilla.ao").orElseThrow();
        assertEquals("true", PresetEngine.encodeOption(ao, true));
        assertEquals(false, PresetEngine.parseOption(ao, "false"));
        SettingDef rd = engine.setting("vanilla.renderDistance").orElseThrow();
        assertEquals("12", PresetEngine.encodeOption(rd, 12.0));
        assertEquals("99", PresetEngine.parseOption(rd, "99"), "out of range values are kept as text");
    }

    @Test
    void everyPresetAndSettingHasStrings() {
        for (Preset p : Preset.values()) {
            assertTrue(I18n.has(p.nameKey()), p.nameKey());
            assertTrue(I18n.has(p.descriptionKey()), p.descriptionKey());
        }
        for (SettingDef d : engine.settings()) {
            assertTrue(I18n.has(d.labelKey()), d.labelKey());
            assertTrue(I18n.has(d.descriptionKey()), d.descriptionKey());
            if (d.target() == Target.VANILLA) {
                assertFalse(I18n.english(d.descriptionKey()).contains("verify on"), d.key() + " still says it needs verifying");
            }
        }
        assertTrue(I18n.has(PresetEngine.ULTRA_QUALITY_WARNING_KEY));
        assertTrue(I18n.has(AdaptiveQuality.NOT_IMPLEMENTED_KEY));
    }

    @Test
    void settingsHaveUniqueKeysAndKnownGroups() {
        Set<String> keys = new LinkedHashSet<>();
        Set<String> groups = Set.of("animation", "effects", "decorations", "hud", "map", "network", "video");
        for (SettingDef d : engine.settings()) {
            assertTrue(keys.add(d.key()), d.key());
            assertTrue(groups.contains(d.group()), d.group());
            assertEquals((d.target() == Target.VANILLA ? "vanilla" : d.target().moduleId()) + "." + d.jsonKey(), d.key());
        }
        assertEquals(Target.MAP, Target.ofModule("MAP"));
        assertNull(Target.ofModule("vanilla"));
    }
}
