package dev.shardlight.client.launch;

import dev.shardlight.client.log.Redactor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A running game started by {@link LaunchService}. Its console output is read on a background thread, passed
 * through {@link Redactor} and written to {@code <gameDir>/logs/shardlight-launch.log}; the last
 * {@link #RECENT_LINES} lines stay in memory for the crash analysis. {@link #onExit()} completes once the
 * process has ended and its output has been read.
 */
public final class GameProcess {
    private static final Logger LOG = Logger.getLogger(GameProcess.class.getName());
    /** lines of output kept in memory */
    public static final int RECENT_LINES = 500;
    /** longer lines are cut (some mods print huge single lines) */
    static final int MAX_LINE = 4000;
    /** how long {@link #stop()} waits for the game to close before ending it forcibly */
    static final long STOP_GRACE_MILLIS = 10_000;

    private final String projectId;
    private final Process process;
    private final Instant startedAt;
    private final Path logFile;
    private final Deque<String> recent = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final CompletableFuture<GameExit> exit = new CompletableFuture<>();
    private volatile boolean stopRequested;

    private GameProcess(String projectId, Process process, Instant startedAt, Path logFile) {
        this.projectId = projectId;
        this.process = process;
        this.startedAt = startedAt;
        this.logFile = logFile;
    }

    /**
     * Start reading the output of {@code process} and watching for its end. {@code onEnded} turns the finished
     * process into a {@link GameExit} (crash detection and analysis); it runs on the watcher thread.
     */
    static GameProcess watch(String projectId, Process process, Instant startedAt, Path logFile, String header,
                             Function<GameProcess, GameExit> onEnded) {
        GameProcess g = new GameProcess(projectId, process, startedAt, logFile);
        Thread pump = new Thread(() -> g.pump(header), "game-output-" + projectId);
        pump.setDaemon(true);
        pump.start();
        Thread watcher = new Thread(() -> g.awaitEnd(pump, onEnded), "game-watch-" + projectId);
        watcher.setDaemon(true);
        watcher.start();
        return g;
    }

    public String projectId() {
        return projectId;
    }

    public long pid() {
        return process.pid();
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** the redacted copy of the game's console output */
    public Path logFile() {
        return logFile;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** true once {@link #stop()} was called */
    public boolean stopRequested() {
        return stopRequested;
    }

    /** completes when the game has ended; never completes exceptionally */
    public CompletableFuture<GameExit> onExit() {
        return exit;
    }

    /** receive every new (redacted) output line, on the reader thread; keep it fast */
    public void addLineListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeLineListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    /** the last {@link #RECENT_LINES} redacted output lines, oldest first */
    public List<String> recentLines() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /**
     * Ask the game to close (like closing its window), and end it forcibly when it hasn't closed after a few
     * seconds. Unsaved progress since the last autosave can be lost; the UI asks first.
     */
    public void stop() {
        if (!process.isAlive()) {
            return;
        }
        stopRequested = true;
        LOG.info("Stopping the game of project " + projectId + " (pid " + process.pid() + ")");
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        process.onExit().orTimeout(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS).whenComplete((p, err) -> {
            if (err != null && process.isAlive()) {
                LOG.warning("The game of project " + projectId + " didn't close; ending it");
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        });
    }

    private void pump(String header) {
        Charset cs = consoleCharset();
        BufferedWriter out = openLog(header);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), cs))) {
            String line;
            while ((line = in.readLine()) != null) {
                String shown = Redactor.redact(line.length() > MAX_LINE ? line.substring(0, MAX_LINE) + " ..." : line);
                synchronized (recent) {
                    if (recent.size() == RECENT_LINES) {
                        recent.removeFirst();
                    }
                    recent.addLast(shown);
                }
                if (out != null) {
                    try {
                        out.write(shown);
                        out.newLine();
                        out.flush();
                    } catch (IOException e) {
                        LOG.log(Level.FINE, "Stopped copying the game output to " + logFile, e);
                        closeQuietly(out);
                        out = null;
                    }
                }
                for (Consumer<String> l : listeners) {
                    try {
                        l.accept(shown);
                    } catch (RuntimeException e) {
                        LOG.log(Level.FINE, "A game output listener failed", e);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Stopped reading the game output", e);
        } finally {
            closeQuietly(out);
        }
    }

    private BufferedWriter openLog(String header) {
        if (logFile == null) {
            return null;
        }
        try {
            Files.createDirectories(logFile.getParent());
            BufferedWriter w = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
            w.write(Redactor.redact(header));
            w.newLine();
            w.flush();
            return w;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Couldn't write the game output to " + logFile, e);
            return null;
        }
    }

    private void awaitEnd(Thread pump, Function<GameProcess, GameExit> onEnded) {
        try {
            process.waitFor();
            pump.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        GameExit result;
        try {
            result = onEnded.apply(this);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Couldn't analyse how the game of project " + projectId + " ended", e);
            int code = process.isAlive() ? -1 : process.exitValue();
            result = new GameExit(code, code != 0 && !stopRequested, stopRequested, null,
                java.time.Duration.between(startedAt, Instant.now()), List.of());
        }
        exit.complete(result);
    }

    /** the game writes its console in the platform's native encoding */
    private static Charset consoleCharset() {
        String name = System.getProperty("native.encoding");
        try {
            return name == null ? Charset.defaultCharset() : Charset.forName(name);
        } catch (RuntimeException e) {
            return Charset.defaultCharset();
        }
    }

    private static void closeQuietly(BufferedWriter w) {
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // nothing more to do
            }
        }
    }

    @Override
    public String toString() {
        return "GameProcess[" + projectId + ", pid " + process.pid() + (process.isAlive() ? ", running" : ", ended") + "]";
    }
}
