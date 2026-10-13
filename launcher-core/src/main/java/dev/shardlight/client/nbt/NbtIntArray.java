package dev.shardlight.client.nbt;

import java.util.Arrays;
import java.util.Objects;

/** TAG_Int_Array: a length-prefixed array of ints. Wraps the given array without copying it. */
public final class NbtIntArray implements NbtTag {
    private final int[] value;

    /** wraps {@code value} (not copied; later changes to the array show through) */
    public NbtIntArray(int[] value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** the backing array (not a copy) */
    public int[] value() {
        return value;
    }

    public int length() {
        return value.length;
    }

    @Override
    public byte type() {
        return TAG_INT_ARRAY;
    }

    @Override
    public NbtIntArray copy() {
        return new NbtIntArray(value.clone());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtIntArray other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("[I;");
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(value[i]).append("");
        }
        return b.append(']').toString();
    }
}
