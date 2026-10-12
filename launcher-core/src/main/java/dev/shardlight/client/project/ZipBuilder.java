package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import dev.shardlight.client.util.PathSafety;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a zip that {@code SafeZip} will accept again: every entry name is checked segment by segment with
 * {@link PathSafety}, names that differ only in case are refused (they would collide on Windows), links
 * and junctions are skipped, and the SHA-256 of the archive is computed while writing. Files that are
 * already compressed (region files, jars, images) are deflated at the fastest level.
 */
final class ZipBuilder implements Closeable {
    private static final Set<String> PACKED = Set.of("mca", "mcr", "jar", "zip", "png", "jpg", "jpeg", "ogg", "gz", "dat", "mrpack");

    private final FileChannel channel;
    private final MessageDigest digest = HashAlgo.SHA256.newDigest();
    private final ZipOutputStream zip;
    private final Set<String> names = new HashSet<>();
    private final byte[] buf = new byte[64 * 1024];
    private final ProgressListener progress;
    private final String stageKey;
    private final long total;
    private final CancellationToken cancel;
    private long done;
    private int level = -2;
    private boolean finished;

    /** create {@code file} (which must not exist yet) */
    ZipBuilder(Path file, ProgressListener progress, String stageKey, long total, CancellationToken cancel) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        OutputStream out = new BufferedOutputStream(new DigestOutputStream(Channels.newOutputStream(channel), digest), 64 * 1024);
        this.zip = new ZipOutputStream(out);
        this.progress = progress == null ? ProgressListener.NONE : progress;
        this.stageKey = stageKey;
        this.total = total;
        this.cancel = cancel == null ? CancellationToken.NONE : cancel;
    }

    /** a small in-memory file */
    void addBytes(String name, byte[] data) throws IOException, LauncherException {
        ZipEntry e = new ZipEntry(checkName(name, false));
        setLevel(name);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    /** a folder entry ({@code name} ends with '/') */
    void addDirectory(String name) throws IOException, LauncherException {
        String n = name.endsWith("/") ? name : name + "/";
        ZipEntry e = new ZipEntry(checkName(n, true));
        zip.putNextEntry(e);
        zip.closeEntry();
    }

    /**
     * One file from disk. A file another program holds open with a lock fails with the message key
     * {@code project.error.fileLocked}.
     */
    void addFile(String name, Path file) throws IOException, LauncherException {
        BasicFileAttributes a = FileTrees.attrs(file);
        if (FileTrees.isLinkLike(a) || !a.isRegularFile()) {
            return;
        }
        addFile(name, file, a);
    }

    private void addFile(String name, Path file, BasicFileAttributes a) throws IOException, LauncherException {
        String entryName = checkName(name, false);
        cancel.check();
        InputStream in;
        try {
            in = Files.newInputStream(file);
        } catch (IOException ex) {
            throw readError(ex, file);
        }
        try (in) {
            ZipEntry e = new ZipEntry(entryName);
            e.setLastModifiedTime(a.lastModifiedTime());
            setLevel(name);
            zip.putNextEntry(e);
            int n;
            while (true) {
                try {
                    n = in.read(buf);
                } catch (IOException ex) {
                    throw readError(ex, file);
                }
                if (n < 0) {
                    break;
                }
                zip.write(buf, 0, n);
                done += n;
                cancel.check();
            }
            zip.closeEntry();
            progress.progress(stageKey, name, done, total);
        }
    }

    /**
     * A folder and everything under it, as {@code prefix/...}. {@code include} receives paths relative to
     * {@code dir} with '/' separators. Nothing is added when {@code dir} is missing or a link.
     */
    void addTree(String prefix, Path dir, Predicate<String> include) throws IOException, LauncherException {
        if (!FileTrees.isPlainDirectory(dir)) {
            return;
        }
        addDirectory(prefix + "/");
        addChildren(prefix, dir, "", include);
    }

    private void addChildren(String prefix, Path dir, String rel, Predicate<String> include) throws IOException, LauncherException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                entries.add(p);
            }
        }
        entries.sort(null);
        for (Path child : entries) {
            cancel.check();
            String name = child.getFileName().toString();
            String childRel = rel.isEmpty() ? name : rel + "/" + name;
            BasicFileAttributes a = FileTrees.attrs(child);
            if (FileTrees.isLinkLike(a) || (include != null && !include.test(childRel))) {
                continue;
            }
            if (a.isDirectory()) {
                ZipEntry e = new ZipEntry(checkName(prefix + "/" + childRel + "/", true));
                e.setLastModifiedTime(a.lastModifiedTime());
                zip.putNextEntry(e);
                zip.closeEntry();
                addChildren(prefix, child, childRel, include);
            } else if (a.isRegularFile()) {
                addFile(prefix + "/" + childRel, child, a);
            }
        }
    }

    /** finish the archive, flush it to disk and return its SHA-256 */
    String finish() throws IOException {
        zip.finish();
        zip.flush();
        channel.force(true);
        finished = true;
        return Hashing.hex(digest.digest());
    }

    /** bytes of file content added so far */
    long bytesAdded() {
        return done;
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } catch (IOException e) {
            if (finished) {
                throw e;
            }
            // an unfinished archive is discarded by the caller anyway
        } finally {
            channel.close();
        }
    }

    private void setLevel(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        int l = PACKED.contains(ext) ? Deflater.BEST_SPEED : Deflater.DEFAULT_COMPRESSION;
        if (l != level) {
            zip.setLevel(l);
            level = l;
        }
    }

    private String checkName(String name, boolean directory) throws LauncherException {
        String n = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : n.split("/", -1)) {
            if (!PathSafety.isSafeSegment(part)) {
                throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "project.zip.unsafeName", n);
            }
        }
        if (!names.add(n.toLowerCase(Locale.ROOT))) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, "project.zip.caseClash", n);
        }
        return directory ? n + "/" : n;
    }

    private static LauncherException readError(IOException e, Path file) {
        return FileTrees.isLockError(e)
            ? new LauncherException(ErrorCode.IO, e, "project.error.fileLocked", file.toString())
            : LauncherException.io(e, file.toString());
    }
}
