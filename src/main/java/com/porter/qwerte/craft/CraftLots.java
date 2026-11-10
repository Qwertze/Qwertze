package com.porter.qwerte.craft;

import com.porter.qwerte.stash.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Planning the craft side of an order (CRAFT-CONTRACT.md "Bot execution", steps 1 and 2): the check made before any click, the ledger, and
 * the craft lines as loads that join the packer. Pure.
 * <ul>
 *     <li>Any failure of {@link CraftOrder#check} (or more than {@link CraftOrder#MAX_LOTS} loads) makes every craft line short with
 *     {@code recipe_mismatch} and nothing is crafted; the rest of the order goes on.</li>
 *     <li>Each craft line is cut into loads of a stack ({@link Order#expand}), marked {@link Order.Lot#PENDING}.</li>
 * </ul>
 */
public final class CraftLots {
    private CraftLots() {
    }

    /**
     * @param ledger  the order's craft ledger (refused at the start when {@code refusal} is set)
     * @param lots    the craft loads, in line order (empty when refused)
     * @param refusal why nothing is crafted, or null
     */
    public record Prepared(CraftLedger ledger, List<Order.Lot> lots, String refusal) {
        public boolean refused() {
            return refusal != null;
        }
    }

    /** Null when the order has no craft lines. */
    public static Prepared prepare(Order order, Recipes table, int maxFills, ToIntFunction<String> stackOf) {
        List<Order.Line> lines = order.craftLines();
        if (lines.isEmpty()) return null;
        String bad = CraftOrder.check(order.craft, table, maxFills);
        List<Order.Lot> lots = new ArrayList<>();
        if (bad == null) {
            long loads = 0;
            for (Order.Line l : lines) {
                int stack = Math.max(1, stackOf.applyAsInt(l.key()));
                loads += (l.count() + (long) stack - 1) / stack;
                if (loads > CraftOrder.MAX_LOTS) {
                    bad = loads + " loads are more than the " + CraftOrder.MAX_LOTS + " one order may make";
                    break;
                }
            }
        }
        if (bad != null) return new Prepared(CraftLedger.refused(lines, table, maxFills, CraftOrder.RECIPE_MISMATCH), List.of(), bad);
        for (Order.Line l : lines) {
            for (Order.Lot lot : Order.expand(l.key(), l.count(), Math.max(1, stackOf.applyAsInt(l.key())))) lots.add(Order.Lot.toCraft(lot.key(), lot.count()));
        }
        return new Prepared(new CraftLedger(order.craft, lines, table, maxFills), lots, null);
    }

    /**
     * A save from before craft states were kept (or a damaged one), found by its content: a plain (state none) load of an item that only a craft
     * line asks for, in a save that has a craft ledger. Such a load must never go down the plain-item path.
     */
    public static boolean oldFormatLot(int state, String key, java.util.Set<String> craftOnlyKeys, boolean hasLedger) {
        return state == Order.Lot.NONE && hasLedger && craftOnlyKeys.contains(key);
    }

    /**
     * The next load to make among a bin's: the one whose line comes first in the order (the server planned the lines in this order, so the
     * bot crafts in it), then the first in the bin. -1 when none is left to make.
     */
    public static int next(List<Order.Lot> lots, CraftLedger ledger) {
        int best = -1, bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < lots.size(); i++) {
            Order.Lot lot = lots.get(i);
            if (!lot.craft() || lot.count() <= 0) continue;
            int rank = ledger.rank(lot.key());
            if (best < 0 || rank < bestRank) {
                best = i;
                bestRank = rank;
            }
        }
        return best;
    }
}
