package dev.shardlight.client.nbt;

/**
 * One NBT value (Minecraft's Named Binary Tag format, Java Edition, big-endian). There are 13 tag types;
 * their ids are the {@code TAG_*} constants. Scalar tags and strings are immutable records; arrays, lists
 * and compounds are mutable containers. {@link #copy()} always returns an independent deep copy.
 * {@link #toString()} gives SNBT-like text for logs and debugging.
 */
public sealed interface NbtTag permits NbtEnd, NbtNumber, NbtString, NbtByteArray, NbtIntArray, NbtLongArray,
    NbtList, NbtCompound {
    byte TAG_END = 0;
    byte TAG_BYTE = 1;
    byte TAG_SHORT = 2;
    byte TAG_INT = 3;
    byte TAG_LONG = 4;
    byte TAG_FLOAT = 5;
    byte TAG_DOUBLE = 6;
    byte TAG_BYTE_ARRAY = 7;
    byte TAG_STRING = 8;
    byte TAG_LIST = 9;
    byte TAG_COMPOUND = 10;
    byte TAG_INT_ARRAY = 11;
    byte TAG_LONG_ARRAY = 12;

    /** this tag's type id ({@code TAG_*}) */
    byte type();

    /** an independent deep copy (immutable tags return themselves) */
    NbtTag copy();

    /** true for a valid tag type id (0..12) */
    static boolean isValidType(int type) {
        return type >= TAG_END && type <= TAG_LONG_ARRAY;
    }

    /** the conventional name of a tag type ("TAG_Compound"...), for messages */
    static String typeName(int type) {
        return switch (type) {
            case TAG_END -> "TAG_End";
            case TAG_BYTE -> "TAG_Byte";
            case TAG_SHORT -> "TAG_Short";
            case TAG_INT -> "TAG_Int";
            case TAG_LONG -> "TAG_Long";
            case TAG_FLOAT -> "TAG_Float";
            case TAG_DOUBLE -> "TAG_Double";
            case TAG_BYTE_ARRAY -> "TAG_Byte_Array";
            case TAG_STRING -> "TAG_String";
            case TAG_LIST -> "TAG_List";
            case TAG_COMPOUND -> "TAG_Compound";
            case TAG_INT_ARRAY -> "TAG_Int_Array";
            case TAG_LONG_ARRAY -> "TAG_Long_Array";
            default -> "TAG_Unknown(" + type + ")";
        };
    }
}
