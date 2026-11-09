package com.porter.qwerte.printer.materials;

import java.util.List;
import java.util.Map;

/**
 * Which stack to shift-click out of an open container next, and whether the inventory has room for it. Pure.
 *
 * <p>A stack is taken when it is plain stock of an item that is still wanted (or a shulker box holding some), the inventory
 * has room for it, and it has not been clicked too often without moving (the server refused or the stack did not fit).
 * The click count is per slot index of the open menu, so it must be cleared whenever another container is opened.
 */
public final class TakeRules {
    private TakeRules() {}

    public static final int MAX_TRIES = 3;

    /**
     * @param index   the slot id in the menu
     * @param itemId  the item id of the stack in it
     * @param plain   the stack has no custom data (stock that places as the default block)
     * @param box     a shulker box that holds something wanted
     * @param room    the inventory can take it
     */
    public record Slot(int index, String itemId, boolean plain, boolean box, boolean room) {}

    /**
     * @param slot    the menu slot to shift-click, or -1
     * @param done    nothing more can be taken from this container
     * @param blocked something wanted was left because the inventory had no room
     */
    public record Decision(int slot, boolean done, boolean blocked) {}

    /** Picks the next slot, counting a click against it in {@code tries}. */
    public static Decision next(List<Slot> slots, Map<String, Long> want, Map<Integer, Integer> tries) {
        return next(slots, want, tries, Integer.MAX_VALUE);
    }

    /**
     * As {@link #next(List, Map, Map)}, but a shulker box is taken only while {@code boxBudget} is above zero (the caller
     * counts down as boxes come into the inventory). Plain stock is not limited by it.
     */
    public static Decision next(List<Slot> slots, Map<String, Long> want, Map<Integer, Integer> tries, int boxBudget) {
        boolean blocked = false;
        for (Slot s : slots) {
            boolean material = s.plain() && want.getOrDefault(s.itemId(), 0L) > 0;
            boolean box = s.box() && !material && boxBudget > 0;
            if (!material && !box) continue;
            if (tries.getOrDefault(s.index(), 0) >= MAX_TRIES) continue;
            if (!s.room()) {
                blocked = true;
                continue;
            }
            tries.merge(s.index(), 1, Integer::sum);
            return new Decision(s.index(), false, false);
        }
        return new Decision(-1, true, blocked);
    }

    /**
     * Whether a stack can come into the inventory without using up the reserve. It merges into stacks of the same item first
     * ({@code mergeable} is the room they have); only what does not fit there spills into empty slots, one per
     * {@code maxStack}. After that, {@code reserve} empty slots must still be left, so a shulker box can always come back.
     */
    public static boolean room(int incoming, int maxStack, int mergeable, int emptySlots, int reserve) {
        int spill = Math.max(0, incoming - Math.max(0, mergeable));
        int slotsNeeded = maxStack <= 0 ? 0 : (spill + maxStack - 1) / maxStack;
        return emptySlots - slotsNeeded >= reserve;
    }
}
