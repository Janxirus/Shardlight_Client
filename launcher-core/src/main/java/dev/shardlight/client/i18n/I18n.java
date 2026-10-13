package dev.shardlight.client.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Localized strings. Messages live in {@code i18n/<area>.properties} bundles (English) with optional
 * {@code i18n/<area>_<lang>.properties} translations; each feature area owns one bundle so they can grow
 * independently. Placeholders use {@link MessageFormat} ({@code {0}}, {@code {1}}...).
 *
 * <p>A new language is just files: {@code ui_de.properties} (and the other areas, if they should be translated too)
 * dropped into the languages folder ({@link #setExternalDirectory}) or into {@code i18n/} on the class path. A key a
 * translation leaves out shows in English, and a translation may name itself with the key {@code language.name}.
 * Files are UTF-8.
 *
 * <p>Right-to-left languages are signalled by {@link #isRightToLeft()} so the UI can flip its layout. The two test
 * languages in {@link PseudoLocale} need no files.
 */
public final class I18n {
    /** every bundle, searched in order; add new areas here */
    public static final List<String> BUNDLES = List.of(
        "i18n/common", "i18n/install", "i18n/auth", "i18n/project", "i18n/content",
        "i18n/shardlight", "i18n/skin", "i18n/discord", "i18n/ui");

    /** the area whose files decide which languages exist */
    public static final String LANGUAGE_AREA = "ui";
    private static final Pattern LANGUAGE_FILE = Pattern.compile("ui_([a-z]{2,3}(?:_[A-Z]{2})?)\\.properties");

    private static volatile Locale locale = Locale.getDefault();
    private static volatile Path externalDirectory;
    private static final ConcurrentHashMap<Locale, List<Map<String, String>>> CACHE = new ConcurrentHashMap<>();

    private I18n() {
    }

    /** a language the player can pick: its tag ("de", "pt-BR") and its own name for it */
    public record Language(String tag, String name) {
    }

    public static Locale locale() {
        return locale;
    }

    public static void setLocale(Locale l) {
        locale = l == null ? Locale.getDefault() : l;
    }

    /** the folder translation files are read from, besides the class path (null: none) */
    public static void setExternalDirectory(Path dir) {
        externalDirectory = dir;
        CACHE.clear();
    }

    public static Path externalDirectory() {
        return externalDirectory;
    }

    /** forget loaded files, so edited ones are read again */
    public static void reload() {
        CACHE.clear();
    }

    public static boolean isRightToLeft() {
        String lang = locale.getLanguage();
        return lang.equals("ar") || lang.equals("he") || lang.equals("fa") || lang.equals("ur");
    }

    /** localized message for the current locale; the key itself when it's missing (never throws) */
    public static String tr(String key, Object... args) {
        return format(locale, key, args);
    }

    /** the English message, used for log lines and exception messages */
    public static String english(String key, Object... args) {
        return format(Locale.ROOT, key, args);
    }

    /** true when some bundle defines this key */
    public static boolean has(String key) {
        for (Map<String, String> b : bundles(Locale.ROOT)) {
            if (b.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static String format(Locale l, String key, Object... args) {
        if (key == null) {
            return "";
        }
        String pattern = null;
        for (Map<String, String> b : bundles(l)) {
            String v = b.get(key);
            if (v != null) {
                pattern = v;
                break;
            }
        }
        if (pattern == null) {
            return args == null || args.length == 0 ? key : key + " " + java.util.Arrays.toString(args);
        }
        // every value is a MessageFormat pattern, with or without arguments, so '' is always one apostrophe
        try {
            return new MessageFormat(pattern, l == Locale.ROOT ? Locale.ENGLISH : l).format(args == null ? new Object[0] : args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    // ---------------------------------------------------------------- languages

    /** English, then every language found as {@code ui_<lang>.properties} on the class path or in the languages folder */
    public static List<Language> available() {
        Map<String, Language> out = new LinkedHashMap<>();
        out.put("en", new Language("en", "English"));
        TreeSet<String> tags = new TreeSet<>(classpathTags());
        tags.addAll(externalTags());
        for (String tag : tags) {
            if (!out.containsKey(tag)) {
                out.put(tag, new Language(tag, nameOf(tag)));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** the two made-up languages (see {@link PseudoLocale}), for testing */
    public static List<Language> testLanguages() {
        return List.of(new Language(PseudoLocale.ACCENTED, "Pseudo: long and accented (test)"),
            new Language(PseudoLocale.MIRRORED, "Pseudo: right to left (test)"));
    }

    /** a language's own name for itself: the file's {@code language.name}, else what Java knows */
    public static String nameOf(String tag) {
        Locale l = Locale.forLanguageTag(tag);
        Map<String, String> ui = loadLanguageFile(LANGUAGE_AREA, l, true);
        String named = ui.get("language.name");
        if (named != null && !named.isBlank()) {
            return named.strip();
        }
        String own = l.getDisplayName(l);
        return own == null || own.isBlank() ? tag : own;
    }

    private static TreeSet<String> classpathTags() {
        TreeSet<String> tags = new TreeSet<>();
        try {
            Enumeration<URL> roots = I18n.class.getClassLoader().getResources("i18n");
            for (URL url : Collections.list(roots)) {
                if ("file".equals(url.getProtocol())) {
                    try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
                        files.forEach(f -> addTag(tags, f.getFileName().toString()));
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile();
                    for (JarEntry e : Collections.list(jar.entries())) {
                        if (e.getName().startsWith("i18n/") && e.getName().indexOf('/', 5) < 0) {
                            addTag(tags, e.getName().substring(5));
                        }
                    }
                }
            }
        } catch (IOException | java.net.URISyntaxException | RuntimeException e) {
            // no extra languages from the class path
        }
        return tags;
    }

    private static TreeSet<String> externalTags() {
        TreeSet<String> tags = new TreeSet<>();
        Path dir = externalDirectory;
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.forEach(f -> addTag(tags, f.getFileName().toString()));
            } catch (IOException e) {
                // an unreadable folder adds nothing
            }
        }
        return tags;
    }

    private static void addTag(TreeSet<String> tags, String fileName) {
        Matcher m = LANGUAGE_FILE.matcher(fileName);
        if (m.matches()) {
            tags.add(m.group(1).replace('_', '-'));
        }
    }

    /**
     * Put what a translator needs into {@code dir}: a README and, under {@code template/}, every English bundle to copy
     * as {@code <area>_<lang>.properties} next to the template folder. Files already there are left alone, except the
     * template itself, which is refreshed.
     */
    public static void exportTemplate(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("template"));
        Path readme = dir.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme, """
                Adding a language to Shardlight Client
                ======================================

                1. Copy template/ui.properties to this folder and rename the copy ui_<language>.properties,
                   for example ui_de.properties for German or ui_pt_BR.properties for Brazilian Portuguese.
                2. Translate the text after each "=". Keep the {0}, {1} ... placeholders, and write a
                   single apostrophe as two ('').
                3. Add a line "language.name=Deutsch" to name the language in its own words.
                4. Do the same for the other files in template/ if you want their messages translated too
                   (common, install, auth, project, content, shardlight, skin, discord). A message you leave
                   out is shown in English.
                5. Save the files as UTF-8, then pick the language in Settings > Appearance and restart.

                Right-to-left languages (Arabic, Hebrew, Persian, Urdu) flip the layout on their own.
                """, StandardCharsets.UTF_8);
        }
        for (String name : BUNDLES) {
            String area = name.substring(name.indexOf('/') + 1);
            try (InputStream in = I18n.class.getClassLoader().getResourceAsStream("i18n/" + area + ".properties")) {
                if (in != null) {
                    Files.write(dir.resolve("template").resolve(area + ".properties"), in.readAllBytes());
                }
            }
        }
    }

    // ---------------------------------------------------------------- loading

    private static List<Map<String, String>> bundles(Locale l) {
        return CACHE.computeIfAbsent(l, loc -> {
            List<Map<String, String>> out = new ArrayList<>();
            for (String name : BUNDLES) {
                String area = name.substring(name.indexOf('/') + 1);
                Map<String, String> merged = new HashMap<>(loadLanguageFile(area, Locale.ROOT, false));
                if (PseudoLocale.is(loc)) {
                    merged.replaceAll((k, v) -> isText(k) ? PseudoLocale.transform(v, loc) : v);
                } else if (loc != Locale.ROOT && !loc.getLanguage().isEmpty()) {
                    merged.putAll(loadLanguageFile(area, Locale.of(loc.getLanguage()), true));
                    if (!loc.getCountry().isEmpty()) {
                        merged.putAll(loadLanguageFile(area, loc, true));
                    }
                }
                out.add(merged);
            }
            return out;
        });
    }

    /** the entries of one file that only describe a language are not text to show */
    private static boolean isText(String key) {
        return !key.equals("language.name");
    }

    /**
     * {@code <area>_<lang>[_<COUNTRY>].properties} (or {@code <area>.properties} for the root locale): the class path
     * first, then the languages folder, which wins. Empty when there is no such file.
     */
    private static Map<String, String> loadLanguageFile(String area, Locale l, boolean withExternal) {
        String suffix = l == Locale.ROOT || l.getLanguage().isEmpty() ? ""
            : "_" + l.getLanguage() + (l.getCountry().isEmpty() ? "" : "_" + l.getCountry());
        String file = area + suffix + ".properties";
        Map<String, String> out = new HashMap<>();
        try (InputStream in = I18n.class.getClassLoader().getResourceAsStream("i18n/" + file)) {
            if (in != null) {
                read(in, out);
            }
        } catch (IOException e) {
            // an unreadable bundle adds nothing; English fills in
        }
        Path dir = externalDirectory;
        if (withExternal && dir != null) {
            Path p = dir.resolve(file);
            if (Files.isRegularFile(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    read(in, out);
                } catch (IOException e) {
                    // the player's file could not be read; the shipped text stays
                }
            }
        }
        return out;
    }

    private static void read(InputStream in, Map<String, String> into) throws IOException {
        Properties p = new Properties();
        p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String key : p.stringPropertyNames()) {
            into.put(key, p.getProperty(key));
        }
    }
}
