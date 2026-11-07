package com.porter.stashmanager.planner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.porter.stashmanager.planner.NbtWriter.compound;
import static com.porter.stashmanager.planner.NbtWriter.compounds;
import static com.porter.stashmanager.planner.NbtWriter.list;
import static com.porter.stashmanager.planner.NbtWriter.state;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicReaderTest {
    private static final Duration T = Duration.ofSeconds(30);

    static Schematic read(Map<String, Object> root) throws SchematicException {
        return SchematicReader.read(NbtWriter.gzip(NbtWriter.write(root)), T);
    }

    static SchematicException fails(Map<String, Object> root) {
        return assertThrows(SchematicException.class, () -> read(root));
    }

    static long count(Schematic s, String state) {
        Long n = s.blockCounts().get(BlockState.parse(state));
        return n == null ? 0 : n;
    }

    // ---------- Litematica ----------

    /** A litematic region of the given palette with entry i = indexes[i]. */
    static Map<String, Object> regionOf(int sx, int sy, int sz, List<Map<String, Object>> palette, int[] indexes) {
        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
        return NbtWriter.region(0, 0, 0, sx, sy, sz, compounds(palette.toArray()), NbtWriter.packLitematica(indexes, bits));
    }

    static List<Map<String, Object>> stonePalette(int n) {
        List<Map<String, Object>> p = new ArrayList<>();
        p.add(state("minecraft:air"));
        String[] names = { "stone", "dirt", "oak_planks", "cobblestone", "glass", "sand", "gravel", "oak_log", "bricks", "granite",
            "diorite", "andesite", "deepslate", "tuff", "calcite", "netherrack", "basalt", "obsidian", "clay", "ice" };
        for (int i = 1; i < n; i++) p.add(state("minecraft:" + names[i - 1]));
        return p;
    }

    @Test
    void litematicEntriesSpanningTwoLongs() throws Exception {
        // 17 entries -> 5 bits; 5 does not divide 64, so entries 12, 25, 38, ... straddle two longs
        List<Map<String, Object>> palette = stonePalette(17);
        int[] idx = new int[4 * 4 * 4];
        for (int i = 0; i < idx.length; i++) idx[i] = (i * 7) % 17;
        long[] packed = NbtWriter.packLitematica(idx, 5);
        assertEquals(5, packed.length); // ceil(64*5/64)
        Schematic s = read(NbtWriter.litematic(6, compound("main", regionOf(4, 4, 4, palette, idx))));
        long[] expect = new long[17];
        for (int i : idx) expect[i]++;
        for (int p = 1; p < 17; p++) assertEquals(expect[p], count(s, (String) palette.get(p).get("Name")), "palette " + p);
        assertEquals(64 - expect[0], s.nonAirBlocks());
        assertEquals(Schematic.Format.LITEMATIC, s.format());
        assertEquals(6, s.formatVersion());
        assertEquals(4189, s.dataVersion());
        assertEquals(64, s.volume());
        assertEquals(1, s.regionCount());
        // the decoder on its own, with a long-straddling entry checked by hand: entry 12 occupies bits 60..64
        long[] counts = SchematicReader.decodeLitematica(new long[] { 0xBL << 60, 0x1L }, 5, 13, 32, Long.MAX_VALUE, "r");
        assertEquals(1, counts[0x1B]);
        assertEquals(12, counts[0]);
    }

    @Test
    void litematicTwoBitMinimum() throws Exception {
        // a one-entry palette still uses 2 bits per entry: 40 entries -> 80 bits -> 2 longs
        List<Map<String, Object>> one = List.of(state("minecraft:stone"));
        Schematic s = read(NbtWriter.litematic(5, compound("r", NbtWriter.region(0, 0, 0, 10, 2, 2, compounds(one.toArray()), new long[2]))));
        assertEquals(40, count(s, "minecraft:stone"));
        // with 1 long it is the wrong length
        SchematicException e = fails(NbtWriter.litematic(5, compound("r", NbtWriter.region(0, 0, 0, 10, 2, 2, compounds(one.toArray()), new long[1]))));
        assertTrue(e.getMessage().contains("expected at least 2"), e.getMessage());
        // two entries: also 2 bits
        int[] idx = new int[33];
        for (int i = 0; i < idx.length; i++) idx[i] = i % 2;
        long[] packed = NbtWriter.packLitematica(idx, 2);
        assertEquals(2, packed.length);
        Schematic s2 = read(NbtWriter.litematic(5, compound("r", NbtWriter.region(0, 0, 0, 33, 1, 1,
            compounds(state("minecraft:air"), state("minecraft:dirt")), packed))));
        assertEquals(16, count(s2, "minecraft:dirt"));
    }

    @Test
    void litematicFivePaletteEntriesUseThreeBits() throws Exception {
        List<Map<String, Object>> palette = List.of(state("minecraft:air"), state("minecraft:stone"),
            state("minecraft:oak_stairs", "half", "top", "facing", "north"), state("minecraft:dirt"), state("minecraft:glass"));
        int[] idx = new int[30]; // 90 bits -> 2 longs; entry 21 covers bits 63..65
        for (int i = 0; i < idx.length; i++) idx[i] = i % 5;
        idx[21] = 4;
        long[] packed = NbtWriter.packLitematica(idx, 3);
        assertEquals(2, packed.length);
        Schematic s = read(NbtWriter.litematic(7, compound("r", NbtWriter.region(0, 0, 0, 5, 3, 2, compounds(palette.toArray()), packed))));
        long[] expect = new long[5];
        for (int i : idx) expect[i]++;
        assertEquals(expect[4], count(s, "minecraft:glass"));
        assertEquals(expect[2], count(s, "minecraft:oak_stairs[facing=north,half=top]"));
        assertEquals(expect[2], s.blockCounts().get(new BlockState("minecraft:oak_stairs", new java.util.TreeMap<>(Map.of("half", "top", "facing", "north")))));
        // an index past the palette (7 with 5 entries) is an error
        idx[3] = 7;
        SchematicException e = fails(NbtWriter.litematic(7, compound("r", NbtWriter.region(0, 0, 0, 5, 3, 2, compounds(palette.toArray()),
            NbtWriter.packLitematica(idx, 3)))));
        assertTrue(e.getMessage().contains("palette entry that does not exist"));
    }

    @Test
    void litematicNegativeSizeAndTwoRegions() throws Exception {
        List<Map<String, Object>> palette = stonePalette(3); // air, stone, dirt
        int[] a = new int[3 * 2 * 2];
        java.util.Arrays.fill(a, 1);
        int[] b = new int[2 * 2 * 2];
        java.util.Arrays.fill(b, 2);
        // region a: position (2,0,1), size (-3,2,-2) -> x 0..2, y 0..1, z 0..1
        Map<String, Object> ra = NbtWriter.region(2, 0, 1, -3, 2, -2, compounds(palette.toArray()), NbtWriter.packLitematica(a, 2));
        // region b: position (5,0,0), size (2,2,2) -> x 5..6
        Map<String, Object> rb = NbtWriter.region(5, 0, 0, 2, 2, 2, compounds(palette.toArray()), NbtWriter.packLitematica(b, 2));
        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put("a", ra);
        regions.put("b", rb);
        Schematic s = read(NbtWriter.litematic(6, regions));
        assertEquals(12, count(s, "minecraft:stone"));
        assertEquals(8, count(s, "minecraft:dirt"));
        assertEquals(2, s.regionCount());
        assertEquals(20, s.volume());
        assertEquals(7, s.sizeX());
        assertEquals(2, s.sizeY());
        assertEquals(2, s.sizeZ());
    }

    @Test
    void litematicWrongArrayLengthVersionAndLimits() throws Exception {
        List<Map<String, Object>> palette = stonePalette(2);
        // padding after the data is accepted (all zeros: palette entry 0, air), a short array is not
        Schematic padded = read(NbtWriter.litematic(6, compound("r", NbtWriter.region(0, 0, 0, 4, 4, 4, compounds(palette.toArray()), new long[3]))));
        assertEquals(64, padded.volume());
        assertEquals(0, padded.nonAirBlocks());
        SchematicException e = fails(NbtWriter.litematic(6, compound("r", NbtWriter.region(0, 0, 0, 4, 4, 4, compounds(palette.toArray()), new long[1]))));
        assertTrue(e.getMessage().contains("BlockStates has 1 longs, expected at least 2"), e.getMessage());
        assertEquals(SchematicException.Code.UNSUPPORTED,
            fails(NbtWriter.litematic(3, compound("r", NbtWriter.region(0, 0, 0, 1, 1, 1, compounds(palette.toArray()), new long[1])))).code());
        // volume over 2^27 is refused before the (missing) block data is looked at
        Map<String, Object> huge = NbtWriter.region(0, 0, 0, 1024, 1024, 129, compounds(palette.toArray()), new long[0]);
        assertEquals(SchematicException.Code.TOO_LARGE, fails(NbtWriter.litematic(6, compound("r", huge))).code());
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) many.put("r" + i, NbtWriter.region(0, 0, 0, 1, 1, 1, compounds(palette.toArray()), new long[1]));
        assertEquals(SchematicException.Code.TOO_LARGE, fails(NbtWriter.litematic(6, many)).code());
        // region names in messages are cut to 64 characters
        String longName = "n".repeat(300);
        SchematicException named = fails(NbtWriter.litematic(6, compound(longName, NbtWriter.region(0, 0, 0, 4, 4, 4, compounds(palette.toArray()), new long[1]))));
        assertTrue(named.getMessage().length() < 150, named.getMessage());
    }

    @Test
    void litematicTileEntityItems() throws Exception {
        Map<String, Object> region = NbtWriter.region(0, 0, 0, 1, 1, 1, compounds(state("minecraft:chest", "facing", "north")), new long[1]);
        region.put("TileEntities", compounds(compound("x", 0, "y", 0, "z", 0, "id", "minecraft:chest", "Items", compounds(
            compound("Slot", (byte) 0, "id", "minecraft:diamond", "count", 5),
            compound("Slot", (byte) 1, "id", "minecraft:diamond", "count", 3, "components", compound("x", 1)),
            compound("Slot", (byte) 2, "id", "stone", "Count", (byte) 2),
            compound("Slot", (byte) 3, "id", "NOT AN ID", "count", 9)))));
        Schematic s = read(NbtWriter.litematic(6, compound("r", region)));
        assertEquals(Map.of("minecraft:diamond", 8L, "minecraft:stone", 2L), s.containerItems());
        assertEquals(1, count(s, "minecraft:chest[facing=north]"));
    }

    // ---------- Sponge ----------

    static Map<String, Object> palette(int n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("minecraft:air", 0);
        for (int i = 1; i < n; i++) p.put("minecraft:block_" + i, i);
        return p;
    }

    @Test
    void spongeV2WithMultiByteVarints() throws Exception {
        Map<String, Object> pal = palette(300);
        pal.put("minecraft:oak_stairs[half=bottom,facing=east]", 300);
        int[] data = new int[4 * 2 * 5];
        for (int i = 0; i < data.length; i++) data[i] = (i * 8) % 300; // values over 127 are two-byte varints
        data[0] = 300;
        data[1] = 200;
        Map<String, Object> root = compound("Version", 2, "DataVersion", 4189, "Width", (short) 4, "Height", (short) 2, "Length", (short) 5,
            "PaletteMax", 301, "Palette", pal, "BlockData", NbtWriter.varints(data),
            "BlockEntities", compounds(compound("Id", "minecraft:chest", "Pos", new int[] { 0, 0, 0 },
                "Items", compounds(compound("id", "minecraft:iron_ingot", "Count", (byte) 12, "Slot", (byte) 0)))));
        Schematic s = read(root);
        assertEquals(Schematic.Format.SPONGE, s.format());
        assertEquals(2, s.formatVersion());
        assertEquals(4189, s.dataVersion());
        assertEquals(1, count(s, "minecraft:oak_stairs[facing=east,half=bottom]"));
        assertEquals(2, count(s, "minecraft:block_200")); // data[1] and data[25]
        assertEquals(1, count(s, "minecraft:block_296"));
        assertEquals(0, count(s, "minecraft:block_8")); // data[1] was overwritten
        assertEquals(40, s.nonAirBlocks()); // the only 0 (data[0]) was overwritten
        assertEquals(Map.of("minecraft:iron_ingot", 12L), s.containerItems());
        assertEquals(4, s.sizeX());
        assertEquals(2, s.sizeY());
        assertEquals(5, s.sizeZ());
    }

    @Test
    void spongePaletteKeysAreKeptToRealisticSizes() {
        String props = "a=b,".repeat(70);
        for (String key : new String[]{"minecraft:stone[" + props + "]", "minecraft:" + "x".repeat(1100)}) {
            Map<String, Object> pal = compound("minecraft:air", 0, key, 1);
            Map<String, Object> root = compound("Version", 2, "DataVersion", 4189, "Width", (short) 1, "Height", (short) 1, "Length", (short) 2,
                "Palette", pal, "BlockData", NbtWriter.varints(0, 1));
            SchematicException e = org.junit.jupiter.api.Assertions.assertThrows(SchematicException.class, () -> read(root));
            assertEquals(SchematicException.Code.INVALID, e.code());
        }
    }

    @Test
    void spongeV3Wrapped() throws Exception {
        Map<String, Object> pal = compound("minecraft:air", 0, "minecraft:stone", 1, "glass", 2);
        Map<String, Object> blocks = compound("Palette", pal, "Data", NbtWriter.varints(1, 1, 2, 0, 1, 2, 0, 0),
            "BlockEntities", compounds(compound("Id", "minecraft:barrel", "Pos", new int[] { 0, 0, 0 },
                "Data", compound("Items", compounds(compound("id", "minecraft:apple", "count", 4))))));
        Map<String, Object> root = compound("Schematic", compound("Version", 3, "DataVersion", 4671, "Width", (short) 2, "Height", (short) 2,
            "Length", (short) 2, "Blocks", blocks));
        Schematic s = read(root);
        assertEquals(3, s.formatVersion());
        assertEquals(4671, s.dataVersion());
        assertEquals(3, count(s, "minecraft:stone"));
        assertEquals(2, count(s, "minecraft:glass"));
        assertEquals(5, s.nonAirBlocks());
        assertEquals(Map.of("minecraft:apple", 4L), s.containerItems());
    }

    @Test
    void spongeUnsignedShortSize() throws Exception {
        byte[] data = new byte[40_000]; // 40000 zero varints (air) + one stone
        data[39_999] = 1;
        Map<String, Object> root = compound("Version", 2, "DataVersion", 4189, "Width", (short) 40_000, "Height", (short) 1, "Length", (short) 1,
            "Palette", compound("minecraft:air", 0, "minecraft:stone", 1), "BlockData", data);
        Schematic s = read(root);
        assertEquals(40_000, s.sizeX());
        assertEquals(1, count(s, "minecraft:stone"));
    }

    @Test
    void spongeBadData() {
        Map<String, Object> pal = compound("minecraft:air", 0, "minecraft:stone", 1);
        // six-byte varint
        byte[] six = { (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00, 0, 0 };
        SchematicException e = fails(compound("Version", 2, "Width", (short) 1, "Height", (short) 1, "Length", (short) 3, "Palette", pal, "BlockData", six));
        assertTrue(e.getMessage().contains("longer than 5 bytes"), e.getMessage());
        // index out of the palette
        e = fails(compound("Version", 2, "Width", (short) 1, "Height", (short) 1, "Length", (short) 2, "Palette", pal, "BlockData", NbtWriter.varints(1, 2)));
        assertTrue(e.getMessage().contains("palette entry that does not exist"));
        // fewer entries than the volume
        e = fails(compound("Version", 2, "Width", (short) 2, "Height", (short) 2, "Length", (short) 2, "Palette", pal, "BlockData", NbtWriter.varints(1, 1, 1)));
        assertTrue(e.getMessage().contains("has 3 entries"), e.getMessage());
        // more entries than the volume
        e = fails(compound("Version", 2, "Width", (short) 1, "Height", (short) 1, "Length", (short) 1, "Palette", pal, "BlockData", NbtWriter.varints(1, 1)));
        assertTrue(e.getMessage().contains("more entries"), e.getMessage());
        // data ending inside a varint
        e = fails(compound("Version", 2, "Width", (short) 1, "Height", (short) 1, "Length", (short) 1, "Palette", pal, "BlockData", new byte[] { (byte) 0x81 }));
        assertTrue(e.getMessage().contains("ends inside a varint"));
        // unknown version
        assertEquals(SchematicException.Code.UNSUPPORTED,
            fails(compound("Schematic", compound("Version", 4, "Width", (short) 1, "Height", (short) 1, "Length", (short) 1))).code());
    }

    // ---------- vanilla structures ----------

    @Test
    void structurePaletteAndVoid() throws Exception {
        Map<String, Object> root = compound("DataVersion", 4189, "size", list(Nbt.TAG_INT, 3, 3, 3),
            "palette", compounds(state("minecraft:stone"), state("minecraft:chest", "facing", "west"), state("minecraft:air")),
            "blocks", compounds(
                compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 0)),
                compound("state", 0, "pos", list(Nbt.TAG_INT, 1, 0, 0)),
                compound("state", 2, "pos", list(Nbt.TAG_INT, 2, 0, 0)),
                compound("state", 1, "pos", list(Nbt.TAG_INT, 0, 1, 0), "nbt", compound("id", "minecraft:chest",
                    "Items", compounds(compound("id", "minecraft:emerald", "count", 64))))),
            "entities", compounds());
        Schematic s = read(root);
        assertEquals(Schematic.Format.STRUCTURE, s.format());
        assertEquals(27, s.volume());
        assertEquals(3, s.nonAirBlocks()); // 23 positions not listed are structure void, one listed air
        assertEquals(2, count(s, "minecraft:stone"));
        assertEquals(1, count(s, "minecraft:chest[facing=west]"));
        assertNull(s.blockCounts().get(BlockState.of("minecraft:air")));
        assertEquals(Map.of("minecraft:emerald", 64L), s.containerItems());
    }

    @Test
    void structureEntriesPastTheVolumeAreIgnored() throws Exception {
        Map<String, Object> root = compound("DataVersion", 4189, "size", list(Nbt.TAG_INT, 1, 1, 2), "palette", compounds(state("minecraft:stone")),
            "blocks", compounds(compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 0)), compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 1)),
                compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 0)), compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 1))));
        assertEquals(2, count(read(root), "minecraft:stone"), "a 1x1x2 structure holds at most 2 blocks");
    }

    @Test
    void structurePalettesUsesTheFirst() throws Exception {
        Map<String, Object> root = compound("DataVersion", 4189, "size", list(Nbt.TAG_INT, 1, 1, 2),
            "palettes", list(Nbt.TAG_LIST, compounds(state("minecraft:oak_log", "axis", "y")), compounds(state("minecraft:birch_log", "axis", "y"))),
            "blocks", compounds(compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 0)), compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 1))));
        Schematic s = read(root);
        assertEquals(2, count(s, "minecraft:oak_log[axis=y]"));
        assertEquals(0, count(s, "minecraft:birch_log[axis=y]"));
        SchematicException e = fails(compound("size", list(Nbt.TAG_INT, 1, 1, 1), "palette", compounds(state("minecraft:stone")),
            "blocks", compounds(compound("state", 1, "pos", list(Nbt.TAG_INT, 0, 0, 0)))));
        assertTrue(e.getMessage().contains("palette entry that does not exist"));
    }

    // ---------- detection ----------

    @Test
    void legacySchematicIsRecognized() {
        Map<String, Object> root = compound("Width", (short) 1, "Height", (short) 1, "Length", (short) 1, "Materials", "Alpha",
            "Blocks", new byte[] { 1 }, "Data", new byte[] { 0 });
        SchematicException e = fails(root);
        assertEquals(SchematicException.Code.LEGACY, e.code());
        assertEquals("this is an old (pre-1.13) .schematic; open it in Litematica or WorldEdit and save it as .litematic or .schem", e.getMessage());
    }

    @Test
    void unknownNbtIsUnsupported() {
        assertEquals(SchematicException.Code.UNSUPPORTED, fails(compound("Level", compound("x", 1))).code());
    }

    @Test
    void rawUncompressedSchematicsWork() throws Exception {
        Map<String, Object> root = compound("size", list(Nbt.TAG_INT, 1, 1, 1), "palette", compounds(state("minecraft:stone")),
            "blocks", compounds(compound("state", 0, "pos", list(Nbt.TAG_INT, 0, 0, 0))));
        assertEquals(1, count(SchematicReader.read(NbtWriter.write(root), T), "minecraft:stone"));
    }

    @Test
    void deadlineCoversDecoding() {
        List<Map<String, Object>> palette = stonePalette(2);
        int[] idx = new int[256 * 256 * 64];
        long[] packed = NbtWriter.packLitematica(idx, 2);
        byte[] file = NbtWriter.gzip(NbtWriter.write(NbtWriter.litematic(6, compound("r",
            NbtWriter.region(0, 0, 0, 256, 64, 256, compounds(palette.toArray()), packed)))));
        SchematicException e = assertThrows(SchematicException.class, () -> SchematicReader.read(file, Duration.ofNanos(1)));
        assertEquals(SchematicException.Code.TIMEOUT, e.code());
        // parsed in time, but the deadline passes before the 4M-entry decode loop
        Nbt.Compound root = assertDoesNotThrow(() -> Nbt.read(file, T));
        e = assertThrows(SchematicException.class, () -> SchematicReader.read(root, System.nanoTime()));
        assertEquals(SchematicException.Code.TIMEOUT, e.code());
    }
}
