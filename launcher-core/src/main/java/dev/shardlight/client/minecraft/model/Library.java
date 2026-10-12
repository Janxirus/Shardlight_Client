package dev.shardlight.client.minecraft.model;

import java.util.List;
import java.util.Map;

/**
 * One {@code libraries} entry. Vanilla entries carry {@code downloads}; Fabric and Quilt profiles give a
 * Maven base {@code url} plus top-level hashes and size instead.
 *
 * @param name      Maven coordinate {@code group:artifact:version[:classifier][@ext]}
 * @param downloads explicit files, or null
 * @param url       Maven repository base URL (Fabric/Quilt/old Forge), or null
 * @param sha1      SHA-1 of the artifact when {@code downloads} is absent
 * @param size      size of the artifact when {@code downloads} is absent
 * @param sha256    SHA-256 of the artifact (Fabric/Quilt meta)
 * @param sha512    SHA-512 of the artifact (Fabric/Quilt meta)
 * @param natives   legacy natives: OS name to classifier (may contain {@code ${arch}})
 * @param extract   legacy natives: what not to unpack
 * @param rules     when the library applies; empty = always
 */
public record Library(String name, LibraryDownloads downloads, String url, String sha1, Long size, String sha256,
                      String sha512, Map<String, String> natives, Extract extract, List<Rule> rules) {
    public Library {
        natives = ModelCopies.map(natives);
        rules = ModelCopies.list(rules);
    }

    /** a plain Maven library with no hashes (tests and loader profiles) */
    public static Library maven(String name, String repositoryUrl) {
        return new Library(name, null, repositoryUrl, null, null, null, null, null, null, null);
    }

    public boolean hasNatives() {
        return !natives.isEmpty();
    }

    /** the exclude prefixes of {@link #extract}, empty when none */
    public List<String> extractExcludes() {
        return extract == null ? List.of() : extract.exclude();
    }
}
