package dev.shardlight.client.nbt;

import java.io.IOException;

/** NBT data that is malformed, nested too deeply or larger than the reader's limits. */
public final class NbtFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    public NbtFormatException(String message) {
        super(message);
    }
}
