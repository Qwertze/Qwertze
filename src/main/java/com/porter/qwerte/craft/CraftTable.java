package com.porter.qwerte.craft;

import com.porter.qwerte.stash.CraftBounds;
import com.porter.qwerte.stash.CraftSettings;

/**
 * Whether the website's crafting table may be used for a craft chunk, decided before the bot walks anywhere (CRAFT-CONTRACT.md "Bot execution",
 * point 6): none set is {@code no_table}; outside the stash's dimension or more than 64 blocks off its area (or no area, or no dimension) is
 * {@code table_blocked}; a loaded block that is not a crafting table is {@code table_missing}. Pure.
 */
public final class CraftTable {
    private CraftTable() {
    }

    /**
     * @param stashDimension   the dimension the stash was recorded in
     * @param bounds           the stash area (minX, minY, minZ, maxX, maxY, maxZ) or null
     * @param currentDimension the dimension the bot is in
     * @param loaded           the table's chunk is loaded
     * @param isTable          (only meaningful when loaded) the block there is a crafting table
     * @return a {@link CraftOrder} reason, or null when the table may be used
     */
    public static String problem(CraftSettings.Table table, String stashDimension, int[] bounds, String currentDimension, boolean loaded, boolean isTable) {
        if (table == null) return CraftOrder.NO_TABLE;
        if (CraftBounds.outOfBounds(stashDimension, bounds, table)) return CraftOrder.TABLE_BLOCKED;
        if (!CraftBounds.sameDimension(currentDimension, table.dimension())) return CraftOrder.TABLE_BLOCKED;
        if (loaded && !isTable) return CraftOrder.TABLE_MISSING;
        return null;
    }

    /** The failure text the crafter and {@link CraftOrder#reasonOf} understand for a reason from {@link #problem}. */
    public static String failureText(String reason) {
        return switch (reason) {
            case CraftOrder.NO_TABLE -> "no crafting table is set for this stash";
            case CraftOrder.TABLE_MISSING -> "the website's crafting table isn't one";
            default -> CraftHealth.NOT_REACHED;
        };
    }
}
