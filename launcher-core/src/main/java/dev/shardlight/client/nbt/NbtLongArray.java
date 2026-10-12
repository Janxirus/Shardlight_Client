package dev.shardlight.client.nbt;

import java.util.Arrays;
import java.util.Objects;

/** TAG_Long_Array: a length-prefixed array of longs. Wraps the given array without copying it. */
public final class NbtLongArray implements NbtTag {
    private final long[] value;

    /** wraps {@code value} (not copied; later changes to the array show through) */
    public NbtLongArray(long[] value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** the backing array (not a copy) */
    public long[] value() {
        return value;
    }

    public int length() {
        return value.length;
    }

    @Override
    public byte type() {
        return TAG_LONG_ARRAY;
    }

    @Override
    public NbtLongArray copy() {
        return new NbtLongArray(value.clone());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtLongArray other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("[L;");
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(value[i]).append("L");
        }
        return b.append(']').toString();
    }
}
