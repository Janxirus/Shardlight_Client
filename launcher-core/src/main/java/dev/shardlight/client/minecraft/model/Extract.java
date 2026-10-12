package dev.shardlight.client.minecraft.model;

import java.util.List;

/** {@code extract} of a native library: entry-name prefixes that must not be unpacked (usually {@code META-INF/}). */
public record Extract(List<String> exclude) {
    public Extract {
        exclude = ModelCopies.list(exclude);
    }
}
