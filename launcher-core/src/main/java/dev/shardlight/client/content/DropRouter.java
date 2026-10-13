package dev.shardlight.client.content;

import dev.shardlight.client.loader.LoaderType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decides what a dropped file is by looking inside it, not only at its name: a {@code .jar} is a mod when it has a
 * mod descriptor, a {@code .zip} is a resource pack, shader pack, world or modpack depending on what it contains.
 * Anything else is refused with the key of a message that says why. Reads only the zip's file list (and the small
 * modpack manifest), never extracts anything.
 */
public final class DropRouter {
    static final String UNKNOWN_TYPE = "drop.reason.type";
    static final String NOT_A_MOD = "drop.reason.notAMod";
    static final String NOT_AN_ARCHIVE = "drop.reason.notAnArchive";
    static final String UNKNOWN_ZIP = "drop.reason.unknownZip";
    static final String NESTED = "drop.reason.nested";
    static final String BAD_IMAGE = "drop.reason.badImage";
    static final String BAD_MODPACK = "drop.reason.badModpack";
    static final String DATA_PACK = "drop.reason.dataPack";
    static final String FOLDER = "drop.reason.folder";
    static final String MISSING = "drop.reason.missing";
    private static final long MAX_MANIFEST = 2L * 1024 * 1024;

    private DropRouter() {
    }

    public static List<DropItem> classify(List<Path> files) {
        List<DropItem> out = new ArrayList<>();
        for (Path f : files) {
            out.add(classify(f));
        }
        return out;
    }

    public static DropItem classify(Path file) {
        if (file == null || !Files.exists(file)) {
            return refuse(file, MISSING);
        }
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            return Files.isRegularFile(file.resolve("level.dat")) ? new DropItem(file, DropKind.WORLD, null, null) : refuse(file, FOLDER);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return refuse(file, UNKNOWN_TYPE);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return image(file, true);
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return image(file, false);
        }
        if (name.endsWith(".shardlight")) {
            return new DropItem(file, DropKind.PROJECT_EXPORT, null, null);
        }
        if (name.endsWith(".jar")) {
            return jar(file);
        }
        if (name.endsWith(".zip") || name.endsWith(".mrpack")) {
            return archive(file, name.endsWith(".mrpack"));
        }
        return refuse(file, UNKNOWN_TYPE);
    }

    private static DropItem refuse(Path file, String reason) {
        return new DropItem(file == null ? Path.of("") : file, DropKind.UNKNOWN, reason, null);
    }

    private static DropItem image(Path file, boolean png) {
        byte[] head = new byte[8];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.readNBytes(head, 0, head.length);
            boolean ok = png
                ? n == 8 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                : n >= 3 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
            return ok ? new DropItem(file, DropKind.SCREENSHOT, null, null) : refuse(file, BAD_IMAGE);
        } catch (IOException e) {
            return refuse(file, BAD_IMAGE);
        }
    }

    private static DropItem jar(Path file) {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            Set<LoaderType> loaders = EnumSet.noneOf(LoaderType.class);
            boolean mod = false;
            if (zf.getEntry("fabric.mod.json") != null) {
                loaders.add(LoaderType.FABRIC);
                mod = true;
            }
            if (zf.getEntry("quilt.mod.json") != null) {
                loaders.add(LoaderType.QUILT);
                mod = true;
            }
            if (zf.getEntry("META-INF/mods.toml") != null || zf.getEntry("mcmod.info") != null) {
                loaders.add(LoaderType.FORGE);
                mod = true;
            }
            if (zf.getEntry("META-INF/neoforge.mods.toml") != null) {
                loaders.add(LoaderType.NEOFORGE);
                mod = true;
            }
            return mod ? new DropItem(file, DropKind.MOD, null, loaders) : refuse(file, NOT_A_MOD);
        } catch (IOException e) {
            return refuse(file, NOT_AN_ARCHIVE);
        }
    }

    private static DropItem archive(Path file, boolean mrpackName) {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            Set<String> names = new HashSet<>();
            Set<String> topFolders = new HashSet<>();
            boolean assets = false;
            boolean data = false;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName().replace('\\', '/');
                if (n.startsWith("__MACOSX/")) {
                    continue;
                }
                names.add(n);
                int slash = n.indexOf('/');
                if (slash > 0) {
                    topFolders.add(n.substring(0, slash));
                    if (n.startsWith("assets/")) {
                        assets = true;
                    }
                    if (n.startsWith("data/")) {
                        data = true;
                    }
                }
            }
            if (names.contains("modrinth.index.json")) {
                return new DropItem(file, DropKind.MODPACK, null, null);
            }
            if (mrpackName) {
                return refuse(file, BAD_MODPACK);
            }
            if (names.contains("manifest.json") && isCurseManifest(zf)) {
                return new DropItem(file, DropKind.MODPACK, null, null);
            }
            if (names.contains("pack.mcmeta")) {
                if (assets || !data) {
                    return new DropItem(file, DropKind.RESOURCE_PACK, null, null);
                }
                return new DropItem(file, DropKind.DATA_PACK, DATA_PACK, null);
            }
            if (topFolders.contains("shaders")) {
                return new DropItem(file, DropKind.SHADER_PACK, null, null);
            }
            if (names.contains("level.dat")) {
                return new DropItem(file, DropKind.WORLD, null, null);
            }
            if (topFolders.size() == 1) {
                String top = topFolders.iterator().next();
                if (names.contains(top + "/level.dat")) {
                    return new DropItem(file, DropKind.WORLD, null, null);
                }
                if (names.contains(top + "/pack.mcmeta") || names.stream().anyMatch(n -> n.startsWith(top + "/shaders/"))) {
                    return refuse(file, NESTED);
                }
            }
            return refuse(file, UNKNOWN_ZIP);
        } catch (IOException e) {
            return refuse(file, NOT_AN_ARCHIVE);
        }
    }

    private static boolean isCurseManifest(ZipFile zf) {
        ZipEntry e = zf.getEntry("manifest.json");
        if (e == null || e.getSize() > MAX_MANIFEST) {
            return false;
        }
        try (InputStream in = zf.getInputStream(e)) {
            String text = new String(in.readNBytes((int) MAX_MANIFEST), StandardCharsets.UTF_8);
            return text.contains("minecraftModpack");
        } catch (IOException ex) {
            return false;
        }
    }
}
