package com.porter.qwerte.craft;

import java.util.List;

/**
 * How many crafting tables the craft-order path may put down: at most {@value #PER_ORDER} per order and {@value #PER_HOUR} an hour. A table
 * counts when it was actually placed (not when it was only planned or taken out of the stash). The hourly stamps are kept in the stash file like
 * {@link CraftFills}. Once the cap is hit the table route says {@code table_blocked}. Pure.
 */
public final class CraftTableCap {
    public static final int PER_ORDER = 1, PER_HOUR = 3;

    private final CraftFills hour = new CraftFills();
    private String order;
    private int inOrder;

    /** A table may be placed now for this order. */
    public synchronized boolean allows(String orderId, long now) {
        int mine = orderId != null && orderId.equals(order) ? inOrder : 0;
        return mine < PER_ORDER && hour.lastHour(now) < PER_HOUR;
    }

    /** A table was placed for this order. */
    public synchronized void placed(String orderId, long now) {
        if (orderId == null || !orderId.equals(order)) {
            order = orderId;
            inOrder = 0;
        }
        inOrder++;
        hour.add(now, 1);
    }

    public synchronized List<long[]> export(long now) {
        return hour.export(now);
    }

    public synchronized void load(List<long[]> saved) {
        hour.load(saved);
    }
}
