package dev.shardlight.client.project;

import dev.shardlight.client.nbt.NbtCompound;
import dev.shardlight.client.nbt.NbtIo;
import dev.shardlight.client.nbt.NbtList;
import dev.shardlight.client.nbt.NbtString;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Helpers for project tests: worlds with a real level.dat, file trees, zips. */
final class TestFiles {
    /** a tiny valid PNG header (signature + IHDR) followed by filler */
    static final byte[] PNG = png();

    private TestFiles() {
    }

    private static byte[] png() {
        byte[] b = new byte[64];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 16, 0, 0, 0, 16};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    static NbtCompound levelDat(String name, long lastPlayed, int gameType, boolean hardcore, boolean cheats,
                                String version, int dataVersion) {
        NbtCompound data = new NbtCompound();
        data.putInt("DataVersion", dataVersion);
        data.putString("LevelName", name);
        data.putLong("LastPlayed", lastPlayed);
        data.putInt("GameType", gameType);
        data.putBoolean("hardcore", hardcore);
        data.putBoolean("allowCommands", cheats);
        data.putLong("RandomSeed", 1234L);
        NbtCompound v = new NbtCompound();
        v.putInt("Id", dataVersion);
        v.putString("Name", version);
        v.putString("Series", "main");
        v.putBoolean("Snapshot", false);
        data.put("Version", v);
        data.put("ServerBrands", NbtList.of(new NbtString("vanilla")));
        data.put("DataPacks", new NbtCompound());
        NbtCompound root = new NbtCompound();
        root.put("Data", data);
        return root;
    }

    /** a world folder with level.dat, a region file, data and a session.lock */
    static Path world(Path saves, String folder, String name, long lastPlayed, String version) throws IOException {
        Path w = Files.createDirectories(saves.resolve(folder));
        Files.write(w.resolve("level.dat"), NbtIo.toBytes(levelDat(name, lastPlayed, 0, false, false, version, 4189), true));
        Files.createDirectories(w.resolve("region"));
        byte[] region = new byte[40_000];
        new Random(folder.hashCode()).nextBytes(region);
        Files.write(w.resolve("region/r.0.0.mca"), region);
        Files.createDirectories(w.resolve("data"));
        Files.writeString(w.resolve("data/raids.dat"), "raids of " + folder);
        Files.createDirectories(w.resolve("playerdata"));
        Files.writeString(w.resolve("playerdata/0000-1111.dat"), "player");
        Files.writeString(w.resolve("session.lock"), "☃");
        return w;
    }

    /** relative path ('/' separated) -> SHA-1 of every regular file under {@code root} */
    static Map<String, String> tree(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString().replace('\\', '/'), Hashing.hash(p, HashAlgo.SHA1));
            }
        }
        return out;
    }

    /** a zip from name/content pairs (null content = directory entry) */
    static Path zip(Path file, String... namesAndContents) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zo = new ZipOutputStream(os)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zo.putNextEntry(new ZipEntry(namesAndContents[i]));
                if (namesAndContents[i + 1] != null) {
                    zo.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                zo.closeEntry();
            }
        }
        return file;
    }

    static boolean anyStaging(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(".staging-") || n.startsWith(".restore-") || n.endsWith(".part");
            });
        }
    }
}
