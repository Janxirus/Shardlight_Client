package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.PathSafety;

/**
 * A Maven coordinate {@code group:artifact:version[:classifier][@extension]} as used in version JSON
 * library names, and the repository path it maps to.
 */
public record MavenCoordinate(String group, String artifact, String version, String classifier, String extension) {

    /**
     * Parse a coordinate. Throws {@link ErrorCode#INVALID_DATA} for malformed names and
     * {@link ErrorCode#UNSAFE_PATH} when a part could escape the libraries folder.
     */
    public static MavenCoordinate parse(String coordinate) throws LauncherException {
        if (coordinate == null || coordinate.isBlank()) {
            throw bad(coordinate);
        }
        String c = coordinate.strip();
        String ext = "jar";
        int at = c.lastIndexOf('@');
        if (at >= 0) {
            ext = c.substring(at + 1);
            c = c.substring(0, at);
        }
        String[] parts = c.split(":", -1);
        if (parts.length < 3 || parts.length > 4) {
            throw bad(coordinate);
        }
        String classifier = parts.length == 4 ? parts[3] : null;
        MavenCoordinate m = new MavenCoordinate(parts[0], parts[1], parts[2], classifier, ext);
        m.validate(coordinate);
        return m;
    }

    private void validate(String original) throws LauncherException {
        if (group.isEmpty() || artifact.isEmpty() || version.isEmpty() || extension.isEmpty()
            || (classifier != null && classifier.isEmpty())) {
            throw bad(original);
        }
        for (String g : group.split("\\.", -1)) {
            requireSafe(g, original);
        }
        requireSafe(artifact, original);
        requireSafe(version, original);
        requireSafe(extension, original);
        if (classifier != null) {
            requireSafe(classifier, original);
        }
        requireSafe(fileName(), original);
    }

    private static void requireSafe(String part, String original) throws LauncherException {
        if (!PathSafety.isSafeSegment(part)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(original));
        }
    }

    private static LauncherException bad(String coordinate) {
        return new LauncherException(ErrorCode.INVALID_DATA, "install.error.badCoordinate", String.valueOf(coordinate));
    }

    /** {@code artifact-version[-classifier].ext} */
    public String fileName() {
        return artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + "." + extension;
    }

    /** repository path with {@code /} separators: {@code org/ow2/asm/asm/9.7/asm-9.7.jar} */
    public String path() {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName();
    }

    /** identity used to de-duplicate libraries: {@code group:artifact[:classifier]} (version ignored) */
    public String key() {
        return group + ":" + artifact + (classifier == null ? "" : ":" + classifier);
    }

    public MavenCoordinate withClassifier(String newClassifier) {
        return new MavenCoordinate(group, artifact, version, newClassifier, extension);
    }

    /** true for {@code natives-*} classifiers (modern LWJGL native jars listed as normal libraries) */
    public boolean isNativesClassifier() {
        return classifier != null && classifier.startsWith("natives-");
    }

    @Override
    public String toString() {
        return group + ":" + artifact + ":" + version + (classifier == null ? "" : ":" + classifier)
            + ("jar".equals(extension) ? "" : "@" + extension);
    }
}
