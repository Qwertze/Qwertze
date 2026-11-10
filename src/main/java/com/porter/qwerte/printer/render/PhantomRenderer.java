package com.porter.qwerte.printer.render;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.printer.loop.TargetBits;
import com.porter.qwerte.printer.render.PhantomCache.Action;
import com.porter.qwerte.printer.render.PhantomCache.Entry;
import com.porter.qwerte.printer.render.PhantomSelect.Cover;
import com.porter.qwerte.printer.source.LitematicaBridge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/**
 * Draws the blocks the printer still has to place as translucent "phantom" models of the real block states, and the wrong
 * blocks as red outlines (or phantoms).
 *
 * <p>Design: the cells to show are the printer's open cells ({@link TargetBits}) in the scope (the active layer when layer
 * building is on) and within the range of the player, the nearest {@code max-blocks} of them. They are baked per 16x16x16
 * section into one GPU buffer each with vanilla's block model renderer ({@link PhantomGpu}), and the buffers are only drawn
 * every frame. A section is baked again only when something in or beside it changed: a target was added or removed
 * ({@link TargetBits.Listener}), a block of it changed in the world (found by a rolling check of {@value #POLL_PER_TICK}
 * cells per tick, plus {@link #touch} for the printer's own placements), the layer, range, cap or the player's cut of the
 * sphere changed (see {@link PhantomCache#decide}). Baking is sliced: at most {@value #BAKE_NANOS} ns of a frame.
 *
 * <p>Per frame the draw loop touches only the cached sections: no list is built, nothing is allocated by this class.
 * Client (render) thread only.
 */
public final class PhantomRenderer {
    /** What the renderer needs from the printer. */
    public interface Host {
        TargetBits targets();

        /** Wrong blocks the printer left alone (skipped): no longer open cells, but still worth marking. */
        TargetBits skipped();

        /** The state the schematic wants at the position (already rotated and mirrored), or null. */
        BlockState wanted(BlockPos pos);

        /** The layer being printed when layer building is on and a layer is active, else null. */
        Integer activeLayer();

        /** A run is going and its schematic is loaded. */
        boolean running();
    }

    /** The settings, read live. */
    public interface Config {
        boolean enabled();

        /** 0.05 to 1. */
        float opacity();

        boolean tint();

        Color tintColor();

        int range();

        int maxBlocks();

        /** Only the active layer, when there is one. */
        boolean currentLayerOnly();

        boolean showWrong();

        /** Wrong blocks as a phantom too (else an outline only). */
        boolean wrongAsPhantom();

        Color wrongColor();

        boolean hideWithLitematica();
    }

    public static final int MAX_RANGE = 64, MAX_BLOCKS = 16384;
    /** Cells in one section. */
    static final int MAX_CELLS = 4096;
    static final int POLL_PER_TICK = 768;
    static final long BAKE_NANOS = 2_000_000L;
    /** Outline boxes drawn per frame, nearest first. */
    private static final int MAX_OUTLINES = 1024;
    private static final int PASS_EVERY = 40, STALE_EVERY = 10, MOVE_SQ = 36, LITEMATICA_EVERY = 20;

    private static final Minecraft mc = Minecraft.getInstance();

    private final Host host;
    private final Config cfg;
    private final PhantomCache<PhantomGpu.Mesh> cache = new PhantomCache<>(PhantomGpu.Mesh::close);
    private final PhantomView.Pending pendingLookup;
    /** Built sections with something to draw or watch, far to near. */
    private final ArrayList<Entry<PhantomGpu.Mesh>> live = new ArrayList<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final int[] hist = new int[MAX_RANGE * MAX_RANGE + 1];
    private final HistVisitor histVisitor = new HistVisitor();
    private final GatherVisitor gather = new GatherVisitor();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final Vector3f forward = new Vector3f();
    /** Schematic cells that were built and have since been broken or replaced, until the printer has them in its own lists again. */
    private final TargetBits reopened = new TargetBits();
    // the cells of the section being baked (one job at a time)
    private final int[] cx = new int[MAX_CELLS], cy = new int[MAX_CELLS], cz = new int[MAX_CELLS];
    private final byte[] kind = new byte[MAX_CELLS];
    private final BlockState[] want = new BlockState[MAX_CELLS], seen = new BlockState[MAX_CELLS];

    private ClientLevel level;
    private PhantomView view;
    private Job job;
    private boolean drawDirty;

    // the selection the sections were decided for
    private boolean haveSel;
    private int selX, selY, selZ;
    private long limit2;
    private Integer selLayer;
    private int cfgRange = -1, cfgCap = -1;
    private boolean cfgLayerOnly, cfgWrong, cfgWrongPhantom;

    private int tickNo, lastPass, litematicaIn;
    private boolean stale, hidden, failed;
    /** Changes identity when resources are reloaded, see {@link PhantomGpu#reloadToken}. */
    private Object reloadToken;
    private String failure;
    private int pollEntry, pollCell;

    public PhantomRenderer(Host host, Config cfg) {
        this.host = host;
        this.cfg = cfg;
        this.pendingLookup = pos -> isPending(pos.getX(), pos.getY(), pos.getZ()) ? host.wanted(pos) : null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Outside events

    /** A cell was added to or removed from the printer's open cells (the {@link TargetBits.Listener}). */
    public void targetChanged(int x, int y, int z) {
        if (!haveSel && cache.size() == 0) return;
        try {
            reopened.remove(x, y, z);
            markAround(x, y, z);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Meteor's block update event (every client-side change of a block, the predicted ones and the server's): a block of the
     * schematic that was placed disappears as a ghost at once, and one that was broken or replaced gets its ghost back at once,
     * without waiting for the printer to look at the cell again.
     */
    public void blockChanged(BlockPos pos, BlockState before, BlockState now) {
        if (!haveSel) return;
        try {
            blockChangedBody(pos, before, now);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void blockChangedBody(BlockPos pos, BlockState before, BlockState now) {
        // Litematica's schematic world fires this event too (it is a client level of its own): only changes of the real world count
        if (mc.level == null || mc.level.getBlockState(pos) != now) return;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int r = cfgRange + 24;
        if (Math.abs(x - selX) > r || Math.abs(z - selZ) > r) return;
        BlockState want = host.wanted(pos);
        if (want == null || want.isAir()) return;
        if (now == want) {
            reopened.remove(x, y, z);
        } else if (before == want && !isPending(x, y, z)) {
            reopened.add(x, y, z);
            stale = true;
            lastPass = tickNo - STALE_EVERY;
        }
        markAround(x, y, z);
    }

    private boolean isPending(int x, int y, int z) {
        return host.targets().contains(x, y, z) || host.skipped().contains(x, y, z) || reopened.contains(x, y, z);
    }

    /** A block at this cell was just placed or broken by the printer: its section is baked again at once. */
    public void touch(int x, int y, int z) {
        try {
            markAround(x, y, z);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** A message once when drawing failed and the phantoms switched themselves off until the module is switched on again. */
    public String takeFailure() {
        String f = failure;
        failure = null;
        return f;
    }

    /** Frees every GPU buffer and starts over; the run changed, the world changed or the module went off. */
    public void reset() {
        reopened.clear();
        if (job != null) {
            job.close();
            job = null;
        }
        cache.clear();
        live.clear();
        queue.clear();
        haveSel = false;
        stale = false;
        drawDirty = false;
        pollEntry = pollCell = 0;
        level = null;
        view = null;
    }

    /** Called when the module is switched on: a failure of an earlier time is forgiven. */
    public void rearm() {
        failed = false;
        failure = null;
    }

    private void markAround(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        mark(sx, sy, sz);
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx == 0) mark(sx - 1, sy, sz);
        else if (lx == 15) mark(sx + 1, sy, sz);
        if (ly == 0) mark(sx, sy - 1, sz);
        else if (ly == 15) mark(sx, sy + 1, sz);
        if (lz == 0) mark(sx, sy, sz - 1);
        else if (lz == 15) mark(sx, sy, sz + 1);
    }

    private void mark(int sx, int sy, int sz) {
        Entry<PhantomGpu.Mesh> e = cache.markDirty(sx, sy, sz);
        if (e != null) {
            queue.add(e.key);
        } else if (haveSel && cache.get(PhantomCache.key(sx, sy, sz)) == null) {
            // a section nobody baked yet may have cells now: the next pass looks (only when it is near enough to matter)
            int r = cfgRange + 24;
            if (Math.abs((sx << 4) + 8 - selX) <= r && Math.abs((sz << 4) + 8 - selZ) <= r) stale = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Tick: decide what is wanted

    private boolean wanted() {
        return !failed && cfg.enabled() && host.running() && mc.level != null && mc.player != null && !hidden;
    }

    /** Once per client tick. Never throws: a phantom bug must not abort the printer's tick. */
    public void tick() {
        try {
            tickBody();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void tickBody() {
        if (mc.level == null || mc.player == null) {
            if (level != null || cache.size() > 0) reset();
            return;
        }
        tickNo++;
        if (!cfg.enabled() || !host.running() || failed) {
            if (level != null) reset();
            return;
        }
        if (--litematicaIn <= 0) {
            litematicaIn = LITEMATICA_EVERY;
            hidden = cfg.hideWithLitematica() && LitematicaBridge.schematicBlocksShown();
        }
        if (hidden) {
            if (level != null) reset();
            return;
        }
        // A resource reload (F3+T, a resource pack change) rebakes the block models and the atlas: what was baked is stale
        Object token = PhantomGpu.reloadToken();
        if (reloadToken != null && token != reloadToken) reset();
        reloadToken = token;
        if (level != mc.level) {
            reset();
            level = mc.level;
            view = new PhantomView(level, pendingLookup);
        }

        boolean force = false;
        int range = Math.min(Math.max(cfg.range(), 4), MAX_RANGE), cap = Math.min(Math.max(cfg.maxBlocks(), 16), MAX_BLOCKS);
        if (range != cfgRange || cap != cfgCap || cfg.currentLayerOnly() != cfgLayerOnly || cfg.showWrong() != cfgWrong
            || cfg.wrongAsPhantom() != cfgWrongPhantom) {
            cfgRange = range;
            cfgCap = cap;
            cfgLayerOnly = cfg.currentLayerOnly();
            cfgWrong = cfg.showWrong();
            cfgWrongPhantom = cfg.wrongAsPhantom();
            for (Entry<PhantomGpu.Mesh> e : cache.markAllDirty()) queue.add(e.key);
            force = true;
        }
        Integer layer = cfgLayerOnly ? host.activeLayer() : null;
        BlockPos p = mc.player.blockPosition();
        int dx = p.getX() - selX, dy = p.getY() - selY, dz = p.getZ() - selZ;
        boolean moved = !haveSel || dx * dx + dy * dy + dz * dz >= MOVE_SQ;
        boolean layerChanged = haveSel && !java.util.Objects.equals(layer, selLayer);
        boolean timed = tickNo - lastPass >= PASS_EVERY || stale && tickNo - lastPass >= STALE_EVERY;
        if (force || moved || layerChanged || timed) {
            if (tickNo - lastPass >= PASS_EVERY) {
                for (Entry<PhantomGpu.Mesh> e : cache.markRetryDirty()) queue.add(e.key);
            }
            pass(layer, p.getX(), p.getY(), p.getZ());
        }
        poll();
    }

    /**
     * Decides every section for the player's position: counts the cells in range, finds the distance that holds the nearest
     * {@code cap} of them, drops the sections outside it and queues the ones that have to be baked, nearest first.
     */
    private void pass(Integer layer, int ox, int oy, int oz) {
        TargetBits t = host.targets(), sk = host.skipped(), ro = reopened;
        long range2 = (long) cfgRange * cfgRange;
        int yf = layer == null ? TargetBits.ANY_Y : layer;
        int sx0 = (ox - cfgRange) >> 4, sx1 = (ox + cfgRange) >> 4, sz0 = (oz - cfgRange) >> 4, sz1 = (oz + cfgRange) >> 4;
        int sy0 = (oy - cfgRange) >> 4, sy1 = (oy + cfgRange) >> 4;
        if (layer != null) sy0 = sy1 = layer >> 4;

        long total = 0;
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sy = sy0; sy <= sy1; sy++) {
                    if (PhantomSelect.cover(sx, sy, sz, ox, oy, oz, range2, layer) != Cover.NONE) total += t.countIn(sx, sy, sz, yf) + sk.countIn(sx, sy, sz, yf) + ro.countIn(sx, sy, sz, yf);
                }
            }
        }
        long lim = range2;
        if (total > cfgCap) {
            Arrays.fill(hist, 0, (int) range2 + 1, 0);
            histVisitor.set(ox, oy, oz, range2);
            for (int sx = sx0; sx <= sx1; sx++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    for (int sy = sy0; sy <= sy1; sy++) {
                        if (PhantomSelect.cover(sx, sy, sz, ox, oy, oz, range2, layer) != Cover.NONE) { t.forEach(sx, sy, sz, yf, histVisitor); sk.forEach(sx, sy, sz, yf, histVisitor); ro.forEach(sx, sy, sz, yf, histVisitor); }
                    }
                }
            }
            lim = PhantomSelect.pickLimit(hist, range2, cfgCap);
        }

        ArrayList<Entry<PhantomGpu.Mesh>> toBuild = new ArrayList<>();
        ArrayList<Long> toDrop = new ArrayList<>();
        for (Entry<PhantomGpu.Mesh> e : cache.entries()) {
            Cover c = PhantomSelect.cover(e.sx, e.sy, e.sz, ox, oy, oz, lim, layer);
            Action a = PhantomCache.decide(e, c, ox, oy, oz, lim, layer);
            if (a == Action.DROP) toDrop.add(e.key);
            else if (a == Action.BUILD) toBuild.add(e);
        }
        for (Long k : toDrop) cache.drop(k);
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sy = sy0; sy <= sy1; sy++) {
                    if (cache.get(PhantomCache.key(sx, sy, sz)) != null) continue;
                    if (PhantomSelect.cover(sx, sy, sz, ox, oy, oz, lim, layer) == Cover.NONE || t.countIn(sx, sy, sz, yf) + sk.countIn(sx, sy, sz, yf) + ro.countIn(sx, sy, sz, yf) == 0) continue;
                    toBuild.add(cache.getOrCreate(sx, sy, sz));
                }
            }
        }
        toBuild.sort(Comparator.comparingLong(e -> PhantomSelect.nearest2(e.sx, e.sy, e.sz, ox, oy, oz, layer)));
        queue.clear();
        for (Entry<PhantomGpu.Mesh> e : toBuild) queue.add(e.key);

        haveSel = true;
        selX = ox;
        selY = oy;
        selZ = oz;
        limit2 = lim;
        selLayer = layer;
        lastPass = tickNo;
        stale = false;
        drawDirty = true;
    }

    /** Looks at a slice of the cells that were baked: one whose block changed in the world sends its section to be baked again. */
    private void poll() {
        if (level == null || live.isEmpty()) return;
        int budget = POLL_PER_TICK, guard = live.size() + POLL_PER_TICK + 1;
        while (budget > 0 && guard-- > 0) {
            if (pollEntry >= live.size()) {
                pollEntry = 0;
                pollCell = 0;
            }
            Entry<PhantomGpu.Mesh> e = live.get(pollEntry);
            Data d = (Data) e.data;
            if (e.dirty || d == null) {
                pollEntry++;
                pollCell = 0;
                continue;
            }
            while (pollCell < d.n && budget > 0) {
                int i = pollCell * 3;
                scratch.set(d.xyz[i], d.xyz[i + 1], d.xyz[i + 2]);
                budget--;
                if (level.getBlockState(scratch) != d.seen[pollCell]) {
                    e.dirty = true;
                    queue.add(e.key);
                    pollCell = d.n;
                    break;
                }
                pollCell++;
            }
            if (pollCell >= d.n) {
                pollEntry++;
                pollCell = 0;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Baking, sliced over frames

    /** What a built section keeps: the cells to watch (with the state each had when baked) and the outline boxes. */
    private static final class Data {
        int n;
        int[] xyz;
        BlockState[] seen;
        int outlines;
        int[] outline;
    }

    private final class HistVisitor implements TargetBits.Visitor {
        private int ox, oy, oz;
        private long range2;

        void set(int ox, int oy, int oz, long range2) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.range2 = range2;
        }

        @Override
        public void cell(int x, int y, int z) {
            long d = PhantomSelect.dist2(x, y, z, ox, oy, oz);
            if (d <= range2) hist[(int) d]++;
        }

    }

    /** Collects the cells of a section that are in (scope and limit) into the job's arrays. */
    private final class GatherVisitor implements TargetBits.Visitor {
        Job job;

        @Override
        public void cell(int x, int y, int z) {
            if (!PhantomSelect.included(x, y, z, job.ox, job.oy, job.oz, job.limit2, job.layer)) return;
            if (job.n >= MAX_CELLS) return;
            int i = job.n++;
            cx[i] = x;
            cy[i] = y;
            cz[i] = z;
        }
    }

    private static final byte SKIP = 0, PHANTOM = 1, WRONG_PHANTOM = 2, OUTLINE = 3, UNWATCHED = -1;

    /** One section being baked. */
    private final class Job implements AutoCloseable {
        final Entry<PhantomGpu.Mesh> e;
        // what it is baked for
        final int ox, oy, oz, layerVal;
        final long limit2;
        final Integer layer;
        final Cover cover;
        final boolean loaded;
        int n;
        int phase, at;
        PhantomGpu.Baker normal, wrong;
        int normalCells, wrongCells;

        Job(Entry<PhantomGpu.Mesh> e) {
            this.e = e;
            this.ox = selX;
            this.oy = selY;
            this.oz = selZ;
            this.limit2 = PhantomRenderer.this.limit2;
            this.layer = selLayer;
            this.layerVal = layer == null ? TargetBits.ANY_Y : layer;
            this.cover = PhantomSelect.cover(e.sx, e.sy, e.sz, ox, oy, oz, limit2, layer);
            this.loaded = level.hasChunk(e.sx, e.sz);
            if (loaded) {
                gather.job = this;
                host.targets().forEach(e.sx, e.sy, e.sz, layerVal, gather);
                host.skipped().forEach(e.sx, e.sy, e.sz, layerVal, gather);
                reopened.forEach(e.sx, e.sy, e.sz, layerVal, gather);
                gather.job = null;
            }
        }

        /** Works until the deadline; true when the section is finished. */
        boolean step(long deadline) {
            int guard = 0;
            while (true) {
                switch (phase) {
                    case 0 -> {
                        while (at < n) {
                            if ((++guard & 7) == 0 && System.nanoTime() - deadline > 0) return false;
                            classify(at++);
                        }
                        phase = 1;
                        at = 0;
                        normal = new PhantomGpu.Baker(view, e.sx << 4, e.sy << 4, e.sz << 4);
                        if (normalCells == 0) {
                            normal.close();
                            normal = null;
                        }
                    }
                    case 1 -> {
                        if (normal != null) {
                            while (at < n) {
                                if ((++guard & 7) == 0 && System.nanoTime() - deadline > 0) return false;
                                int i = at++;
                                if (kind[i] == PHANTOM) normal.add(want[i], cx[i], cy[i], cz[i]);
                            }
                        }
                        phase = 2;
                        at = 0;
                        if (wrongCells > 0) wrong = new PhantomGpu.Baker(view, e.sx << 4, e.sy << 4, e.sz << 4);
                    }
                    case 2 -> {
                        if (wrong != null) {
                            while (at < n) {
                                if ((++guard & 7) == 0 && System.nanoTime() - deadline > 0) return false;
                                int i = at++;
                                if (kind[i] == WRONG_PHANTOM) wrong.add(want[i], cx[i], cy[i], cz[i]);
                            }
                        }
                        phase = 3;
                    }
                    default -> {
                        return true;
                    }
                }
            }
        }

        private void classify(int i) {
            scratch.set(cx[i], cy[i], cz[i]);
            BlockState w = host.wanted(scratch);
            if (w == null || w.isAir()) {
                kind[i] = UNWATCHED;
                return;
            }
            BlockState cur = level.getBlockState(scratch);
            want[i] = w;
            seen[i] = cur;
            if (cur == w) {
                kind[i] = SKIP;
            } else if (cur.isAir() || cur.canBeReplaced()) {
                // nothing there yet: a phantom (a block without a model, such as a chest, has none to show)
                if (PhantomGpu.hasModel(w)) {
                    kind[i] = PHANTOM;
                    normalCells++;
                } else {
                    kind[i] = SKIP;
                }
            } else if (!cfgWrong) {
                kind[i] = SKIP;
            } else if (cfgWrongPhantom && PhantomGpu.hasModel(w)) {
                kind[i] = WRONG_PHANTOM;
                wrongCells++;
            } else {
                kind[i] = OUTLINE;
            }
        }

        /** Uploads the meshes and hands the section its data. */
        void finish() {
            PhantomGpu.Mesh m = null, wm = null;
            boolean handedOver = false;
            try {
                m = normal == null ? null : normal.finish();
                wm = wrong == null ? null : wrong.finish();
                handedOver = attach(m, wm);
            } finally {
                // whatever is still held is freed, however the upload ended (Baker.close is a no-op after finish)
                close();
                if (!handedOver) {
                    if (m != null) m.close();
                    if (wm != null) wm.close();
                }
            }
        }

        /** Gives the section its meshes and data; false when the section was dropped while it baked (the caller frees the meshes). */
        private boolean attach(PhantomGpu.Mesh m, PhantomGpu.Mesh wm) {
            Entry<PhantomGpu.Mesh> cur = cache.get(e.key);
            if (cur != e) return false;
            cache.release(e);
            e.mesh = m;
            e.wrongMesh = wm;
            Data d = new Data();
            int watched = 0, outlines = 0;
            for (int i = 0; i < n; i++) {
                if (kind[i] != UNWATCHED) watched++;
                if (kind[i] == OUTLINE) outlines++;
            }
            d.xyz = new int[watched * 3];
            d.seen = new BlockState[watched];
            d.outline = new int[outlines * 3];
            for (int i = 0; i < n; i++) {
                byte k = kind[i];
                if (k == UNWATCHED) continue;
                int w = d.n++;
                d.xyz[w * 3] = cx[i];
                d.xyz[w * 3 + 1] = cy[i];
                d.xyz[w * 3 + 2] = cz[i];
                d.seen[w] = seen[i];
                if (k == OUTLINE) {
                    int o = d.outlines++ * 3;
                    d.outline[o] = cx[i];
                    d.outline[o + 1] = cy[i];
                    d.outline[o + 2] = cz[i];
                }
            }
            e.data = d;
            e.built = true;
            e.cover = cover;
            e.ox = ox;
            e.oy = oy;
            e.oz = oz;
            e.limit2 = limit2;
            e.layer = layer;
            e.retry = !loaded;
            drawDirty = true;
            Arrays.fill(want, 0, n, null);
            Arrays.fill(seen, 0, n, null);
            return true;
        }

        @Override
        public void close() {
            if (normal != null) normal.close();
            if (wrong != null) wrong.close();
            normal = wrong = null;
        }
    }

    private void bake() {
        long deadline = System.nanoTime() + BAKE_NANOS;
        boolean first = true;
        while (first || System.nanoTime() - deadline < 0) {
            first = false;
            if (job == null) {
                Entry<PhantomGpu.Mesh> e = null;
                while (e == null && !queue.isEmpty()) {
                    Entry<PhantomGpu.Mesh> c = cache.get(queue.pollFirst());
                    if (c == null) continue;
                    Cover cov = PhantomSelect.cover(c.sx, c.sy, c.sz, selX, selY, selZ, limit2, selLayer);
                    if (cov == Cover.NONE) {
                        cache.drop(c.key);
                        drawDirty = true;
                        continue;
                    }
                    if (PhantomCache.decide(c, cov, selX, selY, selZ, limit2, selLayer) == Action.KEEP) continue;
                    e = c;
                }
                if (e == null) return;
                // cleared now: a change during the bake flags it again and it is queued again
                e.dirty = false;
                job = new Job(e);
            }
            if (job.step(deadline)) {
                Job done = job;
                job = null;
                done.finish();
            } else {
                return;
            }
        }
    }

    private void rebuildLive() {
        drawDirty = false;
        live.clear();
        for (Entry<PhantomGpu.Mesh> e : cache.entries()) {
            if (e.built && e.data != null) live.add(e);
        }
        final int ox = selX, oy = selY, oz = selZ;
        // far to near, so nearer translucent faces blend over farther ones
        live.sort(Comparator.comparingLong((Entry<PhantomGpu.Mesh> e) -> -PhantomSelect.dist2((e.sx << 4) + 8, (e.sy << 4) + 8, (e.sz << 4) + 8, ox, oy, oz)));
        pollEntry = pollCell = 0;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Render

    /** Meteor's 3D render event: bakes a slice of what is queued and draws everything cached. */
    public void render(Render3DEvent event) {
        if (!wanted() || level == null || level != mc.level || !haveSel) return;
        try {
            bake();
            if (drawDirty) rebuildLive();
            mc.gameRenderer.getMainCamera().rotation().transform(forward.set(0f, 0f, -1f));
            drawMeshes(event);
            drawOutlines(event);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void drawMeshes(Render3DEvent event) {
        int n = live.size();
        if (n == 0) return;
        float opacity = Math.min(Math.max(cfg.opacity(), 0.05f), 1f);
        float tr = 1f, tg = 1f, tb = 1f;
        if (cfg.tint()) {
            Color c = cfg.tintColor();
            tr = c.r / 255f;
            tg = c.g / 255f;
            tb = c.b / 255f;
        }
        Color wc = cfg.wrongColor();
        float wr = wc.r / 255f, wg = wc.g / 255f, wb = wc.b / 255f;
        // sections behind the camera or past the range are skipped (forward is set by render)
        double cx = event.offsetX, cy = event.offsetY, cz = event.offsetZ;
        double reach = cfgRange + 30;
        double reach2 = reach * reach;
        boolean begun = false;
        for (int i = 0; i < n; i++) {
            Entry<PhantomGpu.Mesh> e = live.get(i);
            if (e.mesh == null && e.wrongMesh == null) continue;
            double dx = (e.sx << 4) - cx, dy = (e.sy << 4) - cy, dz = (e.sz << 4) - cz;
            double mx = dx + 8, my = dy + 8, mz = dz + 8;
            if (mx * mx + my * my + mz * mz > reach2) continue;
            if (mx * forward.x + my * forward.y + mz * forward.z < -14.0) continue;
            if (!begun) {
                PhantomGpu.beginDraw(event);
                begun = true;
            }
            if (e.mesh != null) PhantomGpu.draw(e.mesh, dx, dy, dz, tr, tg, tb, opacity);
            if (e.wrongMesh != null) PhantomGpu.draw(e.wrongMesh, dx, dy, dz, wr, wg, wb, opacity);
        }
        if (begun) PhantomGpu.endDraw();
    }

    private void drawOutlines(Render3DEvent event) {
        if (!cfg.showWrong()) return;
        Color c = cfg.wrongColor();
        double cx = event.offsetX, cy = event.offsetY, cz = event.offsetZ;
        double reach = cfgRange + 30;
        double reach2 = reach * reach;
        int left = MAX_OUTLINES;
        // near to far, so the cap drops the far ones
        for (int i = live.size() - 1; i >= 0 && left > 0; i--) {
            Entry<PhantomGpu.Mesh> e = live.get(i);
            Data d = (Data) e.data;
            if (d == null || d.outlines == 0) continue;
            double mx = (e.sx << 4) + 8 - cx, my = (e.sy << 4) + 8 - cy, mz = (e.sz << 4) + 8 - cz;
            if (mx * mx + my * my + mz * mz > reach2) continue;
            if (mx * forward.x + my * forward.y + mz * forward.z < -14.0) continue;
            int[] o = d.outline;
            for (int j = 0, m = d.outlines * 3; j < m && left > 0; j += 3, left--) {
                event.renderer.boxLines(o[j], o[j + 1], o[j + 2], o[j] + 1, o[j + 1] + 1, o[j + 2] + 1, c, 0);
            }
        }
    }

    private void fail(Throwable t) {
        failed = true;
        failure = "Phantom blocks stopped after a rendering error (" + t.getClass().getSimpleName() + "); see the log. Switch the module off and on to try again.";
        QwerteAddon.LOG.error("printer: phantom block rendering failed, switched off", t);
        try {
            PhantomGpu.endDraw();
        } catch (Throwable ignored) {
            // the draw state may be half set up; the next frame starts clean anyway
        }
        try {
            reset();
        } catch (Throwable ignored) {
            // freeing a buffer failed too; the cache is forgotten so nothing is drawn or watched any more
            cache.forget();
            live.clear();
            queue.clear();
            job = null;
            haveSel = false;
        }
    }
}
