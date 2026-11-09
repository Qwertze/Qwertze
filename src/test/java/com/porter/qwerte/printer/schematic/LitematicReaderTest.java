package com.porter.qwerte.printer.schematic;

import static com.porter.qwerte.printer.schematic.LitematicFixtures.entry;
import static com.porter.qwerte.printer.schematic.LitematicFixtures.file;
import static com.porter.qwerte.printer.schematic.LitematicFixtures.gzip;
import static com.porter.qwerte.printer.schematic.LitematicFixtures.region;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class LitematicReaderTest {
    private static long deadline() {
        return System.nanoTime() + 30_000_000_000L;
    }

    private static SchematicData read(CompoundTag root) throws LitematicReader.Failure {
        return LitematicReader.parse(root, deadline());
    }

    private static String refused(CompoundTag root) {
        return assertThrows(LitematicReader.Failure.class, () -> read(root)).getMessage();
    }

    @Test
    void aValidFileReadsThroughGzipWithItsPaletteAndCells() throws Exception {
        SchematicData d = LitematicReader.read(gzip(file(LitematicFixtures.small())), deadline());
        assertEquals(7, d.version);
        assertEquals(4000, d.dataVersion);
        assertEquals("test build", d.name);
        assertEquals(12, d.totalVolume);
        SchematicData.Region r = d.region("main");
        assertArrayEquals(new String[] { "minecraft:stone", "minecraft:dirt", "minecraft:oak_log[axis=y]" }, r.palette);
        assertEquals(2, r.bitsPer);
        assertEquals("minecraft:stone", r.keyAt(0, 0, 0));
        assertEquals("minecraft:dirt", r.keyAt(1, 0, 0));
        assertEquals("minecraft:oak_log[axis=y]", r.keyAt(0, 0, 1));
        assertEquals(0, r.badIndexes());
    }

    @Test
    void theStorageStaysPackedAsInTheFile() throws Exception {
        long[] packed = LitematicFormat.pack(new int[] { 0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2 }, 2);
        SchematicData d = read(file(LitematicFixtures.small()));
        assertArrayEquals(packed, d.region("main").bits);
    }

    @Test
    void aNegativeSizeKeepsTheAbsoluteCellCountAndMovesTheMinimumCorner() throws Exception {
        CompoundTag reg = region(5, 6, 7, -3, 1, -2, new String[] { "minecraft:stone", "minecraft:dirt" }, new int[] { 0, 1, 0, 1, 0, 1 });
        SchematicData.Region r = read(file(reg)).region("main");
        assertEquals(3, r.absX());
        assertEquals(2, r.absZ());
        assertEquals(6L, r.volume());
        assertEquals(3, r.minRelX());
        assertEquals(6, r.minRelY());
        assertEquals(6, r.minRelZ());
    }

    @Test
    void versionsBelowFourAreRefusedWithAHintAndNewerOnesWarn() throws Exception {
        assertTrue(refused(file(3, 4000, Map.of("main", LitematicFixtures.small()))).contains("open it in Litematica"));
        SchematicData d = read(file(8, 4000, Map.of("main", LitematicFixtures.small())));
        assertEquals(1, d.warnings.size());
        assertEquals(4, read(file(4, 4000, Map.of("main", LitematicFixtures.small()))).version);
    }

    @Test
    void aMissingOrWrongTypedVersionIsRefused() {
        CompoundTag root = file(LitematicFixtures.small());
        root.put("Version", StringTag.valueOf("7"));
        refused(root);
        CompoundTag none = file(LitematicFixtures.small());
        none.remove("Version");
        refused(none);
    }

    @Test
    void moreThanSixtyFourRegionsAreRefused() throws Exception {
        Map<String, CompoundTag> regions = new LinkedHashMap<>();
        for (int i = 0; i < 64; i++) regions.put("r" + i, region(i, 0, 0, 1, 1, 1, new String[] { "minecraft:stone" }, new int[] { 0 }));
        assertEquals(64, read(file(7, 4000, regions)).regions.size());
        regions.put("one more", region(99, 0, 0, 1, 1, 1, new String[] { "minecraft:stone" }, new int[] { 0 }));
        refused(file(7, 4000, regions));
    }

    @Test
    void aRegionOverTheVolumeLimitIsRefusedBeforeItsDataIsLooked_At() {
        CompoundTag huge = new CompoundTag();
        huge.put("Position", LitematicFixtures.xyz(0, 0, 0));
        huge.put("Size", LitematicFixtures.xyz(1 << 13, 1 << 13, 2));
        assertTrue(refused(file(huge)).contains("larger than"));
        CompoundTag overflow = new CompoundTag();
        overflow.put("Position", LitematicFixtures.xyz(0, 0, 0));
        overflow.put("Size", LitematicFixtures.xyz(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(refused(file(overflow)).contains("larger than"));
    }

    @Test
    void theTotalVolumeAcrossRegionsIsLimitedToo() throws Exception {
        long[] data = new long[(int) LitematicFormat.expectedLength(1 << 24, 2)];
        Map<String, CompoundTag> regions = new LinkedHashMap<>();
        for (int i = 0; i < 2; i++) regions.put("r" + i, region(0, 0, 0, 1 << 12, 1 << 12, 1, LitematicFixtures.palette("minecraft:stone"), data));
        assertEquals(1L << 25, read(file(7, 4000, regions)).totalVolume); // exactly the limit is allowed
        regions.put("r2", region(0, 0, 0, 1, 1, 1, new String[] { "minecraft:stone" }, new int[] { 0 }));
        assertTrue(refused(file(7, 4000, regions)).contains("larger than"));
    }

    @Test
    void aPaletteLargerThanTheVolumeOrTheLimitIsRefused() {
        ListTag tooMany = new ListTag();
        for (int i = 0; i < LitematicReader.MAX_PALETTE + 1; i++) tooMany.add(entry("minecraft:stone"));
        assertTrue(refused(file(region(0, 0, 0, 70000, 1, 1, tooMany, new long[0]))).contains("palette"));
        ListTag two = LitematicFixtures.palette("minecraft:stone", "minecraft:dirt");
        assertTrue(refused(file(region(0, 0, 0, 1, 1, 1, two, new long[1]))).contains("palette"));
        assertTrue(refused(file(region(0, 0, 0, 1, 1, 1, new ListTag(), new long[1]))).contains("palette"));
    }

    @Test
    void blockIdsAndPropertiesAreValidated() {
        for (String bad : new String[] { "Minecraft:Stone", "stone", "minecraft:stone dirt", "minecraft:", ":stone", "minecraft:sto\u0000ne" }) {
            CompoundTag reg = region(0, 0, 0, 1, 1, 1, LitematicFixtures.palette("minecraft:stone"), new long[1]);
            ListTag p = new ListTag();
            CompoundTag e = new CompoundTag();
            e.putString("Name", bad);
            p.add(e);
            reg.put("BlockStatePalette", p);
            refused(file(reg));
        }
        CompoundTag longName = new CompoundTag();
        longName.putString("Name", "minecraft:" + "a".repeat(LitematicReader.MAX_NAME));
        ListTag p = new ListTag();
        p.add(longName);
        CompoundTag reg = region(0, 0, 0, 1, 1, 1, LitematicFixtures.palette("minecraft:stone"), new long[1]);
        reg.put("BlockStatePalette", p);
        refused(file(reg));

        CompoundTag badProp = entry("minecraft:oak_log");
        CompoundTag props = new CompoundTag();
        props.putString("AXIS", "y");
        badProp.put("Properties", props);
        ListTag q = new ListTag();
        q.add(badProp);
        CompoundTag reg2 = region(0, 0, 0, 1, 1, 1, LitematicFixtures.palette("minecraft:stone"), new long[1]);
        reg2.put("BlockStatePalette", q);
        refused(file(reg2));
    }

    @Test
    void morePropertiesThanTheLimitAreRefused() {
        CompoundTag e = entry("minecraft:stone");
        CompoundTag props = new CompoundTag();
        for (int i = 0; i < LitematicReader.MAX_PROPERTIES + 1; i++) props.putString("p" + i, "v");
        e.put("Properties", props);
        ListTag p = new ListTag();
        p.add(e);
        assertTrue(refused(file(region(0, 0, 0, 1, 1, 1, p, new long[1]))).contains("propert"));
    }

    @Test
    void theDataLengthMustMatchExactly() {
        String[] pal = { "minecraft:stone", "minecraft:dirt" };
        ListTag palette = LitematicFixtures.palette(pal);
        assertTrue(refused(file(region(0, 0, 0, 8, 8, 1, palette, new long[1]))).contains("data words")); // 64 cells * 2 bits = 2 longs
        assertTrue(refused(file(region(0, 0, 0, 8, 8, 1, palette, new long[3]))).contains("data words"));
        CompoundTag noData = region(0, 0, 0, 8, 8, 1, palette, new long[2]);
        noData.remove("BlockStates");
        refused(file(noData));
        CompoundTag wrongType = region(0, 0, 0, 8, 8, 1, palette, new long[2]);
        wrongType.put("BlockStates", new ListTag());
        refused(file(wrongType));
    }

    @Test
    void aPaletteIndexThatDoesNotExistIsFoundLazilyAndReadsAsNothing() throws Exception {
        // three palette entries use 2 bits, so index 3 fits in the bits but names no entry
        CompoundTag reg = region(0, 0, 0, 4, 1, 1, new String[] { "minecraft:stone", "minecraft:dirt", "minecraft:sand" }, new int[] { 0, 3, 1, 2 });
        SchematicData.Region r = read(file(reg)).region("main");
        assertEquals("minecraft:stone", r.keyAt(0, 0, 0));
        assertEquals(0, r.badIndexes());
        assertNull(r.keyAt(1, 0, 0));
        assertEquals(1, r.badIndexes());
        assertEquals(-1, r.paletteIndex(1, 0, 0));
        assertEquals("minecraft:sand", r.keyAt(3, 0, 0));
    }

    @Test
    void wrongTypesAreRefusedNotThrown() {
        CompoundTag a = file(LitematicFixtures.small());
        a.put("Regions", StringTag.valueOf("nope"));
        refused(a);
        CompoundTag b = LitematicFixtures.small();
        b.put("Size", new ListTag());
        refused(file(b));
        CompoundTag c = LitematicFixtures.small();
        CompoundTag size = LitematicFixtures.xyz(2, 2, 3);
        size.put("x", StringTag.valueOf("2"));
        c.put("Size", size);
        refused(file(c));
        CompoundTag d = LitematicFixtures.small();
        d.put("BlockStatePalette", new LongArrayTag(new long[1]));
        refused(file(d));
        CompoundTag e = LitematicFixtures.small();
        e.remove("Position");
        refused(file(e));
        CompoundTag f = LitematicFixtures.small();
        f.put("Position", LitematicFixtures.xyz(Integer.MAX_VALUE, 0, 0));
        refused(file(f));
        CompoundTag g = LitematicFixtures.small();
        ListTag pal = new ListTag();
        pal.add(IntTag.valueOf(1));
        g.put("BlockStatePalette", pal);
        refused(file(g));
        CompoundTag h = file(LitematicFixtures.small());
        h.put("Regions", new CompoundTag());
        refused(h);
    }

    @Test
    void anEmptyRegionIsLeftOutWithAWarningAndAFileOfOnlyThoseIsRefused() throws Exception {
        Map<String, CompoundTag> regions = new LinkedHashMap<>();
        regions.put("empty", region(0, 0, 0, 0, 5, 5, new String[] { "minecraft:stone" }, new int[0]));
        regions.put("main", LitematicFixtures.small());
        SchematicData d = read(file(7, 4000, regions));
        assertEquals(1, d.regions.size());
        assertEquals(1, d.warnings.size());
        refused(file(7, 4000, Map.of("empty", region(0, 0, 0, 0, 5, 5, new String[] { "minecraft:stone" }, new int[0]))));
    }

    @Test
    void textFromTheFileIsStrippedOfControlCharacters() throws Exception {
        CompoundTag root = file(LitematicFixtures.small());
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "a\nb\u001bc" + "x".repeat(200));
        root.put("Metadata", meta);
        SchematicData d = read(root);
        assertTrue(d.name.startsWith("abc"));
        assertEquals(64, d.name.length());
        assertEquals("", d.author);
    }

    @Test
    void notGzipEmptyAndTruncatedInputIsRefused() {
        byte[] good = gzip(file(LitematicFixtures.small()));
        assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(new byte[0], deadline()));
        assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(null, deadline()));
        String notGzip = assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read("this is plainly not gzip data".getBytes(), deadline())).getMessage();
        assertTrue(notGzip.contains("gzip"));
        byte[] cut = java.util.Arrays.copyOf(good, good.length / 2);
        assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(cut, deadline()));
        byte[] cutShort = java.util.Arrays.copyOf(good, 12);
        assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(cutShort, deadline()));
    }

    @Test
    void aCompressedFileOverTheLimitIsRefusedWithoutInflating() {
        byte[] big = new byte[(int) LitematicReader.MAX_COMPRESSED + 1];
        String m = assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(big, deadline())).getMessage();
        assertTrue(m.contains("larger"));
    }

    @Test
    void deeplyNestedNbtIsRefused() {
        CompoundTag root = file(LitematicFixtures.small());
        CompoundTag inner = new CompoundTag();
        for (int i = 0; i < 200; i++) {
            CompoundTag next = new CompoundTag();
            next.put("n", inner);
            inner = next;
        }
        root.put("deep", inner);
        byte[] bytes = gzip(root);
        assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(bytes, deadline()));
    }

    @Test
    void aGzipBombThatDeclaresAHugeArrayIsStoppedBeforeItIsInflated() throws IOException {
        // a root compound holding a byte array that claims 200 MiB (over the 192 MiB heap quota) and then delivers zeros
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long declared = 200L << 20;
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(7);
            out.writeUTF("a");
            out.writeInt((int) declared);
            byte[] zeros = new byte[1 << 20];
            for (long written = 0; written < declared; written += zeros.length) out.write(zeros);
            out.writeByte(0);
        }
        byte[] bomb = bos.toByteArray();
        assertTrue(bomb.length < 2 << 20, "the bomb is small compressed");
        long start = System.nanoTime();
        assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(bomb, deadline()));
        assertTrue(System.nanoTime() - start < 5_000_000_000L);
    }

    @Test
    void aDeadlineInThePastStopsTheRead() {
        byte[] good = gzip(file(LitematicFixtures.small()));
        String m = assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(good, System.nanoTime() - 1)).getMessage();
        assertTrue(m.contains("too long"));
    }

    @Test
    void chatMarkupAndFormatCharactersAreStrippedFromShownNames() {
        assertEquals("a[highlight]b", LitematicReader.clean("a(highlight)b", 40));
        assertEquals("acbsc", LitematicReader.clean("a§cb%sc", 40));
        assertEquals("xxxx", LitematicReader.clean("xxxxyyyy", 4));
        assertEquals("", LitematicReader.clean(null, 4));
    }

    @Test
    void aDataVersionBeforeTheFlatteningIsRefused() {
        String m = refused(file(7, 1000, Map.of("main", LitematicFixtures.small())));
        assertTrue(m.contains("too old"));
        // 1139 is 1.13 itself, and an unknown (0) data version is allowed
        assertEquals(1139, assertDoesNotThrowRead(file(7, 1139, Map.of("main", LitematicFixtures.small()))).dataVersion);
        assertEquals(0, assertDoesNotThrowRead(file(7, 0, Map.of("main", LitematicFixtures.small()))).dataVersion);
    }

    private static SchematicData assertDoesNotThrowRead(CompoundTag root) {
        try {
            return read(root);
        } catch (LitematicReader.Failure e) {
            throw new AssertionError(e.getMessage());
        }
    }

    @Test
    void aReadOnAnInterruptedThreadStops() {
        byte[] good = gzip(file(LitematicFixtures.small()));
        Thread.currentThread().interrupt();
        try {
            assertThrows(LitematicReader.Failure.class, () -> LitematicReader.read(good, deadline()));
        } finally {
            Thread.interrupted();
        }
    }
}
