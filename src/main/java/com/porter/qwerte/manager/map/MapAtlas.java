package com.porter.qwerte.manager.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The map's pixels: a 512 x 512 RGBA picture of a 32 x 32 chunk window, painted from a cache of 16 x 16 chunk tiles
 * (256 packed map bytes each, {@code colour id * 4 + brightness}, 0 = transparent). The window follows the bot: when the
 * bot nears its edge the window moves and is repainted from the cache. Pure; the palette is given. Client thread only.
 */
public final class MapAtlas implements ViewerState.TileSink {
    public static final int CHUNKS = 32;
    public static final int SIZE = CHUNKS * 16;
    /** How many tiles are kept; past it the ones farthest from the bot go (the website keeps 1,500 too). */
    public static final int CAP = 1500;
    private static final int EDGE = 4;

    private final int[] argb;
    private final byte[] rgba = new byte[SIZE * SIZE * 4];
    private final Map<Long, byte[]> tiles = new HashMap<>();
    private int originX;
    private int originZ;
    private boolean placed;
    private boolean dirty;
    private int focusX;
    private int focusZ;

    /** @param argb 256 colours, index = packed map byte, 0 for transparent */
    public MapAtlas(int[] argb) {
        this.argb = argb.length >= 256 ? argb : java.util.Arrays.copyOf(argb, 256);
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    // ---------- TileSink ----------

    @Override
    public void put(int cx, int cz, byte[] colors) {
        if (colors == null || colors.length != 256) return;
        tiles.put(key(cx, cz), colors);
        if (placed) paint(cx, cz, colors);
        if (tiles.size() > CAP * 2) trim();
    }

    @Override
    public void drop(int cx, int cz) {
        if (tiles.remove(key(cx, cz)) != null && placed) erase(cx, cz);
    }

    @Override
    public void clear() {
        tiles.clear();
        java.util.Arrays.fill(rgba, (byte) 0);
        dirty = true;
        placed = false;
    }

    // ---------- window ----------

    /**
     * Keeps the window around the bot. The first call places it; later it only moves when the chunk is within 4 chunks of an
     * edge (or outside). Everything is repainted from the cache then.
     * @return true when the window moved
     */
    public boolean recentre(int selfCx, int selfCz) {
        focusX = selfCx;
        focusZ = selfCz;
        if (placed) {
            int lx = selfCx - originX;
            int lz = selfCz - originZ;
            if (lx >= EDGE && lx < CHUNKS - EDGE && lz >= EDGE && lz < CHUNKS - EDGE) return false;
        }
        originX = selfCx - CHUNKS / 2;
        originZ = selfCz - CHUNKS / 2;
        placed = true;
        java.util.Arrays.fill(rgba, (byte) 0);
        for (Map.Entry<Long, byte[]> e : tiles.entrySet()) {
            long k = e.getKey();
            paint((int) (k >> 32), (int) k, e.getValue());
        }
        dirty = true;
        return true;
    }

    /** Drops the tiles farthest from the bot until {@link #CAP} are left. */
    public void trim() {
        if (tiles.size() <= CAP) return;
        List<long[]> byDistance = new ArrayList<>(tiles.size());
        for (Long k : tiles.keySet()) {
            long dx = (int) (k >> 32) - focusX;
            long dz = (int) k.longValue() - focusZ;
            byDistance.add(new long[] {dx * dx + dz * dz, k});
        }
        byDistance.sort((a, b) -> Long.compare(b[0], a[0]));
        for (int i = 0; tiles.size() > CAP && i < byDistance.size(); i++) {
            long k = byDistance.get(i)[1];
            tiles.remove(k);
            if (placed) erase((int) (k >> 32), (int) k);
        }
    }

    public int tileCount() {
        return tiles.size();
    }

    public boolean has(int cx, int cz) {
        return tiles.containsKey(key(cx, cz));
    }

    /** The window's first chunk. */
    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public boolean placed() {
        return placed;
    }

    /** The picture; the same array every time, row 0 first, 4 bytes (r, g, b, a) per pixel. */
    public byte[] rgba() {
        return rgba;
    }

    /** True once if the picture changed since the last call. */
    public boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    /** The picture has to be uploaded again (the texture was lost). */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    // ---------- painting ----------

    private boolean inside(int cx, int cz) {
        return cx >= originX && cx < originX + CHUNKS && cz >= originZ && cz < originZ + CHUNKS;
    }

    private void paint(int cx, int cz, byte[] colors) {
        if (!inside(cx, cz)) return;
        int px = (cx - originX) * 16;
        int pz = (cz - originZ) * 16;
        for (int lz = 0; lz < 16; lz++) {
            int row = ((pz + lz) * SIZE + px) * 4;
            for (int lx = 0; lx < 16; lx++) {
                int c = argb[colors[lz * 16 + lx] & 0xff];
                int o = row + lx * 4;
                if ((c >>> 24) == 0) {
                    rgba[o] = 0;
                    rgba[o + 1] = 0;
                    rgba[o + 2] = 0;
                    rgba[o + 3] = 0;
                } else {
                    rgba[o] = (byte) (c >> 16);
                    rgba[o + 1] = (byte) (c >> 8);
                    rgba[o + 2] = (byte) c;
                    rgba[o + 3] = (byte) (c >>> 24);
                }
            }
        }
        dirty = true;
    }

    private void erase(int cx, int cz) {
        if (!inside(cx, cz)) return;
        int px = (cx - originX) * 16;
        int pz = (cz - originZ) * 16;
        for (int lz = 0; lz < 16; lz++) {
            int row = ((pz + lz) * SIZE + px) * 4;
            java.util.Arrays.fill(rgba, row, row + 64, (byte) 0);
        }
        dirty = true;
    }
}
