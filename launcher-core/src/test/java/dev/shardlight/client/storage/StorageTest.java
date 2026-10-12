package dev.shardlight.client.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {
    @TempDir
    Path dir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("shardlight.home");
    }

    @Test
    void layoutKeepsSharedFilesApartFromProjects() throws Exception {
        AppPaths p = new AppPaths(dir.resolve("data/../data"));
        assertEquals(dir.resolve("data").toAbsolutePath().normalize(), p.root());
        assertEquals(p.root().resolve("settings.json"), p.settingsFile());
        assertEquals(p.root().resolve("accounts.json"), p.accountsFile());
        assertEquals(p.root().resolve("game/versions"), p.versions());
        assertEquals(p.root().resolve("game/libraries"), p.libraries());
        assertEquals(p.root().resolve("game/assets"), p.assets());
        assertEquals(p.root().resolve("game/runtimes"), p.runtimes());
        assertEquals(p.root().resolve("projects/abc"), p.project("abc"));
        p.createDirectories();
        for (Path d : List.of(p.logs(), p.cache(), p.versions(), p.libraries(), p.assets(), p.runtimes(), p.files(),
            p.content(), p.projects(), p.backups(), p.trash())) {
            assertTrue(Files.isDirectory(d), d.toString());
        }
        p.createDirectories(); // a second call is harmless
        assertEquals(p.root().toString(), p.toString());
    }

    @Test
    void defaultFolderPerOperatingSystem() {
        String home = System.getProperty("user.home");
        Path mac = AppPaths.defaultRoot(new OsInfo(OsInfo.Os.MACOS, OsInfo.Arch.ARM64, "15"));
        assertEquals(Path.of(home, "Library", "Application Support", "ShardlightClient"), mac);
        Path win = AppPaths.defaultRoot(new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X64, "10.0"));
        assertTrue(win.endsWith("ShardlightClient"), win.toString());
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            assertEquals(Path.of(home, "AppData", "Roaming", "ShardlightClient"), win);
        } else {
            assertEquals(Path.of(appData, "ShardlightClient"), win);
        }
        Path linux = AppPaths.defaultRoot(new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.X64, "6.8"));
        assertTrue(linux.endsWith("shardlight-client"), linux.toString());
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg == null || xdg.isBlank()) {
            assertEquals(Path.of(home, ".local", "share", "shardlight-client"), linux);
        }
    }

    @Test
    void theHomeOverrideWins() {
        System.setProperty("shardlight.home", dir.resolve("portable").toString());
        assertEquals(dir.resolve("portable").toAbsolutePath().normalize(), AppPaths.defaults().root());
    }

    @Test
    void migrationStepsRunInOrderFromTheStoredVersion() {
        List<String> ran = new ArrayList<>();
        JsonMigrator m = new JsonMigrator("thing.json", 3)
            .step(0, o -> ran.add("0->1"))
            .step(1, o -> {
                ran.add("1->2");
                o.addProperty("renamed", o.has("old") ? o.remove("old").getAsString() : "");
            })
            .step(2, o -> ran.add("2->3"));

        JsonObject v0 = JsonParser.parseString("{\"old\": \"x\"}").getAsJsonObject();
        assertTrue(m.migrate(v0));
        assertEquals(List.of("0->1", "1->2", "2->3"), ran);
        assertEquals(3, JsonMigrator.versionOf(v0));
        assertEquals("x", v0.get("renamed").getAsString());
        assertFalse(v0.has("old"));

        ran.clear();
        JsonObject v2 = JsonParser.parseString("{\"schemaVersion\": 2}").getAsJsonObject();
        assertTrue(m.migrate(v2));
        assertEquals(List.of("2->3"), ran);

        ran.clear();
        JsonObject current = JsonParser.parseString("{\"schemaVersion\": 3}").getAsJsonObject();
        assertFalse(m.migrate(current));
        assertFalse(m.isNewer(current));
        assertTrue(ran.isEmpty());

        JsonObject newer = JsonParser.parseString("{\"schemaVersion\": 4, \"keep\": true}").getAsJsonObject();
        assertTrue(m.isNewer(newer));
        assertFalse(m.migrate(newer), "a newer document is left alone");
        assertEquals(4, JsonMigrator.versionOf(newer));
    }

    @Test
    void oddVersionValuesCountAsUnversioned() {
        for (String json : new String[] {"{}", "{\"schemaVersion\": \"two\"}", "{\"schemaVersion\": null}",
            "{\"schemaVersion\": [1]}", "{\"schemaVersion\": 1.5}", "{\"schemaVersion\": -3}",
            "{\"schemaVersion\": 99999999999}", "{\"schemaVersion\": true}"}) {
            assertEquals(0, JsonMigrator.versionOf(JsonParser.parseString(json).getAsJsonObject()), json);
        }
        assertEquals(2, JsonMigrator.versionOf(JsonParser.parseString("{\"schemaVersion\": 2}").getAsJsonObject()));
        assertEquals(2, JsonMigrator.versionOf(JsonParser.parseString("{\"schemaVersion\": 2.0}").getAsJsonObject()));
    }
}
