package com.porter.qwerte.printer.source;

import com.porter.qwerte.printer.schematic.LitematicReader;
import com.porter.qwerte.printer.schematic.SchematicData;
import com.porter.qwerte.printer.schematic.SchematicFile;
import com.porter.qwerte.printer.schematic.Transform;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.minecraft.world.level.block.state.BlockState;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A .litematic read from disk and placed by the user's settings (origin, rotation, mirror), or by a placement
 * Litematica reported (see LitematicaSource). The file is parsed on a worker thread; the source is built on the client
 * thread, where the game's registries are used.
 */
public final class FileSource {
    private FileSource() {
    }

    /** What a load gave: a source, or why not. */
    public record Loaded(BuildSource source, String error) {
        public boolean ok() {
            return source != null;
        }
    }

    /** Places a parsed file: every region at {@code origin}, turned and mirrored as a whole. */
    public static BuildSource create(String label, SchematicData data, int ox, int oy, int oz, Transform.Rot rot, Transform.Mir mir, List<String> notes) {
        BuildSource.Problems problems = new BuildSource.Problems();
        StateResolver resolver = new StateResolver(data.dataVersion, problems);
        List<String> all = new ArrayList<>(notes);
        all.addAll(data.warnings);
        if (resolver.newerThanGame()) all.add("The schematic is from a newer Minecraft version than this one; some blocks may be unknown.");
        List<BuildSource.PlacedRegion> regions = new ArrayList<>();
        for (SchematicData.Region r : data.regions) {
            Transform t = Transform.of(r, ox, oy, oz, rot, mir);
            regions.add(region(r, BuildSource.Mapper.of(t), t, resolver, problems));
        }
        return new BuildSource.Placed(label, "file", all, regions, problems);
    }

    /** One region of a file under a given transform. */
    static BuildSource.PlacedRegion region(SchematicData.Region r, BuildSource.Mapper mapper, Transform t, StateResolver resolver, BuildSource.Problems problems) {
        return new BuildSource.PlacedRegion(LitematicReader.clean(r.name, 40), r.absX(), r.absY(), r.absZ(), mapper) {
            private final BlockState[] states = new BlockState[r.palette.length];
            private final boolean[] done = new boolean[r.palette.length];
            private int next;

            @Override
            protected boolean resolveSome(long deadlineNanos) {
                while (next < done.length) {
                    if (System.nanoTime() - deadlineNanos > 0) return false;
                    resolve(next++);
                }
                return true;
            }

            private void resolve(int i) {
                if (done[i]) return;
                done[i] = true;
                BlockState s = resolver.resolve(r.palette[i]);
                states[i] = s == null ? null : StateResolver.transform(s, t);
            }

            @Override
            protected BlockState cell(int x, int y, int z) {
                int i = r.paletteIndex(x, y, z);
                if (i < 0) {
                    problems.add("(bad palette index in " + name + ")");
                    return null;
                }
                resolve(i);
                return states[i];
            }
        };
    }

    /**
     * Reads {@code file} (already resolved by {@link SchematicFile}) on a worker thread and delivers the placed
     * source on the client thread. Cancel the future to drop the result.
     */
    public static Future<?> loadAsync(Path file, String label, int ox, int oy, int oz, Transform.Rot rot, Transform.Mir mir, List<String> notes, Duration timeout, Consumer<Loaded> done) {
        StateResolver.warmUp();
        return SchematicFile.loadAsync(file, timeout, mc::execute, r -> {
            if (!r.ok()) {
                done.accept(new Loaded(null, r.error()));
                return;
            }
            try {
                done.accept(new Loaded(create(label, r.data(), ox, oy, oz, rot, mir, notes), null));
            } catch (RuntimeException e) {
                done.accept(new Loaded(null, "The schematic could not be set up."));
            }
        });
    }
}
