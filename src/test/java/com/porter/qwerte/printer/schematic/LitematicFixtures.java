package com.porter.qwerte.printer.schematic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;

/** Builds .litematic NBT in code (nothing is read from a user's folders). */
final class LitematicFixtures {
    private LitematicFixtures() {
    }

    /** A palette entry from a state key such as minecraft:oak_stairs[facing=north]. */
    static CompoundTag entry(String key) {
        CompoundTag c = new CompoundTag();
        c.putString("Name", LitematicFormat.keyName(key));
        String[][] props = LitematicFormat.keyProperties(key);
        if (props.length > 0) {
            CompoundTag p = new CompoundTag();
            for (String[] kv : props) p.putString(kv[0], kv[1]);
            c.put("Properties", p);
        }
        return c;
    }

    static ListTag palette(String... keys) {
        ListTag l = new ListTag();
        for (String k : keys) l.add(entry(k));
        return l;
    }

    static CompoundTag xyz(int x, int y, int z) {
        CompoundTag c = new CompoundTag();
        c.putInt("x", x);
        c.putInt("y", y);
        c.putInt("z", z);
        return c;
    }

    /** A region with the given packed data. */
    static CompoundTag region(int px, int py, int pz, int sx, int sy, int sz, ListTag palette, long[] data) {
        CompoundTag r = new CompoundTag();
        r.put("Position", xyz(px, py, pz));
        r.put("Size", xyz(sx, sy, sz));
        r.put("BlockStatePalette", palette);
        r.put("BlockStates", new LongArrayTag(data));
        return r;
    }

    /** A region whose cells are the given palette indexes (in file order: x fastest, then z, then y). */
    static CompoundTag region(int px, int py, int pz, int sx, int sy, int sz, String[] palette, int[] indexes) {
        return region(px, py, pz, sx, sy, sz, palette(palette), LitematicFormat.pack(indexes, LitematicFormat.bitsFor(palette.length)));
    }

    static CompoundTag file(int version, int dataVersion, Map<String, CompoundTag> regions) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", version);
        root.putInt("MinecraftDataVersion", dataVersion);
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "test build");
        meta.putString("Author", "tester");
        root.put("Metadata", meta);
        CompoundTag rs = new CompoundTag();
        for (Map.Entry<String, CompoundTag> e : new TreeMap<>(regions).entrySet()) rs.put(e.getKey(), e.getValue());
        root.put("Regions", rs);
        return root;
    }

    static CompoundTag file(CompoundTag region) {
        return file(7, 4000, Map.of("main", region));
    }

    static byte[] gzip(CompoundTag root) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
                NbtIo.write(root, out);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A 2x2x3 region of stone, dirt and an oak log: every cell has a known entry. */
    static CompoundTag small() {
        return region(0, 0, 0, 2, 2, 3, new String[] { "minecraft:stone", "minecraft:dirt", "minecraft:oak_log[axis=y]" }, new int[] { 0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2 });
    }
}
