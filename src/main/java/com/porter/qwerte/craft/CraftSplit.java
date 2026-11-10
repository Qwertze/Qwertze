package com.porter.qwerte.craft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sorting what a craft chunk's session holds at its end into the load, stock (taken from the stash, or unused ingredients taken over from an
 * earlier chunk) and made surplus (what the steps produced and nothing used). Stock and made are kept apart, whatever the item: a made surplus
 * never offsets a gathered count, and a made surplus taken over by a later chunk is never charged to the budget. Pure.
 * <p>
 * The line's own item can be both gathered (the order's {@code take} may name it: a part of the line is taken from the stash as itself) and
 * made. The load is then the biggest stack of that item the session holds, whether it came from the stash or from the grid (a made stack that
 * was merged onto a gathered one is in a stock slot and would be missed otherwise); if it is a stock slot, what it holds beyond the load goes
 * back ({@link Sorted#lotFromStock}) and is not charged. Of two stacks the same size the made one is the load.
 */
public final class CraftSplit {
    private CraftSplit() {
    }

    /** A carried stack: its inventory slot, item id and count. */
    public record Slot(int slot, String id, int count) {
    }

    /**
     * @param lotSlot      the slot of the stack that is the load, or -1
     * @param lotCount     how many it holds
     * @param lotFromStock the load's slot is a stock slot (gathered, or unused ingredients taken over)
     * @param stock        slot to item id of the other stacks that are stock
     * @param made         slot to item id of the other stacks that were made
     */
    public record Sorted(int lotSlot, int lotCount, boolean lotFromStock, Map<Integer, String> stock, Map<Integer, String> made) {
    }

    /**
     * @param key        the item the load is of
     * @param held       what the session held at its end
     * @param stockSlots the slots the session took stacks from the stash into, and the slots of unused ingredients it took over
     */
    public static Sorted sort(String key, List<Slot> held, Set<Integer> stockSlots) {
        int lot = -1, best = 0;
        boolean fromStock = false;
        for (Slot s : held) {
            if (s.count() <= 0 || !s.id().equals(key)) continue;
            boolean stock = stockSlots.contains(s.slot());
            if (s.count() > best || (s.count() == best && fromStock && !stock)) {
                lot = s.slot();
                best = s.count();
                fromStock = stock;
            }
        }
        Map<Integer, String> stock = new LinkedHashMap<>(), made = new LinkedHashMap<>();
        for (Slot s : held) {
            if (s.count() <= 0 || s.slot() == lot) continue;
            if (stockSlots.contains(s.slot())) stock.put(s.slot(), s.id());
            else made.put(s.slot(), s.id());
        }
        return new Sorted(lot, best, lot >= 0 && fromStock, stock, made);
    }
}
