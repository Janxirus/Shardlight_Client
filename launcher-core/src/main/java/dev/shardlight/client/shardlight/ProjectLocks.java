package dev.shardlight.client.shardlight;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per project shared by the Shardlight module installer and the preset engine, so installing
 * modules, writing presets and saving project.json for the same project never interleave.
 */
public final class ProjectLocks {
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ProjectLocks() {
    }

    /** the lock for a project id (ids are compared case-insensitively, as Windows folder names are) */
    public static ReentrantLock lockFor(String projectId) {
        String key = projectId == null ? "" : projectId.toLowerCase(Locale.ROOT);
        return LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
    }
}
