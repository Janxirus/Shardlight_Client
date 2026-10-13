package dev.shardlight.client.i18n;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Localized strings. Messages live in {@code i18n/<area>.properties} bundles (English) with optional
 * {@code i18n/<area>_<lang>.properties} translations; each feature area owns one bundle so they can grow
 * independently. Placeholders use {@link MessageFormat} ({@code {0}}, {@code {1}}...).
 *
 * <p>Right-to-left languages are signalled by {@link #isRightToLeft()} so the UI can flip its layout.
 */
public final class I18n {
    /** every bundle, searched in order; add new areas here */
    public static final List<String> BUNDLES = List.of(
        "i18n/common", "i18n/install", "i18n/auth", "i18n/project", "i18n/content",
        "i18n/shardlight", "i18n/skin", "i18n/ui");

    private static volatile Locale locale = Locale.getDefault();
    private static final ConcurrentHashMap<Locale, List<ResourceBundle>> CACHE = new ConcurrentHashMap<>();

    private I18n() {
    }

    public static Locale locale() {
        return locale;
    }

    public static void setLocale(Locale l) {
        locale = l == null ? Locale.getDefault() : l;
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
        for (ResourceBundle b : bundles(Locale.ROOT)) {
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
        for (ResourceBundle b : bundles(l)) {
            if (b.containsKey(key)) {
                pattern = b.getString(key);
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

    private static List<ResourceBundle> bundles(Locale l) {
        return CACHE.computeIfAbsent(l, loc -> {
            List<ResourceBundle> out = new ArrayList<>();
            for (String name : BUNDLES) {
                try {
                    out.add(ResourceBundle.getBundle(name, loc, I18n.class.getClassLoader(),
                        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)));
                } catch (MissingResourceException ignored) {
                    // an area without strings yet
                }
            }
            return out;
        });
    }
}
