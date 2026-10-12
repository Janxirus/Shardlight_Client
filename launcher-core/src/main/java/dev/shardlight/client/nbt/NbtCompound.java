package dev.shardlight.client.nbt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * TAG_Compound: named tags in insertion order (the order of the file is kept when it is written back).
 * Getters behave like Minecraft's: a missing key or a tag of another type gives a neutral value (0, false,
 * "", an empty array, a new empty compound or list), and numeric getters convert between number types.
 * Use {@link #contains(String, int)} or {@link #find(String)} to tell "missing" from "zero".
 */
public final class NbtCompound implements NbtTag {
    /** for {@link #contains(String, int)}: any of the six numeric types */
    public static final int ANY_NUMBER = 99;

    private final LinkedHashMap<String, NbtTag> tags = new LinkedHashMap<>();

    public NbtCompound() {
    }

    // ---- reading --------------------------------------------------------------------------------------

    /** the tag stored under {@code key}, or null */
    public NbtTag get(String key) {
        return tags.get(key);
    }

    /** the tag stored under {@code key} */
    public Optional<NbtTag> find(String key) {
        return Optional.ofNullable(tags.get(key));
    }

    public boolean contains(String key) {
        return tags.containsKey(key);
    }

    /** true when {@code key} holds a tag of {@code type} ({@link #ANY_NUMBER} matches every numeric type) */
    public boolean contains(String key, int type) {
        NbtTag t = tags.get(key);
        if (t == null) {
            return false;
        }
        return type == ANY_NUMBER ? t instanceof NbtNumber : t.type() == type;
    }

    /** the type id stored under {@code key}, or {@link NbtTag#TAG_END} when missing */
    public byte typeOf(String key) {
        NbtTag t = tags.get(key);
        return t == null ? TAG_END : t.type();
    }

    public byte getByte(String key) {
        return tags.get(key) instanceof NbtNumber n ? n.byteValue() : 0;
    }

    public short getShort(String key) {
        return tags.get(key) instanceof NbtNumber n ? n.shortValue() : 0;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    /** the number under {@code key} as an int, or {@code def} when missing or not a number */
    public int getInt(String key, int def) {
        return tags.get(key) instanceof NbtNumber n ? n.intValue() : def;
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    /** the number under {@code key} as a long, or {@code def} when missing or not a number */
    public long getLong(String key, long def) {
        return tags.get(key) instanceof NbtNumber n ? n.longValue() : def;
    }

    public float getFloat(String key) {
        return tags.get(key) instanceof NbtNumber n ? n.floatValue() : 0f;
    }

    public double getDouble(String key) {
        return tags.get(key) instanceof NbtNumber n ? n.doubleValue() : 0d;
    }

    /** a byte (or any number) under {@code key} is true when non-zero */
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    /** {@code def} when {@code key} holds no number */
    public boolean getBoolean(String key, boolean def) {
        return tags.get(key) instanceof NbtNumber n ? n.longValue() != 0 || n.doubleValue() != 0 : def;
    }

    /** the string under {@code key}, or "" */
    public String getString(String key) {
        return getString(key, "");
    }

    /** the string under {@code key}, or {@code def} when missing or not a string */
    public String getString(String key, String def) {
        return tags.get(key) instanceof NbtString s ? s.value() : def;
    }

    /** the compound under {@code key}; a new, detached empty compound when missing */
    public NbtCompound getCompound(String key) {
        return tags.get(key) instanceof NbtCompound c ? c : new NbtCompound();
    }

    /** the compound under {@code key} when there is one */
    public Optional<NbtCompound> findCompound(String key) {
        return tags.get(key) instanceof NbtCompound c ? Optional.of(c) : Optional.empty();
    }

    /** the list under {@code key}; a new, detached empty list when missing */
    public NbtList getList(String key) {
        return tags.get(key) instanceof NbtList l ? l : new NbtList();
    }

    /**
     * the list under {@code key} when its elements are of {@code elementType} (an empty list matches any
     * type); otherwise a new, detached empty list
     */
    public NbtList getList(String key, int elementType) {
        if (tags.get(key) instanceof NbtList l && (l.isEmpty() || l.elementType() == elementType)) {
            return l;
        }
        return new NbtList();
    }

    /** the byte array under {@code key} (the backing array), or an empty array */
    public byte[] getByteArray(String key) {
        return tags.get(key) instanceof NbtByteArray a ? a.value() : new byte[0];
    }

    /** the int array under {@code key} (the backing array), or an empty array */
    public int[] getIntArray(String key) {
        return tags.get(key) instanceof NbtIntArray a ? a.value() : new int[0];
    }

    /** the long array under {@code key} (the backing array), or an empty array */
    public long[] getLongArray(String key) {
        return tags.get(key) instanceof NbtLongArray a ? a.value() : new long[0];
    }

    // ---- writing --------------------------------------------------------------------------------------

    /** store {@code tag} under {@code key} (replacing, keeping the key's position); returns the old tag */
    public NbtTag put(String key, NbtTag tag) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tag, "tag");
        if (tag.type() == TAG_END) {
            throw new IllegalArgumentException("TAG_End can't be stored in a compound");
        }
        return tags.put(key, tag);
    }

    public NbtCompound putByte(String key, byte v) {
        put(key, new NbtByte(v));
        return this;
    }

    public NbtCompound putBoolean(String key, boolean v) {
        put(key, NbtByte.of(v));
        return this;
    }

    public NbtCompound putShort(String key, short v) {
        put(key, new NbtShort(v));
        return this;
    }

    public NbtCompound putInt(String key, int v) {
        put(key, new NbtInt(v));
        return this;
    }

    public NbtCompound putLong(String key, long v) {
        put(key, new NbtLong(v));
        return this;
    }

    public NbtCompound putFloat(String key, float v) {
        put(key, new NbtFloat(v));
        return this;
    }

    public NbtCompound putDouble(String key, double v) {
        put(key, new NbtDouble(v));
        return this;
    }

    public NbtCompound putString(String key, String v) {
        put(key, new NbtString(v));
        return this;
    }

    public NbtCompound putByteArray(String key, byte[] v) {
        put(key, new NbtByteArray(v));
        return this;
    }

    public NbtCompound putIntArray(String key, int[] v) {
        put(key, new NbtIntArray(v));
        return this;
    }

    public NbtCompound putLongArray(String key, long[] v) {
        put(key, new NbtLongArray(v));
        return this;
    }

    /** remove {@code key}; returns the removed tag or null */
    public NbtTag remove(String key) {
        return tags.remove(key);
    }

    /** the keys in file order, read-only */
    public Set<String> keySet() {
        return Collections.unmodifiableSet(tags.keySet());
    }

    /** the entries in file order, read-only */
    public Set<Map.Entry<String, NbtTag>> entries() {
        return Collections.unmodifiableMap(tags).entrySet();
    }

    public int size() {
        return tags.size();
    }

    public boolean isEmpty() {
        return tags.isEmpty();
    }

    @Override
    public byte type() {
        return TAG_COMPOUND;
    }

    @Override
    public NbtCompound copy() {
        NbtCompound c = new NbtCompound();
        for (Map.Entry<String, NbtTag> e : tags.entrySet()) {
            c.tags.put(e.getKey(), e.getValue().copy());
        }
        return c;
    }

    /** used by the reader, which has already checked the tag */
    void putUnchecked(String key, NbtTag tag) {
        tags.put(key, tag);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtCompound other && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return tags.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, NbtTag> e : tags.entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(Snbt.key(e.getKey())).append(':').append(e.getValue());
        }
        return b.append('}').toString();
    }
}
