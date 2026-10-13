package dev.shardlight.client.auth;

import dev.shardlight.client.i18n.I18n;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The auth bundle: every key the code uses exists, and every message renders cleanly. */
class AuthMessagesTest {
    private static Properties bundle() throws IOException {
        Properties p = new Properties();
        try (InputStream in = AuthMessagesTest.class.getResourceAsStream("/i18n/auth.properties")) {
            assertNotNull(in, "i18n/auth.properties is on the classpath");
            p.load(in);
        }
        return p;
    }

    @Test
    void everyMessageRendersWithAndWithoutArguments() throws Exception {
        Properties p = bundle();
        assertFalse(p.isEmpty());
        for (String key : p.stringPropertyNames()) {
            assertTrue(key.startsWith("auth."), "auth keys only: " + key);
            assertTrue(I18n.has(key), key);
            String plain = I18n.english(key);
            String withArgs = I18n.english(key, "A", "B", "C");
            assertFalse(plain.isBlank(), key);
            assertFalse(withArgs.isBlank(), key);
            if (!p.getProperty(key).contains("{0}")) {
                // shown verbatim when there are no arguments: must not depend on MessageFormat quoting
                assertFalse(p.getProperty(key).contains("'"), "no apostrophes in argument-free message " + key);
                assertEquals(plain, withArgs, key);
            }
            assertFalse(withArgs.contains("''"), key + ": " + withArgs);
            assertFalse(withArgs.contains("{0}"), key + ": " + withArgs);
        }
        assertEquals("Couldn't use macOS Keychain to keep your sign-in (locked).",
            I18n.english("auth.error.secretStore", "macOS Keychain", "locked"));
    }

    @Test
    void everyKeyUsedInTheCodeExists() throws IOException {
        Pattern literal = Pattern.compile("\"(auth\\.[A-Za-z0-9_.]+)\"");
        TreeSet<String> missing = new TreeSet<>();
        List<Path> sources = new ArrayList<>();
        for (String pkg : List.of("auth", "account")) {
            Path dir = Path.of("src/main/java/dev/shardlight/client", pkg);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
                }
            }
        }
        if (sources.isEmpty()) {
            return; // not run from the module folder
        }
        int found = 0;
        for (Path f : sources) {
            Matcher m = literal.matcher(Files.readString(f));
            while (m.find()) {
                found++;
                if (!I18n.has(m.group(1))) {
                    missing.add(m.group(1) + " (" + f.getFileName() + ")");
                }
            }
        }
        assertTrue(found > 20, "found the message keys in the sources");
        assertTrue(missing.isEmpty(), "missing message keys: " + missing);
    }
}
