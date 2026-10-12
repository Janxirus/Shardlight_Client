package dev.shardlight.client.minecraft.model;

import java.util.Map;

/**
 * {@code downloads} of a library.
 *
 * @param artifact    the main jar, or null (native-only legacy libraries)
 * @param classifiers native jars by classifier ({@code natives-windows}, {@code natives-windows-64}...)
 */
public record LibraryDownloads(DownloadInfo artifact, Map<String, DownloadInfo> classifiers) {
    public LibraryDownloads {
        classifiers = ModelCopies.map(classifiers);
    }
}
