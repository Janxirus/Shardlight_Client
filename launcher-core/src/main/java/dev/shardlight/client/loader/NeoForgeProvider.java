package dev.shardlight.client.loader;

import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge (experimental) from {@code <neoForgeMaven>net/neoforged/neoforge/maven-metadata.xml}, installed with its
 * official installer. The installer creates the version {@code neoforge-<version>}.
 *
 * <p>NeoForge version numbers encode the Minecraft version: for Minecraft 1.20.2 and later {@code 20.2.x} means
 * 1.20.2, {@code 20.4.x} means 1.20.4, {@code 21.0.x} means 1.21 and {@code 21.1.x} means 1.21.1. <b>Assumption:</b>
 * for the year-numbered Minecraft line (26.1, 26.2, 26.3...) the scheme continues with the Minecraft version as its
 * first two numbers, so {@code 26.3.x} means Minecraft 26.3; a four-number version {@code 26.3.1.x} is read as
 * Minecraft 26.3.1 (and {@code 26.3.0.x} as 26.3). NeoForge for 1.20.1 was published as
 * {@code net.neoforged:forge} and isn't listed here.
 */
public final class NeoForgeProvider extends InstallerLoaderProvider {
    /** the first major number of the year-based Minecraft versions */
    static final int FIRST_YEAR_VERSION = 25;

    public NeoForgeProvider(HttpService http, Endpoints endpoints, AppPaths paths, DownloadManager downloads,
                            GameInstaller installer, JavaRuntimeService java) {
        super(http, endpoints.neoForgeMaven(), paths, downloads, installer, java);
    }

    @Override
    public LoaderType type() {
        return LoaderType.NEOFORGE;
    }

    @Override
    String groupPath() {
        return "net/neoforged";
    }

    @Override
    String artifact() {
        return "neoforge";
    }

    @Override
    String mavenVersion(String minecraftVersion, String loaderVersion) {
        return loaderVersion;
    }

    @Override
    public String versionId(String minecraftVersion, String loaderVersion) {
        return "neoforge-" + loaderVersion;
    }

    @Override
    List<LoaderVersion> select(List<String> mavenVersions, String minecraftVersion, CancellationToken cancel) {
        List<String> matching = new ArrayList<>();
        for (String v : mavenVersions) {
            if (minecraftVersion.equals(minecraftOf(v))) {
                matching.add(v);
            }
        }
        matching.sort(LoaderVersions.ORDER.reversed());
        List<LoaderVersion> out = new ArrayList<>();
        boolean recommended = false;
        for (String v : matching) {
            boolean stable = !v.contains("-");
            out.add(new LoaderVersion(v, stable, stable && !recommended));
            recommended |= stable;
        }
        return List.copyOf(out);
    }

    /**
     * The Minecraft version a NeoForge version belongs to, or null when it doesn't follow the scheme:
     * {@code 20.2.88} gives 1.20.2, {@code 21.0.167} gives 1.21, {@code 21.4.0-beta} gives 1.21.4, and (assumed)
     * {@code 26.3.5} gives 26.3, {@code 26.3.1.4} gives 26.3.1.
     */
    public static String minecraftOf(String neoForgeVersion) {
        if (neoForgeVersion == null) {
            return null;
        }
        String release = neoForgeVersion.trim();
        int dash = release.indexOf('-');
        if (dash >= 0) {
            release = release.substring(0, dash);
        }
        String[] parts = release.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        int[] n = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 6 || !parts[i].chars().allMatch(Character::isDigit)) {
                return null;
            }
            n[i] = Integer.parseInt(parts[i]);
        }
        if (n[0] < 20) {
            return null;
        }
        if (n[0] < FIRST_YEAR_VERSION) {
            return "1." + n[0] + (n[1] == 0 ? "" : "." + n[1]);
        }
        if (parts.length >= 4 && n[2] != 0) {
            return n[0] + "." + n[1] + "." + n[2];
        }
        return n[0] + "." + n[1];
    }
}
