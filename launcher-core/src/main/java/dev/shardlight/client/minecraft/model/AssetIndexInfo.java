package dev.shardlight.client.minecraft.model;

/**
 * {@code assetIndex} of a version.
 *
 * @param id        index name ({@code 19}, {@code 1.8}, {@code legacy}, {@code pre-1.6})
 * @param sha1      SHA-1 of the index file
 * @param size      size of the index file
 * @param totalSize size of every object the index lists
 * @param url       where to fetch the index
 */
public record AssetIndexInfo(String id, String sha1, Long size, Long totalSize, String url) {
}
