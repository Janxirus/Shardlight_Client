package dev.shardlight.client.nbt;

/** TAG_Double: an IEEE 754 double. */
public record NbtDouble(double value) implements NbtNumber {
    @Override
    public byte type() {
        return TAG_DOUBLE;
    }

    @Override
    public NbtDouble copy() {
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
        return value + "d";
    }
}
