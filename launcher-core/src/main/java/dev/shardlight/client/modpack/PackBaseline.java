package dev.shardlight.client.modpack;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.util.AtomicFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What a modpack installed, as it was shipped: every file's path (relative to the game directory) and SHA-1.
 * Saved next to {@code project.json} as {@code modpack-baseline.json} when a pack is imported or updated. An
 * update compares the pack's new files with this record and with the files on disk, so it can tell a file the
 * player changed from one only the pack changed.
 */
public final class PackBaseline {
    public static final String FILE = "modpack-baseline.json";
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** "mrpack" or "curseforge" */
    public String format;
    public String packName;
    public String packVersion;
    public String minecraft;
    public LoaderSpec loader = LoaderSpec.vanilla();
    /** game-directory-relative path with forward slashes to the SHA-1 the pack shipped */
    public TreeMap<String, String> files = new TreeMap<>();

    /** the baseline of the project in {@code projectDir}, or empty when it wasn't made from a pack */
    public static Optional<PackBaseline> read(Path projectDir) throws LauncherException {
        Path file = projectDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        PackBaseline b = AtomicFiles.readJson(file, PackBaseline.class, () -> null).value();
        return b == null || b.files == null ? Optional.empty() : Optional.of(b);
    }

    public void write(Path projectDir) throws LauncherException {
        AtomicFiles.writeJson(projectDir.resolve(FILE), this);
    }
}
