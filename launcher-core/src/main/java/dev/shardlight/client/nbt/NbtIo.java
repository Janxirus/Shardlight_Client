package dev.shardlight.client.nbt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Reads and writes Java Edition NBT (big-endian, modified UTF-8 strings). {@link #read} detects gzip
 * ({@code level.dat}), zlib and uncompressed data ({@code servers.dat}) by their first bytes.
 *
 * <p>Untrusted input is safe to read: nesting deeper than {@link Limits#maxDepth()} (512, as in Minecraft)
 * and data whose decoded size would exceed {@link Limits#maxBytes()} fail with {@link NbtFormatException}
 * before large arrays or lists are allocated. The caller's streams are never closed.
 */
public final class NbtIo {
    /** Minecraft's own nesting limit */
    public static final int MAX_DEPTH = 512;

    /** how a stream is (or should be) compressed */
    public enum Compression { NONE, GZIP, ZLIB }

    /**
     * Reader limits.
     *
     * @param maxBytes approximate budget for decoded data and the objects built from it
     * @param maxDepth how deeply lists and compounds may nest
     */
    public record Limits(long maxBytes, int maxDepth) {
        /** 64 MiB and depth 512: far beyond any level.dat or servers.dat */
        public static final Limits DEFAULT = new Limits(64L << 20, MAX_DEPTH);

        public Limits {
            if (maxBytes <= 0 || maxDepth <= 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    /** a root tag and its name (usually "") */
    public record NamedTag(String name, NbtTag tag) {
        public NamedTag {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(tag, "tag");
        }
    }

    private NbtIo() {
    }

    // ---- reading --------------------------------------------------------------------------------------

    /** read a root compound (gzip, zlib or uncompressed) with the default limits */
    public static NbtCompound read(InputStream in) throws IOException {
        return requireCompound(readNamed(in, Limits.DEFAULT));
    }

    /** read a whole file's root compound */
    public static NbtCompound readFile(Path file) throws IOException {
        return requireCompound(readNamedFile(file));
    }

    /** read a whole file, keeping the root's name */
    public static NamedTag readNamedFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return readNamed(in, Limits.DEFAULT);
        }
    }

    /** read one named root tag of any type, detecting the compression */
    public static NamedTag readNamed(InputStream in, Limits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        BufferedInputStream buf = new BufferedInputStream(new NonClosingInput(in), 8192);
        buf.mark(2);
        int b0 = buf.read();
        int b1 = buf.read();
        buf.reset();
        if (b0 < 0) {
            throw new NbtFormatException("No NBT data (empty input)");
        }
        Compression c = detect(b0, b1);
        switch (c) {
            case GZIP -> {
                try (GZIPInputStream gz = new GZIPInputStream(buf, 8192)) {
                    return parse(gz, limits);
                } catch (java.util.zip.ZipException e) {
                    throw new NbtFormatException("Broken gzip data: " + e.getMessage());
                }
            }
            case ZLIB -> {
                Inflater inflater = new Inflater();
                try (InflaterInputStream z = new InflaterInputStream(buf, inflater, 8192)) {
                    return parse(z, limits);
                } catch (java.util.zip.ZipException e) {
                    throw new NbtFormatException("Broken zlib data: " + e.getMessage());
                } finally {
                    inflater.end();
                }
            }
            default -> {
                return parse(buf, limits);
            }
        }
    }

    /** the compression of data starting with bytes {@code b0} and {@code b1} (-1 when absent) */
    public static Compression detect(int b0, int b1) {
        if (b0 == 0x1f && b1 == 0x8b) {
            return Compression.GZIP;
        }
        // an uncompressed root starts with a tag type 0..12; zlib's CMF byte is 0x08..0x78 with CM = 8
        if (b0 > TAG_MAX && b1 >= 0 && (b0 & 0x0f) == 8 && (b0 >> 4) <= 7 && ((b0 << 8) | b1) % 31 == 0) {
            return Compression.ZLIB;
        }
        return Compression.NONE;
    }

    private static final int TAG_MAX = NbtTag.TAG_LONG_ARRAY;

    private static NbtCompound requireCompound(NamedTag t) throws NbtFormatException {
        if (t.tag() instanceof NbtCompound c) {
            return c;
        }
        throw new NbtFormatException("Root tag is " + NbtTag.typeName(t.tag().type()) + ", not a compound");
    }

    private static NamedTag parse(InputStream decoded, Limits limits) throws IOException {
        InputStream limited = new LimitedInput(new BufferedInputStream(decoded, 16384), limits.maxBytes());
        return new Reader(new DataInputStream(limited), limits).readRoot();
    }

    // ---- writing --------------------------------------------------------------------------------------

    /** write {@code root} as an unnamed root compound, gzip-compressed when {@code compressed} */
    public static void write(OutputStream out, NbtCompound root, boolean compressed) throws IOException {
        write(out, new NamedTag("", root), compressed ? Compression.GZIP : Compression.NONE);
    }

    /** write a named root tag with the given compression */
    public static void write(OutputStream out, NamedTag root, Compression compression) throws IOException {
        Objects.requireNonNull(root, "root");
        OutputStream base = new NonClosingOutput(out);
        switch (compression) {
            case GZIP -> {
                try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(base, 8192), 16384))) {
                    new Writer(d).writeRoot(root);
                }
            }
            case ZLIB -> {
                Deflater deflater = new Deflater();
                try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(base, deflater, 8192), 16384))) {
                    new Writer(d).writeRoot(root);
                } finally {
                    deflater.end();
                }
            }
            default -> {
                try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(base, 16384))) {
                    new Writer(d).writeRoot(root);
                }
            }
        }
    }

    /** the encoded bytes of an unnamed root compound */
    public static byte[] toBytes(NbtCompound root, boolean compressed) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        write(bos, root, compressed);
        return bos.toByteArray();
    }

    /** the encoded bytes of a named root tag */
    public static byte[] toBytes(NamedTag root, Compression compression) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        write(bos, root, compression);
        return bos.toByteArray();
    }

    // ---- implementation -------------------------------------------------------------------------------

    private static final class Reader {
        private final DataInputStream in;
        private final int maxDepth;
        private long budget;

        Reader(DataInputStream in, Limits limits) {
            this.in = in;
            this.maxDepth = limits.maxDepth();
            this.budget = limits.maxBytes();
        }

        NamedTag readRoot() throws IOException {
            byte type = in.readByte();
            if (type == NbtTag.TAG_END) {
                return new NamedTag("", NbtEnd.INSTANCE);
            }
            checkType(type);
            String name = readString();
            return new NamedTag(name, readPayload(type, 0));
        }

        private void spend(long n) throws NbtFormatException {
            if (n < 0 || n > budget) {
                throw new NbtFormatException("NBT data is larger than the allowed limit");
            }
            budget -= n;
        }

        private static void checkType(byte type) throws NbtFormatException {
            if (!NbtTag.isValidType(type)) {
                throw new NbtFormatException("Unknown tag type " + type);
            }
        }

        private int readLength() throws IOException {
            int len = in.readInt();
            if (len < 0) {
                throw new NbtFormatException("Negative length " + len);
            }
            return len;
        }

        private String readString() throws IOException {
            spend(16);
            try {
                String s = in.readUTF();
                spend(2L * s.length());
                return s;
            } catch (UTFDataFormatException e) {
                throw new NbtFormatException("Malformed string: " + e.getMessage());
            }
        }

        private NbtTag readPayload(byte type, int depth) throws IOException {
            switch (type) {
                case NbtTag.TAG_BYTE -> {
                    spend(9);
                    return new NbtByte(in.readByte());
                }
                case NbtTag.TAG_SHORT -> {
                    spend(10);
                    return new NbtShort(in.readShort());
                }
                case NbtTag.TAG_INT -> {
                    spend(12);
                    return new NbtInt(in.readInt());
                }
                case NbtTag.TAG_LONG -> {
                    spend(16);
                    return new NbtLong(in.readLong());
                }
                case NbtTag.TAG_FLOAT -> {
                    spend(12);
                    return new NbtFloat(in.readFloat());
                }
                case NbtTag.TAG_DOUBLE -> {
                    spend(16);
                    return new NbtDouble(in.readDouble());
                }
                case NbtTag.TAG_BYTE_ARRAY -> {
                    int len = readLength();
                    spend(24L + len);
                    byte[] a = new byte[len];
                    in.readFully(a);
                    return new NbtByteArray(a);
                }
                case NbtTag.TAG_STRING -> {
                    return new NbtString(readString());
                }
                case NbtTag.TAG_LIST -> {
                    enter(depth);
                    spend(40);
                    byte elementType = in.readByte();
                    int len = readLength();
                    checkType(elementType);
                    if (elementType == NbtTag.TAG_END && len > 0) {
                        throw new NbtFormatException("List of TAG_End with " + len + " elements");
                    }
                    spend(8L * len);
                    NbtList list = new NbtList(elementType, Math.min(len, 1024));
                    for (int i = 0; i < len; i++) {
                        list.addUnchecked(readPayload(elementType, depth + 1));
                    }
                    return list;
                }
                case NbtTag.TAG_COMPOUND -> {
                    enter(depth);
                    spend(48);
                    NbtCompound c = new NbtCompound();
                    while (true) {
                        byte t = in.readByte();
                        if (t == NbtTag.TAG_END) {
                            return c;
                        }
                        checkType(t);
                        String key = readString();
                        spend(32);
                        c.putUnchecked(key, readPayload(t, depth + 1));
                    }
                }
                case NbtTag.TAG_INT_ARRAY -> {
                    int len = readLength();
                    spend(24L + 4L * len);
                    int[] a = new int[len];
                    for (int i = 0; i < len; i++) {
                        a[i] = in.readInt();
                    }
                    return new NbtIntArray(a);
                }
                case NbtTag.TAG_LONG_ARRAY -> {
                    int len = readLength();
                    spend(24L + 8L * len);
                    long[] a = new long[len];
                    for (int i = 0; i < len; i++) {
                        a[i] = in.readLong();
                    }
                    return new NbtLongArray(a);
                }
                default -> throw new NbtFormatException("Unexpected tag type " + type);
            }
        }

        private void enter(int depth) throws NbtFormatException {
            if (depth >= maxDepth) {
                throw new NbtFormatException("NBT nested deeper than " + maxDepth + " levels");
            }
        }
    }

    private static final class Writer {
        private final DataOutputStream out;

        Writer(DataOutputStream out) {
            this.out = out;
        }

        void writeRoot(NamedTag root) throws IOException {
            out.writeByte(root.tag().type());
            if (root.tag().type() != NbtTag.TAG_END) {
                writeString(root.name());
                writePayload(root.tag(), 0);
            }
        }

        private void writeString(String s) throws IOException {
            try {
                out.writeUTF(s);
            } catch (UTFDataFormatException e) {
                throw new NbtFormatException("String too long for NBT (over 65535 bytes encoded)");
            }
        }

        private void writePayload(NbtTag tag, int depth) throws IOException {
            switch (tag) {
                case NbtEnd e -> {
                    // no payload
                }
                case NbtByte b -> out.writeByte(b.value());
                case NbtShort s -> out.writeShort(s.value());
                case NbtInt i -> out.writeInt(i.value());
                case NbtLong l -> out.writeLong(l.value());
                case NbtFloat f -> out.writeFloat(f.value());
                case NbtDouble d -> out.writeDouble(d.value());
                case NbtString s -> writeString(s.value());
                case NbtByteArray a -> {
                    out.writeInt(a.length());
                    out.write(a.value());
                }
                case NbtIntArray a -> {
                    out.writeInt(a.length());
                    for (int v : a.value()) {
                        out.writeInt(v);
                    }
                }
                case NbtLongArray a -> {
                    out.writeInt(a.length());
                    for (long v : a.value()) {
                        out.writeLong(v);
                    }
                }
                case NbtList l -> {
                    enter(depth);
                    out.writeByte(l.elementType());
                    out.writeInt(l.size());
                    for (NbtTag t : l) {
                        writePayload(t, depth + 1);
                    }
                }
                case NbtCompound c -> {
                    enter(depth);
                    for (Map.Entry<String, NbtTag> e : c.entries()) {
                        out.writeByte(e.getValue().type());
                        writeString(e.getKey());
                        writePayload(e.getValue(), depth + 1);
                    }
                    out.writeByte(NbtTag.TAG_END);
                }
            }
        }

        private static void enter(int depth) throws NbtFormatException {
            if (depth >= MAX_DEPTH) {
                throw new NbtFormatException("NBT nested deeper than " + MAX_DEPTH + " levels");
            }
        }
    }

    /** fails once more than {@code max} decoded bytes have been read (decompression bombs) */
    private static final class LimitedInput extends FilterInputStream {
        private long remaining;

        LimitedInput(InputStream in, long max) {
            super(in);
            this.remaining = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            if (s > 0) {
                count(s);
            }
            return s;
        }

        private void count(long n) throws NbtFormatException {
            remaining -= n;
            if (remaining < 0) {
                throw new NbtFormatException("NBT data is larger than the allowed limit");
            }
        }
    }

    private static final class NonClosingInput extends FilterInputStream {
        NonClosingInput(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // the caller owns the stream
        }
    }

    private static final class NonClosingOutput extends FilterOutputStream {
        NonClosingOutput(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
