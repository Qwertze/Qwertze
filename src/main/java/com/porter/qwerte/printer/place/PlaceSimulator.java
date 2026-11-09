package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs the game's own {@code Block.getStateForPlacement} for a recipe, with a look and sneak state of our choosing and
 * the REAL client level, and compares the result with the target's matched properties. The mapper proposes recipes;
 * this confirms the first one that reproduces the target.
 *
 * <p>Why the real level: getStateForPlacement reads it through the context for neighbour-dependent results (door
 * hinge from the blocks beside, chest merging, trapdoor/slab replacement, stair/fence shape, redstone power for open
 * doors, survival checks). The target cell and the clicked neighbour must therefore be in the state they will be in
 * when the click is sent, or the answer is for a different situation.
 *
 * <p>The context overrides, all public non-final in BlockPlaceContext / UseOnContext (identical signatures and
 * modifiers in the 1.21.4 and 1.21.11 jars, checked with javap): getNearestLookingDirection,
 * getNearestLookingVerticalDirection, getNearestLookingDirections, getHorizontalDirection, getRotation and
 * isSecondaryUseActive. getPlayer stays the real player; no getStateForPlacement in the jars reads the player's look
 * directly (checked over every Block class: the only Player call is LecternBlock's canUseGameMasterBlocks).
 *
 * <p>The look is held in statics because the BlockPlaceContext constructor calls overridable methods
 * (BlockState.canBeReplaced reads isSecondaryUseActive) before a subclass's own fields exist. Client thread only.
 */
public final class PlaceSimulator {
    private PlaceSimulator() {}

    private static float simYaw, simPitch;
    private static boolean simSneak;

    /** The game's own sin/cos tables (Mth.sin takes a float in 1.21.4 and a double in 1.21.11; this compiles on both). */
    private static final LookWindow.Trig MTH = new LookWindow.Trig() {
        @Override
        public float sin(float r) {
            return Mth.sin(r);
        }

        @Override
        public float cos(float r) {
            return Mth.cos(r);
        }
    };

    private static Direction mc(Dir d) {
        return Direction.valueOf(d.name());
    }

    private static Direction[] mc(Dir[] ds) {
        Direction[] out = new Direction[ds.length];
        for (int i = 0; i < ds.length; i++) out[i] = mc(ds[i]);
        return out;
    }

    private static final class SimContext extends BlockPlaceContext {
        SimContext(Level level, Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
            super(level, player, hand, stack, hit);
        }

        @Override
        public Direction getNearestLookingDirection() {
            return mc(LookWindow.nearest(simYaw, simPitch, MTH));
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return simPitch < 0 ? Direction.UP : Direction.DOWN;
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] dirs = mc(LookWindow.nearestOrder(simYaw, simPitch, MTH));
            if (this.replaceClicked) return dirs;
            Direction clicked = getClickedFace();
            int i = 0;
            while (i < dirs.length && dirs[i] != clicked.getOpposite()) i++;
            if (i > 0) {
                System.arraycopy(dirs, 0, dirs, 1, i);
                dirs[0] = clicked.getOpposite();
            }
            return dirs;
        }

        @Override
        public Direction getHorizontalDirection() {
            return mc(Dir.fromYaw(simYaw));
        }

        @Override
        public float getRotation() {
            return simYaw;
        }

        @Override
        public boolean isSecondaryUseActive() {
            return simSneak;
        }
    }

    /** What a simulated placement produced. {@code state} is null when the block refused; {@code why} says what. */
    public record Outcome(BlockState state, String why) {}

    /**
     * Simulates one placement of {@code target}'s block at {@code pos} with the recipe's click, looking at (yaw, pitch).
     * Mirrors BlockItem.place: the context must resolve to {@code pos}, the cell must accept a placement, the block
     * must give a state, and the state must survive there.
     */
    public static Outcome simulate(Level level, Player player, BlockState target, BlockPos pos, PlaceRecipe r, double yaw, double pitch) {
        Block block = target.getBlock();
        Item item = block.asItem();
        if (item == Items.AIR) return new Outcome(null, "block has no item");
        int[] clicked = r.clickedBlock(pos.getX(), pos.getY(), pos.getZ());
        double[] at = r.hitAt(pos.getX(), pos.getY(), pos.getZ());
        BlockHitResult hit = new BlockHitResult(new Vec3(at[0], at[1], at[2]), mc(r.face()), new BlockPos(clicked[0], clicked[1], clicked[2]), false);
        float oy = simYaw, op = simPitch;
        boolean os = simSneak;
        simYaw = (float) yaw;
        simPitch = (float) pitch;
        simSneak = r.sneak();
        try {
            BlockPlaceContext ctx = new SimContext(level, player, InteractionHand.MAIN_HAND, new ItemStack(item), hit);
            if (!ctx.getClickedPos().equals(pos)) return new Outcome(null, "the click lands on " + ctx.getClickedPos().toShortString() + ", not the target");
            if (!ctx.canPlace()) return new Outcome(null, "the cell does not accept a placement");
            BlockState st = block.getStateForPlacement(ctx);
            if (st == null) return new Outcome(null, "the block refused the placement");
            if (!st.canSurvive(level, pos)) return new Outcome(null, "the state would not survive there");
            return new Outcome(st, "");
        } catch (RuntimeException e) {
            return new Outcome(null, "simulation failed: " + e);
        } finally {
            simYaw = oy;
            simPitch = op;
            simSneak = os;
        }
    }

    /** True when the placed state has the same block and every matched property of the spec. */
    public static boolean matches(StateSpec spec, BlockState placed) {
        if (!StateClassifier.idOf(placed.getBlock()).equals(spec.blockId())) return false;
        for (var e : spec.matched().entrySet()) {
            String have = null;
            for (Property<?> p : placed.getProperties()) {
                if (p.getName().equals(e.getKey())) {
                    have = valueName(placed, p);
                    break;
                }
            }
            if (!e.getValue().equals(have)) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    /** A recipe the game agreed with, and the state it predicts. */
    public record Confirmed(PlaceRecipe recipe, BlockState predicted) {}

    /**
     * The first recipe whose simulated result matches the target at the window's middle. If it also matches at the
     * window's corners the whole window is kept; otherwise the window is narrowed to +-5 degrees around the middle.
     * Returns null when no recipe reproduces the target (the caller reports it as not placeable right now).
     */
    public static Confirmed confirm(Level level, Player player, BlockState target, BlockPos pos, StateSpec spec, List<PlaceRecipe> recipes) {
        for (PlaceRecipe r : recipes) {
            List<double[]> samples = r.look().samples();
            double[] mid = samples.get(0);
            Outcome o = simulate(level, player, target, pos, r, mid[0], mid[1]);
            if (o.state() == null || !matches(spec, o.state())) continue;
            boolean all = true;
            for (int i = 1; i < samples.size() && all; i++) {
                Outcome c = simulate(level, player, target, pos, r, samples.get(i)[0], samples.get(i)[1]);
                all = c.state() != null && matches(spec, c.state());
            }
            return new Confirmed(all ? r : r.withLook(r.look().narrowed(5, 5)), o.state());
        }
        return null;
    }
}
