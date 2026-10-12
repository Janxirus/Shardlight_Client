package dev.shardlight.client.nbt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * TAG_List: an ordered list whose elements all have the same tag type. An empty list remembers the
 * element type it was read or created with (so files round-trip byte for byte); adding to an empty list
 * of type {@link NbtTag#TAG_END} adopts the first element's type. Typed getters return a neutral value
 * (0, "", an empty compound) when the index holds another type, like Minecraft does.
 */
public final class NbtList implements NbtTag, Iterable<NbtTag> {
    private byte elementType;
    private final List<NbtTag> items;

    /** an empty list with no element type yet */
    public NbtList() {
        this(TAG_END);
    }

    /** an empty list for elements of {@code elementType} */
    public NbtList(byte elementType) {
        this(elementType, 8);
    }

    NbtList(byte elementType, int capacity) {
        if (!NbtTag.isValidType(elementType)) {
            throw new IllegalArgumentException("Unknown tag type " + elementType);
        }
        this.elementType = elementType;
        this.items = new ArrayList<>(Math.max(0, capacity));
    }

    /** a list holding these tags (all of one type) */
    public static NbtList of(NbtTag... tags) {
        NbtList l = new NbtList();
        for (NbtTag t : tags) {
            l.add(t);
        }
        return l;
    }

    /** the type of the elements ({@link NbtTag#TAG_END} for a list that never had one) */
    public byte elementType() {
        return elementType;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** the element at {@code index} */
    public NbtTag get(int index) {
        return items.get(index);
    }

    /** append; throws {@link IllegalArgumentException} when the type differs from the other elements */
    public NbtList add(NbtTag tag) {
        accept(tag);
        items.add(tag);
        return this;
    }

    /** replace the element at {@code index} (same type rule as {@link #add}) */
    public NbtTag set(int index, NbtTag tag) {
        Objects.checkIndex(index, items.size());
        requireElement(tag);
        if (items.size() == 1) {
            elementType = tag.type();
        } else if (tag.type() != elementType) {
            throw mismatch(tag);
        }
        return items.set(index, tag);
    }

    public NbtTag remove(int index) {
        return items.remove(index);
    }

    public void clear() {
        items.clear();
    }

    /** the elements, read-only */
    public List<NbtTag> items() {
        return Collections.unmodifiableList(items);
    }

    /** the compound elements (empty unless this is a list of compounds) */
    public List<NbtCompound> compounds() {
        List<NbtCompound> out = new ArrayList<>();
        for (NbtTag t : items) {
            if (t instanceof NbtCompound c) {
                out.add(c);
            }
        }
        return out;
    }

    /** the compound at {@code index}, or a new empty compound when it isn't one */
    public NbtCompound getCompound(int index) {
        return index >= 0 && index < items.size() && items.get(index) instanceof NbtCompound c ? c : new NbtCompound();
    }

    /** the string at {@code index}, or "" */
    public String getString(int index) {
        return index >= 0 && index < items.size() && items.get(index) instanceof NbtString s ? s.value() : "";
    }

    /** the number at {@code index} as an int, or 0 */
    public int getInt(int index) {
        return index >= 0 && index < items.size() && items.get(index) instanceof NbtNumber n ? n.intValue() : 0;
    }

    /** the number at {@code index} as a long, or 0 */
    public long getLong(int index) {
        return index >= 0 && index < items.size() && items.get(index) instanceof NbtNumber n ? n.longValue() : 0;
    }

    /** the number at {@code index} as a double, or 0 */
    public double getDouble(int index) {
        return index >= 0 && index < items.size() && items.get(index) instanceof NbtNumber n ? n.doubleValue() : 0;
    }

    /** the list at {@code index}, or a new empty list */
    public NbtList getList(int index) {
        return index >= 0 && index < items.size() && items.get(index) instanceof NbtList l ? l : new NbtList();
    }

    /** used by the reader: the element type was checked once for the whole list */
    void addUnchecked(NbtTag tag) {
        items.add(tag);
    }

    private void accept(NbtTag tag) {
        requireElement(tag);
        if (items.isEmpty()) {
            elementType = tag.type();
        } else if (tag.type() != elementType) {
            throw mismatch(tag);
        }
    }

    private static void requireElement(NbtTag tag) {
        Objects.requireNonNull(tag, "tag");
        if (tag.type() == TAG_END) {
            throw new IllegalArgumentException("TAG_End can't be a list element");
        }
    }

    private IllegalArgumentException mismatch(NbtTag tag) {
        return new IllegalArgumentException("List of " + NbtTag.typeName(elementType) + " can't hold "
            + NbtTag.typeName(tag.type()));
    }

    @Override
    public Iterator<NbtTag> iterator() {
        return items().iterator();
    }

    @Override
    public byte type() {
        return TAG_LIST;
    }

    @Override
    public NbtList copy() {
        NbtList l = new NbtList(elementType, items.size());
        for (NbtTag t : items) {
            l.items.add(t.copy());
        }
        return l;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtList other && elementType == other.elementType && items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return 31 * elementType + items.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(items.get(i));
        }
        return b.append(']').toString();
    }
}
