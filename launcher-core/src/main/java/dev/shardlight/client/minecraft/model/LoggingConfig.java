package dev.shardlight.client.minecraft.model;

/**
 * {@code logging.client}.
 *
 * @param argument JVM argument with a {@code ${path}} placeholder for the file
 * @param file     the configuration file
 * @param type     {@code log4j2-xml}
 */
public record LoggingConfig(String argument, LoggingFile file, String type) {
}
