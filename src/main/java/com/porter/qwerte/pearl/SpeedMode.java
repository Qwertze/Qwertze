package com.porter.qwerte.pearl;

/**
 * How fast Reset Pearl goes. Every mode does the same steps and keeps every safety (exact trapdoor targets, the stasis
 * trapdoor never closed, the pearl check before "Re-armed", the elytra put back); they differ in the waits between
 * them and in how many clicks go out in one tick.
 */
public enum SpeedMode {
    /** The waits of 0.4.0-dev.10.2: one click at a time, 3 ticks between clicks, a stop on the edge before the throw. */
    Safe(new Params(true, 1, 3, false, false, 6, 3, 3, 1, 3, 15, false, false, 0.0005, false)),
    /**
     * The same careful approach as Safe (exit onto the rim, sneak to the edge, stop, aimed throw from the real
     * position), minus the waits that only were caution: one click a tick, the climb not held up by the elytra swap,
     * no fixed wait before the cover, the controls and the elytra back during the pearl check.
     */
    Fast(new Params(false, 1, 0, false, false, 3, 2, 0, 1, 0, 15, true, true, 0.004, true)),
    /** Like Fast with two trapdoor clicks a tick, a 1 tick stop on the edge and a shorter pearl check. */
    Max(new Params(false, 2, 0, false, false, 1, 1, 0, 2, 0, 10, true, true, 0.004, true));

    /**
     * @param elytraBlocksClimb     the climb waits for the elytra swap to be checked (3 ticks) instead of going on at once
     * @param trapClicksPerTick     trapdoors opened per tick on the way out (different blocks)
     * @param trapCooldown          ticks between those batches
     * @param sprintSwim            look straight up and sprint-swim up the shaft (faster than jump-swimming)
     * @param throwWhileClimbing    throw the pearl from inside the shaft once the eyes are above the water
     * @param minHang               ticks on the rim before the edge is taken as reached (the fallback throw)
     * @param stillTicks            ticks standing still before that throw
     * @param coverWait             fixed ticks before the first cover click (the state check for sneaking is always on)
     * @param coverClicksPerTick    covers closed per tick
     * @param coverCooldown         ticks between those batches
     * @param verifyTicks           ticks the new pearl has to rest after the cover before "Re-armed"
     * @param parallelRestore       the elytra and the hotbar go back during that check, not after it
     * @param awaitServer           a clicked trapdoor is not clicked again until the server's block update (or a
     *                              ping-aware timeout) has shown what the click did; Safe keeps its fixed spacing
     * @param stillEps              blocks per tick below which the player counts as standing still
     * @param briskApproach         walk (not sneak) the first part of the way back from the rim block
     */
    public record Params(boolean elytraBlocksClimb, int trapClicksPerTick, int trapCooldown, boolean sprintSwim,
                         boolean throwWhileClimbing, int minHang, int stillTicks, int coverWait, int coverClicksPerTick,
                         int coverCooldown, int verifyTicks, boolean parallelRestore, boolean awaitServer, double stillEps,
                         boolean briskApproach) {}

    private final Params params;

    SpeedMode(Params params) {
        this.params = params;
    }

    public Params params() {
        return params;
    }
}
