package com.porter.qwerte.printer.source;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.printer.schematic.LitematicReader;
import com.porter.qwerte.printer.schematic.Transform;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Litematica, reached by reflection only (no compile-time dependency, so the addon loads without it). Every class and
 * method name below was checked with javap against litematica-fabric 0.26.16 (Minecraft 1.21.11) and 0.21.7 (1.21.4),
 * where they are identical. Anything missing or throwing turns into "not available" and the caller falls back. Client
 * thread only: Litematica's data is not thread safe.
 */
public final class LitematicaBridge {
    private LitematicaBridge() {
    }

    private static final String P = "fi.dy.masa.litematica.";

    /** A sub-region placement: its position is relative to the placement origin and not yet transformed. */
    public record Sub(Object handle, String name, int x, int y, int z, Transform.Rot rot, Transform.Mir mir, boolean enabled) {
    }

    /** A schematic placement as Litematica holds it, read once. {@code file} is a Path (1.21.11) or File (1.21.4) or null. */
    public record Placement(Object handle, String name, boolean enabled, int ox, int oy, int oz, Transform.Rot rot, Transform.Mir mir,
                            Object schematic, Path file, List<Sub> subs) {
    }

    private static final class Api {
        MethodHandle dmPlacementManager, dmBaseDir;
        MethodHandle pmSelected, pmAll;
        MethodHandle plEnabled, plName, plOrigin, plRotation, plMirror, plSchematic, plFile, plSubs;
        MethodHandle subName, subPos, subRotation, subMirror, subEnabled;
        MethodHandle schAreaSize, schContainer;
        MethodHandle ctSize, ctGet;
        MethodHandle puPlacementPos, puBlockPos;
    }

    private static Api api;
    private static boolean tried;
    private static String reason = "Litematica is not installed.";

    /** Whether Litematica is loaded and every name below resolved; the first call does the lookups. */
    public static synchronized boolean available() {
        if (!tried) {
            tried = true;
            try {
                if (!FabricLoader.getInstance().isModLoaded("litematica")) {
                    reason = "Litematica is not installed.";
                } else {
                    Class.forName(P + "data.DataManager", false, LitematicaBridge.class.getClassLoader());
                    api = lookup();
                    reason = null;
                }
            } catch (Throwable t) {
            fatal(t);
                api = null;
                reason = "Litematica's API is not the one this was written for (" + t.getClass().getSimpleName() + ").";
                QwerteAddon.LOG.warn("printer: Litematica lookup failed", t);
            }
        }
        return api != null;
    }

    /** Why {@link #available()} is false. */
    public static synchronized String reason() {
        available();
        return reason;
    }

    private static Api lookup() throws ReflectiveOperationException {
        ClassLoader cl = LitematicaBridge.class.getClassLoader();
        Class<?> dm = Class.forName(P + "data.DataManager", false, cl);
        Class<?> pm = Class.forName(P + "schematic.placement.SchematicPlacementManager", false, cl);
        Class<?> pl = Class.forName(P + "schematic.placement.SchematicPlacement", false, cl);
        Class<?> sp = Class.forName(P + "schematic.placement.SubRegionPlacement", false, cl);
        Class<?> sch = Class.forName(P + "schematic.LitematicaSchematic", false, cl);
        Class<?> ct = Class.forName(P + "schematic.container.LitematicaBlockStateContainer", false, cl);
        Class<?> pu = Class.forName(P + "util.PositionUtils", false, cl);
        Api a = new Api();
        a.dmPlacementManager = h(dm, "getSchematicPlacementManager");
        a.dmBaseDir = h(dm, "getSchematicsBaseDirectory");
        a.pmSelected = h(pm, "getSelectedSchematicPlacement");
        a.pmAll = h(pm, "getAllSchematicsPlacements");
        a.plEnabled = h(pl, "isEnabled");
        a.plName = h(pl, "getName");
        a.plOrigin = h(pl, "getOrigin");
        a.plRotation = h(pl, "getRotation");
        a.plMirror = h(pl, "getMirror");
        a.plSchematic = h(pl, "getSchematic");
        a.plFile = h(pl, "getSchematicFile");
        a.plSubs = h(pl, "getAllSubRegionsPlacements");
        a.subName = h(sp, "getName");
        a.subPos = h(sp, "getPos");
        a.subRotation = h(sp, "getRotation");
        a.subMirror = h(sp, "getMirror");
        a.subEnabled = h(sp, "isEnabled");
        a.schAreaSize = h(sch, "getAreaSize", String.class);
        a.schContainer = h(sch, "getSubRegionContainer", String.class);
        a.ctSize = h(ct, "getSize");
        a.ctGet = h(ct, "get", int.class, int.class, int.class);
        a.puPlacementPos = h(pu, "getTransformedPlacementPosition", BlockPos.class, pl, sp);
        a.puBlockPos = h(pu, "getTransformedBlockPos", BlockPos.class, Mirror.class, Rotation.class);
        return a;
    }

    /** Method handles throw Throwable; only exceptions and linkage problems (Litematica changed) are survived, other Errors propagate. */
    private static void fatal(Throwable t) {
        if (t instanceof Error e && !(t instanceof LinkageError)) throw e;
    }

    private static MethodHandle h(Class<?> c, String name, Class<?>... params) throws ReflectiveOperationException {
        return MethodHandles.publicLookup().unreflect(c.getMethod(name, params));
    }

    // ---------- placements ----------

    /** The selected placement, else the last one added; null when there is none or Litematica can't be read. */
    public static Placement latest() {
        List<Placement> all = placements();
        if (all.isEmpty()) return null;
        try {
            Object sel = api.pmSelected.invoke(api.dmPlacementManager.invoke());
            if (sel != null) {
                for (Placement p : all) if (p.handle() == sel) return p;
            }
        } catch (Throwable t) {
            fatal(t);
            QwerteAddon.LOG.warn("printer: reading Litematica's selected placement failed", t);
        }
        return all.get(all.size() - 1);
    }

    /** The placement with this name (case blind), or the only one whose name contains it. */
    public static Placement byName(String name) {
        if (name == null || name.isBlank()) return null;
        String q = LitematicReader.clean(name, 40).trim().toLowerCase(Locale.ROOT);
        Placement partial = null;
        int partials = 0;
        for (Placement p : placements()) {
            String n = p.name().toLowerCase(Locale.ROOT);
            if (n.equals(q)) return p;
            if (n.contains(q)) {
                partial = p;
                partials++;
            }
        }
        return partials == 1 ? partial : null;
    }

    /** Why {@link #byName} finds nothing for this name: no placement matches, or several partly match. Null when it finds one. */
    public static String byNameProblem(String name) {
        if (byName(name) != null) return null;
        String q = LitematicReader.clean(name == null ? "" : name, 40).trim().toLowerCase(Locale.ROOT);
        int partials = 0;
        for (Placement p : placements()) if (!q.isEmpty() && p.name().toLowerCase(Locale.ROOT).contains(q)) partials++;
        return partials > 1 ? "several Litematica placements match that name; use the exact name" : "no Litematica placement has that name";
    }

    public static List<Placement> placements() {
        List<Placement> out = new ArrayList<>();
        if (!available()) return out;
        try {
            Object list = api.pmAll.invoke(api.dmPlacementManager.invoke());
            if (list instanceof Collection<?> c) {
                for (Object o : c) {
                    Placement p = read(o);
                    if (p != null) out.add(p);
                }
            }
        } catch (Throwable t) {
            fatal(t);
            QwerteAddon.LOG.warn("printer: reading Litematica's placements failed", t);
        }
        return out;
    }

    private static Placement read(Object o) {
        if (o == null) return null;
        try {
            BlockPos origin = (BlockPos) api.plOrigin.invoke(o);
            List<Sub> subs = new ArrayList<>();
            if (api.plSubs.invoke(o) instanceof Collection<?> c) {
                for (Object s : c) {
                    BlockPos pos = (BlockPos) api.subPos.invoke(s);
                    subs.add(new Sub(s, (String) api.subName.invoke(s) /* the raw name: Litematica needs it back; clean it for display */, pos.getX(), pos.getY(), pos.getZ(),
                        Transform.Rot.valueOf(((Rotation) api.subRotation.invoke(s)).name()), Transform.Mir.valueOf(((Mirror) api.subMirror.invoke(s)).name()),
                        (boolean) api.subEnabled.invoke(s)));
                }
            }
            Object file = api.plFile.invoke(o);
            Path path = file instanceof Path p ? p : file instanceof File f ? f.toPath() : null;
            String name = (String) api.plName.invoke(o);
            return new Placement(o, name == null ? "" : LitematicReader.clean(name, 40), (boolean) api.plEnabled.invoke(o), origin.getX(), origin.getY(), origin.getZ(),
                Transform.Rot.valueOf(((Rotation) api.plRotation.invoke(o)).name()), Transform.Mir.valueOf(((Mirror) api.plMirror.invoke(o)).name()),
                api.plSchematic.invoke(o), path, subs);
        } catch (Throwable t) {
            fatal(t);
            QwerteAddon.LOG.warn("printer: reading a Litematica placement failed", t);
            return null;
        }
    }

    /** Litematica's schematics folder (a Path or a File depending on the version); null when unreadable. */
    public static Path baseDir() {
        if (!available()) return null;
        try {
            Object d = api.dmBaseDir.invoke();
            Path dir = d instanceof Path p ? p : d instanceof File f ? f.toPath() : null;
            if (dir == null) return null;
            // a base folder that is a drive root or the home folder would make nearly every .litematic on the machine readable
            Path real = dir.toRealPath();
            if (real.getParent() == null || real.equals(Path.of(System.getProperty("user.home", "")).toRealPath())) return null;
            return dir;
        } catch (Throwable t) {
            fatal(t);
            return null;
        }
    }

    // ---------- the render layer range ----------

    // Litematica's DataManager.getRenderLayerRange() returns malilib's LayerRange (fi.dy.masa.malilib.util.LayerRange in
    // malilib 0.23.5 for 1.21.4 and 0.27.x for 1.21.11; javap checked). Its class is taken from the method's return type, so
    // this works wherever malilib keeps it. All handles are optional: if any is missing the printer simply builds everything.
    private static MethodHandle layerGet, lrMode, lrAxis, lrSingle, lrAbove, lrBelow, lrMin, lrMax;
    private static boolean layerTried, layerOk, layerFailLogged;
    private static long layerRetryAt;
    private static final LayerCache layerCache = new LayerCache();

    /** The current render-layer range, or {@link LayerFilter#ALL} when Litematica is absent, unreadable or shows everything. Client thread only. */
    public static synchronized LayerFilter layer() {
        if (!available()) return LayerFilter.ALL;
        if (!layerTried) {
            layerTried = true;
            try {
                Class<?> dm = Class.forName(P + "data.DataManager", false, LitematicaBridge.class.getClassLoader());
                java.lang.reflect.Method get = dm.getMethod("getRenderLayerRange");
                Class<?> lr = get.getReturnType();
                layerGet = MethodHandles.publicLookup().unreflect(get);
                lrMode = h(lr, "getLayerMode");
                lrAxis = h(lr, "getAxis");
                lrSingle = h(lr, "getLayerSingle");
                lrAbove = h(lr, "getLayerAbove");
                lrBelow = h(lr, "getLayerBelow");
                lrMin = h(lr, "getLayerRangeMin");
                lrMax = h(lr, "getLayerRangeMax");
                layerOk = true;
            } catch (ReflectiveOperationException | LinkageError t) {
                // The names are not the ones this was written for: the layer feature is off for good
                QwerteAddon.LOG.warn("printer: Litematica's render layer range can't be read; the layer modes are ignored", t);
            } catch (Throwable t) {
                fatal(t);
                QwerteAddon.LOG.warn("printer: Litematica's render layer range can't be read; the layer modes are ignored", t);
            }
        }
        if (!layerOk) return LayerFilter.ALL;
        // A read that failed is not tried again for about 5 seconds; meanwhile the last good range is used
        if (System.currentTimeMillis() < layerRetryAt) return layerCache.fallback();
        try {
            Object range = layerGet.invoke();
            if (range == null) return LayerFilter.ALL;
            Object mode = lrMode.invoke(range);
            Object axis = lrAxis.invoke(range);
            LayerFilter read = LayerFilter.of(mode instanceof Enum<?> e ? e.name() : null, axis instanceof Enum<?> a ? a.ordinal() : 1,
                (int) lrSingle.invoke(range), (int) lrAbove.invoke(range), (int) lrBelow.invoke(range), (int) lrMin.invoke(range), (int) lrMax.invoke(range));
            layerCache.ok(read);
            return read;
        } catch (Throwable t) {
            fatal(t);
            // Keep the last good range (or build everything if none was ever read), another try in 5 seconds; the log hears of it once
            layerRetryAt = System.currentTimeMillis() + 5000;
            if (!layerFailLogged) {
                layerFailLogged = true;
                QwerteAddon.LOG.warn("printer: reading Litematica's render layer range failed; keeping the last filter and trying again", t);
            }
            return layerCache.fallback();
        }
    }

    /** At least one render-layer read has worked, so a reported "no filter" is real and not a failure. */
    public static synchronized boolean layerWorks() {
        return layerOk && layerCache.has();
    }

    // ---------- the in-memory schematic ----------

    /** The signed size of a sub-region ({@code getAreaSize}); null when unknown. */
    public static int[] areaSize(Placement p, String region) {
        try {
            if (p.schematic() == null || !(api.schAreaSize.invoke(p.schematic(), region) instanceof BlockPos b)) return null;
            return new int[] { b.getX(), b.getY(), b.getZ() };
        } catch (Throwable t) {
            fatal(t);
            return null;
        }
    }

    /** Litematica's block container of a sub-region, or null. */
    public static Object container(Placement p, String region) {
        try {
            return p.schematic() == null ? null : api.schContainer.invoke(p.schematic(), region);
        } catch (Throwable t) {
            fatal(t);
            return null;
        }
    }

    /** A container's absolute size. */
    public static int[] containerSize(Object container) {
        try {
            if (!(api.ctSize.invoke(container) instanceof Vec3i v)) return null;
            return new int[] { v.getX(), v.getY(), v.getZ() };
        } catch (Throwable t) {
            fatal(t);
            return null;
        }
    }

    /** The raw (untransformed) state of a container cell. */
    public static BlockState state(Object container, int x, int y, int z) {
        // After this many failures Litematica is taken to be broken: no more calls (and no more exceptions) for this game run
        if (stateFailures >= MAX_STATE_FAILURES) return null;
        try {
            return (BlockState) api.ctGet.invoke(container, x, y, z);
        } catch (Throwable t) {
            fatal(t);
            if (++stateFailures == MAX_STATE_FAILURES) QwerteAddon.LOG.warn("printer: reading Litematica's blocks failed {} times, giving up on it", MAX_STATE_FAILURES, t);
            return null;
        }
    }

    private static final int MAX_STATE_FAILURES = 1000;
    private static volatile int stateFailures;

    /** True once reading Litematica's blocks has failed too often to go on. */
    public static boolean stateReadsFailing() {
        return stateFailures >= MAX_STATE_FAILURES;
    }

    /** Forgets earlier failures (a new load). */
    public static void resetStateFailures() {
        stateFailures = 0;
    }

    // ---------- the transform, as Litematica computes it ----------

    /**
     * Where Litematica puts a cell: origin + T(minRel, main) + its getTransformedPlacementPosition (the order of the
     * additions is taken from the bytecode of the placing code, not from a call). {@code minRel} is the region's smallest
     * corner relative to the origin. Null if the call fails.
     */
    public static int[] litematicaWorld(Placement p, Sub sub, int minRelX, int minRelY, int minRelZ, int cx, int cy, int cz) {
        try {
            BlockPos region = (BlockPos) api.puBlockPos.invoke(new BlockPos(minRelX, minRelY, minRelZ), Mirror.valueOf(p.mir().name()), Rotation.valueOf(p.rot().name()));
            BlockPos cell = (BlockPos) api.puPlacementPos.invoke(new BlockPos(cx, cy, cz), p.handle(), sub.handle());
            return new int[] { p.ox() + region.getX() + cell.getX(), p.oy() + region.getY() + cell.getY(), p.oz() + region.getZ() + cell.getZ() };
        } catch (Throwable t) {
            fatal(t);
            return null;
        }
    }

    /**
     * Compares our {@link Transform} with Litematica's on about 64 cells of a region (its corners and fixed pseudo-random
     * cells). True when every one lands on the same block; false (or a failed call) means Litematica's own mapping must
     * be used instead.
     */
    public static boolean transformAgrees(Placement p, Sub sub, Transform ours, int minRelX, int minRelY, int minRelZ, int sx, int sy, int sz) {
        Random rnd = new Random(0x51A7E5L);
        for (int i = 0; i < 64; i++) {
            int cx, cy, cz;
            if (i < 8) {
                cx = (i & 1) == 0 ? 0 : sx - 1;
                cy = (i & 2) == 0 ? 0 : sy - 1;
                cz = (i & 4) == 0 ? 0 : sz - 1;
            } else {
                cx = rnd.nextInt(sx);
                cy = rnd.nextInt(sy);
                cz = rnd.nextInt(sz);
            }
            int[] theirs = litematicaWorld(p, sub, minRelX, minRelY, minRelZ, cx, cy, cz);
            int[] mine = ours.toWorld(cx, cy, cz);
            if (theirs == null || theirs[0] != mine[0] || theirs[1] != mine[1] || theirs[2] != mine[2]) return false;
        }
        return true;
    }
}
