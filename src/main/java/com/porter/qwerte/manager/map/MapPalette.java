package com.porter.qwerte.manager.map;

import net.minecraft.world.level.material.MapColor;

/**
 * The game's own map colours: 256 ARGB values, index = packed map byte ({@code colour id * 4 + brightness}). Entries 0-3
 * (colour id 0, "none") are transparent. Built once; an entry the game refuses is left transparent so one bad id can't
 * spoil the rest. {@code MapColor.getColorFromPackedId} gives ARGB in 1.21.4 and 1.21.11 (checked with javap).
 */
public final class MapPalette {
    private static int[] cached;

    private MapPalette() {
    }

    public static synchronized int[] argb() {
        if (cached == null) cached = build();
        return cached;
    }

    static int[] build() {
        int[] out = new int[256];
        for (int i = 4; i < 256; i++) {
            try {
                out[i] = MapColor.getColorFromPackedId(i);
            } catch (RuntimeException | LinkageError e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
