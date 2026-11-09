package com.porter.qwerte.printer.source;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.printer.schematic.LitematicFormat;
import com.porter.qwerte.printer.schematic.LitematicReader;
import com.porter.qwerte.printer.schematic.SchematicData;
import com.porter.qwerte.printer.schematic.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A source built from a Litematica placement: full mode reads Litematica's in-memory block containers (no file I/O);
 * file mode re-reads the placement's file but keeps the placement's origin, rotation, mirror and sub-region settings.
 * In both, our {@link Transform} is checked against Litematica's own on sampled cells; if they ever disagree,
 * Litematica's call is used for every position and a note says so.
 */
public final class LitematicaSource {
    private LitematicaSource() {
    }

    /**
     * Step 1: the placement's in-memory containers. Returns null, with {@code why} holding one reason, when anything
     * needed is missing (the caller then reads the file).
     */
    public static BuildSource live(LitematicaBridge.Placement p, List<String> notes, List<String> why) {
        LitematicaBridge.resetStateFailures();
        List<String> all = new ArrayList<>(notes);
        if (!p.enabled()) all.add("The placement \"" + p.name() + "\" is switched off in Litematica; building it anyway.");
        BuildSource.Problems problems = new BuildSource.Problems();
        List<BuildSource.PlacedRegion> regions = new ArrayList<>();
        boolean[] fallbackMapper = { false };
        long total = 0;
        for (LitematicaBridge.Sub sub : p.subs()) {
            if (!sub.enabled()) continue;
            int[] area = LitematicaBridge.areaSize(p, sub.name());
            Object container = LitematicaBridge.container(p, sub.name());
            int[] size = container == null ? null : LitematicaBridge.containerSize(container);
            if (area == null || size == null) {
                why.add("Litematica's data for the region \"" + LitematicReader.clean(sub.name(), 40) + "\" could not be read.");
                return null;
            }
            if (size[0] != Math.abs(area[0]) || size[1] != Math.abs(area[1]) || size[2] != Math.abs(area[2]) || size[0] <= 0 || size[1] <= 0 || size[2] <= 0) {
                why.add("Litematica's region \"" + LitematicReader.clean(sub.name(), 40) + "\" has an unexpected size.");
                return null;
            }
            long volume = (long) size[0] * size[1] * size[2];
            total += volume;
            if (volume > LitematicReader.MAX_VOLUME || total > LitematicReader.MAX_VOLUME) {
                why.add("The placement is larger than the supported size.");
                return null;
            }
            int minX = LitematicFormat.minCorner(sub.x(), area[0]), minY = LitematicFormat.minCorner(sub.y(), area[1]), minZ = LitematicFormat.minCorner(sub.z(), area[2]);
            BuildSource.Mapper mapper = mapper(p, sub, minX, minY, minZ, size[0], size[1], size[2], fallbackMapper);
            Transform t = transform(p, sub, minX, minY, minZ);
            regions.add(new LiveRegion(LitematicReader.clean(sub.name(), 40), size, mapper, container, t, problems));
        }
        if (regions.isEmpty()) {
            why.add("The placement has no enabled regions.");
            return null;
        }
        if (fallbackMapper[0]) all.add("Litematica's own position mapping is used for this placement (this addon's differed on a sample).");
        return new BuildSource.Placed(p.name(), "litematica", all, regions, problems);
    }

    /**
     * Step 2: the placement's file, already parsed, under the placement's origin, rotation, mirror and sub-region
     * settings. A file region the placement does not list is placed with the file's own position and no turn.
     */
    public static BuildSource fromFile(LitematicaBridge.Placement p, SchematicData data, List<String> notes) {
        List<String> all = new ArrayList<>(notes);
        all.addAll(data.warnings);
        BuildSource.Problems problems = new BuildSource.Problems();
        StateResolver resolver = new StateResolver(data.dataVersion, problems);
        if (resolver.newerThanGame()) all.add("The schematic is from a newer Minecraft version than this one; some blocks may be unknown.");
        List<BuildSource.PlacedRegion> regions = new ArrayList<>();
        boolean[] fallbackMapper = { false };
        for (SchematicData.Region r : data.regions) {
            LitematicaBridge.Sub sub = null;
            for (LitematicaBridge.Sub s : p.subs()) if (s.name().equals(r.name)) sub = s;
            if (sub == null) {
                sub = new LitematicaBridge.Sub(null, r.name, r.posX, r.posY, r.posZ, Transform.Rot.NONE, Transform.Mir.NONE, true);
                all.add("The region \"" + LitematicReader.clean(r.name, 40) + "\" is not in the Litematica placement; using its position from the file.");
            }
            if (!sub.enabled()) continue;
            int minX = LitematicFormat.minCorner(sub.x(), r.sizeX), minY = LitematicFormat.minCorner(sub.y(), r.sizeY), minZ = LitematicFormat.minCorner(sub.z(), r.sizeZ);
            Transform t = transform(p, sub, minX, minY, minZ);
            BuildSource.Mapper mapper = sub.handle() == null ? BuildSource.Mapper.of(t) : mapper(p, sub, minX, minY, minZ, r.absX(), r.absY(), r.absZ(), fallbackMapper);
            regions.add(FileSource.region(r, mapper, t, resolver, problems));
        }
        if (regions.isEmpty()) {
            all.add("The placement has no enabled regions.");
        }
        if (fallbackMapper[0]) all.add("Litematica's own position mapping is used for this placement (this addon's differed on a sample).");
        return new BuildSource.Placed(p.name(), "litematica+file", all, regions, problems);
    }

    private static Transform transform(LitematicaBridge.Placement p, LitematicaBridge.Sub sub, int minX, int minY, int minZ) {
        return new Transform(p.ox(), p.oy(), p.oz(), p.rot(), p.mir(), sub.rot(), sub.mir(), minX, minY, minZ);
    }

    /** Our mapping when it agrees with Litematica's on the sample, otherwise Litematica's forward mapping with our inverse, verified. */
    private static BuildSource.Mapper mapper(LitematicaBridge.Placement p, LitematicaBridge.Sub sub, int minX, int minY, int minZ, int sx, int sy, int sz, boolean[] fallback) {
        Transform ours = transform(p, sub, minX, minY, minZ);
        if (LitematicaBridge.transformAgrees(p, sub, ours, minX, minY, minZ, sx, sy, sz)) return BuildSource.Mapper.of(ours);
        QwerteAddon.LOG.warn("printer: this addon's transform differs from Litematica's for placement {} region {}; using Litematica's", p.name(), LitematicReader.clean(sub.name(), 40));
        fallback[0] = true;
        return new BuildSource.Mapper() {
            @Override
            public int[] toWorld(int cx, int cy, int cz) {
                int[] w = LitematicaBridge.litematicaWorld(p, sub, minX, minY, minZ, cx, cy, cz);
                return w != null ? w : ours.toWorld(cx, cy, cz);
            }

            @Override
            public int[] toLocal(int wx, int wy, int wz) {
                int[] l = ours.toLocal(wx, wy, wz);
                if (l[0] < 0 || l[1] < 0 || l[2] < 0 || l[0] >= sx || l[1] >= sy || l[2] >= sz) return l;
                int[] w = toWorld(l[0], l[1], l[2]);
                return w[0] == wx && w[1] == wy && w[2] == wz ? l : null;
            }
        };
    }

    /** A region read from Litematica's own container. */
    private static final class LiveRegion extends BuildSource.PlacedRegion {
        private final Object container;
        private final Transform transform;
        private final BuildSource.Problems problems;
        private final Map<BlockState, BlockState> cache = new HashMap<>();

        LiveRegion(String name, int[] size, BuildSource.Mapper mapper, Object container, Transform transform, BuildSource.Problems problems) {
            super(name, size[0], size[1], size[2], mapper);
            this.container = container;
            this.transform = transform;
            this.problems = problems;
        }

        @Override
        protected BlockState cell(int x, int y, int z) {
            BlockState raw = LitematicaBridge.state(container, x, y, z);
            if (raw == null) {
                problems.add("(unreadable cell in " + name + ")");
                return null;
            }
            if (cache.containsKey(raw)) return cache.get(raw);
            BlockState out = raw.is(Blocks.STRUCTURE_VOID) ? null : StateResolver.transform(raw, transform);
            cache.put(raw, out);
            return out;
        }
    }
}
