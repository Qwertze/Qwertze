package com.porter.qwerte.tweaks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Picks the item that stands for a container's contents. Pure, so it is tested without a game.
 *
 * <p>Items are grouped by key. The winner has the highest total count; ties go to the key spread over more stacks, then
 * to the one whose first stack comes first. The representative is the first stack of the winner.
 */
public final class MostCommon {
    private MostCommon() {}

    private static final class Tally {
        final int first;
        int total, stacks;

        Tally(int first) {
            this.first = first;
        }
    }

    /**
     * @param keys   what each slot holds, null for an empty slot
     * @param counts how many are in each slot, a count of 0 or less counts as empty
     * @return the index of the representative slot, or -1 if every slot is empty
     */
    public static <K> int pick(K[] keys, int[] counts) {
        Map<K, Tally> tallies = new LinkedHashMap<>();
        int n = Math.min(keys.length, counts.length);

        for (int i = 0; i < n; i++) {
            if (keys[i] == null || counts[i] <= 0) continue;
            Tally tally = tallies.get(keys[i]);
            if (tally == null) {
                tally = new Tally(i);
                tallies.put(keys[i], tally);
            }
            tally.total += counts[i];
            tally.stacks++;
        }

        Tally best = null;
        for (Tally tally : tallies.values()) {
            if (best == null || tally.total > best.total
                || tally.total == best.total && (tally.stacks > best.stacks || tally.stacks == best.stacks && tally.first < best.first)) {
                best = tally;
            }
        }
        return best == null ? -1 : best.first;
    }
}
