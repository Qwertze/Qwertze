package com.porter.stashmanager.planner;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads Litematica (.litematic), Sponge (.schem v1-3) and vanilla structure (.nbt) files into a {@link Schematic}. The
 * format is detected from the root tags, not the file name. Pre-1.13 .schematic files are recognized and rejected
 * with {@link SchematicException.Code#LEGACY}.
 */
public final class SchematicReader {
    /** Most blocks (air included) across all regions. */
    public static final long MAX_VOLUME = 1L << 27;
    public static final int MAX_REGIONS = 64;
    /** Most entries in a structure file's blocks list (the {@link Nbt#MAX_TAGS} limit usually stops a file first). */
    public static final int MAX_STRUCTURE_BLOCKS = 4_000_000;
    /** Most distinct item ids collected from block entities. */
    public static final int MAX_CONTAINER_ITEM_IDS = 2_000;
    /** States that are not blocks to build: left out of {@link Schematic#blockCounts()} and the non-air count. */
    public static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void");

    static final String LEGACY_MESSAGE =
        "this is an old (pre-1.13) .schematic; open it in Litematica or WorldEdit and save it as .litematic or .schem";
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");

    private SchematicReader() {
    }

    /** Parses a schematic file. {@code timeout} covers decompression, NBT parsing and decoding (null = 10 s). */
    public static Schematic read(byte[] file, Duration timeout) throws SchematicException {
        long deadline = Nbt.deadlineAfter(timeout);
        return read(Nbt.read(file, deadline), deadline);
    }

    static Schematic read(Nbt.Compound root, long deadline) throws SchematicException {
        if (root.has("Blocks") && root.has("Materials")) throw new SchematicException(SchematicException.Code.LEGACY, LEGACY_MESSAGE);
        if (root.compound("Regions") != null) return litematic(root, deadline);
        Nbt.Compound wrapped = root.compound("Schematic");
        if (wrapped != null && wrapped.has("Version")) return sponge(wrapped, deadline);
        if (root.has("Version") && (root.has("Palette") || root.has("BlockData") || root.has("Width"))) return sponge(root, deadline);
        if (root.list("size") != null && root.list("blocks") != null) return structure(root, deadline);
        throw new SchematicException(SchematicException.Code.UNSUPPORTED, "not a recognized schematic (.litematic, .schem or structure .nbt)");
    }

    // ---------- Litematica ----------

    private static Schematic litematic(Nbt.Compound root, long deadline) throws SchematicException {
        Integer version = root.intValue("Version");
        if (version == null) throw SchematicException.invalid("the Litematica file has no Version");
        if (version < 4 || version > 7) {
            throw new SchematicException(SchematicException.Code.UNSUPPORTED, "Litematica schematic version " + version + " is not supported (4 to 7 are)");
        }
        int dataVersion = orZero(root.intValue("MinecraftDataVersion"));
        Nbt.Compound regions = root.compound("Regions");
        if (regions.size() > MAX_REGIONS) throw SchematicException.tooLarge("the schematic has more than " + MAX_REGIONS + " regions");

        Counter counter = new Counter();
        long totalVolume = 0;
        long[] min = { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE }, max = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };
        for (String name : regions.keys()) {
            String where = "region \"" + SchematicException.safe(name) + "\"";
            Nbt.Compound region = regions.compound(name);
            if (region == null) throw SchematicException.invalid(where + " is not a compound");
            Nbt.Compound size = region.compound("Size");
            if (size == null) throw SchematicException.invalid(where + " has no Size");
            long[] dims = new long[3];
            long[] pos = new long[3];
            Nbt.Compound position = region.compound("Position");
            String[] axes = { "x", "y", "z" };
            for (int a = 0; a < 3; a++) {
                Integer v = size.intValue(axes[a]);
                if (v == null) throw SchematicException.invalid(where + " has no Size." + axes[a]);
                dims[a] = v;
                pos[a] = position == null ? 0 : orZero(position.intValue(axes[a]));
            }
            long volume;
            try {
                volume = Math.multiplyExact(Math.multiplyExact(Math.abs(dims[0]), Math.abs(dims[1])), Math.abs(dims[2]));
            } catch (ArithmeticException e) {
                throw SchematicException.tooLarge("the schematic is larger than " + MAX_VOLUME + " blocks");
            }
            if (volume > MAX_VOLUME) throw SchematicException.tooLarge("the schematic is larger than " + MAX_VOLUME + " blocks");
            totalVolume += volume;
            if (totalVolume > MAX_VOLUME) throw SchematicException.tooLarge("the schematic is larger than " + MAX_VOLUME + " blocks");
            if (volume == 0) continue;
            for (int a = 0; a < 3; a++) {
                // a negative size extends from the position towards negative coordinates (the position is the far corner)
                long lo = dims[a] >= 0 ? pos[a] : pos[a] + dims[a] + 1;
                long hi = dims[a] >= 0 ? pos[a] + dims[a] - 1 : pos[a];
                min[a] = Math.min(min[a], lo);
                max[a] = Math.max(max[a], hi);
            }

            BlockState[] palette = palette(region.list("BlockStatePalette"), where, deadline);
            if (palette.length == 0) throw SchematicException.invalid(where + " has an empty BlockStatePalette");
            long[] states = region.longArray("BlockStates");
            if (states == null) throw SchematicException.invalid(where + " has no BlockStates");
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.length - 1));
            long expected = (volume * bits + 63) / 64;
            if (states.length < expected) { // longer is padding (some writers round up); the extra longs are ignored
                throw SchematicException.invalid(where + ": BlockStates has " + states.length + " longs, expected at least " + expected);
            }
            counter.add(palette, decodeLitematica(states, bits, volume, palette.length, deadline, where), deadline);
            addBlockEntityItems(region.list("TileEntities"), counter, deadline);
        }
        int[] box = new int[3];
        for (int a = 0; a < 3; a++) box[a] = min[a] > max[a] ? 0 : (int) Math.min(Integer.MAX_VALUE, max[a] - min[a] + 1);
        return counter.build(Schematic.Format.LITEMATIC, version, dataVersion, box, regions.size(), totalVolume);
    }

    /**
     * Counts palette indexes in a Litematica bit array: entries of {@code bits} bits, packed LSB-first one after
     * another, so an entry may span two longs (LitematicaBitArray; vanilla chunk storage since 1.16 does not span).
     */
    static long[] decodeLitematica(long[] data, int bits, long volume, int paletteSize, long deadline, String where) throws SchematicException {
        long[] counts = new long[paletteSize];
        long mask = (1L << bits) - 1;
        long bitPos = 0;
        for (long i = 0; i < volume; i++, bitPos += bits) {
            if ((i & (Nbt.CHECK_EVERY - 1)) == 0) Nbt.checkDeadline(deadline);
            int word = (int) (bitPos >>> 6);
            int offset = (int) (bitPos & 63);
            long v = data[word] >>> offset;
            if (offset + bits > 64) v |= data[word + 1] << (64 - offset);
            int index = (int) (v & mask);
            if (index >= paletteSize) throw SchematicException.invalid(where + " refers to a palette entry that does not exist");
            counts[index]++;
        }
        return counts;
    }

    /** Real schematics have a few thousand states at most; Litematica's own limit per region is far below this. */
    static final int MAX_PALETTE = 65_536;
    /** The longest vanilla block state string is a few hundred characters with about 10 properties. */
    static final int MAX_STATE_KEY = 1024;
    static final int MAX_STATE_PROPERTIES = 64;

    // ---------- Sponge ----------

    private static Schematic sponge(Nbt.Compound c, long deadline) throws SchematicException {
        Integer version = c.intValue("Version");
        if (version == null) throw SchematicException.invalid("the Sponge schematic has no Version");
        if (version < 1 || version > 3) {
            throw new SchematicException(SchematicException.Code.UNSUPPORTED, "Sponge schematic version " + version + " is not supported (1 to 3 are)");
        }
        int dataVersion = orZero(c.intValue("DataVersion"));
        int w = unsignedShort(c, "Width"), h = unsignedShort(c, "Height"), l = unsignedShort(c, "Length");
        long volume = (long) w * h * l;
        if (volume > MAX_VOLUME) throw SchematicException.tooLarge("the schematic is larger than " + MAX_VOLUME + " blocks");
        int[] box = { w, h, l };
        Counter counter = new Counter();

        Nbt.Compound blocks = version >= 3 ? c.compound("Blocks") : c;
        if (blocks == null) return counter.build(Schematic.Format.SPONGE, version, dataVersion, box, 1, volume); // v3: blocks are optional
        Nbt.Compound paletteTag = blocks.compound("Palette");
        byte[] data = blocks.byteArray(version >= 3 ? "Data" : "BlockData");
        if (paletteTag == null) throw SchematicException.invalid("the schematic has no Palette");
        if (data == null) throw SchematicException.invalid("the schematic has no block data");

        if (paletteTag.keys().size() > MAX_PALETTE) {
            throw SchematicException.tooLarge("the block palette has more than " + MAX_PALETTE + " entries");
        }
        int maxIndex = -1;
        for (String key : paletteTag.keys()) {
            Integer v = paletteTag.intValue(key);
            if (v == null || v < 0 || v >= MAX_PALETTE) throw SchematicException.invalid("a palette entry has an invalid index");
            maxIndex = Math.max(maxIndex, v);
        }
        BlockState[] palette = new BlockState[maxIndex + 1];
        int seen = 0;
        for (String key : paletteTag.keys()) {
            if ((++seen & 1023) == 0) Nbt.checkDeadline(deadline);
            int v = paletteTag.intValue(key);
            if (palette[v] != null) throw SchematicException.invalid("two palette entries share index " + v);
            // Parsing a key makes a few objects per property: keep keys to what real block states look like.
            if (key.length() > MAX_STATE_KEY) throw SchematicException.invalid("a palette entry is longer than " + MAX_STATE_KEY + " characters");
            int commas = 0;
            for (int i = 0; i < key.length(); i++) if (key.charAt(i) == ',') commas++;
            if (commas >= MAX_STATE_PROPERTIES) throw SchematicException.invalid("a palette entry has more than " + MAX_STATE_PROPERTIES + " properties");
            palette[v] = BlockState.parse(key);
        }

        long[] counts = new long[palette.length];
        long entries = 0;
        int pos = 0;
        while (pos < data.length) {
            if ((entries & (Nbt.CHECK_EVERY - 1)) == 0) Nbt.checkDeadline(deadline);
            int value = 0, b, n = 0;
            do {
                if (pos >= data.length) throw SchematicException.invalid("the block data ends inside a varint");
                if (n == 5) throw SchematicException.invalid("a varint in the block data is longer than 5 bytes");
                b = data[pos++];
                value |= (b & 0x7f) << (7 * n);
                n++;
            } while ((b & 0x80) != 0);
            if (value < 0 || value >= palette.length || palette[value] == null) {
                throw SchematicException.invalid("the block data refers to a palette entry that does not exist");
            }
            if (entries >= volume) throw SchematicException.invalid("the block data has more entries than the " + w + "x" + h + "x" + l + " size");
            counts[value]++;
            entries++;
        }
        if (entries != volume) {
            throw SchematicException.invalid("the block data has " + entries + " entries but the size " + w + "x" + h + "x" + l + " needs " + volume);
        }
        counter.add(palette, counts, deadline);
        Nbt.ListTag entities = blocks.list("BlockEntities");
        if (entities == null) entities = blocks.list("TileEntities"); // Sponge v1
        addBlockEntityItems(entities, counter, deadline);
        return counter.build(Schematic.Format.SPONGE, version, dataVersion, box, 1, volume);
    }

    private static int unsignedShort(Nbt.Compound c, String name) throws SchematicException {
        Object v = c.get(name);
        if (v instanceof Short s) return s & 0xffff;
        if ((v instanceof Byte || v instanceof Integer) && ((Number) v).intValue() >= 0 && ((Number) v).intValue() <= 0xffff) {
            return ((Number) v).intValue();
        }
        throw SchematicException.invalid("the schematic has no valid " + name);
    }

    // ---------- vanilla structure ----------

    private static Schematic structure(Nbt.Compound root, long deadline) throws SchematicException {
        int dataVersion = orZero(root.intValue("DataVersion"));
        Nbt.ListTag size = root.list("size");
        if (size.size() != 3 || size.elementType() != Nbt.TAG_INT) throw SchematicException.invalid("the structure size is not 3 numbers");
        int[] box = new int[3];
        long volume = 1;
        for (int a = 0; a < 3; a++) {
            box[a] = (Integer) size.get(a);
            if (box[a] < 0) throw SchematicException.invalid("the structure size is negative");
            volume *= box[a];
            if (volume > MAX_VOLUME) throw SchematicException.tooLarge("the schematic is larger than " + MAX_VOLUME + " blocks");
        }
        Nbt.ListTag paletteList = root.list("palette");
        Nbt.ListTag palettes = root.list("palettes");
        if (palettes != null && palettes.size() > 0) {
            if (!(palettes.get(0) instanceof Nbt.ListTag first)) throw SchematicException.invalid("the structure palettes are not lists");
            paletteList = first;
        }
        if (paletteList == null) throw SchematicException.invalid("the structure has no palette");
        BlockState[] palette = palette(paletteList, "the structure", deadline);
        Nbt.ListTag blocks = root.list("blocks");
        if (blocks.size() > MAX_STRUCTURE_BLOCKS) throw SchematicException.tooLarge("the structure lists more than " + MAX_STRUCTURE_BLOCKS + " blocks");
        if (blocks.size() > 0 && blocks.elementType() != Nbt.TAG_COMPOUND) throw SchematicException.invalid("the structure blocks are not compounds");

        Counter counter = new Counter();
        long[] counts = new long[palette.length];
        // Entries past the declared volume are ignored: a structure can't hold more blocks than its size.
        long limit = Math.min(blocks.size(), volume);
        for (int i = 0; i < limit; i++) {
            if ((i & (Nbt.CHECK_EVERY - 1)) == 0) Nbt.checkDeadline(deadline);
            Nbt.Compound block = (Nbt.Compound) blocks.get(i);
            Integer state = block.intValue("state");
            if (state == null || state < 0 || state >= palette.length) {
                throw SchematicException.invalid("a structure block refers to a palette entry that does not exist");
            }
            counts[state]++;
            Nbt.Compound nbt = block.compound("nbt");
            if (nbt != null) addItems(nbt.list("Items"), counter);
        }
        counter.add(palette, counts, deadline);
        return counter.build(Schematic.Format.STRUCTURE, 0, dataVersion, box, 1, volume);
    }

    // ---------- shared ----------

    /** A {@code [{Name, Properties{...}}]} palette list (Litematica and structures). */
    private static BlockState[] palette(Nbt.ListTag list, String where, long deadline) throws SchematicException {
        if (list == null) throw SchematicException.invalid(where + " has no block palette");
        if (list.size() > 0 && list.elementType() != Nbt.TAG_COMPOUND) throw SchematicException.invalid(where + " has a malformed block palette");
        if (list.size() > MAX_PALETTE) throw SchematicException.tooLarge(where + " has more than " + MAX_PALETTE + " palette entries");
        BlockState[] out = new BlockState[list.size()];
        for (int i = 0; i < out.length; i++) {
            if ((i & 1023) == 0) Nbt.checkDeadline(deadline);
            Nbt.Compound e = (Nbt.Compound) list.get(i);
            String name = e.string("Name");
            if (name == null) throw SchematicException.invalid(where + " has a palette entry without a Name");
            Map<String, String> props = new HashMap<>();
            Nbt.Compound p = e.compound("Properties");
            if (p != null) {
                for (String k : p.keys()) {
                    Object v = p.get(k);
                    if (v instanceof String s) props.put(k, s);
                }
            }
            out[i] = new BlockState(name, new java.util.TreeMap<>(props));
        }
        return out;
    }

    /** Block entities {@code [{..., Items[...]}]} (Litematica, Sponge v1/v2) or {@code [{..., Data{Items[...]}}]} (Sponge v3). */
    private static void addBlockEntityItems(Nbt.ListTag entities, Counter counter, long deadline) throws SchematicException {
        if (entities == null) return;
        for (int i = 0; i < entities.size(); i++) {
            if ((i & (Nbt.CHECK_EVERY - 1)) == 0) Nbt.checkDeadline(deadline);
            if (!(entities.get(i) instanceof Nbt.Compound be)) continue;
            Nbt.ListTag items = be.list("Items");
            Nbt.Compound data = be.compound("Data");
            if (items == null && data != null) items = data.list("Items");
            addItems(items, counter);
        }
    }

    /** {@code Items[{id, count|Count}]}; item components (and so shulker contents) are ignored. */
    private static void addItems(Nbt.ListTag items, Counter counter) {
        if (items == null) return;
        for (Object o : items.values()) {
            if (!(o instanceof Nbt.Compound item)) continue;
            String id = item.string("id");
            if (id == null) continue;
            id = BlockState.normalizeId(id);
            if (!ITEM_ID.matcher(id).matches()) continue;
            Integer n = item.intValue("count");
            if (n == null) n = item.intValue("Count");
            if (n == null) n = 1;
            if (n <= 0) continue;
            if (counter.items.size() >= MAX_CONTAINER_ITEM_IDS && !counter.items.containsKey(id)) continue;
            counter.items.merge(id, (long) n, Long::sum);
        }
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    /** Folds per-palette-index counts into per-state counts. */
    private static final class Counter {
        final Map<BlockState, Long> states = new HashMap<>();
        final Map<String, Long> items = new HashMap<>();
        long nonAir;

        void add(BlockState[] palette, long[] counts, long deadline) throws SchematicException {
            for (int i = 0; i < palette.length; i++) {
                if ((i & 1023) == 0) Nbt.checkDeadline(deadline);
                if (counts[i] == 0 || palette[i] == null || AIR.contains(palette[i].name())) continue;
                states.merge(palette[i], counts[i], Long::sum);
                nonAir += counts[i];
            }
        }

        Schematic build(Schematic.Format format, int formatVersion, int dataVersion, int[] box, int regions, long volume) {
            return new Schematic(format, formatVersion, dataVersion, box[0], box[1], box[2], regions, volume, nonAir, states, items);
        }
    }
}
