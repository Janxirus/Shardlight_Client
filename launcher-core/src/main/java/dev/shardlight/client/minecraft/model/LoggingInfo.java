package dev.shardlight.client.minecraft.model;

/** {@code logging} of a version; only the client side matters to a launcher. */
public record LoggingInfo(LoggingConfig client) {
}
