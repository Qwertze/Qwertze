package com.porter.qwerte.printer.materials;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which inventory shulker boxes go back into the ender chest after they were taken out of it. Pure.
 *
 * <p>The boxes in the inventory now, minus the ones that were there before the trip (as a multiset, so a box of the player
 * stays even when it looks the same as a taken one), capped at the number taken. A box is told apart by its item and a
 * fingerprint of its components.
 */
public final class EchestReturn {
    private EchestReturn() {}

    /** @param invIndex the inventory slot, 0..35 */
    public record ShulkerSlot(int invIndex, String itemId, String fingerprint) {
        String kind() {
            return itemId + "|" + Objects.toString(fingerprint, "");
        }
    }

    /** The slots to move, lowest slot first, at most {@code takenCount} of them. */
    public static List<ShulkerSlot> slotsToReturn(List<ShulkerSlot> now, List<ShulkerSlot> before, int takenCount) {
        Map<String, Integer> known = new HashMap<>();
        for (ShulkerSlot s : before) known.merge(s.kind(), 1, Integer::sum);
        List<ShulkerSlot> sorted = new ArrayList<>(now);
        sorted.sort((a, b) -> Integer.compare(a.invIndex(), b.invIndex()));
        List<ShulkerSlot> out = new ArrayList<>();
        // The earliest slots of each kind are matched with the boxes that were there before; the later ones are the new ones
        for (ShulkerSlot s : sorted) {
            int left = known.getOrDefault(s.kind(), 0);
            if (left > 0) {
                known.put(s.kind(), left - 1);
                continue;
            }
            if (out.size() < takenCount) out.add(s);
        }
        return out;
    }
}
