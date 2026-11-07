package com.porter.stashmanager.planner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static com.porter.stashmanager.planner.NbtWriter.compound;
import static com.porter.stashmanager.planner.NbtWriter.list;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtTest {
    private static final Duration T = Duration.ofSeconds(30);

    private static SchematicException fails(byte[] data) {
        return assertThrows(SchematicException.class, () -> Nbt.read(data, T));
    }

    /** Root compound header followed by hand-written payload bytes (no END for the root). */
    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] raw(Body body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeByte(Nbt.TAG_COMPOUND);
        out.writeUTF("");
        body.write(out);
        out.flush();
        return b.toByteArray();
    }

    @Test
    void readsEveryTagType() throws Exception {
        byte[] data = NbtWriter.write(compound(
            "b", (byte) -5, "s", (short) 1234, "i", 123456789, "l", 1L << 40, "f", 1.5f, "d", -2.25,
            "ba", new byte[] { 1, 2, 3 }, "str", "héllo \u0000 wörld", "list", list(Nbt.TAG_INT, 1, 2, 3),
            "c", compound("inner", "x"), "ia", new int[] { -1, 0, 7 }, "la", new long[] { Long.MIN_VALUE, 42 },
            "empty", list(Nbt.TAG_END)));
        Nbt.Compound c = Nbt.read(data, T);
        assertEquals((byte) -5, c.get("b"));
        assertEquals((short) 1234, c.get("s"));
        assertEquals(123456789, c.get("i"));
        assertEquals(1L << 40, c.get("l"));
        assertEquals(1.5f, c.get("f"));
        assertEquals(-2.25, c.get("d"));
        assertArrayEquals(new byte[] { 1, 2, 3 }, c.byteArray("ba"));
        assertEquals("héllo \u0000 wörld", c.string("str"));
        assertEquals(Nbt.TAG_INT, c.list("list").elementType());
        assertEquals(List.of(1, 2, 3), c.list("list").values());
        assertEquals("x", c.compound("c").string("inner"));
        assertArrayEquals(new int[] { -1, 0, 7 }, c.intArray("ia"));
        assertArrayEquals(new long[] { Long.MIN_VALUE, 42 }, c.longArray("la"));
        assertEquals(0, c.list("empty").size());
        assertEquals(List.of("b", "s", "i", "l", "f", "d", "ba", "str", "list", "c", "ia", "la", "empty"), List.copyOf(c.keys()));
        // typed getters return null on a type mismatch
        assertEquals(null, c.compound("i"));
        assertEquals(-5, c.intValue("b"));
        assertEquals(null, c.intValue("l"));
    }

    @Test
    void rawAndGzipGiveTheSameTree() throws Exception {
        long[] big = new long[100_000];
        for (int i = 0; i < big.length; i++) big[i] = i * 31L;
        byte[] raw = NbtWriter.write(compound("name", "x", "la", big));
        byte[] gz = NbtWriter.gzip(raw);
        assertEquals(0x1f, gz[0] & 0xff);
        assertArrayEquals(Nbt.read(raw, T).longArray("la"), Nbt.read(gz, T).longArray("la"));
        assertEquals("x", Nbt.read(gz, T).string("name"));
    }

    private static Object nest(int levels) {
        Object v = 1;
        for (int i = 0; i < levels; i++) v = compound("a", v);
        return v;
    }

    @Test
    void depthLimit() throws Exception {
        // root is depth 1; 31 more nested compounds reach depth 32
        Nbt.read(NbtWriter.write(compound("a", nest(31))), T);
        SchematicException e = fails(NbtWriter.write(compound("a", nest(32))));
        assertEquals(SchematicException.Code.TOO_LARGE, e.code());
        // lists count as levels too
        Object v = list(Nbt.TAG_INT, 1);
        for (int i = 0; i < 31; i++) v = list(Nbt.TAG_LIST, v);
        assertEquals(SchematicException.Code.TOO_LARGE, fails(NbtWriter.write(compound("l", v))).code());
    }

    @Test
    void tagCountLimit() throws Exception {
        // root + list tag + elements
        List<Object> ok = Collections.nCopies(Nbt.MAX_TAGS - 2, (Object) (byte) 0);
        assertEquals(Nbt.MAX_TAGS - 2, Nbt.read(NbtWriter.write(compound("l", list(Nbt.TAG_BYTE, ok))), T).list("l").size());
        List<Object> over = Collections.nCopies(Nbt.MAX_TAGS - 1, (Object) (byte) 0);
        SchematicException e = fails(NbtWriter.write(compound("l", list(Nbt.TAG_BYTE, over))));
        assertEquals(SchematicException.Code.TOO_LARGE, e.code());
    }

    @Test
    void negativeLengths() throws Exception {
        for (int type : new int[] { Nbt.TAG_BYTE_ARRAY, Nbt.TAG_INT_ARRAY, Nbt.TAG_LONG_ARRAY }) {
            byte[] data = raw(out -> {
                out.writeByte(type);
                out.writeUTF("a");
                out.writeInt(-1);
            });
            assertTrue(fails(data).getMessage().contains("negative"), "type " + type);
        }
        byte[] list = raw(out -> {
            out.writeByte(Nbt.TAG_LIST);
            out.writeUTF("l");
            out.writeByte(Nbt.TAG_INT);
            out.writeInt(-5);
        });
        assertTrue(fails(list).getMessage().contains("negative"));
    }

    @Test
    void lengthOverBudgetIsRejectedBeforeAllocating() throws Exception {
        byte[] bytes = raw(out -> {
            out.writeByte(Nbt.TAG_BYTE_ARRAY);
            out.writeUTF("a");
            out.writeInt(1000);
            out.write(new byte[10]);
        });
        assertEquals(SchematicException.Code.INVALID, fails(bytes).code());
        // 2^28 longs would be 2 GiB: refused from the length alone
        byte[] longs = raw(out -> {
            out.writeByte(Nbt.TAG_LONG_ARRAY);
            out.writeUTF("a");
            out.writeInt(1 << 28);
        });
        assertTrue(fails(longs).getMessage().contains("longer than the rest"));
        byte[] gzLongs = NbtWriter.gzip(longs);
        assertEquals(SchematicException.Code.TOO_LARGE, assertThrows(SchematicException.class, () -> Nbt.read(gzLongs, T)).code());
        // a list of 1e9 compounds needs at least 1e9 bytes
        byte[] lst = raw(out -> {
            out.writeByte(Nbt.TAG_LIST);
            out.writeUTF("l");
            out.writeByte(Nbt.TAG_COMPOUND);
            out.writeInt(1_000_000_000);
        });
        assertTrue(fails(lst).getMessage().contains("longer than the rest"));
        // one entry more than MAX_LONG_ARRAY, gzip so the byte budget is the 64 MiB limit
        byte[] tooMany = NbtWriter.gzip(raw(out -> {
            out.writeByte(Nbt.TAG_LONG_ARRAY);
            out.writeUTF("a");
            out.writeInt(Nbt.MAX_LONG_ARRAY + 1);
        }));
        assertEquals(SchematicException.Code.TOO_LARGE, assertThrows(SchematicException.class, () -> Nbt.read(tooMany, T)).code());
    }

    @Test
    void truncatedAndMalformedInput() throws Exception {
        byte[] good = NbtWriter.write(compound("a", "hello", "b", new int[] { 1, 2, 3 }, "c", compound("d", 1L)));
        for (int cut = 1; cut < good.length; cut++) {
            byte[] part = Arrays.copyOf(good, cut);
            SchematicException e = fails(part);
            assertEquals(SchematicException.Code.INVALID, e.code(), "cut at " + cut);
        }
        assertEquals("the file is truncated", fails(Arrays.copyOf(good, good.length - 1)).getMessage());
        assertTrue(fails(new byte[0]).getMessage().contains("empty"));
        assertTrue(fails(new byte[] { 8, 0, 0, 0, 0 }).getMessage().contains("root"));
        byte[] badType = raw(out -> {
            out.writeByte(13);
            out.writeUTF("x");
        });
        assertTrue(fails(badType).getMessage().contains("unknown NBT tag type 13"));
        byte[] badListType = raw(out -> {
            out.writeByte(Nbt.TAG_LIST);
            out.writeUTF("x");
            out.writeByte(99);
            out.writeInt(0);
        });
        assertTrue(fails(badListType).getMessage().contains("unknown NBT tag type"));
        byte[] endList = raw(out -> {
            out.writeByte(Nbt.TAG_LIST);
            out.writeUTF("x");
            out.writeByte(Nbt.TAG_END);
            out.writeInt(3);
        });
        assertTrue(fails(endList).getMessage().contains("no element type"));
        byte[] badUtf = raw(out -> {
            out.writeByte(Nbt.TAG_STRING);
            out.writeUTF("x");
            out.writeShort(2);
            out.write(new byte[] { (byte) 0xc0, (byte) 0x41 });
            out.writeByte(0);
        });
        assertTrue(fails(badUtf).getMessage().contains("UTF-8"));
        byte[] badGzip = { 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4 };
        assertEquals(SchematicException.Code.INVALID, fails(badGzip).code());
    }

    @Test
    void gzipBombOfZerosIsStoppedAt64MiB() throws Exception {
        // 70 MiB decompressed: a 60 MiB byte array of zeros, then a 10 MiB one that no longer fits the budget
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(b))) {
            out.writeByte(Nbt.TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(Nbt.TAG_BYTE_ARRAY);
            out.writeUTF("a");
            out.writeInt(60 << 20);
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 60; i++) out.write(zeros);
            out.writeByte(Nbt.TAG_BYTE_ARRAY);
            out.writeUTF("b");
            out.writeInt(10 << 20);
            for (int i = 0; i < 10; i++) out.write(zeros);
            out.writeByte(0);
        }
        byte[] bomb = b.toByteArray();
        assertTrue(bomb.length < 200_000, "compressed size " + bomb.length);
        SchematicException e = assertThrows(SchematicException.class, () -> Nbt.read(bomb, T));
        assertEquals(SchematicException.Code.TOO_LARGE, e.code());
    }

    @Test
    void gzipBombOfStringsHitsTheDecompressedByteLimit() throws Exception {
        // the string list passes the length check (2 bytes per string minimum), so the counting stream must stop it
        String s = "a".repeat(65_535);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(b))) {
            out.writeByte(Nbt.TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(Nbt.TAG_LIST);
            out.writeUTF("l");
            out.writeByte(Nbt.TAG_STRING);
            out.writeInt(1_100);
            for (int i = 0; i < 1_100; i++) out.writeUTF(s); // ~72 MB
            out.writeByte(0);
        }
        byte[] bomb = b.toByteArray();
        SchematicException e = assertThrows(SchematicException.class, () -> Nbt.read(bomb, T));
        assertEquals(SchematicException.Code.TOO_LARGE, e.code());
        assertTrue(e.getMessage().contains("64 MiB"));
    }

    @Test
    void deadlineStopsALargeValidInput() {
        List<Object> ints = new ArrayList<>();
        for (int i = 0; i < 500_000; i++) ints.add(i);
        byte[] data = NbtWriter.write(compound("l", list(Nbt.TAG_INT, ints), "la", new long[1_000_000]));
        SchematicException e = assertThrows(SchematicException.class, () -> Nbt.read(data, Duration.ofNanos(1)));
        assertEquals(SchematicException.Code.TIMEOUT, e.code());
        // and the same input is fine with time to spare
        assertFalse(assertDoesNotThrowRead(data).list("l").values().isEmpty());
    }

    private static Nbt.Compound assertDoesNotThrowRead(byte[] data) {
        try {
            return Nbt.read(data, T);
        } catch (SchematicException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void safeMessagesCutLongNames() {
        String longName = "x".repeat(500) + "\u0007";
        String s = SchematicException.safe(longName);
        assertEquals(67, s.length());
        assertTrue(s.endsWith("..."));
        assertEquals("a?b", SchematicException.safe("a\nb"));
        Map<String, Object> m = compound("k", 1);
        assertEquals(1, m.size());
    }
}
