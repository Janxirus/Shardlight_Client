package dev.shardlight.client.nbt;

import java.util.Arrays;
import java.util.Objects;

/** TAG_Byte_Array: a length-prefixed array of bytes. Wraps the given array without copying it. */
public final class NbtByteArray implements NbtTag {
    private final byte[] value;

    /** wraps {@code value} (not copied; later changes to the array show through) */
    public NbtByteArray(byte[] value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** the backing array (not a copy) */
    public byte[] value() {
        return value;
    }

    public int length() {
        return value.length;
    }

    @Override
    public byte type() {
        return TAG_BYTE_ARRAY;
    }

    @Override
    public NbtByteArray copy() {
        return new NbtByteArray(value.clone());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtByteArray other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("[B;");
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(value[i]).append("b");
        }
        return b.append(']').toString();
    }
}
