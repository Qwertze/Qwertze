package com.porter.qwerte.manager.model;

import com.porter.qwerte.stash.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns cart lines into the shulkers an order delivers, exactly as the website's planCart does and as the bot packs it
 * ({@link Order#expand} and {@link Order#pack} are the one algorithm): packed-shulker lines first, then empty shulker
 * lines, then the loaded bins. Pure.
 */
public final class CartPlan {
    public enum Kind { SHULKER, EMPTY, BIN }

    /** One shulker of the delivery. {@code lots} is only filled for a BIN. */
    public record Unit(Kind kind, String key, List<Order.Lot> lots) {
        /** Slots in use in the shulker's 27 (the website's bar). */
        public int used() {
            return kind == Kind.BIN ? lots.size() : 0;
        }
    }

    public static final int MAX_SHULKERS = Order.MAX_BINS;
    public static final int SLOTS = Order.SLOTS;

    private CartPlan() {
    }

    /** An item line whose item is a shulker box means empty shulkers of that kind. */
    public static boolean isEmptyShulkerLine(Order.Line l, IndexModel index) {
        if (!Order.ITEM.equals(l.type())) return false;
        IndexModel.Item item = index.item(l.key());
        return item != null && item.isShulkerBox();
    }

    static int maxStack(String key, IndexModel index) {
        IndexModel.Item item = index.item(key);
        return item == null ? 64 : item.maxStack();
    }

    /** Shulkers the lines need, without building the plan (safe for huge counts). */
    public static long shulkersNeeded(List<Order.Line> lines, IndexModel index) {
        long units = 0;
        long slots = 0;
        for (Order.Line l : lines) {
            if (Order.SHULKER.equals(l.type()) || isEmptyShulkerLine(l, index)) {
                units += l.count();
            } else {
                int max = Math.max(1, maxStack(l.key(), index));
                slots += (l.count() + max - 1L) / max;
            }
        }
        return units + (slots + SLOTS - 1) / SLOTS;
    }

    /** The planned delivery in chest order, or null when it needs more than {@link #MAX_SHULKERS} shulkers. */
    public static List<Unit> plan(List<Order.Line> lines, IndexModel index) {
        if (shulkersNeeded(lines, index) > MAX_SHULKERS) return null;
        List<Unit> shulkers = new ArrayList<>();
        List<Unit> empties = new ArrayList<>();
        List<Order.Lot> lots = new ArrayList<>();
        for (Order.Line l : lines) {
            if (Order.SHULKER.equals(l.type())) {
                for (int i = 0; i < l.count(); i++) shulkers.add(new Unit(Kind.SHULKER, l.key(), List.of()));
            } else if (isEmptyShulkerLine(l, index)) {
                for (int i = 0; i < l.count(); i++) empties.add(new Unit(Kind.EMPTY, l.key(), List.of()));
            } else {
                lots.addAll(Order.expand(l.key(), l.count(), maxStack(l.key(), index)));
            }
        }
        List<Unit> out = new ArrayList<>(shulkers);
        out.addAll(empties);
        for (List<Order.Lot> bin : Order.pack(lots)) out.add(new Unit(Kind.BIN, "", List.copyOf(bin)));
        return out;
    }
}
