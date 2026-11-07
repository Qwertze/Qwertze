package com.porter.qwerte.pearl;

import java.util.function.IntFunction;

/**
 * Plans taking an elytra out of the chest slot and putting it back. Slots are the player inventory menu ids: 6 is the
 * chest armour slot, 9 to 35 the main inventory, 36 to 44 the hotbar. The offhand is never used.
 */
public final class ElytraSwap {
    public static final int CHEST = 6, FIRST = 9, MAIN_END = 35, LAST = 44;

    public enum Kind { EMPTY, ELYTRA, CHESTPLATE, OTHER }

    public enum Action { NONE, SWAP_CHESTPLATE, MOVE_FREE, NO_ROOM }

    public record Plan(Action action, int slot) {}

    /** What was done, so it can be undone: the slot the elytra went to, and the chest piece that came out of it. */
    public record Swapped(int slot, boolean withChestplate, String chestplateId) {}

    public enum Restore { RESTORE, DONE, LOST, CHANGED }

    private ElytraSwap() {}

    /**
     * @param chest  what is in the chest slot
     * @param kindOf the kind of the item in a menu slot id (9 to 44)
     */
    public static Plan plan(Kind chest, IntFunction<Kind> kindOf) {
        // Nothing to do unless the elytra is worn (one in the offhand or the inventory glides nobody)
        if (chest != Kind.ELYTRA) return new Plan(Action.NONE, -1);
        for (int id = FIRST; id <= LAST; id++) {
            if (kindOf.apply(id) == Kind.CHESTPLATE) return new Plan(Action.SWAP_CHESTPLATE, id);
        }
        // Main inventory before the hotbar: the hotbar is where the pearl and the held slot live
        for (int id = FIRST; id <= LAST; id++) {
            if (kindOf.apply(id) == Kind.EMPTY) return new Plan(Action.MOVE_FREE, id);
        }
        return new Plan(Action.NO_ROOM, -1);
    }

    /** Whether the elytra can go back, after checking that both slots still hold what we left. */
    public static Restore restore(Swapped swapped, Kind chest, String chestId, Kind slot) {
        if (chest == Kind.ELYTRA) return Restore.DONE;
        if (slot != Kind.ELYTRA) return Restore.LOST;
        if (swapped.withChestplate()) {
            return chest == Kind.CHESTPLATE && swapped.chestplateId().equals(chestId) ? Restore.RESTORE : Restore.CHANGED;
        }
        return chest == Kind.EMPTY ? Restore.RESTORE : Restore.CHANGED;
    }
}
