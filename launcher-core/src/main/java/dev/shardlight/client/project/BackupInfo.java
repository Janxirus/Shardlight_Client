package dev.shardlight.client.project;

/**
 * One backup: {@code backups/<projectId>/<id>.zip} with this record as the sidecar {@code <id>.json}.
 *
 * @param id          file name without extension: {@code <yyyyMMdd-HHmmss>-<kind>[-<label>]}
 * @param projectId   the project it belongs to
 * @param kind        {@link #PROJECT} or {@link #WORLD}
 * @param worldFolder the world's folder in {@code saves/} for world backups, otherwise null
 * @param label       the user's label ({@link BackupService#PRE_RESTORE} for automatic safety copies)
 * @param createdAt   epoch milliseconds
 * @param sizeBytes   size of the zip
 * @param sha256      SHA-256 of the zip, checked before every restore
 * @param scope       what it contains
 */
public record BackupInfo(String id, String projectId, String kind, String worldFolder, String label, long createdAt,
                         long sizeBytes, String sha256, BackupScope scope) {
    public static final String PROJECT = "project";
    public static final String WORLD = "world";

    /** an automatic copy taken before a restore */
    public boolean isPreRestore() {
        return BackupService.PRE_RESTORE.equals(label);
    }
}
