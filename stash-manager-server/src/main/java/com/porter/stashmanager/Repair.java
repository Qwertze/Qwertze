package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Repair with XP bottles (PROTOCOL.md, "Additions: repair with XP bottles"). One formula shared with the page, the
 * addon and the plugin: a Bottle o' Enchanting gives about 7 XP, Mending repairs 2 durability per XP, so one bottle is
 * about 14 durability.
 */
final class Repair {
    static final int DURABILITY_PER_BOTTLE = 14;
    static final String BOTTLE_KEY = "minecraft:experience_bottle";
    static final String NO_MENDING = "no Mending";
    static final String NOT_DAMAGED = "not damaged";
    static final String NOT_SINGLE = "not a single item";
    /** The reasons the bots use for a skipped item (anything else is hidden for a forced streamer user). */
    static final java.util.Set<String> SKIP_REASONS = java.util.Set.of(NO_MENDING, NOT_DAMAGED, NOT_SINGLE, "no bottles", "interrupted");
    static final int MAX_REASON = 60;

    /** A details entry for Mending: "Mending", or "Mending I" / "Mending 1" (one space, a roman numeral or digits). */
    private static final java.util.regex.Pattern MENDING_DETAIL =
        java.util.regex.Pattern.compile("mending(?: (?:[ivxlcdm]+|[0-9]+))?", java.util.regex.Pattern.CASE_INSENSITIVE);

    private Repair() {
    }

    /** Bottles to repair this much damage: ceil(damage / 14); 0 for no damage. */
    static long bottlesFor(long damage) {
        return damage <= 0 ? 0 : (damage + DURABILITY_PER_BOTTLE - 1) / DURABILITY_PER_BOTTLE;
    }

    /** Whether the index item has Mending: the bot's flag, else (older bots) a details entry "Mending". */
    static boolean hasMending(JsonObject item) {
        JsonElement m = item.get("mending");
        if (m != null && m.isJsonPrimitive() && m.getAsJsonPrimitive().isBoolean()) return m.getAsBoolean();
        JsonElement d = item.get("details");
        if (d != null && d.isJsonArray()) {
            for (JsonElement e : d.getAsJsonArray()) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && MENDING_DETAIL.matcher(e.getAsString().strip()).matches()) return true;
            }
        }
        return false;
    }

    /** What an index item is for repair: the reason it can't be repaired (null when it can) and the bottles for one. */
    record Info(String reason, long bottles, boolean damaged) {
        Info(String reason, long bottles) {
            this(reason, bottles, false);
        }

        boolean repairable() {
            return reason == null;
        }
    }

    /** The index item's repair info (maxStack: the page's rule, 64 when missing). */
    static Info of(JsonObject item) {
        double maxStack = 64;
        JsonElement ms = item.get("maxStack");
        if (ms != null && ms.isJsonPrimitive() && ms.getAsJsonPrimitive().isNumber()) maxStack = Math.max(1, Math.floor(ms.getAsDouble()));
        long damage = 0;
        JsonElement dm = item.get("damage");
        if (dm != null && dm.isJsonPrimitive() && dm.getAsJsonPrimitive().isNumber()) {
            double d = dm.getAsDouble();
            if (d > 0 && !Double.isInfinite(d)) damage = (long) Math.min(d, 1_000_000_000d);
        }
        boolean damaged = damage > 0;
        JsonElement id = item.get("id");
        if (id != null && id.isJsonPrimitive() && id.getAsString().startsWith("minecraft:") && id.getAsString().endsWith("shulker_box")) {
            return new Info(NOT_SINGLE, 0, damaged);
        }
        if (!hasMending(item)) return new Info(NO_MENDING, 0, damaged);
        if (!damaged) return new Info(NOT_DAMAGED, 0, false);
        if (maxStack != 1) return new Info(NOT_SINGLE, 0, true);
        return new Info(null, bottlesFor(damage), true);
    }
}
