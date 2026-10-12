package dev.shardlight.client.update;

import dev.shardlight.client.error.LauncherException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Where the launcher looks for updates: the manifest URL and the distributor's Ed25519 public key, read
 * from {@code launcher-core/src/main/resources/update/update.properties} (keys {@code manifestUrl} and
 * {@code publicKey}). Both are empty in the source tree; the distributor fills them in for release builds
 * (docs/RELEASING.md). A blank or invalid value means "not configured".
 *
 * @param manifestUrlText the configured manifest URL text, possibly blank
 * @param publicKeyText   the configured public key text, possibly blank
 */
public record UpdateConfig(String manifestUrlText, String publicKeyText) {
    private static final Logger LOG = Logger.getLogger(UpdateConfig.class.getName());

    public UpdateConfig {
        manifestUrlText = manifestUrlText == null ? "" : manifestUrlText.trim();
        publicKeyText = publicKeyText == null ? "" : publicKeyText.trim();
    }

    /** the configuration bundled with this build */
    public static UpdateConfig bundled() {
        try (InputStream in = UpdateConfig.class.getResourceAsStream(UpdateService.CONFIG_RESOURCE)) {
            if (in == null) {
                return new UpdateConfig("", "");
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warning("Couldn't read " + UpdateService.CONFIG_RESOURCE + ": " + e);
            return new UpdateConfig("", "");
        }
    }

    /** read {@code manifestUrl} and {@code publicKey} from properties text */
    public static UpdateConfig read(Reader reader) throws IOException {
        Properties p = new Properties();
        p.load(reader);
        return new UpdateConfig(p.getProperty("manifestUrl", ""), p.getProperty("publicKey", ""));
    }

    /** the manifest URL, when one is configured and well-formed */
    public Optional<URI> manifestUrl() {
        if (manifestUrlText.isEmpty()) {
            return Optional.empty();
        }
        try {
            // braces aren't legal in a URI, so the {channel} placeholder is kept percent-encoded
            return Optional.of(new URI(manifestUrlText.replace(UpdateService.CHANNEL_PLACEHOLDER,
                UpdateService.CHANNEL_PLACEHOLDER_ENCODED)));
        } catch (URISyntaxException | IllegalArgumentException e) {
            LOG.warning("Ignoring invalid update manifest URL in " + UpdateService.CONFIG_RESOURCE);
            return Optional.empty();
        }
    }

    /** the public key, when one is configured and valid */
    public Optional<PublicKey> publicKey() {
        if (publicKeyText.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UpdateKeys.parsePublicKey(publicKeyText));
        } catch (LauncherException e) {
            LOG.warning("Ignoring invalid update public key in " + UpdateService.CONFIG_RESOURCE);
            return Optional.empty();
        }
    }
}
