package dev.shardlight.client.java;

import java.nio.file.Path;

/**
 * A Java runtime that can start the game.
 *
 * @param executable   the program to launch the game with ({@code javaw.exe} on Windows when present, so no
 *                     console window opens; {@code java} elsewhere)
 * @param majorVersion Java feature release (8, 17, 21, 25...)
 * @param version      full version ({@code 21.0.7}, {@code 1.8.0_402})
 * @param vendor       {@code java.vendor} (Eclipse Adoptium, Microsoft, Oracle Corporation...)
 * @param arch         {@code os.arch} of the runtime (amd64, x86, aarch64)
 * @param managed      true for runtimes the launcher downloaded itself (Mojang's)
 */
public record JavaInstall(Path executable, int majorVersion, String version, String vendor, String arch, boolean managed) {
    /** the folder holding the executable ({@code <java home>/bin}) */
    public Path binDir() {
        return executable.toAbsolutePath().getParent();
    }

    @Override
    public String toString() {
        return "Java " + version + " (" + vendor + ", " + arch + (managed ? ", managed" : "") + ") at " + executable;
    }
}
