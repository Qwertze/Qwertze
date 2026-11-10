package com.porter.qwerte.craft;

/**
 * Which crafting table a craft order uses when the website sets none (the plugin's {@code reconcileOwnTable} and side mode). The user's table,
 * set by hand, is kept for shulker crafting. Orders share it while it is within the limits; when it is out of them they use a slot of their own
 * (found, placed or crafted), which never overwrites it. A table the bot picked that is out of the limits is simply forgotten. Pure.
 */
public final class CraftSide {
    private CraftSide() {
    }

    public enum Use {
        /** No table is known: the bot finds, places or crafts one. */
        NONE,
        /** The remembered table is within the limits: orders use it. */
        SHARED,
        /** The user's hand-set table is out of the limits: orders use their own slot. */
        SLOT,
        /** A table the bot picked is out of the limits: forget it and find another. */
        FORGET
    }

    /**
     * @param hasTable   a table is remembered (the user's own, or one the bot picked)
     * @param source     where it came from: "" or "set" for a hand-set one, else found, placed or crafted
     * @param outOfLimits it is outside the stash's dimension, the 64-block margin or the height range
     */
    public static Use decide(boolean hasTable, String source, boolean outOfLimits) {
        if (!hasTable) return Use.NONE;
        if (!outOfLimits) return Use.SHARED;
        return source == null || source.isEmpty() || source.equals("set") ? Use.SLOT : Use.FORGET;
    }
}
