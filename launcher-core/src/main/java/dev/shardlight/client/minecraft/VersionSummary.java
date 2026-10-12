package dev.shardlight.client.minecraft;

import java.net.URI;
import java.time.Instant;

/**
 * One entry of Mojang's version list ({@code version_manifest_v2.json}).
 *
 * @param id              version id ({@code 1.21.4}, {@code 26.1}, {@code 25w14a})
 * @param type            release, snapshot, old_beta or old_alpha
 * @param url             where the version JSON is
 * @param sha1            SHA-1 of the version JSON
 * @param releaseTime     when it was released
 * @param complianceLevel Mojang's compliance level (0 for old versions, 1 for versions with safety features)
 */
public record VersionSummary(String id, String type, URI url, String sha1, Instant releaseTime, int complianceLevel) {
    public static final String RELEASE = "release";
    public static final String SNAPSHOT = "snapshot";

    public boolean isRelease() {
        return RELEASE.equals(type);
    }

    public boolean isSnapshot() {
        return SNAPSHOT.equals(type);
    }
}
