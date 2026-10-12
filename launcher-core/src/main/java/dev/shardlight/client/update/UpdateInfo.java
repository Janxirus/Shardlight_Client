package dev.shardlight.client.update;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A newer launcher release, read from a manifest whose signature was verified.
 *
 * @param version    the release's semantic version, for example {@code 1.2.0} or {@code 1.3.0-beta.1}
 * @param channel    the manifest's channel ({@code stable}, {@code beta}); not covered by the signature
 * @param releasedAt when it was released, or null when the manifest didn't say
 * @param notesUrl   the release notes page (https), or null
 * @param changelog  short plain-text changes, or ""
 * @param downloads  the files offered, per platform
 */
public record UpdateInfo(String version, String channel, Instant releasedAt, URI notesUrl, String changelog,
                         List<Download> downloads) {

    /** an installer type: Windows Installer package */
    public static final String TYPE_MSI = "msi";
    /** an installer type: portable zip */
    public static final String TYPE_ZIP = "zip";

    public UpdateInfo {
        changelog = changelog == null ? "" : changelog;
        downloads = downloads == null ? List.of() : List.copyOf(downloads);
    }

    /**
     * One downloadable file.
     *
     * @param platform for example {@code windows-x64}
     * @param type     {@code msi} or {@code zip}
     * @param url      https address of the file
     * @param sha256   expected SHA-256, lowercase hex
     * @param size     expected size in bytes
     */
    public record Download(String platform, String type, URI url, String sha256, long size) {
    }

    /** the parsed version */
    public SemVer semVer() {
        return SemVer.parse(version);
    }

    /** true for a pre-release */
    public boolean isPreRelease() {
        return semVer().isPreRelease();
    }

    /** the downloads for a platform (case-insensitive), installer (msi) first */
    public List<Download> downloads(String platform) {
        return downloads.stream()
            .filter(d -> d.platform().equalsIgnoreCase(platform))
            .sorted((a, b) -> Boolean.compare(!TYPE_MSI.equals(a.type()), !TYPE_MSI.equals(b.type())))
            .toList();
    }

    /** the preferred download for a platform: the msi installer when offered, otherwise the first one */
    public Optional<Download> download(String platform) {
        return downloads(platform).stream().findFirst();
    }

    /** the download of one type for a platform */
    public Optional<Download> download(String platform, String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return downloads(platform).stream().filter(d -> d.type().equals(t)).findFirst();
    }
}
