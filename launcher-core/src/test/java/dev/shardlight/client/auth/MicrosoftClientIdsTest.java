package dev.shardlight.client.auth;

import dev.shardlight.client.settings.SettingsService;
import dev.shardlight.client.storage.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftClientIdsTest {
    static final String ID = "4f2b1a9c-1234-4cde-9abc-0123456789ab";
    static final String BUILD_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @TempDir
    Path dir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(MicrosoftClientIds.PROPERTY);
    }

    @Test
    void noBuiltInId() {
        System.clearProperty(MicrosoftClientIds.PROPERTY);
        assertEquals("", MicrosoftClientIds.buildDefault(), "there is no built-in client id");
        assertEquals("", MicrosoftClientIds.of(() -> "").get());
        assertEquals("", MicrosoftClientIds.of(() -> null).get());
    }

    @Test
    void settingsWinOverTheBuildDefault() {
        System.setProperty(MicrosoftClientIds.PROPERTY, " " + BUILD_ID + " ");
        assertEquals(BUILD_ID, MicrosoftClientIds.buildDefault());
        assertEquals(BUILD_ID, MicrosoftClientIds.of(() -> "  ").get());
        assertEquals(ID, MicrosoftClientIds.of(() -> " " + ID + "\t").get());
    }

    @Test
    void readsTheSettingOnEveryCall() throws Exception {
        SettingsService settings = new SettingsService(new AppPaths(dir));
        Supplier<String> id = MicrosoftClientIds.fromSettings(settings);
        assertEquals("", id.get());
        settings.update(s -> s.auth.microsoftClientId = ID);
        assertEquals(ID, id.get());
        settings.update(s -> s.auth.microsoftClientId = "");
        System.setProperty(MicrosoftClientIds.PROPERTY, BUILD_ID);
        assertEquals(BUILD_ID, id.get());
    }

    @Test
    void validatesTheShapeOfAnId() {
        assertTrue(MicrosoftClientIds.looksValid(ID));
        assertTrue(MicrosoftClientIds.looksValid(" " + ID.toUpperCase() + " "));
        assertFalse(MicrosoftClientIds.looksValid(""));
        assertFalse(MicrosoftClientIds.looksValid(null));
        assertFalse(MicrosoftClientIds.looksValid("my-app"));
        assertFalse(MicrosoftClientIds.looksValid(ID + "0"));
        assertFalse(MicrosoftClientIds.looksValid("{" + ID + "}"));
    }
}
