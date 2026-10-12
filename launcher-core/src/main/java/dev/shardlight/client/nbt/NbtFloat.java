package dev.shardlight.client.nbt;

/** TAG_Float: an IEEE 754 float. */
public record NbtFloat(float value) implements NbtNumber {
    @Override
    public byte type() {
        return TAG_FLOAT;
    }

    @Override
    public NbtFloat copy() {
        return this;
    }

    @Override
    public long longValue() {
        return (long) Math.floor(value);
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public String toString() {
        return value + "f";
    }
}
