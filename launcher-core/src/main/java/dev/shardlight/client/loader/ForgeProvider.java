package dev.shardlight.client.loader;

import com.google.gson.JsonParseException;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Minecraft Forge (experimental) from {@code <forgeMaven>net/minecraftforge/forge/maven-metadata.xml}, whose versions
 * look like {@code <mc>-<forge>} ({@code 1.20.1-47.3.0}); the loader version a project stores is the part after the
 * Minecraft version. The recommended version comes from {@code promotions_slim.json} next to the metadata when it
 * can be read, otherwise it is the newest one. The installer creates the version {@code <mc>-forge-<forge>}.
 */
public final class ForgeProvider extends InstallerLoaderProvider {
    private static final Logger LOG = Logger.getLogger(ForgeProvider.class.getName());

    /** {@code promotions_slim.json}: {"homepage": "...", "promos": {"1.20.1-recommended": "47.3.0", "1.20.1-latest": "47.3.12"}} */
    record Promotions(String homepage, Map<String, String> promos) {
    }

    public ForgeProvider(HttpService http, Endpoints endpoints, AppPaths paths, DownloadManager downloads,
                         GameInstaller installer, JavaRuntimeService java) {
        super(http, endpoints.forgeMaven(), paths, downloads, installer, java);
    }

    @Override
    public LoaderType type() {
        return LoaderType.FORGE;
    }

    @Override
    String groupPath() {
        return "net/minecraftforge";
    }

    @Override
    String artifact() {
        return "forge";
    }

    @Override
    String mavenVersion(String minecraftVersion, String loaderVersion) {
        return minecraftVersion + "-" + loaderVersion;
    }

    @Override
    public String versionId(String minecraftVersion, String loaderVersion) {
        return minecraftVersion + "-forge-" + loaderVersion;
    }

    @Override
    List<LoaderVersion> select(List<String> mavenVersions, String minecraftVersion, CancellationToken cancel)
        throws LauncherException {
        String prefix = minecraftVersion + "-";
        List<String> matching = new ArrayList<>();
        for (String v : mavenVersions) {
            if (v.startsWith(prefix) && v.length() > prefix.length()) {
                matching.add(v.substring(prefix.length()));
            }
        }
        if (matching.isEmpty()) {
            return List.of();
        }
        matching.sort(LoaderVersions.ORDER.reversed());
        String recommended = promoted(minecraftVersion, cancel);
        if (recommended == null || !matching.contains(recommended)) {
            recommended = matching.get(0);
        }
        List<LoaderVersion> out = new ArrayList<>();
        for (String v : matching) {
            out.add(new LoaderVersion(v, true, v.equals(recommended)));
        }
        return List.copyOf(out);
    }

    /** Forge's recommended (or else latest) build for {@code mc}, or null when the promotions can't be read */
    private String promoted(String mc, CancellationToken cancel) throws LauncherException {
        try {
            String text = http.getText(HttpService.uri(maven, groupPath() + "/" + artifact() + "/promotions_slim.json", null),
                cancel == null ? CancellationToken.NONE : cancel);
            Promotions p = Json.read(text, Promotions.class);
            if (p == null || p.promos() == null) {
                return null;
            }
            String r = p.promos().get(mc + "-recommended");
            return r != null ? r : p.promos().get(mc + "-latest");
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                throw e;
            }
            LOG.fine(() -> "Forge promotions unavailable: " + e.getMessage());
            return null;
        } catch (JsonParseException | IllegalStateException e) {
            LOG.fine(() -> "Forge promotions unreadable: " + e.getMessage());
            return null;
        }
    }
}
