package dev.shardlight.client.minecraft;

import dev.shardlight.client.i18n.I18n;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every message key the install, Java, launch, loader and diagnostics code uses exists in a bundle, and
 * every message in {@code i18n/install.properties} is a valid {@link MessageFormat} pattern whose
 * apostrophes are doubled.
 */
class InstallMessagesTest {
    static final List<String> PACKAGES = List.of("minecraft", "java", "launch", "loader", "diagnostics");
    static final Pattern KEY = Pattern.compile("\"((?:install|loader|launch|diagnostics|error)\\.[A-Za-z0-9_.]*[A-Za-z0-9_])\"");

    @Test
    void everyUsedKeyExists() throws IOException {
        Path src = Path.of("src/main/java/dev/shardlight/client");
        if (!Files.isDirectory(src)) {
            return; // not run from the module folder
        }
        TreeSet<String> missing = new TreeSet<>();
        int found = 0;
        for (String pkg : PACKAGES) {
            Path dir = src.resolve(pkg);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(p -> p.toString().endsWith(".java")).toList();
            }
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = KEY.matcher(text);
                while (m.find()) {
                    found++;
                    if (!I18n.has(m.group(1))) {
                        missing.add(m.group(1) + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(found > 20, "the scan found the code's keys");
        assertEquals(new TreeSet<String>(), missing, "keys without a message");
    }

    @Test
    void messagesAreValidPatterns() throws IOException {
        Properties p = new Properties();
        try (InputStream in = I18n.class.getClassLoader().getResourceAsStream("i18n/install.properties")) {
            assertNotNull(in);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> bad = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            String v = p.getProperty(key);
            try {
                new MessageFormat(v);
            } catch (IllegalArgumentException e) {
                bad.add(key + ": " + e.getMessage());
                continue;
            }
            if (v.replace("''", "").contains("'")) {
                bad.add(key + ": single apostrophe");
            }
            if (v.isBlank()) {
                bad.add(key + ": empty");
            }
        }
        assertEquals(List.of(), bad);
        assertTrue(I18n.english("install.error.versionNotFound", "1.99").contains("doesn't exist"));
    }
}
