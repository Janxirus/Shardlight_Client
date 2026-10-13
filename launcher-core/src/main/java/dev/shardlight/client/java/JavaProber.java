package dev.shardlight.client.java;

import java.nio.file.Path;
import java.util.Optional;

/** Runs a Java executable to learn its version; replaceable in tests. */
@FunctionalInterface
public interface JavaProber {
    /** the properties of {@code javaExecutable}, or empty when it doesn't run or isn't Java */
    Optional<JavaProperties> probe(Path javaExecutable);
}
