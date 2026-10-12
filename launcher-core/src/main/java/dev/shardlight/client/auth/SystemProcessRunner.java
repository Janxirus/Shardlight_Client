package dev.shardlight.client.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** {@link ProcessRunner} on {@link ProcessBuilder}; output is read on separate threads so nothing blocks. */
final class SystemProcessRunner implements ProcessRunner {
    /** cap per stream so a misbehaving tool can't fill memory */
    static final int MAX_OUTPUT = 1 << 20;

    @Override
    public Result run(List<String> command, String stdin, Duration timeout) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("empty command");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        // keep the tools' messages in English so they are predictable in logs
        pb.environment().put("LC_ALL", "C");
        Process p = pb.start();
        FutureTask<byte[]> out = new FutureTask<>(() -> drain(p.getInputStream()));
        FutureTask<byte[]> err = new FutureTask<>(() -> drain(p.getErrorStream()));
        Thread.ofPlatform().daemon().name("process-stdout").start(out);
        Thread.ofPlatform().daemon().name("process-stderr").start(err);
        try {
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                    in.flush();
                }
            } catch (IOException e) {
                // the program exited before reading its input; its exit code tells the rest
            }
            long millis = Math.max(1, timeout == null ? 30_000 : timeout.toMillis());
            if (!p.waitFor(millis, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new IOException(command.get(0) + " didn't finish within " + millis + " ms");
            }
            return new Result(p.exitValue(), text(out), text(err));
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + command.get(0), e);
        }
    }

    private static String text(FutureTask<byte[]> f) throws IOException, InterruptedException {
        try {
            return new String(f.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("couldn't read process output", e);
        }
    }

    private static byte[] drain(InputStream in) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (buf.size() < MAX_OUTPUT) {
                    buf.write(chunk, 0, Math.min(n, MAX_OUTPUT - buf.size()));
                }
            }
        } catch (IOException ignored) {
            // the stream closes when the process is killed
        }
        return buf.toByteArray();
    }
}
