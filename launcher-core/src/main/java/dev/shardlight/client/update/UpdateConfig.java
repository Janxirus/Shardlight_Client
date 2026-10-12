package dev.shardlight.client.update;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.error.LauncherException;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PublicKey;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Where the launcher looks for updates: the manifest URL and the distributor's Ed25519 public key. A build
 * gets them from {@link BuildInfo} ({@code SHARDLIGHT_UPDATE_MANIFEST_URL} and
 * {@code SHARDLIGHT_UPDATE_PUBLIC_KEY} at build time, see docs/RELEASING.md); both are empty in a developer
 * build. A blank or invalid value means "not configured".
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

    /** the configuration baked into this build */
    public static UpdateConfig bundled() {
        return new UpdateConfig(BuildInfo.updateManifestUrl().orElse(""), BuildInfo.updatePublicKey().orElse(""));
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
            LOG.warning("The update manifest URL in this build isn't valid; updates are off");
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
            LOG.warning("The update public key in this build isn't valid; updates are off");
            return Optional.empty();
        }
    }
}
