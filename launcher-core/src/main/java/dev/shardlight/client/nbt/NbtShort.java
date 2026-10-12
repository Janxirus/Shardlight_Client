package dev.shardlight.client.nbt;

/** TAG_Short: a signed short. */
public record NbtShort(short value) implements NbtNumber {
    @Override
    public byte type() {
        return TAG_SHORT;
    }

    @Override
    public NbtShort copy() {
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
        return value + "s";
    }
}
