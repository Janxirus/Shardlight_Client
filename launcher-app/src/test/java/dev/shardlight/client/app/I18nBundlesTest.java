package dev.shardlight.client.app;

import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every string the app shows exists, and every bundle value is a valid MessageFormat pattern. */
class I18nBundlesTest {
    private static final Pattern KEY = Pattern.compile("\"((?:ui|common|services)\\.[A-Za-z0-9_.]+)\"");
    private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

    @Test
    void everyValueIsAValidPatternWithDoubledApostrophes() {
        List<String> problems = new ArrayList<>();
        for (String name : I18n.BUNDLES) {
            ResourceBundle b;
            try {
                b = ResourceBundle.getBundle(name, Locale.ROOT, I18nBundlesTest.class.getClassLoader(),
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            } catch (MissingResourceException e) {
                continue;
            }
            for (String key : b.keySet()) {
                String value = b.getString(key);
                try {
                    new MessageFormat(value, Locale.ENGLISH);
                } catch (IllegalArgumentException e) {
                    problems.add(name + ": " + key + " is not a valid pattern: " + e.getMessage());
                }
                if (LONE_APOSTROPHE.matcher(value).find()) {
                    problems.add(name + ": " + key + " has a single apostrophe (write '')");
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void apostrophesShowOnceWithOrWithoutArguments() {
        assertEquals("Don't", new MessageFormat("Don''t", Locale.ENGLISH).format(new Object[0]));
        for (String key : List.of("ui.nav.home", "common.cancel")) {
            assertTrue(I18n.has(key), key);
            assertTrue(!I18n.english(key).contains("''"), key);
        }
    }

    @Test
    void everyKeyUsedInTheSourceExists() throws IOException {
        Set<String> missing = new TreeSet<>();
        for (Path root : List.of(Path.of("src/main/java"),
            Path.of("../launcher-core/src/main/java/dev/shardlight/client/services"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = KEY.matcher(Files.readString(f));
                    while (m.find()) {
                        String key = m.group(1);
                        // prefixes completed at run time, e.g. "ui.settings.section." + id
                        if (!key.endsWith(".") && !I18n.has(key)) {
                            missing.add(key + "  (" + f.getFileName() + ")");
                        }
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
    }

    @Test
    void keysBuiltFromEnumsExist() {
        List<String> missing = new ArrayList<>();
        for (ContentKind k : ContentKind.values()) {
            check(missing, "ui.content.filter." + k.name().toLowerCase(Locale.ROOT));
        }
        for (ContentSource s : ContentSource.values()) {
            check(missing, "ui.content.source." + s.name().toLowerCase(Locale.ROOT));
        }
        for (LoaderType t : LoaderType.values()) {
            check(missing, "ui.wizard.loader." + t.name().toLowerCase(Locale.ROOT));
        }
        for (String id : List.of("accounts", "appearance", "java", "downloads", "content", "privacy", "updates", "storage",
            "about")) {
            check(missing, "ui.settings.section." + id);
            check(missing, "ui.settings.section." + id + ".about");
        }
        for (String m : List.of("ui", "map", "party", "companion")) {
            check(missing, "ui.shardlight.module." + m);
        }
        assertEquals(List.of(), missing);
    }

    private static void check(List<String> missing, String key) {
        if (!I18n.has(key)) {
            missing.add(key);
        }
    }
}
