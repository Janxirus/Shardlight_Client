package dev.shardlight.client.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.storage.JsonMigrator;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Loads, migrates and saves settings.json. Readers get a snapshot copy; changes go through
 * {@link #update} which saves atomically and notifies listeners.
 */
public final class SettingsService {
    private static final Logger LOG = Logger.getLogger(SettingsService.class.getName());
    static final JsonMigrator MIGRATOR = new JsonMigrator("settings.json", LauncherSettings.SCHEMA_VERSION)
        // 0 -> 1: the first versioned schema; nothing to move
        .step(0, o -> { });

    private final Path file;
    private LauncherSettings settings;
    private Path recoveredFrom;
    private boolean readOnly;
    private final List<Consumer<LauncherSettings>> listeners = new CopyOnWriteArrayList<>();

    public SettingsService(AppPaths paths) throws LauncherException {
        this.file = paths.settingsFile();
        load();
    }

    private synchronized void load() throws LauncherException {
        settings = new LauncherSettings();
        if (!Files.exists(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (MIGRATOR.isNewer(o)) {
                // written by a newer launcher: use it, but don't overwrite it
                readOnly = true;
                LOG.warning("settings.json is from a newer Shardlight Client; changes won't be saved");
            }
            boolean migrated = MIGRATOR.migrate(o);
            LauncherSettings s = Json.GSON.fromJson(o, LauncherSettings.class);
            settings = fillDefaults(s);
            if (migrated) {
                Files.copy(file, file.resolveSibling("settings.json.pre-migration"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                save();
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            Path aside = file.resolveSibling("settings.json.corrupt-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.move(file, aside);
            } catch (IOException io) {
                throw LauncherException.io(io, file.toString());
            }
            recoveredFrom = aside;
            LOG.warning("settings.json was unreadable and was moved to " + aside.getFileName());
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
    }

    /** older files may miss whole sections; Gson leaves them null */
    static LauncherSettings fillDefaults(LauncherSettings s) {
        LauncherSettings d = new LauncherSettings();
        if (s.appearance == null) s.appearance = d.appearance;
        if (s.downloads == null) s.downloads = d.downloads;
        if (s.java == null) s.java = d.java;
        if (s.privacy == null) s.privacy = d.privacy;
        if (s.catalog == null) s.catalog = d.catalog;
        if (s.auth == null) s.auth = d.auth;
        if (s.updates == null) s.updates = d.updates;
        if (s.recentProjects == null) s.recentProjects = d.recentProjects;
        s.downloads.maxConcurrent = Math.max(1, Math.min(16, s.downloads.maxConcurrent));
        s.downloads.maxRetries = Math.max(0, Math.min(10, s.downloads.maxRetries));
        s.appearance.scale = Math.max(0.8, Math.min(1.6, s.appearance.scale));
        return s;
    }

    /** a copy of the current settings */
    public synchronized LauncherSettings get() {
        return Json.copy(settings, LauncherSettings.class);
    }

    /** apply {@code change} to a copy, save it, then notify listeners */
    public void update(Consumer<LauncherSettings> change) throws LauncherException {
        LauncherSettings snapshot;
        synchronized (this) {
            LauncherSettings copy = Json.copy(settings, LauncherSettings.class);
            change.accept(copy);
            settings = fillDefaults(copy);
            save();
            snapshot = get();
        }
        for (Consumer<LauncherSettings> l : listeners) {
            l.accept(snapshot);
        }
    }

    private void save() throws LauncherException {
        if (!readOnly) {
            AtomicFiles.writeJson(file, settings);
        }
    }

    /** where an unreadable settings file was moved to, or null */
    public Path recoveredFrom() {
        return recoveredFrom;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void addListener(Consumer<LauncherSettings> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<LauncherSettings> l) {
        listeners.remove(l);
    }
}
