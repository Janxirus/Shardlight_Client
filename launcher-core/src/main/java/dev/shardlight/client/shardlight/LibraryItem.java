package dev.shardlight.client.shardlight;

import java.nio.file.Path;

/**
 * One file in the Shardlight content library ({@code <data>/content}).
 *
 * @param id              stable id of the item inside the library
 * @param kind            {@link #MODULE}, {@link #UI_PACK} or {@link #SOUND_PACK}
 * @param moduleOrThemeId the module id ({@code ui}, {@code map}...) or theme id ({@code EMBER}...)
 * @param version         the jar's version from fabric.mod.json; packs carry no version, so for them it is
 *                        the first eight hex digits of the file's SHA-256 (tells two builds apart)
 * @param file            absolute path of the stored file
 * @param sha256          SHA-256 of the file, lowercase hex
 * @param size            size in bytes
 * @param importedAt      when it was added, epoch milliseconds
 */
public record LibraryItem(String id, String kind, String moduleOrThemeId, String version, Path file, String sha256,
                          long size, long importedAt) {
    /** a Shardlight mod jar */
    public static final String MODULE = "module";
    /** a Shardlight UI texture pack */
    public static final String UI_PACK = "ui-pack";
    /** a Shardlight Sounds pack */
    public static final String SOUND_PACK = "sound-pack";

    public boolean isModule() {
        return MODULE.equals(kind);
    }

    public boolean isPack() {
        return UI_PACK.equals(kind) || SOUND_PACK.equals(kind);
    }

    /** the stored file's name */
    public String fileName() {
        return file.getFileName().toString();
    }
}
