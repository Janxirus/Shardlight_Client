package dev.shardlight.client.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nLanguagesTest {
    @TempDir
    Path dir;
    Locale before;

    @BeforeEach
    void setUp() {
        before = I18n.locale();
        I18n.setExternalDirectory(dir);
    }

    @AfterEach
    void tearDown() {
        I18n.setExternalDirectory(null);
        I18n.setLocale(before);
    }

    private void write(String file, String text) throws Exception {
        Files.writeString(dir.resolve(file), text, StandardCharsets.UTF_8);
        I18n.reload();
    }

    @Test
    void englishIsAlwaysThere() {
        List<I18n.Language> all = I18n.available();
        assertEquals("en", all.get(0).tag());
        assertEquals("English", all.get(0).name());
    }

    @Test
    void aFileInTheLanguagesFolderAddsALanguage() throws Exception {
        write("ui_de.properties", "language.name=Deutsch\nui.nav.home=Startseite\n");
        write("ui_pt_BR.properties", "ui.nav.home=Início\n");
        write("notes.txt", "not a language");
        write("ui_toolong.properties", "x=y");
        List<I18n.Language> all = I18n.available();
        assertEquals(List.of("en", "de", "pt-BR"), all.stream().map(I18n.Language::tag).toList());
        assertEquals("Deutsch", all.get(1).name(), "the file names its own language");
        assertFalse(all.get(2).name().isBlank(), "else Java's own name is used");
    }

    @Test
    void aTranslationReplacesEnglishAndMissingKeysFallBack() throws Exception {
        write("common_de.properties", "common.cancel=Abbrechen\n");
        I18n.setLocale(Locale.forLanguageTag("de"));
        assertEquals("Abbrechen", I18n.tr("common.cancel"));
        assertEquals(I18n.english("common.close"), I18n.tr("common.close"), "left out: English");
        assertEquals("Cancel", I18n.english("common.cancel"), "English itself is untouched");
    }

    @Test
    void aRegionalFileWinsOverTheLanguageFile() throws Exception {
        write("ui_pt.properties", "ui.nav.home=Casa\nui.nav.library=Biblioteca\n");
        write("ui_pt_BR.properties", "ui.nav.home=Início\n");
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("Início", I18n.tr("ui.nav.home"));
        assertEquals("Biblioteca", I18n.tr("ui.nav.library"));
    }

    @Test
    void filesAreReadAsUtf8AndPlaceholdersWork() throws Exception {
        write("ui_de.properties", "ui.play.started=„{0}“ läuft\n");
        I18n.setLocale(Locale.GERMAN);
        assertEquals("„Welt“ läuft", I18n.tr("ui.play.started", "Welt"));
    }

    @Test
    void otherAreasCanBeTranslatedToo() throws Exception {
        write("install_de.properties", "install.stage.metadata=Fertig\n");
        write("common_de.properties", "common.cancel=Abbrechen\n");
        I18n.setLocale(Locale.GERMAN);
        assertEquals("Abbrechen", I18n.tr("common.cancel"));
        assertEquals("Fertig", I18n.tr("install.stage.metadata"));
    }

    @Test
    void rightToLeftLanguagesAreRecognised() {
        I18n.setLocale(Locale.forLanguageTag("ar"));
        assertTrue(I18n.isRightToLeft());
        I18n.setLocale(Locale.forLanguageTag("he"));
        assertTrue(I18n.isRightToLeft());
        I18n.setLocale(Locale.forLanguageTag("de"));
        assertFalse(I18n.isRightToLeft());
        I18n.setLocale(Locale.forLanguageTag(PseudoLocale.MIRRORED));
        assertTrue(I18n.isRightToLeft(), "the mirrored test language flips the layout");
    }

    @Test
    void theAccentedTestLanguageLengthensEveryMessageAndKeepsArguments() {
        I18n.setLocale(Locale.forLanguageTag(PseudoLocale.ACCENTED));
        String english = I18n.english("error.network", "Welt", "x");
        String pseudo = I18n.tr("error.network", "Welt", "x");
        assertTrue(pseudo.startsWith("["), pseudo);
        assertTrue(pseudo.endsWith("]"), pseudo);
        assertTrue(pseudo.contains("Welt"), "arguments are filled in as given: " + pseudo);
        assertTrue(pseudo.length() > english.length() + english.length() / 4, pseudo + " vs " + english);
        assertNotEquals(english, pseudo);
    }

    @Test
    void theAccentedTestLanguageAlsoReachesTheWordsInsideChoiceArguments() {
        String p = PseudoLocale.transform("{0,choice,1#1 day ago|1<{0,number,integer} days ago}", Locale.forLanguageTag("en-XA"));
        String one = new java.text.MessageFormat(p, Locale.ENGLISH).format(new Object[] {1});
        String many = new java.text.MessageFormat(p, Locale.ENGLISH).format(new Object[] {3});
        assertTrue(one.startsWith("[1 đåý"), one);
        assertTrue(many.startsWith("[3 đåýš"), many);
    }

    @Test
    void pseudoTextKeepsMessageSyntaxIntact() {
        Locale l = Locale.forLanguageTag(PseudoLocale.ACCENTED);
        String out = PseudoLocale.transform("Couldn''t open {0} ({1,number,#}) '{literal}'", l);
        assertTrue(out.contains("{0}") && out.contains("{1,number,#}"), out);
        assertTrue(out.contains("''"), "an escaped apostrophe stays escaped: " + out);
        assertTrue(out.contains("'{literal}'"), "quoted text is left alone: " + out);
        // the result is still a valid pattern
        assertEquals(true, new java.text.MessageFormat(out, Locale.ENGLISH).format(new Object[] {"a", 5}).length() > 10);
    }

    @Test
    void textWithoutLettersIsLeftAlone() {
        Locale l = Locale.forLanguageTag(PseudoLocale.ACCENTED);
        assertEquals("{0}", PseudoLocale.transform("{0}", l));
        assertEquals("123", PseudoLocale.transform("123", l));
        assertEquals("", PseudoLocale.transform("", l));
    }

    @Test
    void everyEnglishMessageSurvivesThePseudoTransform() {
        Locale l = Locale.forLanguageTag(PseudoLocale.ACCENTED);
        I18n.setLocale(l);
        for (String name : I18n.BUNDLES) {
            java.util.ResourceBundle b;
            try {
                b = java.util.ResourceBundle.getBundle(name, Locale.ROOT, I18n.class.getClassLoader());
            } catch (java.util.MissingResourceException e) {
                continue;
            }
            for (String key : b.keySet()) {
                String text = I18n.tr(key, "a", "b", "c", "d");
                assertFalse(text.equals(key), key + " is missing in the test language");
            }
        }
    }

    @Test
    void theTemplateHasEveryEnglishFileAndAReadme() throws Exception {
        Path target = dir.resolve("languages");
        I18n.exportTemplate(target);
        assertTrue(Files.isRegularFile(target.resolve("README.txt")));
        assertTrue(Files.isRegularFile(target.resolve("template").resolve("common.properties")));
        String ui = Files.readString(target.resolve("template").resolve("common.properties"), StandardCharsets.UTF_8);
        assertTrue(ui.isEmpty() || ui.contains("="));
        // a second call keeps the README the translator may have edited
        Files.writeString(target.resolve("README.txt"), "mine");
        I18n.exportTemplate(target);
        assertEquals("mine", Files.readString(target.resolve("README.txt")));
    }

    @Test
    void theTemplateFilesAreNotLanguages() throws Exception {
        I18n.exportTemplate(dir);
        assertEquals(1, I18n.available().size(), "template/ui.properties is not ui_<lang>.properties");
    }
}
