package dev.shardlight.client.nbt;

/** TAG_Byte: a signed byte. */
public record NbtByte(byte value) implements NbtNumber {
    public static final NbtByte FALSE = new NbtByte((byte) 0);
    public static final NbtByte TRUE = new NbtByte((byte) 1);

    /** Minecraft stores booleans as bytes 0 and 1 */
    public static NbtByte of(boolean b) {
        return b ? TRUE : FALSE;
    }

    /** true for any non-zero value */
    public boolean booleanValue() {
        return value != 0;
    }

    @Override
    public byte type() {
        return TAG_BYTE;
    }

    @Override
    public NbtByte copy() {
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
        return value + "b";
    }
}
