package com.porter.stashmanager.planner;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Bounded reader for Java Edition (big-endian) NBT, gzip-compressed or raw. The input is untrusted: every size is
 * checked before anything is allocated, and the whole read is limited in decompressed bytes, tags, depth and time.
 *
 * <p>Tree model: a {@link Compound} (ordered name -> value map) or {@link ListTag}, with values {@code Byte, Short,
 * Integer, Long, Float, Double, byte[], String, ListTag, Compound, int[], long[]}.
 */
public final class Nbt {
    public static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4, TAG_FLOAT = 5, TAG_DOUBLE = 6,
        TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9, TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    /** Most bytes read after decompression (or from a raw file). */
    public static final long MAX_BYTES = 64L << 20;
    /** Deepest nesting of compounds and lists; the root compound is depth 1. */
    public static final int MAX_DEPTH = 32;
    /** Most tags in the file, counting every list element (array elements are not tags). */
    public static final int MAX_TAGS = 1_000_000;
    /**
     * Estimated heap the parsed tree may take (object headers, boxes, map entries), on top of the byte budget: a
     * small file of many tiny tags must not turn into hundreds of MiB of objects.
     */
    public static final long MAX_HEAP = 160L * 1024 * 1024;
    /** Most entries in one long array (64 MiB). */
    public static final int MAX_LONG_ARRAY = 8_388_608;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    /** The deadline is checked every this many tags or array/loop elements. */
    static final int CHECK_EVERY = 65_536;

    private Nbt() {
    }

    // ---------- tree model ----------

    /** A compound tag: names in file order. Lookups return null when the tag is missing or of another type. */
    public static final class Compound {
        private final Map<String, Object> map;

        Compound(Map<String, Object> map) {
            this.map = map;
        }

        public Object get(String name) {
            return map.get(name);
        }

        public boolean has(String name) {
            return map.containsKey(name);
        }

        public int size() {
            return map.size();
        }

        public Set<String> keys() {
            return Collections.unmodifiableSet(map.keySet());
        }

        public Map<String, Object> asMap() {
            return Collections.unmodifiableMap(map);
        }

        public Compound compound(String name) {
            return map.get(name) instanceof Compound c ? c : null;
        }

        public ListTag list(String name) {
            return map.get(name) instanceof ListTag l ? l : null;
        }

        public String string(String name) {
            return map.get(name) instanceof String s ? s : null;
        }

        /** A byte, short or int tag as an Integer; null otherwise. */
        public Integer intValue(String name) {
            Object v = map.get(name);
            if (v instanceof Byte || v instanceof Short || v instanceof Integer) return ((Number) v).intValue();
            return null;
        }

        public byte[] byteArray(String name) {
            return map.get(name) instanceof byte[] b ? b : null;
        }

        public int[] intArray(String name) {
            return map.get(name) instanceof int[] a ? a : null;
        }

        public long[] longArray(String name) {
            return map.get(name) instanceof long[] a ? a : null;
        }
    }

    /** A list tag: all elements have {@link #elementType()}. */
    public static final class ListTag {
        private final int elementType;
        private final List<Object> values;

        ListTag(int elementType, List<Object> values) {
            this.elementType = elementType;
            this.values = values;
        }

        public int elementType() {
            return elementType;
        }

        public int size() {
            return values.size();
        }

        public Object get(int i) {
            return values.get(i);
        }

        public List<Object> values() {
            return Collections.unmodifiableList(values);
        }
    }

    // ---------- reading ----------

    /** Reads the root compound with a timeout from now (null = {@link #DEFAULT_TIMEOUT}). */
    public static Compound read(byte[] data, Duration timeout) throws SchematicException {
        return read(data, deadlineAfter(timeout));
    }

    /** Reads the root compound; {@code deadlineNanos} is a {@link System#nanoTime()} value. */
    public static Compound read(byte[] data, long deadlineNanos) throws SchematicException {
        if (data == null || data.length == 0) throw SchematicException.invalid("the file is empty");
        boolean gzip = data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
        long budget = gzip ? MAX_BYTES : Math.min(MAX_BYTES, data.length);
        try {
            InputStream base = new ByteArrayInputStream(data);
            if (gzip) base = new BufferedInputStream(new GZIPInputStream(base, 65_536), 65_536);
            Counting counting = new Counting(base, MAX_BYTES);
            Reader r = new Reader(new DataInputStream(counting), counting, budget, gzip, deadlineNanos);
            int type = r.in.readUnsignedByte();
            if (type != TAG_COMPOUND) throw SchematicException.invalid("not an NBT file (the root is not a compound tag)");
            r.in.readUTF();
            r.countTag();
            return (Compound) r.payload(TAG_COMPOUND, 1);
        } catch (TooManyBytes e) {
            throw SchematicException.tooLarge("the file decompresses to more than 64 MiB");
        } catch (EOFException e) {
            throw SchematicException.invalid("the file is truncated");
        } catch (UTFDataFormatException e) {
            throw SchematicException.invalid("a string in the file is not valid UTF-8");
        } catch (ZipException e) {
            throw SchematicException.invalid("the file is not valid gzip data");
        } catch (IOException e) {
            throw SchematicException.invalid("the file could not be read");
        }
    }

    public static long deadlineAfter(Duration timeout) {
        Duration t = timeout == null ? DEFAULT_TIMEOUT : timeout;
        return System.nanoTime() + Math.max(0, Math.min(t.toNanos(), Duration.ofHours(1).toNanos()));
    }

    /** Throws {@link SchematicException.Code#TIMEOUT} once {@code deadlineNanos} has passed. */
    public static void checkDeadline(long deadlineNanos) throws SchematicException {
        if (System.nanoTime() - deadlineNanos > 0) {
            throw new SchematicException(SchematicException.Code.TIMEOUT, "the schematic took too long to read");
        }
    }

    private static final class Reader {
        final DataInputStream in;
        final Counting counting;
        final long budget;
        final boolean gzip;
        final long deadline;
        int tags;

        Reader(DataInputStream in, Counting counting, long budget, boolean gzip, long deadline) {
            this.in = in;
            this.counting = counting;
            this.budget = budget;
            this.gzip = gzip;
            this.deadline = deadline;
        }

        long heap;

        /** Adds an estimated object size to the heap budget. */
        void charge(long bytes) throws SchematicException {
            heap += bytes;
            if (heap > MAX_HEAP) throw SchematicException.tooLarge("the file would take more than " + (MAX_HEAP >> 20) + " MiB to read");
        }

        void countTag() throws SchematicException {
            if (++tags > MAX_TAGS) throw SchematicException.tooLarge("the file has more than " + MAX_TAGS + " tags");
            if (tags % CHECK_EVERY == 0) checkDeadline(deadline);
        }

        /** Checks that {@code length} elements of {@code size} bytes can still be in the file, before allocating. */
        void checkLength(int length, int size) throws SchematicException {
            if (length < 0) throw SchematicException.invalid("a list or array has a negative length");
            if ((long) length * size > budget - counting.count) {
                throw gzip ? SchematicException.tooLarge("a list or array is larger than the 64 MiB limit allows")
                    : SchematicException.invalid("a list or array is longer than the rest of the file");
            }
        }

        Object payload(int type, int depth) throws IOException, SchematicException {
            switch (type) {
                case TAG_BYTE: charge(16); return in.readByte();
                case TAG_SHORT: charge(16); return in.readShort();
                case TAG_INT: charge(16); return in.readInt();
                case TAG_LONG: charge(24); return in.readLong();
                case TAG_FLOAT: charge(16); return in.readFloat();
                case TAG_DOUBLE: charge(24); return in.readDouble();
                case TAG_STRING: {
                    String str = in.readUTF();
                    charge(48 + 2L * str.length());
                    return str;
                }
                case TAG_BYTE_ARRAY: {
                    int n = in.readInt();
                    checkLength(n, 1);
                    charge(16L + n);
                    byte[] a = new byte[n];
                    for (int off = 0; off < n; off += CHECK_EVERY * 16) {
                        checkDeadline(deadline);
                        in.readFully(a, off, Math.min(CHECK_EVERY * 16, n - off));
                    }
                    return a;
                }
                case TAG_INT_ARRAY: {
                    int n = in.readInt();
                    checkLength(n, 4);
                    charge(16L + 4L * n);
                    int[] a = new int[n];
                    byte[] buf = new byte[Math.min(n, CHECK_EVERY) * 4];
                    for (int off = 0; off < n; off += CHECK_EVERY) {
                        checkDeadline(deadline);
                        int k = Math.min(CHECK_EVERY, n - off);
                        in.readFully(buf, 0, k * 4);
                        ByteBuffer.wrap(buf, 0, k * 4).asIntBuffer().get(a, off, k);
                    }
                    return a;
                }
                case TAG_LONG_ARRAY: {
                    int n = in.readInt();
                    checkLength(n, 8);
                    if (n > MAX_LONG_ARRAY) throw SchematicException.tooLarge("a long array has more than " + MAX_LONG_ARRAY + " entries");
                    charge(16L + 8L * n);
                    long[] a = new long[n];
                    byte[] buf = new byte[Math.min(n, CHECK_EVERY) * 8];
                    for (int off = 0; off < n; off += CHECK_EVERY) {
                        checkDeadline(deadline);
                        int k = Math.min(CHECK_EVERY, n - off);
                        in.readFully(buf, 0, k * 8);
                        ByteBuffer.wrap(buf, 0, k * 8).asLongBuffer().get(a, off, k);
                    }
                    return a;
                }
                case TAG_LIST: {
                    if (depth > MAX_DEPTH) throw SchematicException.tooLarge("the file is nested more than " + MAX_DEPTH + " levels deep");
                    int elem = in.readUnsignedByte();
                    if (elem > TAG_LONG_ARRAY) throw SchematicException.invalid("unknown NBT tag type " + elem);
                    int n = in.readInt();
                    if (elem == TAG_END && n > 0) throw SchematicException.invalid("a list has no element type");
                    checkLength(n, minSize(elem));
                    charge(64 + 8L * n);
                    List<Object> values = new ArrayList<>(Math.min(n, 4096));
                    for (int i = 0; i < n; i++) {
                        countTag();
                        values.add(payload(elem, depth + 1));
                    }
                    return new ListTag(elem, values);
                }
                case TAG_COMPOUND: {
                    if (depth > MAX_DEPTH) throw SchematicException.tooLarge("the file is nested more than " + MAX_DEPTH + " levels deep");
                    charge(96);
                    Map<String, Object> map = new LinkedHashMap<>();
                    while (true) {
                        int t = in.readUnsignedByte();
                        if (t == TAG_END) break;
                        if (t > TAG_LONG_ARRAY) throw SchematicException.invalid("unknown NBT tag type " + t);
                        String name = in.readUTF();
                        charge(64 + 48 + 2L * name.length());
                        countTag();
                        map.put(name, payload(t, depth + 1));
                    }
                    return new Compound(map);
                }
                default:
                    throw SchematicException.invalid("unknown NBT tag type " + type);
            }
        }
    }

    /** Smallest encoded size of one list element of this type. */
    static int minSize(int type) {
        return switch (type) {
            case TAG_END -> 0;
            case TAG_BYTE, TAG_COMPOUND -> 1;
            case TAG_SHORT, TAG_STRING -> 2;
            case TAG_INT, TAG_FLOAT, TAG_BYTE_ARRAY, TAG_INT_ARRAY, TAG_LONG_ARRAY -> 4;
            case TAG_LIST -> 5;
            default -> 8; // long, double
        };
    }

    private static final class TooManyBytes extends IOException {
        TooManyBytes() {
            super("decompressed size limit", null);
        }
    }

    /** Counts the bytes handed to the parser and fails once more than {@code limit} have been read. */
    private static final class Counting extends FilterInputStream {
        final long limit;
        long count;

        Counting(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        private void add(long n) throws TooManyBytes {
            if (n > 0) {
                count += n;
                if (count > limit) throw new TooManyBytes();
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            add(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            add(s);
            return s;
        }
    }
}
