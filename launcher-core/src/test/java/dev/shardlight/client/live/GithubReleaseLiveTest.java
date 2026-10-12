package dev.shardlight.client.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHub's "latest release" answer still has the fields the module downloader reads
 * ({@code tag_name} and, per asset, {@code name}, {@code size}, {@code state}, {@code browser_download_url}).
 * A public repository stands in for the Shardlight repositories, which are private until they are published.
 */
@Tag("live")
class GithubReleaseLiveTest {
    @Test
    void theLatestReleaseHasTheFieldsTheLauncherReads() throws Exception {
        JsonObject release = LiveHttp.json("https://api.github.com/repos/FabricMC/fabric-installer/releases/latest").getAsJsonObject();
        assertFalse(release.get("tag_name").getAsString().isBlank());
        JsonArray assets = release.getAsJsonArray("assets");
        assertTrue(assets.size() > 0);
        JsonObject asset = assets.get(0).getAsJsonObject();
        assertFalse(asset.get("name").getAsString().isBlank());
        assertTrue(asset.get("size").getAsLong() > 0);
        assertTrue(asset.has("state"));
        assertTrue(asset.get("browser_download_url").getAsString().startsWith("https://github.com/"));
    }
}
