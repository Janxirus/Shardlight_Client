package dev.shardlight.client.minecraft;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.model.AssetIndex;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.PathSafety;
import dev.shardlight.client.util.SafeZip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * File work the launch step does right before starting the game: unpacking native libraries and, for
 * very old versions ({@code map_to_resources} asset index), copying assets into the game folder.
 */
public final class LaunchPreparation {
    private static final Logger LOG = Logger.getLogger(LaunchPreparation.class.getName());

    private LaunchPreparation() {
    }

    /** both steps below */
    public static void prepare(InstalledVersion installed, Path gameDir, Path nativesDir, CancellationToken cancel)
        throws LauncherException {
        extractNatives(installed, nativesDir, cancel);
        copyMappedResources(installed, gameDir, cancel);
    }

    /**
     * Unpack every native jar of {@code installed} into {@code nativesDir}, skipping
     * {@link InstalledVersion#nativeExcludes()} (always {@code META-INF/}). Entries are checked like any
     * archive from outside ({@link SafeZip}); files that are already there with the same size and CRC are
     * left alone, so a natives folder a running game holds open doesn't break a second launch.
     *
     * @return how many files were written
     */
    public static int extractNatives(InstalledVersion installed, Path nativesDir, CancellationToken cancel)
        throws LauncherException {
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        int written = 0;
        try {
            Files.createDirectories(nativesDir);
        } catch (IOException e) {
            throw LauncherException.io(e, nativesDir.toString());
        }
        List<String> excludes = installed.nativeExcludes();
        for (Path jar : installed.nativeJars()) {
            c.check();
            if (!Files.isRegularFile(jar)) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "install.error.nativeMissing", jar.getFileName().toString());
            }
            Map<String, long[]> entries = entryInfo(jar);
            written += SafeZip.extract(jar, "", nativesDir, SafeZip.Limits.SMALL,
                name -> !excluded(name, excludes) && !upToDate(nativesDir, name, entries.get(name)), c).size();
        }
        if (written > 0) {
            LOG.fine("Extracted " + written + " native files to " + nativesDir);
        }
        return written;
    }

    /**
     * For a {@code map_to_resources} asset index (Minecraft before 1.6), copy every object to
     * {@code <gameDir>/resources/<path>}. Does nothing for other versions.
     *
     * @return how many files were copied
     */
    public static int copyMappedResources(InstalledVersion installed, Path gameDir, CancellationToken cancel)
        throws LauncherException {
        if (!installed.mapToResources()) {
            return 0;
        }
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        AssetIndex index = Assets.read(installed.assetIndexFile());
        Path dir = gameDir.resolve("resources");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        PathSafety.requireNoLinkEscape(gameDir, dir);
        return Assets.copyObjects(index, installed.assetsRoot(), dir, c);
    }

    static boolean excluded(String name, List<String> excludes) {
        String n = name.replace('\\', '/');
        for (String ex : excludes) {
            if (ex != null && !ex.isEmpty() && n.startsWith(ex.replace('\\', '/'))) {
                return true;
            }
        }
        return false;
    }

    private static boolean upToDate(Path dir, String name, long[] sizeAndCrc) {
        if (sizeAndCrc == null || name.endsWith("/")) {
            return false;
        }
        Path target;
        try {
            target = PathSafety.resolveInside(dir, name);
        } catch (LauncherException e) {
            return false; // SafeZip rejects it with the proper error
        }
        try {
            if (!Files.isRegularFile(target) || Files.size(target) != sizeAndCrc[0] || sizeAndCrc[1] < 0) {
                return false;
            }
            CRC32 crc = new CRC32();
            try (InputStream in = Files.newInputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    crc.update(buf, 0, n);
                }
            }
            return crc.getValue() == sizeAndCrc[1];
        } catch (IOException e) {
            return false;
        }
    }

    /** entry name to {size, crc} */
    private static Map<String, long[]> entryInfo(Path jar) throws LauncherException {
        Map<String, long[]> out = new HashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                out.put(e.getName().replace('\\', '/'), new long[] {e.getSize(), e.getCrc()});
            }
        } catch (ZipException e) {
            throw new LauncherException(ErrorCode.INVALID_ARCHIVE, e, "error.archive.invalid", jar.getFileName().toString(),
                e.getMessage());
        } catch (IOException e) {
            throw LauncherException.io(e, jar.toString());
        }
        return out;
    }
}
