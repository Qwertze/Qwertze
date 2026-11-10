package com.porter.qwerte.notify;

import java.util.ArrayList;
import java.util.List;

/** Which stashes to fetch orders for in one poll cycle. Pure. */
public final class PollPlan {
    /** Every this many cycles all stashes are fetched, as a safety net for an order that opened and closed between two polls. */
    public static final int FULL_EVERY = 6;

    private PollPlan() {}

    /**
     * A stash is fetched when it has no baseline yet, when the server says it has open orders (or does not say), when an order
     * there was open at the last look (it may have finished since), and on every {@link #FULL_EVERY}th cycle.
     */
    public static List<String> scopes(List<OrderSnaps.StashEntry> stashes, OrderWatch watch, int cycle) {
        boolean full = cycle % FULL_EVERY == 0;
        List<String> out = new ArrayList<>();
        for (OrderSnaps.StashEntry s : stashes) {
            if (full || !watch.baselined(s.scope()) || s.open() != 0 || watch.hasOpen(s.scope())) out.add(s.scope());
        }
        return out;
    }
}
