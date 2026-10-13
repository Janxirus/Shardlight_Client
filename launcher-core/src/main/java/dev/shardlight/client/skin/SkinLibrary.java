package dev.shardlight.client.skin;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The skins the player has used, kept on this computer: one PNG per skin under {@code skins/} named by its hash,
 * and {@code skins/library.json} with the name, arm shape and dates. The same picture is stored once.
 */
public final class SkinLibrary {
    /** more than this and the oldest unused ones are not kept */
    public static final int MAX_SKINS = 200;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_NAME = 40;

    /** one saved skin */
    public static final class Entry {
        /** SHA-256 of the PNG, which is also the file name */
        public String id;
        public String name;
        /** "classic" or "slim" */
        public String variant = "classic";
        public long addedAt;
        public long lastUsedAt;

        public SkinVariant variant() {
            return SkinVariant.parse(variant);
        }
    }

    private static final class FileData {
        int schemaVersion = 1;
        List<Entry> skins = new ArrayList<>();
    }

    private final Path dir;
    private final Path index;
    private final Clock clock;

    public SkinLibrary(AppPaths paths) {
        this(paths.skins(), Clock.systemUTC());
    }

    public SkinLibrary(Path dir, Clock clock) {
        this.dir = dir;
        this.index = dir.resolve("library.json");
        this.clock = clock;
    }

    /** the saved skins, most recently used first */
    public List<Entry> list() throws LauncherException {
        ReentrantLock lock = AtomicFiles.lockFor(index);
        lock.lock();
        try {
            List<Entry> out = new ArrayList<>();
            for (Entry e : load().skins) {
                if (e != null && e.id != null && ID.matcher(e.id).matches() && Files.isRegularFile(file(e.id))) {
                    out.add(e);
                }
            }
            out.sort(Comparator.comparingLong((Entry e) -> e.lastUsedAt).reversed());
            return out;
        } finally {
            lock.unlock();
        }
    }

    public Optional<Entry> find(String id) throws LauncherException {
        return list().stream().filter(e -> e.id.equals(id)).findFirst();
    }

    /** the picture of a saved skin */
    public byte[] bytes(String id) throws LauncherException {
        if (id == null || !ID.matcher(id).matches()) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "skin.error.notInLibrary");
        }
        try {
            return Files.readAllBytes(file(id));
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.NOT_FOUND, e, "skin.error.notInLibrary");
        }
    }

    /**
     * Save a skin, or mark it as used again when this exact picture is already there (its name and arm shape
     * are updated). The picture is checked first; a file that is not a skin is refused.
     */
    public Entry add(String name, SkinVariant variant, byte[] png) throws LauncherException {
        SkinImage.readSkin(png);
        String id = Hashing.hash(png, HashAlgo.SHA256);
        ReentrantLock lock = AtomicFiles.lockFor(index);
        lock.lock();
        try {
            FileData data = load();
            long now = clock.millis();
            Entry e = data.skins.stream().filter(x -> id.equals(x.id)).findFirst().orElse(null);
            if (e == null) {
                e = new Entry();
                e.id = id;
                e.addedAt = now;
                data.skins.add(e);
            }
            e.name = cleanName(name);
            e.variant = variant.api();
            e.lastUsedAt = now;
            AtomicFiles.writeBytes(file(id), png);
            prune(data);
            AtomicFiles.writeJson(index, data);
            return e;
        } finally {
            lock.unlock();
        }
    }

    public void rename(String id, String name) throws LauncherException {
        edit(id, e -> e.name = cleanName(name));
    }

    /** the player wore this skin again */
    public void markUsed(String id) throws LauncherException {
        edit(id, e -> e.lastUsedAt = clock.millis());
    }

    public void remove(String id) throws LauncherException {
        if (id == null || !ID.matcher(id).matches()) {
            return;
        }
        ReentrantLock lock = AtomicFiles.lockFor(index);
        lock.lock();
        try {
            FileData data = load();
            data.skins.removeIf(e -> id.equals(e.id));
            AtomicFiles.writeJson(index, data);
            Files.deleteIfExists(file(id));
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        } finally {
            lock.unlock();
        }
    }

    private void edit(String id, java.util.function.Consumer<Entry> change) throws LauncherException {
        ReentrantLock lock = AtomicFiles.lockFor(index);
        lock.lock();
        try {
            FileData data = load();
            data.skins.stream().filter(e -> id != null && id.equals(e.id)).findFirst().ifPresent(change);
            AtomicFiles.writeJson(index, data);
        } finally {
            lock.unlock();
        }
    }

    private FileData load() throws LauncherException {
        FileData d = AtomicFiles.readJson(index, FileData.class, FileData::new).value();
        if (d.skins == null) {
            d.skins = new ArrayList<>();
        }
        d.skins.removeIf(e -> e == null);
        return d;
    }

    private void prune(FileData data) {
        if (data.skins.size() <= MAX_SKINS) {
            return;
        }
        data.skins.sort(Comparator.comparingLong((Entry e) -> e.lastUsedAt).reversed());
        while (data.skins.size() > MAX_SKINS) {
            Entry gone = data.skins.remove(data.skins.size() - 1);
            try {
                Files.deleteIfExists(file(gone.id));
            } catch (IOException ignored) {
                // the picture stays behind; it is not listed any more
            }
        }
    }

    private Path file(String id) {
        return dir.resolve(id + ".png");
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (n.isEmpty()) {
            n = "Skin";
        }
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME).strip() : n;
    }
}
