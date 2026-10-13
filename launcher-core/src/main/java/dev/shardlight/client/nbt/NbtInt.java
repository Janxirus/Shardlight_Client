package dev.shardlight.client.nbt;

/** TAG_Int: a signed int. */
public record NbtInt(int value) implements NbtNumber {
    @Override
    public byte type() {
        return TAG_INT;
    }

    @Override
    public NbtInt copy() {
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
        return value + "";
    }
}
