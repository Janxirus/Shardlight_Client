package dev.shardlight.client.nbt;

/**
 * The six numeric tags. Every numeric tag converts to every Java number type like Minecraft does
 * (integers narrow by truncation, floating point values to integers by flooring).
 */
public sealed interface NbtNumber extends NbtTag permits NbtByte, NbtShort, NbtInt, NbtLong, NbtFloat, NbtDouble {
    /** the value as a long (floating point values are floored) */
    long longValue();

    /** the value as a double */
    double doubleValue();

    default int intValue() {
        return (int) longValue();
    }

    default short shortValue() {
        return (short) longValue();
    }

    default byte byteValue() {
        return (byte) longValue();
    }

    default float floatValue() {
        return (float) doubleValue();
    }
}
