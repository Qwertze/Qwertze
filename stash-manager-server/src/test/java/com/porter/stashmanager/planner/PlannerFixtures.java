package com.porter.stashmanager.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.porter.stashmanager.planner.NbtWriter.compound;
import static com.porter.stashmanager.planner.NbtWriter.compounds;
import static com.porter.stashmanager.planner.NbtWriter.list;
import static com.porter.stashmanager.planner.NbtWriter.state;

/** Schematic files for tests outside this package (built with {@link NbtWriter}). */
public final class PlannerFixtures {
    private PlannerFixtures() {
    }

    /**
     * A vanilla structure .nbt holding the given number of each block (in a row along x), gzip-compressed. marker, when
     * not null, is stored as an extra string tag so a test can look for the file's bytes elsewhere.
     */
    public static byte[] structure(int dataVersion, Map<String, Integer> blocks, String marker) {
        return structure(dataVersion, blocks, Map.of(), marker);
    }

    /** The same, plus one chest holding chestItems (id -> count) when that is not empty. */
    public static byte[] structure(int dataVersion, Map<String, Integer> blocks, Map<String, Integer> chestItems, String marker) {
        List<Object> palette = new ArrayList<>();
        List<Object> placed = new ArrayList<>();
        int x = 0;
        int i = 0;
        for (Map.Entry<String, Integer> e : blocks.entrySet()) {
            palette.add(state(e.getKey()));
            for (int n = 0; n < e.getValue(); n++) placed.add(compound("state", i, "pos", list(Nbt.TAG_INT, x++, 0, 0)));
            i++;
        }
        if (!chestItems.isEmpty()) {
            palette.add(state("minecraft:chest", "facing", "north"));
            List<Object> items = new ArrayList<>();
            chestItems.forEach((id, n) -> items.add(compound("id", id, "count", n)));
            placed.add(compound("state", i, "pos", list(Nbt.TAG_INT, x++, 0, 0), "nbt", compound("id", "minecraft:chest",
                "Items", NbtWriter.list(Nbt.TAG_COMPOUND, items))));
        }
        Map<String, Object> root = compound("DataVersion", dataVersion, "size", list(Nbt.TAG_INT, Math.max(1, x), 1, 1),
            "palette", NbtWriter.list(Nbt.TAG_COMPOUND, palette), "blocks", NbtWriter.list(Nbt.TAG_COMPOUND, placed), "entities", compounds());
        if (marker != null) root.put("author", marker);
        return marker != null ? NbtWriter.write(root) : NbtWriter.gzip(NbtWriter.write(root));
    }

    /** A pre-1.13 MCEdit .schematic (numeric block ids). */
    public static byte[] legacy() {
        return NbtWriter.gzip(NbtWriter.write(compound("Width", (short) 1, "Height", (short) 1, "Length", (short) 1,
            "Materials", "Alpha", "Blocks", new byte[] { 1 }, "Data", new byte[] { 0 })));
    }
}
