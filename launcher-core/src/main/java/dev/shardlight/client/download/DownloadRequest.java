package dev.shardlight.client.download;

import dev.shardlight.client.util.HashAlgo;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * One file to fetch. {@code hash} (with {@code hashAlgo}) and {@code size} are checked after the download
 * when known; a file that already exists and passes the check is not downloaded again.
 *
 * @param url       where to download from
 * @param mirrors   other URLs tried, in order, when {@code url} fails
 * @param target    final location; the file only appears there after it was verified
 * @param hashAlgo  algorithm of {@code hash}, or null when no hash is known
 * @param hash      expected hex hash, or null
 * @param size      expected size in bytes, or -1 when unknown
 * @param label     short name for the downloads list
 * @param cacheable true to share the verified file through the launcher's download cache (needs a SHA-1)
 * @param executable mark the file executable (Java runtimes on Linux/macOS)
 */
public record DownloadRequest(URI url, List<URI> mirrors, Path target, HashAlgo hashAlgo, String hash, long size,
                              String label, boolean cacheable, boolean executable) {
    public DownloadRequest {
        mirrors = mirrors == null ? List.of() : List.copyOf(mirrors);
        if (label == null || label.isBlank()) {
            label = target.getFileName().toString();
        }
    }

    public static DownloadRequest of(URI url, Path target) {
        return new DownloadRequest(url, List.of(), target, null, null, -1, null, false, false);
    }

    public static DownloadRequest sha1(URI url, Path target, String sha1, long size) {
        return new DownloadRequest(url, List.of(), target, sha1 == null ? null : HashAlgo.SHA1, sha1, size, null, false, false);
    }

    public DownloadRequest withLabel(String l) {
        return new DownloadRequest(url, mirrors, target, hashAlgo, hash, size, l, cacheable, executable);
    }

    public DownloadRequest withHash(HashAlgo algo, String h) {
        return new DownloadRequest(url, mirrors, target, algo, h, size, label, cacheable, executable);
    }

    public DownloadRequest withMirrors(List<URI> m) {
        return new DownloadRequest(url, m, target, hashAlgo, hash, size, label, cacheable, executable);
    }

    public DownloadRequest cached() {
        return new DownloadRequest(url, mirrors, target, hashAlgo, hash, size, label, true, executable);
    }

    public DownloadRequest asExecutable() {
        return new DownloadRequest(url, mirrors, target, hashAlgo, hash, size, label, cacheable, true);
    }

    public boolean hasHash() {
        return hashAlgo != null && hash != null && !hash.isBlank();
    }
}
