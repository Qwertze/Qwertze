package com.porter.qwerte.stash;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The kit score of one shulker box (KIT-CONTRACT.md "Kit score"): points for a kit-like name, an item mix, a full box and OP items;
 * a box is a kit when the total reaches the threshold. Pure: the slot contents are item ids, so it needs no game.
 */
public final class KitScore {
    private KitScore() {
    }

    /** The slots of a shulker box. */
    public static final int SLOTS = 27;

    /** @param kit total >= threshold, not blocked by the single-type rule, and not an empty box */
    public record Result(int name, int mix, int full, int op, int total, boolean kit) {
        static final Result NONE = new Result(0, 0, 0, 0, 0, false);
    }

    /** One slot: the item id ("minecraft:totem_of_undying") and its stack count; a null or empty id is an empty slot. */
    public record Slot(String id, int count) {
    }

    /**
     * @param slots      the slots of the box, normally 27
     * @param customName the plain custom name, null when the box has none
     */
    public static Result score(List<Slot> slots, String customName, KitSettings.Scoring scoring, KitItems items) {
        int occupied = 0;
        Set<String> ids = new HashSet<>();
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        for (Slot slot : slots) {
            String id = slot.id();
            if (id == null || id.isEmpty()) continue;
            occupied++;
            ids.add(id);
            counts.merge(id, (long) Math.max(1, slot.count()), Long::sum);
        }
        boolean full = occupied >= SLOTS;

        int name = KitNames.isKit(KitNames.normalize(customName)) ? scoring.name() : 0;
        int mix = 0;
        if (!ids.isEmpty()) {
            for (KitItems.Group g : items.groups()) {
                int have = 0;
                for (String id : ids) if (g.items().contains(id)) have++;
                if (have >= g.min()) {
                    mix = scoring.mix();
                    break;
                }
            }
        }
        int fullPoints = full ? scoring.full() : 0;
        // OP items are counted per item: the sum of the stack counts of every listed id must reach opMin
        long opItems = 0;
        for (String id : ids) if (items.op().contains(id)) opItems += counts.get(id);
        int op = opItems >= scoring.opMin() ? scoring.op() : 0;
        int total = name + mix + fullPoints + op;
        // An empty box is never a kit; a box of one item is only one when it is full
        boolean kit = occupied > 0 && total >= scoring.threshold() && !(ids.size() == 1 && !full);
        return new Result(name, mix, fullPoints, op, total, kit);
    }
}
