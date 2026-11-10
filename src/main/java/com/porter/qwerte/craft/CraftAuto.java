package com.porter.qwerte.craft;

import java.util.Set;

/**
 * The table a craft order is made at when the website sets none (the auto table): the bot finds a crafting table nearby, takes one out of the
 * stash and places it, or crafts one (4 planks, or a log turned into planks, in the 2x2 grid) and places it, and records it as the stash's bot
 * table. A website table still has priority. This is the decision and the words the heartbeat uses for it (additive fields of
 * {@code craftOrders}: {@code table: "auto"}, {@code tableWhy}, {@code botTable}); the finding, placing and crafting are the sorter's
 * ({@code TableFinder}, {@code prepareTable}). Pure.
 */
public final class CraftAuto {
    private CraftAuto() {
    }

    /** The heartbeat's {@code craftOrders.table} when no website table is set and the bot will find, place or craft one. It counts as usable. */
    public static final String AUTO = "auto";

    /** The heartbeat's {@code craftOrders.tableWhy} codes. */
    public static final String NO_AREA = "no_area", NO_DIMENSION = "no_dimension", WRONG_DIMENSION = "wrong_dimension", TOO_FAR = "too_far",
        NOT_TABLE = "not_table", NO_STAND = "no_stand", PATH_FAILED = "path_failed", NOT_LOADED = "not_loaded", NO_MATERIAL = "no_material";

    public static final Set<String> WHY = Set.of(NO_AREA, NO_DIMENSION, WRONG_DIMENSION, TOO_FAR, NOT_TABLE, NO_STAND, PATH_FAILED, NOT_LOADED, NO_MATERIAL);

    /** Where the bot's own table came from, in {@code botTable.source}. */
    public static final Set<String> BOT_SOURCES = Set.of("found", "placed", "crafted");

    /**
     * @param websiteSet    a table is set on the website for this stash
     * @param websiteStatus its state (unset, ok, unloaded, not_table, unreachable, other_dimension); "unset" without one
     * @param hasArea       the stash has an area
     * @param hasDimension  the stash has a recorded dimension
     * @param obtainable    a table is known, found nearby, held by the stash, or planks or logs to craft one are
     * @param autoWhy       why the last try at an auto table failed (a code), or null
     * @param handSetFar    the table the user set by hand is out of the limits (craft orders use a table of their own then)
     */
    public record State(boolean websiteSet, String websiteStatus, boolean hasArea, boolean hasDimension, boolean obtainable, String autoWhy, boolean handSetFar) {
        public State(boolean websiteSet, String websiteStatus, boolean hasArea, boolean hasDimension, boolean obtainable, String autoWhy) {
            this(websiteSet, websiteStatus, hasArea, hasDimension, obtainable, autoWhy, false);
        }
    }

    /** The auto path is available: the stash has an area and a dimension, and a table can be had, and the last try did not fail. */
    public static boolean autoAvailable(State s) {
        return s.hasArea() && s.hasDimension() && s.obtainable() && s.autoWhy() == null;
    }

    private static boolean websiteUsable(State s) {
        return s.websiteSet() && ("ok".equals(s.websiteStatus()) || "unloaded".equals(s.websiteStatus()));
    }

    /** A table is usable: the website's (when one is set), else the auto path. */
    public static boolean usable(State s) {
        return s.websiteSet() ? websiteUsable(s) : autoAvailable(s);
    }

    /**
     * Whether the bot can carry out craft orders as far as the table goes: the website's table when one is set (it must work: no fallback to a
     * table of its own), else the auto path. The same as the plugin's. The heartbeat still says why when it is not.
     */
    public static boolean tableOk(State s) {
        return usable(s);
    }

    /** The heartbeat's {@code craftOrders.table}: the website table's state when one is set, else {@link #AUTO} when available, else "unset". */
    public static String status(State s) {
        if (s.websiteSet()) return s.websiteStatus();
        return autoAvailable(s) ? AUTO : "unset";
    }

    /** The heartbeat's {@code craftOrders.tableWhy}: null while the table is usable, else a code. */
    public static String why(State s) {
        if (usable(s)) return null;
        if (!s.hasArea()) return NO_AREA;
        if (!s.hasDimension()) return NO_DIMENSION;
        if (s.websiteSet()) {
            return switch (s.websiteStatus()) {
                case "other_dimension" -> WRONG_DIMENSION;
                case "unreachable" -> TOO_FAR;
                case "not_table" -> NOT_TABLE;
                default -> s.autoWhy() != null ? s.autoWhy() : NOT_LOADED;
            };
        }
        if (s.autoWhy() != null) return s.autoWhy();
        // Nothing to use: no table known or found, none in the stash, and nothing to make one from. A table set by hand that is out of the limits is
        // what the user would expect to be used, so that is what is said
        return s.handSetFar() ? TOO_FAR : NO_MATERIAL;
    }

    /** The source to report for the bot's own table ({@code found}, {@code placed}, {@code crafted}), or null for a table set by hand or none. */
    public static String botSource(String source) {
        return source != null && BOT_SOURCES.contains(source) ? source : null;
    }
}
