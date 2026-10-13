package dev.shardlight.client.project;

import java.nio.file.Path;

/**
 * One screenshot file.
 *
 * @param file      the image file
 * @param takenAt   when it was taken (from Minecraft's file name, else the file time), epoch milliseconds
 * @param sizeBytes file size
 */
public record Screenshot(Path file, long takenAt, long sizeBytes) {
}
