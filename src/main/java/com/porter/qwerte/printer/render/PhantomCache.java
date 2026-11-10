package com.porter.qwerte.printer.render;

import com.porter.qwerte.printer.render.PhantomSelect.Cover;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What has been baked for each 16x16x16 section and when it has to be baked again. The mesh type is the GPU handle of the
 * renderer in use ({@code M}); this class only holds it and gives it to the disposer when it goes. Pure.
 *
 * <p>Rules ({@link #decide}): a section is rebuilt when it was marked dirty (a block or target changed in it, or beside it),
 * when the scope layer changed, or when its cut by the distance limit changed enough (see {@link #decide}): a section wholly inside the limit
 * stays as it is however the player moves; one that is cut by it ({@code PARTIAL}) is baked for one player position and one
 * limit, rebuilt once the player has moved 16 blocks or the limit radius changed by 8. A section that dropped out of the limit is dropped (meshes freed).
 */
public final class PhantomCache<M> {
    public enum Action { KEEP, BUILD, DROP }

    /** One section: what was baked and what it was baked for. */
    public static final class Entry<M> {
        public final long key;
        public final int sx, sy, sz;
        public M mesh, wrongMesh;
        /** What the bake was made for. */
        public Cover cover = Cover.NONE;
        public int ox, oy, oz;
        public long limit2;
        public Integer layer;
        public boolean built, dirty;
        /** The bake left cells out that may turn up later (chunk not loaded yet): looked at again by the periodic pass. */
        public boolean retry;
        /** Whatever the renderer keeps with the section (cells to watch, outline boxes). */
        public Object data;

        Entry(long key, int sx, int sy, int sz) {
            this.key = key;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }
    }

    private final Map<Long, Entry<M>> entries = new HashMap<>();
    private final Consumer<M> disposer;

    public PhantomCache(Consumer<M> disposer) {
        this.disposer = disposer;
    }

    public static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFFF) << 38) | ((long) (sz & 0x3FFFFFF) << 12) | (sy & 0xFFF);
    }

    public int size() {
        return entries.size();
    }

    public Entry<M> get(long key) {
        return entries.get(key);
    }

    public Entry<M> getOrCreate(int sx, int sy, int sz) {
        long k = key(sx, sy, sz);
        Entry<M> e = entries.get(k);
        if (e == null) {
            e = new Entry<>(k, sx, sy, sz);
            entries.put(k, e);
        }
        return e;
    }

    /** Flags the section to be baked again. Returns the entry when it was clean and exists (so the caller queues it once), else null. */
    public Entry<M> markDirty(int sx, int sy, int sz) {
        Entry<M> e = entries.get(key(sx, sy, sz));
        if (e == null || e.dirty) return null;
        e.dirty = true;
        return e;
    }

    /** Flags every section; returns those that were clean. */
    public List<Entry<M>> markAllDirty() {
        List<Entry<M>> flagged = new ArrayList<>();
        for (Entry<M> e : entries.values()) {
            if (!e.dirty) {
                e.dirty = true;
                flagged.add(e);
            }
        }
        return flagged;
    }

    /** Flags the sections that may be missing cells (see {@link Entry#retry}); returns those flagged. */
    public List<Entry<M>> markRetryDirty() {
        List<Entry<M>> flagged = new ArrayList<>();
        for (Entry<M> e : entries.values()) {
            if (e.retry && !e.dirty) {
                e.dirty = true;
                flagged.add(e);
            }
        }
        return flagged;
    }

    /** All entries (for the periodic pass; allocates an iterator, never call it per frame). */
    public Iterable<Entry<M>> entries() {
        return entries.values();
    }

    public void release(Entry<M> e) {
        if (e.mesh != null) disposer.accept(e.mesh);
        if (e.wrongMesh != null) disposer.accept(e.wrongMesh);
        e.mesh = null;
        e.wrongMesh = null;
    }

    /** Frees the section's meshes and forgets it. */
    public void drop(long key) {
        Entry<M> e = entries.remove(key);
        if (e != null) release(e);
    }

    public void clear() {
        for (Entry<M> e : entries.values()) release(e);
        entries.clear();
    }

    /** A section cut by the limit is kept until the player has moved this many blocks from where it was baked (squared below). */
    public static final int PARTIAL_MOVE = 16;
    /** ... or until the limit's radius has changed by this many blocks. */
    public static final int PARTIAL_RADIUS = 8;

    /**
     * What to do with a section now.
     *
     * <p>A section wholly inside the limit before and now is kept whatever happens. A cut one is kept while the player has
     * moved less than {@link #PARTIAL_MOVE} blocks from where it was baked and the limit's radius changed by less than
     * {@link #PARTIAL_RADIUS}: its edge is then off by at most that much, but walking with the cap on no longer rebakes the
     * whole ring of cut sections every few blocks.
     *
     * @param e       what is cached for it, or null
     * @param now     how much of it the current limit covers
     * @param layer   the scope layer now (null: all)
     */
    public static <M> Action decide(Entry<M> e, Cover now, int ox, int oy, int oz, long limit2, Integer layer) {
        if (now == Cover.NONE) return e == null ? Action.KEEP : Action.DROP;
        if (e == null || !e.built || e.dirty) return Action.BUILD;
        if (!Objects.equals(e.layer, layer)) return Action.BUILD;
        if (now == Cover.ALL && e.cover == Cover.ALL) return Action.KEEP;
        long dx = ox - e.ox, dy = oy - e.oy, dz = oz - e.oz;
        if (dx * dx + dy * dy + dz * dz >= (long) PARTIAL_MOVE * PARTIAL_MOVE) return Action.BUILD;
        if (Math.abs(Math.sqrt(limit2) - Math.sqrt(e.limit2)) >= PARTIAL_RADIUS) return Action.BUILD;
        return Action.KEEP;
    }

    /** Forgets every entry without freeing anything (only for when freeing has failed). */
    public void forget() {
        entries.clear();
    }
}
