package com.porter.qwerte.printer.source;

import com.mojang.serialization.Dynamic;
import com.porter.qwerte.compat.GameVersion;
import com.porter.qwerte.printer.schematic.LitematicFormat;
import com.porter.qwerte.printer.schematic.LitematicReader;
import com.porter.qwerte.printer.schematic.Transform;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Turns palette entries ({@link LitematicFormat#stateKey} strings) into block states: through the game's data fixer when
 * the schematic is from an older version, then checked against the block registry and the block's own properties
 * (so an unknown block or property value is listed instead of flooding the log), and later mirrored and rotated for
 * a placement. Client thread only (the registries and the fixer are the game's). Unknown blocks are listed, not placed.
 */
public final class StateResolver {
    private static Map<String, Block> blocks;

    private final int dataVersion;
    private final int current;
    private final BuildSource.Problems problems;
    private final Map<String, BlockState> cache = new HashMap<>();

    /** @param dataVersion the schematic's MinecraftDataVersion (0 when it does not say) */
    public StateResolver(int dataVersion, BuildSource.Problems problems) {
        this.dataVersion = dataVersion;
        this.current = GameVersion.dataVersion();
        this.problems = problems;
    }

    /** True when the schematic is from a newer game version than this one: the fixer can't help and unknown states are likely. */
    public boolean newerThanGame() {
        return dataVersion > current;
    }

    /** True when the data version is older than 1.13: nothing from it is resolved (the reader refuses such files already). */
    public boolean tooOld() {
        return dataVersion > 0 && dataVersion < LitematicReader.MIN_DATA_VERSION;
    }

    private static boolean warming;

    /** Builds the block table now, on the calling (client) thread, so the first palette entry doesn't pay for it. */
    public static void prepare() {
        blocks();
    }

    /**
     * Runs one throw-away data fixer update on a background thread, so the fixer's lazy set-up (10 to 500 ms) is not paid on
     * the client thread when the first palette entry of an older schematic is resolved. Harmless when it fails.
     */
    public static synchronized void warmUp() {
        if (warming) return;
        warming = true;
        Thread t = new Thread(() -> {
            try {
                CompoundTag tag = new CompoundTag();
                tag.put("Name", StringTag.valueOf("minecraft:stone"));
                int to = GameVersion.dataVersion();
                DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), Math.max(1, to - 1), to);
            } catch (RuntimeException | LinkageError e) {
                // only a warm-up
            }
        }, "Qwerte-Schematic-Warmup");
        t.setDaemon(true);
        t.start();
    }

    /** Block id to block, built once from the registry without naming the registry key type (it differs between versions). */
    private static Map<String, Block> blocks() {
        if (blocks == null) {
            Map<String, Block> m = new HashMap<>();
            for (Block b : BuiltInRegistries.BLOCK) m.put(BuiltInRegistries.BLOCK.getKey(b).toString(), b);
            blocks = m;
        }
        return blocks;
    }

    /**
     * The state a palette entry means, or null when it is unknown to this game version (listed in the problems) or is
     * structure_void (never built, not listed).
     */
    public BlockState resolve(String key) {
        if (cache.containsKey(key)) return cache.get(key);
        BlockState state = build(key);
        cache.put(key, state);
        return state;
    }

    private BlockState build(String key) {
        String name = LitematicFormat.keyName(key);
        try {
            if (tooOld()) {
                problems.add(name);
                return null;
            }
            CompoundTag tag = new CompoundTag();
            tag.put("Name", StringTag.valueOf(name));
            String[][] props = LitematicFormat.keyProperties(key);
            if (props.length > 0) {
                CompoundTag p = new CompoundTag();
                for (String[] kv : props) p.put(kv[0], StringTag.valueOf(kv[1]));
                tag.put("Properties", p);
            }
            if (dataVersion > 0 && dataVersion < current) {
                Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), dataVersion, current);
                if (fixed.getValue() instanceof CompoundTag c) tag = c;
            }
            String fixedName = NbtOps.INSTANCE.getStringValue(tag.get("Name")).result().orElse(name);
            Block block = blocks().get(fixedName);
            if (block == null) {
                problems.add(name);
                return null;
            }
            if (block == Blocks.STRUCTURE_VOID) return null;
            // readBlockState logs every property it does not know; checking first keeps a hostile palette from flooding the log
            if (tag.get("Properties") instanceof CompoundTag p) {
                var entries = NbtOps.INSTANCE.getMapValues(p).result().orElse(null);
                if (entries == null) {
                    problems.add(name);
                    return null;
                }
                boolean[] ok = { true };
                entries.forEach(e -> {
                    String k = NbtOps.INSTANCE.getStringValue(e.getFirst()).result().orElse(null);
                    String v = NbtOps.INSTANCE.getStringValue(e.getSecond()).result().orElse(null);
                    Property<?> prop = k == null ? null : block.getStateDefinition().getProperty(k);
                    if (prop == null || v == null || prop.getValue(v).isEmpty()) ok[0] = false;
                });
                if (!ok[0]) {
                    problems.add(name + " (property)");
                    return null;
                }
            }
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
        } catch (RuntimeException e) {
            problems.add(name);
            return null;
        }
    }

    /** A state mirrored and rotated the way Litematica does for a placement (see {@link Transform}). */
    public static BlockState transform(BlockState state, Transform t) {
        for (Transform.Mir m : t.stateMirrors()) {
            if (m != Transform.Mir.NONE) state = state.mirror(Mirror.valueOf(m.name()));
        }
        Transform.Rot r = t.stateRotation();
        if (r != Transform.Rot.NONE) state = state.rotate(Rotation.valueOf(r.name()));
        return state;
    }
}
