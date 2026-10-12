package dev.shardlight.client.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.storage.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsServiceTest {
    @TempDir
    Path dir;

    private AppPaths paths() {
        return new AppPaths(dir);
    }

    private void write(String json) throws Exception {
        Files.writeString(paths().settingsFile(), json, StandardCharsets.UTF_8);
    }

    private JsonObject onDisk() throws Exception {
        return JsonParser.parseString(Files.readString(paths().settingsFile())).getAsJsonObject();
    }

    private List<String> asideFiles(String prefix) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(prefix)).sorted().toList();
        }
    }

    @Test
    void defaultsWhenThereIsNoFileAndNothingIsWrittenUntilAChange() throws Exception {
        SettingsService s = new SettingsService(paths());
        LauncherSettings v = s.get();
        assertEquals(LauncherSettings.SCHEMA_VERSION, v.schemaVersion);
        assertEquals("dark", v.appearance.theme);
        assertEquals(6, v.downloads.maxConcurrent);
        assertEquals("modrinth", v.catalog.defaultSource);
        assertEquals("", v.auth.microsoftClientId);
        assertFalse(v.privacy.telemetry, "there is no telemetry, and the switch defaults to off");
        assertFalse(v.privacy.discordRichPresence);
        assertTrue(v.updates.checkOnStart);
        assertFalse(Files.exists(paths().settingsFile()));
        assertNull(s.recoveredFrom());
        assertFalse(s.isReadOnly());
    }

    @Test
    void changesPersistNotifyListenersAndReadersGetCopies() throws Exception {
        SettingsService s = new SettingsService(paths());
        List<LauncherSettings> seen = new ArrayList<>();
        Consumer<LauncherSettings> listener = seen::add;
        s.addListener(listener);
        s.update(v -> {
            v.appearance.theme = "light";
            v.downloads.maxConcurrent = 3;
            v.recentProjects.add("p1");
        });
        assertEquals(1, seen.size());
        assertEquals("light", seen.get(0).appearance.theme);

        LauncherSettings copy = s.get();
        copy.appearance.theme = "changed without update";
        assertEquals("light", s.get().appearance.theme, "get() returns a copy");

        LauncherSettings reloaded = new SettingsService(paths()).get();
        assertEquals("light", reloaded.appearance.theme);
        assertEquals(3, reloaded.downloads.maxConcurrent);
        assertEquals(List.of("p1"), reloaded.recentProjects);
        assertEquals(LauncherSettings.SCHEMA_VERSION, onDisk().get("schemaVersion").getAsInt());

        s.removeListener(listener);
        s.update(v -> v.appearance.compact = true);
        assertEquals(1, seen.size(), "a removed listener hears nothing more");
        assertTrue(new SettingsService(paths()).get().appearance.compact);
    }

    @Test
    void outOfRangeValuesAreClamped() throws Exception {
        SettingsService s = new SettingsService(paths());
        s.update(v -> {
            v.downloads.maxConcurrent = 500;
            v.downloads.maxRetries = -4;
            v.appearance.scale = 9;
        });
        LauncherSettings v = s.get();
        assertEquals(16, v.downloads.maxConcurrent);
        assertEquals(0, v.downloads.maxRetries);
        assertEquals(1.6, v.appearance.scale);
        s.update(x -> {
            x.downloads.maxConcurrent = 0;
            x.appearance.scale = 0.1;
        });
        assertEquals(1, s.get().downloads.maxConcurrent);
        assertEquals(0.8, s.get().appearance.scale);
    }

    @Test
    void anUnversionedFileIsMigratedWithABackupAndMissingSectionsFilled() throws Exception {
        write("{\"selectedProjectId\": \"abc\", \"appearance\": {\"theme\": \"light\", \"scale\": 1.2},"
            + " \"downloads\": {\"maxConcurrent\": 40}}");
        SettingsService s = new SettingsService(paths());
        LauncherSettings v = s.get();
        assertEquals("abc", v.selectedProjectId);
        assertEquals("light", v.appearance.theme);
        assertEquals(1.2, v.appearance.scale);
        assertEquals(16, v.downloads.maxConcurrent, "clamped while loading");
        assertNotNull(v.java, "a missing section gets its defaults");
        assertEquals(4096, v.java.maxMemoryMb);
        assertNotNull(v.updates);
        assertNotNull(v.auth);
        assertTrue(Files.exists(dir.resolve("settings.json.pre-migration")), "the old file is kept");
        assertTrue(Files.readString(dir.resolve("settings.json.pre-migration")).contains("\"abc\""));
        assertEquals(LauncherSettings.SCHEMA_VERSION, onDisk().get("schemaVersion").getAsInt());
    }

    @Test
    void aFileFromANewerLauncherIsReadButNeverOverwritten() throws Exception {
        String newer = "{\"schemaVersion\": 99, \"appearance\": {\"theme\": \"light\"}, \"futureThing\": {\"x\": 1}}";
        write(newer);
        SettingsService s = new SettingsService(paths());
        assertTrue(s.isReadOnly());
        assertEquals("light", s.get().appearance.theme);
        s.update(v -> v.appearance.theme = "dark");
        assertEquals("dark", s.get().appearance.theme, "the change applies for this run");
        assertEquals(newer, Files.readString(paths().settingsFile()), "the newer file is untouched");
        assertFalse(Files.exists(dir.resolve("settings.json.pre-migration")));
    }

    @Test
    void unreadableFilesAreMovedAsideAndDefaultsUsed() throws Exception {
        for (String broken : new String[] {"{ this is not json", "", "[1, 2, 3]", "\"just a string\"",
            "{\"downloads\": \"six\"}", "{\"recentProjects\": {\"a\": 1}}", "{\"schemaVersion\": \"one\"}"}) {
            write(broken);
            SettingsService s = new SettingsService(paths());
            assertNotNull(s.recoveredFrom(), "recovered from: " + broken);
            assertTrue(Files.exists(s.recoveredFrom()));
            assertEquals(broken, Files.readString(s.recoveredFrom()), "the broken file is kept as it was");
            assertFalse(Files.exists(paths().settingsFile()));
            assertEquals("dark", s.get().appearance.theme);
            s.update(v -> v.firstRunDone = true);
            assertTrue(new SettingsService(paths()).get().firstRunDone, "a fresh file is written on the next change");
        }
        assertEquals(7, asideFiles("settings.json.corrupt-").size(), "each broken file is kept under its own name");
    }

    @Test
    void oddValuesInAReadableFileFallBackToDefaults() throws Exception {
        write("{\"appearance\": {\"theme\": null, \"accent\": null, \"scale\": NaN},"
            + " \"catalog\": {\"defaultSource\": null}, \"updates\": {\"channel\": null},"
            + " \"auth\": {\"microsoftClientId\": null}, \"recentProjects\": [\"a\", null, \"b\"],"
            + " \"java\": null}");
        SettingsService s = new SettingsService(paths());
        assertNull(s.recoveredFrom(), "the file was readable");
        LauncherSettings v = s.get();
        assertEquals("dark", v.appearance.theme);
        assertEquals("#7C6CF2", v.appearance.accent);
        assertEquals(1.0, v.appearance.scale);
        assertEquals("modrinth", v.catalog.defaultSource);
        assertEquals("stable", v.updates.channel);
        assertEquals("", v.auth.microsoftClientId);
        assertEquals(List.of("a", "b"), v.recentProjects);
        assertEquals(4096, v.java.maxMemoryMb);
        assertEquals(LauncherSettings.SCHEMA_VERSION, onDisk().get("schemaVersion").getAsInt(), "migrated");
    }
}
