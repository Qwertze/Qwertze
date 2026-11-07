package com.porter.qwerte.pearl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Tells the trapdoors of a stasis shaft apart. The STASIS trapdoor is the one at the water: in the water block, at the
 * surface or directly above it (y up to waterTop + 1). It has to stay OPEN for a pearl to stay in stasis, and closing it
 * is what pulls the pearl. The COVER trapdoors are everything above it; they only keep people out and may be closed.
 * A shaft with a single high trapdoor has a cover and no stasis trapdoor (a plain water column).
 */
public final class TrapRoles {
    public enum Role { STASIS, COVER, NONE }

    public static final int NO_STASIS = Integer.MIN_VALUE;

    private final int stasisY;
    private final List<Integer> coverYs;
    private final String error;

    private TrapRoles(int stasisY, List<Integer> coverYs, String error) {
        this.stasisY = stasisY;
        this.coverYs = List.copyOf(coverYs);
        this.error = error;
    }

    /** Null error: fine. Otherwise the trapdoors can't be told apart and nothing should be clicked. */
    public String error() {
        return error;
    }

    public boolean hasStasis() {
        return stasisY != NO_STASIS;
    }

    public int stasisY() {
        return stasisY;
    }

    public List<Integer> coverYs() {
        return coverYs;
    }

    public Role roleOf(int y) {
        if (error != null) return Role.NONE;
        if (y == stasisY) return Role.STASIS;
        return coverYs.contains(y) ? Role.COVER : Role.NONE;
    }

    /** The trapdoors that may be closed again, of those that were opened: covers only, lowest first. Never the stasis one. */
    public List<Integer> coverPlan(Collection<Integer> openedYs) {
        List<Integer> plan = new ArrayList<>();
        if (error != null) return plan;
        for (int y : openedYs) if (coverYs.contains(y) && !plan.contains(y)) plan.add(y);
        plan.sort(null);
        return plan;
    }

    public static TrapRoles classify(int waterTop, List<Integer> trapYs) {
        List<Integer> sorted = new ArrayList<>(trapYs);
        sorted.sort(null);
        List<Integer> low = new ArrayList<>(), high = new ArrayList<>();
        for (int y : sorted) (y <= waterTop + 1 ? low : high).add(y);
        if (low.size() > 1) {
            return new TrapRoles(NO_STASIS, List.of(), "two trapdoors are at the water (y " + low.get(0) + " and " + low.get(1)
                + "), so it is not clear which one keeps the pearl");
        }
        return new TrapRoles(low.isEmpty() ? NO_STASIS : low.get(0), high, null);
    }

    /** What the check after the cover says. */
    public enum Armed { WAIT, ARMED, TRIGGERED, NOT_SETTLED }

    /**
     * @param ticks        ticks since the cover was done
     * @param needed       how many ticks the pearl has to sit still for
     * @param pearlPresent a pearl of the player's is in the shaft
     * @param pearlSlow    and it is slow (resting or drifting in water, not falling or flying)
     * @param pearlAtWater and it is near the water
     * @param teleported   the player was moved a long way since the cover was done
     */
    public static Armed decide(int ticks, int needed, boolean pearlPresent, boolean pearlSlow, boolean pearlAtWater, boolean teleported) {
        if (teleported) return Armed.TRIGGERED;
        if (!pearlPresent) return ticks > 0 ? Armed.TRIGGERED : Armed.WAIT;
        if (pearlSlow && pearlAtWater) return ticks >= needed ? Armed.ARMED : Armed.WAIT;
        return ticks > needed * 2 ? Armed.NOT_SETTLED : Armed.WAIT;
    }
}
