package dev.shardlight.client.minecraft.model;

/** One object of an asset index: SHA-1 and size. */
public record AssetObject(String hash, Long size) {
    public long sizeOrUnknown() {
        return size == null || size < 0 ? -1 : size;
    }
}
