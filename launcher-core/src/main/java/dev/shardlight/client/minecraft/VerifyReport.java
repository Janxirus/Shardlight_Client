package dev.shardlight.client.minecraft;

import java.nio.file.Path;
import java.util.List;

/**
 * The result of {@link GameInstaller#verify}: every expected file was checked against its size and hash.
 *
 * @param versionId    the version checked
 * @param checkedFiles how many files were checked
 * @param missing      files that don't exist
 * @param corrupt      files whose size or hash is wrong
 */
public record VerifyReport(String versionId, int checkedFiles, List<Path> missing, List<Path> corrupt) {
    public VerifyReport {
        missing = List.copyOf(missing);
        corrupt = List.copyOf(corrupt);
    }

    /** true when nothing is missing or corrupt */
    public boolean isOk() {
        return missing.isEmpty() && corrupt.isEmpty();
    }

    public int problemCount() {
        return missing.size() + corrupt.size();
    }
}
