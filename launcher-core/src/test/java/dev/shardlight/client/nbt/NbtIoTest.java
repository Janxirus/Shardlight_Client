package dev.shardlight.client.nbt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtIoTest {

    static NbtCompound everyType() {
        NbtCompound c = new NbtCompound();
        c.putByte("byte", (byte) -7);
        c.putShort("short", (short) 31000);
        c.putInt("int", 123456789);
        c.putLong("long", Long.MIN_VALUE + 5);
        c.putFloat("float", 1.5f);
        c.putDouble("double", -0.25);
        c.putByteArray("bytes", new byte[] {1, 2, -3});
        c.putString("string", "h\u00e9llo \u0000 \uD83D\uDE00");
        c.put("list", NbtList.of(new NbtString("a"), new NbtString("b")));
        c.put("emptyList", new NbtList());
        c.put("emptyIntList", new NbtList(NbtTag.TAG_INT));
        NbtCompound inner = new NbtCompound();
        inner.putString("z", "last").putString("a", "first");
        c.put("compound", inner);
        c.putIntArray("ints", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
        c.putLongArray("longs", new long[] {Long.MAX_VALUE, -1});
        return c;
    }

    @Test
    void roundTripsAllThirteenTagTypesInEveryCompression() throws IOException {
        NbtCompound root = everyType();
        for (NbtIo.Compression c : NbtIo.Compression.values()) {
            byte[] bytes = NbtIo.toBytes(new NbtIo.NamedTag("root", root), c);
            NbtIo.NamedTag back = NbtIo.readNamed(new ByteArrayInputStream(bytes), NbtIo.Limits.DEFAULT);
            assertEquals("root", back.name(), c.name());
            assertEquals(root, back.tag(), c.name());
            assertArrayEquals(bytes, NbtIo.toBytes(back, c), "re-encoding is identical for " + c);
        }
        // TAG_End as a root
        byte[] end = NbtIo.toBytes(new NbtIo.NamedTag("", NbtEnd.INSTANCE), NbtIo.Compression.NONE);
        assertArrayEquals(new byte[] {0}, end);
        assertEquals(NbtEnd.INSTANCE, NbtIo.readNamed(new ByteArrayInputStream(end), NbtIo.Limits.DEFAULT).tag());
    }

    @Test
    void detectsCompressionByMagicBytes() throws IOException {
        NbtCompound root = everyType();
        byte[] gzip = NbtIo.toBytes(root, true);
        byte[] plain = NbtIo.toBytes(root, false);
        byte[] zlib = NbtIo.toBytes(new NbtIo.NamedTag("", root), NbtIo.Compression.ZLIB);
        assertEquals(NbtIo.Compression.GZIP, NbtIo.detect(gzip[0] & 0xff, gzip[1] & 0xff));
        assertEquals(NbtIo.Compression.ZLIB, NbtIo.detect(zlib[0] & 0xff, zlib[1] & 0xff));
        assertEquals(NbtIo.Compression.NONE, NbtIo.detect(plain[0] & 0xff, plain[1] & 0xff));
        for (byte[] b : List.of(gzip, plain, zlib)) {
            assertEquals(root, NbtIo.read(new ByteArrayInputStream(b)));
        }
    }

    @Test
    void doesNotCloseTheCallersStream() throws IOException {
        boolean[] closed = {false};
        InputStream in = new ByteArrayInputStream(NbtIo.toBytes(everyType(), true)) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        NbtIo.read(in);
        assertFalse(closed[0]);
    }

    @Test
    void compoundKeepsInsertionOrderThroughFiles() throws IOException {
        NbtCompound c = new NbtCompound();
        for (String k : List.of("zeta", "alpha", "mid", "beta")) {
            c.putInt(k, k.length());
        }
        NbtCompound back = NbtIo.read(new ByteArrayInputStream(NbtIo.toBytes(c, false)));
        assertEquals(List.of("zeta", "alpha", "mid", "beta"), List.copyOf(back.keySet()));
        c.putInt("alpha", 99); // replacing keeps the position
        assertEquals(List.of("zeta", "alpha", "mid", "beta"), List.copyOf(c.keySet()));
    }

    @Test
    void stringsUseModifiedUtf8() throws IOException {
        NbtCompound c = new NbtCompound();
        c.putString("s", "\u0000\uD83D\uDE00");
        byte[] bytes = NbtIo.toBytes(c, false);
        String hex = HexFormat.of().formatHex(bytes);
        // NUL is C0 80 and the emoji is two 3-byte surrogates, as Java's DataOutput writes them
        assertTrue(hex.contains("0008" + "c080" + "eda0bd" + "edb880"), hex);
        assertEquals("\u0000\uD83D\uDE00", NbtIo.read(new ByteArrayInputStream(bytes)).getString("s"));
    }

    @Test
    void gettersConvertNumbersAndGiveNeutralDefaults() {
        NbtCompound c = new NbtCompound();
        c.putByte("b", (byte) 1);
        c.putDouble("d", 2.9);
        c.putLong("l", 1L << 40);
        c.putString("s", "text");
        assertEquals(1, c.getInt("b"));
        assertTrue(c.getBoolean("b"));
        assertEquals(2, c.getInt("d"));
        assertEquals(1L << 40, c.getLong("l"));
        assertEquals(0, c.getInt("missing"));
        assertEquals(7, c.getInt("missing", 7));
        assertEquals(0, c.getInt("s"), "a string is not a number");
        assertEquals("", c.getString("b"));
        assertEquals("text", c.getString("s"));
        assertEquals("dflt", c.getString("missing", "dflt"));
        assertTrue(c.getCompound("missing").isEmpty());
        assertTrue(c.getList("missing").isEmpty());
        assertEquals(0, c.getByteArray("missing").length);
        assertTrue(c.contains("d", NbtCompound.ANY_NUMBER));
        assertFalse(c.contains("s", NbtCompound.ANY_NUMBER));
        assertEquals(NbtTag.TAG_STRING, c.typeOf("s"));
        assertTrue(c.findCompound("s").isEmpty());
    }

    @Test
    void listsEnforceOneElementType() {
        NbtList l = new NbtList();
        l.add(new NbtInt(1));
        assertEquals(NbtTag.TAG_INT, l.elementType());
        assertThrows(IllegalArgumentException.class, () -> l.add(new NbtString("x")));
        assertThrows(IllegalArgumentException.class, () -> l.add(NbtEnd.INSTANCE));
        l.set(0, new NbtString("only element may change type"));
        assertEquals(NbtTag.TAG_STRING, l.elementType());
        assertEquals("only element may change type", l.getString(0));
        assertEquals(0, l.getInt(5));
        NbtCompound c = new NbtCompound();
        assertThrows(IllegalArgumentException.class, () -> c.put("x", NbtEnd.INSTANCE));
    }

    @Test
    void emptyListKeepsItsDeclaredTypeForByteExactFiles() throws IOException {
        NbtCompound c = new NbtCompound();
        c.put("l", new NbtList(NbtTag.TAG_COMPOUND));
        byte[] bytes = NbtIo.toBytes(c, false);
        NbtCompound back = NbtIo.read(new ByteArrayInputStream(bytes));
        assertEquals(NbtTag.TAG_COMPOUND, back.getList("l").elementType());
        assertArrayEquals(bytes, NbtIo.toBytes(back, false));
    }

    @Test
    void copiesAreDeep() {
        NbtCompound c = everyType();
        NbtCompound copy = c.copy();
        assertEquals(c, copy);
        copy.getCompound("compound").putString("z", "changed");
        copy.getByteArray("bytes")[0] = 42;
        assertEquals("last", c.getCompound("compound").getString("z"));
        assertEquals(1, c.getByteArray("bytes")[0]);
        assertNotSame(c.get("list"), copy.get("list"));
    }

    // ---- malicious input -------------------------------------------------------------------------------

    private static byte[] nestedLists(int depth) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(NbtTag.TAG_COMPOUND);
        d.writeUTF("");
        d.writeByte(NbtTag.TAG_LIST);
        d.writeUTF("deep");
        for (int i = 0; i < depth; i++) {
            d.writeByte(NbtTag.TAG_LIST);
            d.writeInt(1);
        }
        d.writeByte(NbtTag.TAG_END);
        d.writeInt(0);
        d.writeByte(NbtTag.TAG_END);
        return bos.toByteArray();
    }

    @Test
    void rejectsNestingDeeperThan512() throws IOException {
        NbtFormatException e = assertThrows(NbtFormatException.class,
            () -> NbtIo.read(new ByteArrayInputStream(nestedLists(600))));
        assertTrue(e.getMessage().contains("deeper"), e.getMessage());
        assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(nestedLists(100_000))));
        // 509 nested lists inside the root compound and its list stay within the limit
        NbtCompound ok = NbtIo.read(new ByteArrayInputStream(nestedLists(509)));
        assertEquals(1, ok.getList("deep").size());
    }

    @Test
    void writerAlsoRefusesAbsurdNesting() {
        NbtCompound root = new NbtCompound();
        NbtCompound cur = root;
        for (int i = 0; i < 600; i++) {
            NbtCompound next = new NbtCompound();
            cur.put("n", next);
            cur = next;
        }
        assertThrows(NbtFormatException.class, () -> NbtIo.toBytes(root, false));
    }

    private static byte[] header(byte type, String key, int length) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(NbtTag.TAG_COMPOUND);
        d.writeUTF("");
        d.writeByte(type);
        d.writeUTF(key);
        d.writeInt(length);
        return bos.toByteArray();
    }

    @Test
    void hugeDeclaredSizesFailBeforeAllocating() throws IOException {
        for (byte type : new byte[] {NbtTag.TAG_BYTE_ARRAY, NbtTag.TAG_INT_ARRAY, NbtTag.TAG_LONG_ARRAY}) {
            byte[] bytes = header(type, "a", Integer.MAX_VALUE);
            assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(bytes)), "type " + type);
        }
        // a list claiming two billion elements
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(NbtTag.TAG_COMPOUND);
        d.writeUTF("");
        d.writeByte(NbtTag.TAG_LIST);
        d.writeUTF("l");
        d.writeByte(NbtTag.TAG_COMPOUND);
        d.writeInt(2_000_000_000);
        assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(bos.toByteArray())));
    }

    @Test
    void rejectsNegativeLengthsUnknownTypesAndTruncation() throws IOException {
        assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(header(NbtTag.TAG_INT_ARRAY, "a", -1))));
        assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(new byte[] {10, 0, 0, 13, 0, 1, 'x'})));
        assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(new byte[] {(byte) 0x99, 0})));
        // list of TAG_End with elements
        assertThrows(NbtFormatException.class,
            () -> NbtIo.read(new ByteArrayInputStream(new byte[] {10, 0, 0, 9, 0, 1, 'l', 0, 0, 0, 0, 3})));
        byte[] good = NbtIo.toBytes(everyType(), false);
        byte[] cut = java.util.Arrays.copyOf(good, good.length - 5);
        assertThrows(EOFException.class, () -> NbtIo.read(new ByteArrayInputStream(cut)));
        assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(new byte[0])));
        // a root that isn't a compound
        byte[] str = NbtIo.toBytes(new NbtIo.NamedTag("", new NbtString("x")), NbtIo.Compression.NONE);
        assertThrows(NbtFormatException.class, () -> NbtIo.read(new ByteArrayInputStream(str)));
    }

    @Test
    void decompressionBombsHitTheLimit() throws IOException {
        NbtCompound c = new NbtCompound();
        c.putByteArray("zeros", new byte[8 * 1024 * 1024]);
        byte[] gz = NbtIo.toBytes(c, true);
        assertTrue(gz.length < 100_000, "compresses well: " + gz.length);
        assertThrows(NbtFormatException.class,
            () -> NbtIo.readNamed(new ByteArrayInputStream(gz), new NbtIo.Limits(1024 * 1024, 512)));
        assertEquals(8 * 1024 * 1024, NbtIo.read(new ByteArrayInputStream(gz)).getByteArray("zeros").length);
    }

    @Test
    void brokenGzipIsAFormatError() {
        byte[] bad = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5};
        assertThrows(IOException.class, () -> NbtIo.read(new ByteArrayInputStream(bad)));
    }

    @Test
    void stringsLongerThan65535BytesCantBeWritten() {
        NbtCompound c = new NbtCompound();
        c.putString("s", "x".repeat(70_000));
        assertThrows(NbtFormatException.class, () -> NbtIo.toBytes(c, false));
    }

    @Test
    void levelDatRoundTripsByteForByte() throws IOException {
        NbtCompound root = levelDat("My World", 1_700_000_000_000L, 1, "1.21.4", 4189);
        byte[] gz = NbtIo.toBytes(root, true);
        NbtCompound back = NbtIo.read(new ByteArrayInputStream(gz));
        NbtCompound data = back.getCompound("Data");
        assertEquals("My World", data.getString("LevelName"));
        assertEquals(1_700_000_000_000L, data.getLong("LastPlayed"));
        assertEquals(1, data.getInt("GameType"));
        assertEquals("1.21.4", data.getCompound("Version").getString("Name"));
        assertEquals(4189, data.getInt("DataVersion"));
        assertArrayEquals(gunzip(gz), gunzip(NbtIo.toBytes(back, true)));
        assertArrayEquals(gz, NbtIo.toBytes(back, true));
    }

    /** a level.dat as Minecraft writes it (subset) */
    public static NbtCompound levelDat(String name, long lastPlayed, int gameType, String version, int dataVersion) {
        NbtCompound data = new NbtCompound();
        data.putInt("DataVersion", dataVersion);
        data.putString("LevelName", name);
        data.putLong("LastPlayed", lastPlayed);
        data.putInt("GameType", gameType);
        data.putBoolean("hardcore", false);
        data.putBoolean("allowCommands", true);
        data.putLong("RandomSeed", 42L);
        NbtCompound v = new NbtCompound();
        v.putInt("Id", dataVersion);
        v.putString("Name", version);
        v.putString("Series", "main");
        v.putBoolean("Snapshot", false);
        data.put("Version", v);
        NbtCompound gameRules = new NbtCompound();
        gameRules.putString("doDaylightCycle", "true");
        data.put("GameRules", gameRules);
        data.put("ServerBrands", NbtList.of(new NbtString("vanilla")));
        NbtCompound player = new NbtCompound();
        player.put("Pos", NbtList.of(new NbtDouble(0.5), new NbtDouble(64), new NbtDouble(-3.25)));
        player.putIntArray("UUID", new int[] {1, 2, 3, 4});
        data.put("Player", player);
        NbtCompound root = new NbtCompound();
        root.put("Data", data);
        return root;
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    @Test
    void readsGzipWrittenByAnotherWriter() throws IOException {
        byte[] plain = NbtIo.toBytes(everyType(), false);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(plain);
        }
        assertEquals(everyType(), NbtIo.read(new ByteArrayInputStream(bos.toByteArray())));
    }

    @Test
    void toStringIsReadableSnbt() {
        NbtCompound c = new NbtCompound();
        c.putByte("b", (byte) 1).putString("s", "a\"b").putLongArray("la", new long[] {1});
        assertEquals("{b:1b,s:\"a\\\"b\",la:[L;1L]}", c.toString());
    }
}
