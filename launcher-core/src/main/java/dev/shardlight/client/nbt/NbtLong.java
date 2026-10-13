package dev.shardlight.client.nbt;

/** TAG_Long: a signed long. */
public record NbtLong(long value) implements NbtNumber {
    @Override
    public byte type() {
        return TAG_LONG;
    }

    @Override
    public NbtLong copy() {
        return this;
    }

    @Override
    public long longValue() {
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public String toString() {
        return value + "L";
    }
}
