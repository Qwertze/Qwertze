package com.porter.qwerte.stash;

/**
 * Where the website's crafting table may be: in the stash's dimension and within a margin of the stash area, with a height the game
 * allows. With no area or no recorded dimension there is no limit to check against, so the table is refused. Pure. A port of the plugin's
 * {@code CraftBounds} (the margin is 64 blocks for both), with the addon's dimension strings understood ({@code ResourceKey[minecraft:dimension / minecraft:overworld]}).
 */
public final class CraftBounds {
    private CraftBounds() {
    }

    /** The most blocks outside the stash area the table may be (CRAFT-CONTRACT.md "Craft settings"). */
    public static final int MARGIN = 64;
    /** The height range a table may have. */
    public static final int MIN_Y = -64, MAX_Y = 320;

    /** The heartbeat state of a table whose chunk is not loaded now: "ok" when it was seen standing before, else "unloaded" (never checked). */
    public static String unloadedStatus(boolean seenBefore) {
        return seenBefore ? "ok" : "unloaded";
    }

    /** "ResourceKey[minecraft:dimension / minecraft:overworld]" (the addon's own form) or "minecraft:overworld" as "minecraft:overworld"; blank stays blank. */
    public static String normalise(String dimension) {
        if (dimension == null) return "";
        String d = dimension.trim();
        int slash = d.lastIndexOf(" / ");
        if (slash >= 0) d = d.substring(slash + 3).replace("]", "").trim();
        return d;
    }

    /** The full id of a dimension: "minecraft:" is the namespace of a bare name; the addon's own form is unwrapped; blank stays blank. */
    public static String fullId(String dimension) {
        String d = normalise(dimension);
        if (d.isEmpty()) return "";
        return d.indexOf(':') < 0 ? "minecraft:" + d : d;
    }

    /** Two dimensions are the same when their full ids are ("overworld" is "minecraft:overworld"; "other:overworld" is not). Case-insensitive. */
    public static boolean sameDimension(String a, String b) {
        if (a == null || b == null) return false;
        String x = fullId(a), y = fullId(b);
        return !x.isEmpty() && x.equalsIgnoreCase(y);
    }

    /**
     * @param stashDimension the dimension the stash was recorded in, or null/blank
     * @param bounds         minX, minY, minZ, maxX, maxY, maxZ of the stash area, or null when it has none
     * @return true when the table must not be used
     */
    public static boolean outOfBounds(String stashDimension, int[] bounds, CraftSettings.Table t, int margin) {
        if (stashDimension == null || stashDimension.isBlank() || bounds == null || bounds.length < 6) return true;
        if (!sameDimension(stashDimension, t.dimension())) return true;
        if (t.y() < MIN_Y || t.y() > MAX_Y) return true;
        return t.x() < bounds[0] - margin || t.x() > bounds[3] + margin || t.y() < bounds[1] - margin || t.y() > bounds[4] + margin
            || t.z() < bounds[2] - margin || t.z() > bounds[5] + margin;
    }

    /** As above with the contract's margin. */
    public static boolean outOfBounds(String stashDimension, int[] bounds, CraftSettings.Table t) {
        return outOfBounds(stashDimension, bounds, t, MARGIN);
    }
}
