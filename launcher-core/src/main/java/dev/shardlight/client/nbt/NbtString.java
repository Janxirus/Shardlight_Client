package dev.shardlight.client.nbt;

import java.util.Objects;

/**
 * TAG_String: text stored as Java's modified UTF-8 with an unsigned 16-bit length, so the encoded form is
 * at most 65535 bytes.
 */
public record NbtString(String value) implements NbtTag {
    public NbtString {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public byte type() {
        return TAG_STRING;
    }

    @Override
    public NbtString copy() {
        return this;
    }

    @Override
    public String toString() {
        return Snbt.quote(value);
    }
}
