package com.porter.stashmanager.planner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Test helper that writes big-endian NBT. Values: Byte, Short, Integer, Long, Float, Double, byte[], String, int[],
 * long[], {@link Map} (compound, in insertion order) and {@link ListOf} (list with an explicit element type).
 */
final class NbtWriter {
    record ListOf(int type, List<Object> items) {
    }

    private NbtWriter() {
    }

    static Map<String, Object> compound(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static ListOf list(int type, Object... items) {
        return new ListOf(type, Arrays.asList(items));
    }

    static ListOf list(int type, List<?> items) {
        return new ListOf(type, List.copyOf(items));
    }

    static ListOf compounds(Object... items) {
        return list(Nbt.TAG_COMPOUND, items);
    }

    /** Raw (uncompressed) NBT with an unnamed root compound. */
    static byte[] write(Map<String, Object> root) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(Nbt.TAG_COMPOUND);
            out.writeUTF("");
            payload(out, root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static int typeOf(Object v) {
        if (v instanceof Byte) return Nbt.TAG_BYTE;
        if (v instanceof Short) return Nbt.TAG_SHORT;
        if (v instanceof Integer) return Nbt.TAG_INT;
        if (v instanceof Long) return Nbt.TAG_LONG;
        if (v instanceof Float) return Nbt.TAG_FLOAT;
        if (v instanceof Double) return Nbt.TAG_DOUBLE;
        if (v instanceof byte[]) return Nbt.TAG_BYTE_ARRAY;
        if (v instanceof String) return Nbt.TAG_STRING;
        if (v instanceof ListOf) return Nbt.TAG_LIST;
        if (v instanceof Map) return Nbt.TAG_COMPOUND;
        if (v instanceof int[]) return Nbt.TAG_INT_ARRAY;
        if (v instanceof long[]) return Nbt.TAG_LONG_ARRAY;
        throw new IllegalArgumentException("not an NBT value: " + v);
    }

    @SuppressWarnings("unchecked")
    static void payload(DataOutputStream out, Object v) throws IOException {
        switch (typeOf(v)) {
            case Nbt.TAG_BYTE -> out.writeByte((Byte) v);
            case Nbt.TAG_SHORT -> out.writeShort((Short) v);
            case Nbt.TAG_INT -> out.writeInt((Integer) v);
            case Nbt.TAG_LONG -> out.writeLong((Long) v);
            case Nbt.TAG_FLOAT -> out.writeFloat((Float) v);
            case Nbt.TAG_DOUBLE -> out.writeDouble((Double) v);
            case Nbt.TAG_BYTE_ARRAY -> {
                byte[] a = (byte[]) v;
                out.writeInt(a.length);
                out.write(a);
            }
            case Nbt.TAG_STRING -> out.writeUTF((String) v);
            case Nbt.TAG_LIST -> {
                ListOf l = (ListOf) v;
                out.writeByte(l.type());
                out.writeInt(l.items().size());
                for (Object o : l.items()) payload(out, o);
            }
            case Nbt.TAG_COMPOUND -> {
                for (var e : ((Map<String, Object>) v).entrySet()) {
                    out.writeByte(typeOf(e.getValue()));
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(Nbt.TAG_END);
            }
            case Nbt.TAG_INT_ARRAY -> {
                int[] a = (int[]) v;
                out.writeInt(a.length);
                for (int x : a) out.writeInt(x);
            }
            default -> {
                long[] a = (long[]) v;
                out.writeInt(a.length);
                for (long x : a) out.writeLong(x);
            }
        }
    }

    // ---------- format builders ----------

    /** Packs palette indexes the way LitematicaBitArray does: LSB-first, entries may span two longs. */
    static long[] packLitematica(int[] indexes, int bits) {
        long[] out = new long[(int) (((long) indexes.length * bits + 63) / 64)];
        for (int i = 0; i < indexes.length; i++) {
            long bitPos = (long) i * bits;
            int word = (int) (bitPos >>> 6);
            int off = (int) (bitPos & 63);
            out[word] |= ((long) indexes[i]) << off;
            if (off + bits > 64) out[word + 1] |= ((long) indexes[i]) >>> (64 - off);
        }
        return out;
    }

    static byte[] varints(int... values) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int v : values) {
            while ((v & ~0x7f) != 0) {
                b.write((v & 0x7f) | 0x80);
                v >>>= 7;
            }
            b.write(v);
        }
        return b.toByteArray();
    }

    /** A palette entry {@code {Name, Properties}} (properties as name, value pairs). */
    static Map<String, Object> state(String name, String... props) {
        Map<String, Object> m = compound("Name", name);
        if (props.length > 0) {
            Map<String, Object> p = new LinkedHashMap<>();
            for (int i = 0; i < props.length; i += 2) p.put(props[i], props[i + 1]);
            m.put("Properties", p);
        }
        return m;
    }

    static Map<String, Object> region(int px, int py, int pz, int sx, int sy, int sz, ListOf palette, long[] states) {
        return compound("Position", compound("x", px, "y", py, "z", pz), "Size", compound("x", sx, "y", sy, "z", sz),
            "BlockStatePalette", palette, "BlockStates", states, "TileEntities", compounds());
    }

    static Map<String, Object> litematic(int version, Map<String, Object> regions) {
        return compound("MinecraftDataVersion", 4189, "Version", version, "Metadata", compound("Name", "test"), "Regions", regions);
    }
}
