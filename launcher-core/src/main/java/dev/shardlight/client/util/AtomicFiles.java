package dev.shardlight.client.util;

import com.google.gson.JsonParseException;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Crash-safe file writes: data goes to a temporary file in the same folder, is flushed to disk and then
 * moved over the target in one step, so a crash or power cut leaves either the old or the new file, never
 * half of one. Writes to the same path are serialized inside this process.
 */
public final class AtomicFiles {
    private static final Logger LOG = Logger.getLogger(AtomicFiles.class.getName());
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AtomicFiles() {
    }

    /** the in-process lock guarding one file; hold it for read-modify-write sequences */
    public static ReentrantLock lockFor(Path file) {
        return LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), p -> new ReentrantLock());
    }

    public static void writeString(Path file, String text) throws LauncherException {
        writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytes(Path file, byte[] data) throws LauncherException {
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + file.getFileName() + ".", ".tmp");
            try {
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                    ch.force(true);
                }
                moveReplacing(tmp, file);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        } finally {
            lock.unlock();
        }
    }

    /** move {@code from} over {@code to}, atomically where the file system allows it */
    public static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void writeJson(Path file, Object value) throws LauncherException {
        writeString(file, Json.write(value) + "\n");
    }

    /**
     * Read a JSON file. A missing file gives {@code defaults}. A file that can't be parsed is renamed to
     * {@code <name>.corrupt-<time>} (so nothing is lost) and {@code defaults} is returned; the caller can
     * tell the user through {@link ReadResult#recoveredFrom()}.
     */
    public static <T> ReadResult<T> readJson(Path file, Class<T> type, Supplier<T> defaults) throws LauncherException {
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return new ReadResult<>(defaults.get(), null);
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            try {
                T v = Json.read(text, type);
                if (v == null) {
                    throw new JsonParseException("empty file");
                }
                return new ReadResult<>(v, null);
            } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
                Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
                LOG.warning("Unreadable " + file + " was moved to " + aside.getFileName() + ": " + e.getMessage());
                return new ReadResult<>(defaults.get(), aside);
            }
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.IO, e, "error.io", file.toString(), e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** the value read, and where an unreadable file was moved to (null when the file was fine or missing) */
    public record ReadResult<T>(T value, Path recoveredFrom) {
        public boolean wasRecovered() {
            return recoveredFrom != null;
        }
    }
}
