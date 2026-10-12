package dev.shardlight.client.java;

import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/** JSON shapes of Mojang's Java runtime index ({@code all.json}) and per-runtime manifests. */
final class MojangRuntimes {
    private MojangRuntimes() {
    }

    /** platform -> component -> entries (in practice one) */
    static final Type INDEX = new TypeToken<Map<String, Map<String, List<Entry>>>>() { }.getType();

    record Ref(String sha1, Long size, String url) {
    }

    record VersionRef(String name, String released) {
    }

    record Availability(Integer group, Integer progress) {
    }

    record Entry(Availability availability, Ref manifest, VersionRef version) {
    }

    record FileEntry(String type, Boolean executable, Map<String, Ref> downloads, String target) {
        boolean isExecutable() {
            return Boolean.TRUE.equals(executable);
        }
    }

    record Manifest(Map<String, FileEntry> files) {
    }

    /** the {@code .version} marker written into an installed runtime */
    record Marker(String component, String platform, String name, String released, String manifestSha1) {
    }
}
