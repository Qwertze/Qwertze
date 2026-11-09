package com.porter.qwerte.printer.place;

/**
 * The waterlogging decisions of the Printer. Pure. A block whose target state has {@code waterlogged=true} is placed dry
 * (the placement itself can't make it wet) and then gets water from a water bucket used on it. The bucket is emptied by
 * that, so a refill (picking water up again from a source in reach) is a separate, optional step.
 */
public final class WaterlogRules {
    private WaterlogRules() {}

    public enum Action {
        /** Nothing to do: not wanted wet, already wet, or the setting is off. */
        NONE,
        /** Use the water bucket on the block. */
        USE_BUCKET,
        /** Wanted wet, but no water bucket is carried: leave it dry (one warning per run). */
        NEED_BUCKET
    }

    public static Action decide(boolean settingOn, boolean targetWaterlogged, boolean currentWaterlogged, boolean waterBucketAvailable) {
        if (!targetWaterlogged || currentWaterlogged || !settingOn) return Action.NONE;
        return waterBucketAvailable ? Action.USE_BUCKET : Action.NEED_BUCKET;
    }

    /** A block that is left dry although it was wanted wet: the one warning of the run is due. */
    public static boolean leftDry(boolean settingOn, boolean targetWaterlogged, boolean waterBucketAvailable) {
        return targetWaterlogged && (!settingOn || !waterBucketAvailable);
    }

    /**
     * A block a bucket may pick water up from: a real water block (never a waterlogged block, which is some other block
     * holding water), a source, and not a cell the schematic wants something in.
     */
    public static boolean refillSource(boolean isWaterBlock, boolean isSource, boolean schematicWantsBlock) {
        return isWaterBlock && isSource && !schematicWantsBlock;
    }

    /**
     * Pick water up now: the setting is on, an empty bucket is carried and no full one, there is waterlogging still to do,
     * and a water source is within reach.
     */
    public static boolean shouldRefill(boolean refillSetting, boolean emptyBucketCarried, boolean waterBucketAvailable, boolean waterWorkAhead, boolean sourceInReach) {
        return refillSetting && emptyBucketCarried && !waterBucketAvailable && waterWorkAhead && sourceInReach;
    }
}
