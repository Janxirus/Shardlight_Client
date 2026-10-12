package dev.shardlight.client.shardlight;

import com.google.gson.JsonParseException;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The optional Shardlight modules and themes the launcher knows, loaded from the bundled
 * {@code shardlight/catalog.json} (schema version 1). Immutable and safe to share between threads.
 */
public final class ShardlightCatalog {
    /** the catalog format this launcher reads */
    public static final int SCHEMA_VERSION = 1;
    /** class-path location of the bundled catalog */
    public static final String RESOURCE = "/shardlight/catalog.json";

    private static final Pattern MODULE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private static final Pattern THEME_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    /** "Shardlight UI - Dark Green (Mossgrove).zip", "Shardlight Sounds - Ember.zip", "... (1).zip" */
    private static final Pattern PACK_NAME = Pattern.compile(
        "(?i)^shardlight (ui|sounds?) - (.+?)(?: \\(([^()]+)\\))?(?: \\(\\d+\\))?\\.zip$");

    private static volatile ShardlightCatalog bundled;

    private final List<ShardlightModule> modules;
    private final List<ShardlightTheme> themes;

    /** which theme and kind a pack file belongs to; {@code exactName} when it is the catalog's own file name */
    public record ThemePackMatch(ShardlightTheme theme, boolean sounds, boolean exactName) {
    }

    /** a catalog from already-parsed parts; validates ids, file names and colours */
    public ShardlightCatalog(List<ShardlightModule> modules, List<ShardlightTheme> themes) throws LauncherException {
        this.modules = List.copyOf(modules);
        this.themes = List.copyOf(themes);
        validate();
    }

    /** the catalog bundled with the launcher (parsed once, then shared) */
    public static ShardlightCatalog load() throws LauncherException {
        ShardlightCatalog c = bundled;
        if (c != null) {
            return c;
        }
        synchronized (ShardlightCatalog.class) {
            if (bundled == null) {
                try (InputStream in = ShardlightCatalog.class.getResourceAsStream(RESOURCE)) {
                    if (in == null) {
                        throw new LauncherException(ErrorCode.INTERNAL, "shardlight.catalog.invalid", "missing " + RESOURCE);
                    }
                    bundled = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw LauncherException.io(e, RESOURCE);
                }
            }
            return bundled;
        }
    }

    /** parse and validate catalog JSON */
    public static ShardlightCatalog parse(String json) throws LauncherException {
        CatalogFile f;
        try {
            f = Json.read(json, CatalogFile.class);
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "shardlight.catalog.invalid", String.valueOf(e.getMessage()));
        }
        if (f == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.catalog.invalid", "empty");
        }
        if (f.schemaVersion != SCHEMA_VERSION) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.catalog.invalid",
                "schemaVersion " + f.schemaVersion + " (expected " + SCHEMA_VERSION + ")");
        }
        return new ShardlightCatalog(f.modules == null ? List.of() : f.modules, f.themes == null ? List.of() : f.themes);
    }

    /** every module, in catalog order (ui, map, party, companion) */
    public List<ShardlightModule> modules() {
        return modules;
    }

    /** the module with this id (case-insensitive) */
    public Optional<ShardlightModule> module(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return modules.stream().filter(m -> m.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    /** the module whose jar declares this Fabric mod id */
    public Optional<ShardlightModule> moduleByModId(String modId) {
        if (modId == null) {
            return Optional.empty();
        }
        return modules.stream().filter(m -> m.modId().equals(modId.trim())).findFirst();
    }

    /** every theme, in the UI mod's order */
    public List<ShardlightTheme> themes() {
        return themes;
    }

    /** the theme with this id (case-insensitive) */
    public Optional<ShardlightTheme> theme(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return themes.stream().filter(t -> t.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    /**
     * Which theme a pack file belongs to, by its file name: the catalog's exact {@code uiPackFile} /
     * {@code soundPackFile} (case-insensitive), or {@code "Shardlight UI - <theme>[ (<title>)].zip"} /
     * {@code "Shardlight Sounds - ..."} where {@code <theme>} is a theme's name, id, pack title or alias
     * (browser copies such as {@code "... (1).zip"} are accepted too).
     */
    public Optional<ThemePackMatch> matchThemePack(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        for (ShardlightTheme t : themes) {
            if (name.equalsIgnoreCase(t.uiPackFile())) {
                return Optional.of(new ThemePackMatch(t, false, true));
            }
            if (name.equalsIgnoreCase(t.soundPackFile())) {
                return Optional.of(new ThemePackMatch(t, true, true));
            }
        }
        Matcher m = PACK_NAME.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        boolean sounds = m.group(1).toLowerCase(Locale.ROOT).startsWith("sound");
        Optional<ShardlightTheme> t = themeByName(m.group(2));
        if (t.isEmpty() && m.group(3) != null) {
            t = themeByName(m.group(3));
        }
        if (t.isEmpty() && m.group(3) != null) {
            // "Shardlight UI - Something (Else).zip" where the whole text is the name
            t = themeByName(m.group(2) + " (" + m.group(3) + ")");
        }
        return t.map(theme -> new ThemePackMatch(theme, sounds, false));
    }

    /** a theme by its display name, id, pack title or alias (case- and separator-insensitive) */
    public Optional<ShardlightTheme> themeByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = squash(name);
        for (ShardlightTheme t : themes) {
            if (squash(t.displayName()).equals(key) || squash(t.id()).equals(key) || squash(t.packTitle()).equals(key)) {
                return Optional.of(t);
            }
            for (String a : t.aliases()) {
                if (squash(a).equals(key)) {
                    return Optional.of(t);
                }
            }
        }
        return Optional.empty();
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    private void validate() throws LauncherException {
        Set<String> ids = new HashSet<>();
        Set<String> modIds = new HashSet<>();
        Path probe = Path.of("probe").toAbsolutePath();
        for (ShardlightModule m : modules) {
            require(m.id() != null && MODULE_ID.matcher(m.id()).matches(), "module id " + m.id());
            require(ids.add(m.id()), "duplicate module " + m.id());
            require(m.modId() != null && MOD_ID.matcher(m.modId()).matches(), "mod id " + m.modId());
            require(modIds.add(m.modId()), "duplicate mod id " + m.modId());
            require(m.nameKey() != null && m.descriptionKey() != null, "keys of " + m.id());
            require(m.minLoaderVersion() != null && !m.minLoaderVersion().isBlank(), "minLoaderVersion of " + m.id());
            require(m.configFile() != null, "configFile of " + m.id());
            PathSafety.resolveInside(probe, m.configFile());
            for (ShardlightModule.Source s : m.sources()) {
                require(s.type() != null && s.assetPattern() != null, "source of " + m.id());
                if ("github-release".equals(s.type())) {
                    require(s.repo() != null && REPO.matcher(s.repo()).matches() && !s.repo().contains(".."), "repo " + s.repo());
                }
            }
            for (ShardlightModule.Dependency d : m.requires()) {
                require(d.source() != null && d.project() != null && d.reasonKey() != null, "dependency of " + m.id());
            }
        }
        for (ShardlightModule m : modules) {
            for (String w : m.worksBetterWith()) {
                require(ids.contains(w) && !w.equals(m.id()), "worksBetterWith " + w + " of " + m.id());
            }
        }
        Set<String> themeIds = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (ShardlightTheme t : themes) {
            require(t.id() != null && THEME_ID.matcher(t.id()).matches(), "theme id " + t.id());
            require(themeIds.add(t.id()), "duplicate theme " + t.id());
            require(t.displayName() != null && t.packTitle() != null, "names of " + t.id());
            for (String c : new String[] {t.accent(), t.panel(), t.edge()}) {
                require(c != null && COLOUR.matcher(c).matches(), "colour " + c + " of " + t.id());
            }
            for (String file : new String[] {t.uiPackFile(), t.soundPackFile()}) {
                require(file != null && PathSafety.isSafeFileName(file) && file.toLowerCase(Locale.ROOT).endsWith(".zip"),
                    "pack file " + file + " of " + t.id());
                require(files.add(file.toLowerCase(Locale.ROOT)), "duplicate pack file " + file);
            }
        }
    }

    private static void require(boolean ok, String what) throws LauncherException {
        if (!ok) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.catalog.invalid", what);
        }
    }

    /** the JSON file */
    private static final class CatalogFile {
        int schemaVersion;
        List<ShardlightModule> modules = new ArrayList<>();
        List<ShardlightTheme> themes = new ArrayList<>();
    }
}
