package dev.shardlight.client.minecraft.model;

/** {@code logging.client.file}: the Log4j configuration file. */
public record LoggingFile(String id, String sha1, Long size, String url) {
}
