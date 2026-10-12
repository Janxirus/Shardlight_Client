package dev.shardlight.client.nbt;

/** TAG_End: marks the end of a compound; also the element type of an empty list. Never stored as a value. */
public enum NbtEnd implements NbtTag {
    INSTANCE;

    @Override
    public byte type() {
        return TAG_END;
    }

    @Override
    public NbtEnd copy() {
        return this;
    }

    @Override
    public String toString() {
        return "END";
    }
}
