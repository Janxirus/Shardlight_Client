package dev.shardlight.client.minecraft.model;

/**
 * One downloadable file as Mojang (and loader profiles) describe it: {@code downloads.client},
 * {@code downloads.artifact}, a classifier entry. Every field may be absent.
 *
 * @param path library path relative to the libraries folder (libraries only)
 * @param sha1 expected SHA-1, hex
 * @param size expected size in bytes
 * @param url  where to fetch it; an empty string means "generated locally" (Forge/NeoForge processors)
 */
public record DownloadInfo(String path, String sha1, Long size, String url) {
    /** the size, or -1 when unknown */
    public long sizeOrUnknown() {
        return size == null || size <= 0 ? -1 : size;
    }

    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }
}
