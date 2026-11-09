package com.porter.qwerte.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.ICustomGoalProcess;
import com.porter.qwerte.compat.BaritoneFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Thin wrapper around dekrom's Baritone fork (https://github.com/dekrom/baritone).
 * Only call this after checking Baritone is installed, it references Baritone classes directly.
 */
public final class BaritoneHelper {
    private static final List<Block> protectedBlocks = new ArrayList<>();

    // The user's own values, restored by unprotect()
    private static Boolean waterBucketFall;
    // Break, place and the safe walk settings while a walk is confined: the setting, what it was, what we set it to
    private static final List<Object[]> confined = new ArrayList<>();

    private BaritoneHelper() {}

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /**
     * Paths to anywhere within {@code range} blocks of {@code pos}, 0 meaning exactly there.
     */
    public static Goal moveNear(BlockPos pos, int range) {
        Goal goal = new GoalNear(pos, range);
        baritone().getCustomGoalProcess().setGoalAndPath(goal);
        return goal;
    }

    /**
     * Clears the custom goal process, but only if its current goal is {@code ours}. Never cancels anything else, so
     * the user's own Baritone processes and goals survive. Returns true if we released it.
     */
    public static boolean releaseOurs(Goal ours) {
        if (ours == null) return false;
        ICustomGoalProcess process = baritone().getCustomGoalProcess();
        if (process.getGoal() != ours) return false;
        process.onLostControl();
        return true;
    }

    /**
     * True while the custom goal process still has {@code ours} as its goal: it has not been cleared or replaced.
     */
    public static boolean goalIs(Goal ours) {
        return ours != null && baritone().getCustomGoalProcess().getGoal() == ours;
    }

    /**
     * True while Baritone is pathing or any process is active, whoever owns it.
     */
    public static boolean isActive() {
        IBaritone baritone = baritone();
        return baritone.getPathingBehavior().isPathing() || baritone.getCustomGoalProcess().isActive()
            || baritone.getPathingBehavior().getInProgress().isPresent();
    }

    public static boolean isPathing() {
        return baritone().getPathingBehavior().isPathing();
    }

    /**
     * True if Baritone is busy with something that is not our goal {@code ours}: another process is in control, or
     * the custom goal process was given a different goal (e.g. the user's #goto).
     */
    public static boolean controlledByOther(Goal ours) {
        if (!isActive()) return false;
        IBaritone baritone = baritone();
        ICustomGoalProcess custom = baritone.getCustomGoalProcess();
        if (ours == null || custom.getGoal() != ours) return true;
        IBaritoneProcess inControl = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        return inControl != null && inControl != custom;
    }

    /**
     * True while Baritone is still calculating or walking a path for our goal.
     */
    public static boolean isMoving() {
        IBaritone baritone = baritone();
        return baritone.getPathingBehavior().isPathing() || baritone.getCustomGoalProcess().isActive();
    }

    /**
     * True while Baritone is still working out a path, which can take a while for far away goals.
     */
    public static boolean isCalculating() {
        return baritone().getPathingBehavior().getInProgress().isPresent();
    }

    public static void stop() {
        baritone().getPathingBehavior().cancelEverything();
    }

    /**
     * Stops Baritone from breaking these blocks, placing blocks in water (which deletes water sources) or using our
     * water buckets to break falls. Applied on top of whatever the user already has set.
     */
    public static void protect(Collection<Block> blocks) {
        Settings settings = BaritoneAPI.getSettings();

        // Copy rather than mutate, the list Baritone holds may be immutable
        List<Block> disallowed = new ArrayList<>(settings.blocksToDisallowBreaking.value);
        for (Block block : blocks) {
            if (disallowed.contains(block)) continue;
            disallowed.add(block);
            protectedBlocks.add(block);
        }
        settings.blocksToDisallowBreaking.value = disallowed;

        if (waterBucketFall == null) {
            waterBucketFall = settings.allowWaterBucketFall.value;
            settings.allowWaterBucketFall.value = false;
        }
        BaritoneFluids.protect(settings);
    }

    /**
     * Keeps Baritone from breaking and placing blocks at all, remembering the user's own settings for {@link #unconfine()}.
     */
    public static void confine(boolean safe) {
        Settings settings = BaritoneAPI.getSettings();
        confineOne(settings.allowBreak, false);
        confineOne(settings.allowPlace, false);
        if (!safe) return;

        // Safe walk: no parkour, no diagonal drops, no falls beyond a one block step down (that step is a descend, which
        // doesn't use the fall height), no jumping at the build limit and no bucket falls
        confineOne(settings.allowParkour, false);
        confineOne(settings.allowParkourAscend, false);
        confineOne(settings.allowParkourPlace, false);
        confineOne(settings.allowDiagonalDescend, false);
        confineOne(settings.allowWaterBucketFall, false);
        confineOne(settings.allowJumpAt256, false);
        confineOne(settings.maxFallHeightNoWater, 1);
    }

    /**
     * Sets a setting for the duration of the confinement, remembering what it was.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void confineOne(Settings.Setting setting, Object ours) {
        for (Object[] saved : confined) if (saved[0] == setting) return;
        confined.add(new Object[] { setting, setting.value, ours });
        setting.value = ours;
    }

    /**
     * Gives Baritone back what {@link #confine(boolean)} took away. A setting the user changed in the meantime (it no
     * longer holds the value we set) is left as they made it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void unconfine() {
        if (confined.isEmpty()) return;
        for (Object[] saved : confined) {
            Settings.Setting setting = (Settings.Setting) saved[0];
            if (Objects.equals(setting.value, saved[2])) setting.value = saved[1];
        }
        confined.clear();
    }

    /**
     * True while the settings are confined.
     */
    public static boolean confined() {
        return !confined.isEmpty();
    }

    /**
     * Paths to anywhere the player's eyes are within {@code reach} of the centre of one of {@code parts}: close enough
     * to click it, not next to it. A container up high is reached from below like a player would, instead of Baritone
     * looking for a spot beside it that may not exist.
     */
    public static Goal moveInReach(Collection<BlockPos> parts, double reach) {
        Goal goal = new GoalReach(List.copyOf(parts), reach);
        baritone().getCustomGoalProcess().setGoalAndPath(goal);
        return goal;
    }

    /**
     * Like {@link #moveInReach(Collection, double)}, but never ends on a feet cell for which {@code badFeet} is true (the
     * cell the player's feet would be in), nor on one whose head cell (one above) is. The predicate is called from
     * Baritone's path finding threads: it must be thread-safe and quick, and must not touch the world.
     */
    public static Goal moveInReach(Collection<BlockPos> parts, double reach, Predicate<BlockPos> badFeet) {
        Goal goal = new GoalReach(List.copyOf(parts), reach, badFeet);
        baritone().getCustomGoalProcess().setGoalAndPath(goal);
        return goal;
    }

    /** Feet positions whose eyes are within reach of a block's centre; see {@link #moveInReach}. */
    private record GoalReach(List<BlockPos> parts, double reach, Predicate<BlockPos> badFeet) implements Goal {
        // Standing eye height, and a little less reach than the setting so the arrival check never misses by a hair
        private static final double EYES = 1.62, MARGIN = 0.25;

        GoalReach(List<BlockPos> parts, double reach) {
            this(parts, reach, null);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (badFeet != null && (badFeet.test(new BlockPos(x, y, z)) || badFeet.test(new BlockPos(x, y + 1, z)))) return false;
            double r = Math.max(1, reach - MARGIN);
            for (BlockPos p : parts) {
                double dx = x + 0.5 - (p.getX() + 0.5), dy = y + EYES - (p.getY() + 0.5), dz = z + 0.5 - (p.getZ() + 0.5);
                if (dx * dx + dy * dy + dz * dz <= r * r) return true;
            }
            return false;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // Toward the nearest part, with any height from which the eyes can reach it counting as the right height
            double best = Double.MAX_VALUE;
            int span = (int) Math.floor(reach);
            for (BlockPos p : parts) {
                int top = p.getY() - 1, bottom = p.getY() - 1 - span;
                int dy = y > top ? y - top : y < bottom ? y - bottom : 0;
                best = Math.min(best, baritone.api.pathing.goals.GoalBlock.calculate(x - p.getX(), dy, z - p.getZ()));
            }
            return best;
        }
    }

    /**
     * Paths to anywhere in the column at x, z, whatever the height.
     */
    public static Goal moveXZ(int x, int z) {
        Goal goal = new GoalXZ(x, z);
        baritone().getCustomGoalProcess().setGoalAndPath(goal);
        return goal;
    }

    /**
     * Undoes {@link #protect(Collection)}, leaving the user's own entries alone.
     */
    public static void unprotect() {
        Settings settings = BaritoneAPI.getSettings();

        if (!protectedBlocks.isEmpty()) {
            List<Block> disallowed = new ArrayList<>(settings.blocksToDisallowBreaking.value);
            disallowed.removeAll(protectedBlocks);
            protectedBlocks.clear();
            settings.blocksToDisallowBreaking.value = disallowed;
        }

        if (waterBucketFall != null) {
            settings.allowWaterBucketFall.value = waterBucketFall;
            waterBucketFall = null;
        }
        BaritoneFluids.restore(settings);
    }
}
