package dev.shardlight.client.minecraft;

import dev.shardlight.client.download.DownloadRequest;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One file an installed version needs.
 *
 * @param path  where it belongs
 * @param url   where to fetch it, or null when it can only be created locally (loader processors, copies)
 * @param algo  hash algorithm of {@code hash}, or null
 * @param hash  expected hex hash, or null when unknown
 * @param size  expected size, or -1 when unknown
 * @param label short name for the downloads list
 */
record PlannedFile(Path path, URI url, HashAlgo algo, String hash, long size, String label) {
    boolean downloadable() {
        return url != null;
    }

    boolean hasHash() {
        return algo != null && hash != null;
    }

    DownloadRequest request() {
        return new DownloadRequest(url, List.of(), path, hasHash() ? algo : null, hasHash() ? hash : null, size, label,
            false, false);
    }

    /** the result of checking the file on disk */
    enum Check { OK, MISSING, CORRUPT }

    /** full check: existence, size and (when known) hash */
    Check check() {
        if (!Files.isRegularFile(path)) {
            return Check.MISSING;
        }
        try {
            if (size >= 0 && Files.size(path) != size) {
                return Check.CORRUPT;
            }
            if (hasHash() && !Hashing.hash(path, algo).equalsIgnoreCase(hash)) {
                return Check.CORRUPT;
            }
            return Check.OK;
        } catch (IOException e) {
            return Check.CORRUPT;
        }
    }

    /** quick check: exists and (when known) has the right size */
    boolean presentWithSize() {
        try {
            return Files.isRegularFile(path) && (size < 0 || Files.size(path) == size);
        } catch (IOException e) {
            return false;
        }
    }
}
