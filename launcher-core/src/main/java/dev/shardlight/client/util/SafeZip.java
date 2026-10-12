package dev.shardlight.client.util;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.task.CancellationToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Zip reading and extraction that can't be abused: every entry name is checked with {@link PathSafety}
 * (no {@code ..}, absolute paths, drive letters or reserved names), duplicate names are rejected, the
 * number of entries, the total uncompressed size and the compression ratio are capped (zip bombs), and
 * extraction never follows a symbolic link in the destination. Java's zip API never creates links, so a
 * "symlink" entry can only ever become a plain file.
 */
public final class SafeZip {
    /** caps for one archive */
    public record Limits(int maxEntries, long maxTotalBytes, long maxEntryBytes, double maxRatio) {
        /** modpacks, project exports, backups */
        public static final Limits DEFAULT = new Limits(100_000, 8L << 30, 2L << 30, 200);
        /** resource packs and small archives */
        public static final Limits SMALL = new Limits(20_000, 1L << 30, 512L << 20, 200);
    }

    /** one checked entry */
    public record Entry(String name, long size, boolean directory) {
    }

    private SafeZip() {
    }

    /** list and validate every entry; throws {@link ErrorCode#INVALID_ARCHIVE} / {@link ErrorCode#UNSAFE_PATH} */
    public static List<Entry> list(Path zip, Limits limits) throws LauncherException {
        List<Entry> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Set<String> seen = new HashSet<>();
            long total = 0;
            int count = 0;
            Path probe = Path.of("probe").toAbsolutePath();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++count > limits.maxEntries()) {
                    throw invalid(zip, "too many entries");
                }
                String name = e.getName();
                PathSafety.resolveInside(probe, name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                String key = name.toLowerCase(Locale.ROOT).replace('\\', '/');
                if (!seen.add(key)) {
                    throw invalid(zip, "duplicate entry " + name);
                }
                long size = e.getSize();
                if (!e.isDirectory()) {
                    if (size > limits.maxEntryBytes()) {
                        throw invalid(zip, "entry too large: " + name);
                    }
                    long comp = e.getCompressedSize();
                    if (size > 1_000_000 && comp > 0 && size / (double) comp > limits.maxRatio()) {
                        throw invalid(zip, "suspicious compression ratio: " + name);
                    }
                    total += Math.max(0, size);
                    if (total > limits.maxTotalBytes()) {
                        throw invalid(zip, "archive too large when unpacked");
                    }
                }
                out.add(new Entry(name, size, e.isDirectory()));
            }
        } catch (ZipException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "error.archive.invalid", zip.getFileName().toString(), e.getMessage());
        } catch (IOException e) {
            throw LauncherException.io(e, zip.toString());
        }
        return out;
    }

    /** read one entry fully (small files such as manifests); null when absent */
    public static byte[] read(Path zip, String entryName, long maxBytes) throws LauncherException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry e = zf.getEntry(entryName);
            if (e == null || e.isDirectory()) {
                return null;
            }
            try (InputStream in = zf.getInputStream(e)) {
                byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                if (data.length > maxBytes) {
                    throw invalid(zip, "entry too large: " + entryName);
                }
                return data;
            }
        } catch (ZipException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "error.archive.invalid", zip.getFileName().toString(), e.getMessage());
        } catch (IOException e) {
            throw LauncherException.io(e, zip.toString());
        }
    }

    /**
     * Extract the entries under {@code prefix} (for example {@code "overrides/"}; empty for all) into
     * {@code dest}, with the prefix stripped. {@code accept} filters the stripped names. Returns the files
     * written. The archive is fully validated before anything is written.
     */
    public static List<Path> extract(Path zip, String prefix, Path dest, Limits limits, Predicate<String> accept,
                                     CancellationToken cancel) throws LauncherException {
        list(zip, limits);
        String pre = prefix == null ? "" : prefix.replace('\\', '/');
        List<Path> written = new ArrayList<>();
        long total = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Files.createDirectories(dest);
            Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[64 * 1024];
            while (en.hasMoreElements()) {
                cancel.check();
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (!name.startsWith(pre)) {
                    continue;
                }
                String rel = name.substring(pre.length());
                if (rel.isEmpty() || rel.equals("/")) {
                    continue;
                }
                if (accept != null && !accept.test(rel)) {
                    continue;
                }
                String clean = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
                Path out = PathSafety.resolveInside(dest, clean);
                PathSafety.requireNoLinkEscape(dest, out.getParent());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                if (Files.isSymbolicLink(out)) {
                    Files.delete(out); // replace the link itself, never write through it
                }
                if (Files.isDirectory(out, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid(zip, "entry collides with a folder: " + rel);
                }
                Path tmp = out.resolveSibling(out.getFileName() + ".unzip-tmp");
                long entryBytes = 0;
                try (InputStream in = zf.getInputStream(e); OutputStream os = Files.newOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        entryBytes += n;
                        total += n;
                        if (entryBytes > limits.maxEntryBytes() || total > limits.maxTotalBytes()) {
                            throw invalid(zip, "archive expands beyond its declared size");
                        }
                        os.write(buf, 0, n);
                    }
                } catch (LauncherException | IOException ex) {
                    Files.deleteIfExists(tmp);
                    throw ex;
                }
                AtomicFiles.moveReplacing(tmp, out);
                written.add(out);
            }
        } catch (ZipException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "error.archive.invalid", zip.getFileName().toString(), e.getMessage());
        } catch (IOException e) {
            throw LauncherException.io(e, dest.toString());
        }
        return written;
    }

    private static LauncherException invalid(Path zip, String why) {
        return new LauncherException(ErrorCode.INVALID_ARCHIVE, "error.archive.invalid", zip.getFileName().toString(), why);
    }
}
