package dev.shardlight.client.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Probes a runtime by running {@code java -XshowSettings:properties -version} with a timeout and parsing the
 * properties it prints. Environment variables that inject JVM options are removed so they can't change
 * or break the output.
 */
public final class ProcessJavaProber implements JavaProber {
    private static final Logger LOG = Logger.getLogger(ProcessJavaProber.class.getName());
    private static final int MAX_OUTPUT = 1 << 20;

    private final Duration timeout;

    public ProcessJavaProber(Duration timeout) {
        this.timeout = timeout;
    }

    /** the default: 10 seconds */
    public ProcessJavaProber() {
        this(Duration.ofSeconds(10));
    }

    @Override
    public Optional<JavaProperties> probe(Path javaExecutable) {
        if (javaExecutable == null || !Files.isRegularFile(javaExecutable)) {
            return Optional.empty();
        }
        ProcessBuilder pb = new ProcessBuilder(javaExecutable.toAbsolutePath().toString(), "-XshowSettings:properties", "-version");
        pb.redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.environment().remove("JAVA_OPTIONS");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            LOG.fine(() -> "Can't run " + javaExecutable + ": " + e.getMessage());
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().name("java-probe").start(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) {
                        if (out.size() < MAX_OUTPUT) {
                            out.write(buf, 0, Math.min(n, MAX_OUTPUT - out.size()));
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process went away
            }
        });
        try {
            p.getOutputStream().close();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                LOG.info("Java probe timed out: " + javaExecutable);
                return Optional.empty();
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            p.destroyForcibly();
            return Optional.empty();
        }
        String text;
        synchronized (out) {
            text = out.toString(StandardCharsets.UTF_8);
        }
        return JavaProperties.parse(text);
    }
}
