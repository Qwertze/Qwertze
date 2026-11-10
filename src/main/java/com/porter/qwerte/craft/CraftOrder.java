package com.porter.qwerte.craft;

import com.porter.qwerte.stash.Order;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The checks a bot makes before it clicks anything for a craft order (CRAFT-CONTRACT.md "Bot execution", step 1), and the failure
 * reasons the bots report. Pure. A port of the plugin's {@code CraftOrder} (1.15.0), so both bots accept and refuse the same orders.
 * <p>
 * The check: {@code craft.v == 1}; {@code craft.recipes} is the hash of the bot's own recipe table; every step's recipe is in that table
 * with the same result and count; every input id is accepted by some cell of that recipe; the total grid fills are within the bot's cap;
 * every {@code take} id (and every craft line key) is a plain item id, never one with a component patch ({@code id#hash}), because the
 * server counts plain keys. Any failure makes every craft line short with {@link #RECIPE_MISMATCH} and nothing is crafted; the rest of
 * the order goes on.
 */
public final class CraftOrder {
    private CraftOrder() {
    }

    /**
     * Whether this build can carry out the craft orders it checks: gather the order's {@code take} from the stash, craft the steps at the
     * website's table, build the bins from the results and report {@code result.craft} (CRAFT-CONTRACT.md "Bot execution", points 2 to 8). The
     * runner is {@code OrderRunner} (planning, the per-bin chunks, the ledger, the end-of-order wait) with {@code StashSorter} and {@code Crafter}
     * (the table, the window). {@link CraftCapability} turns this and the settings into what the claim and the heartbeat say; with it false the
     * server would never give this bot a craft order.
     */
    public static final boolean EXECUTES = true;

    /** The failure reasons (CRAFT-CONTRACT.md "Result from the bot"). */
    public static final String NO_TABLE = "no_table", TABLE_MISSING = "table_missing", TABLE_BLOCKED = "table_blocked",
        INGREDIENTS_SHORT = "ingredients_short", INVENTORY_FULL = "inventory_full", RECIPE_MISMATCH = "recipe_mismatch", GRID_STUCK = "grid_stuck",
        TIMEOUT = "timeout", INTERRUPTED = "interrupted", CAP = "cap", CANCELLED = "cancelled";

    public static final Set<String> REASONS = Set.of(NO_TABLE, TABLE_MISSING, TABLE_BLOCKED, INGREDIENTS_SHORT, INVENTORY_FULL, RECIPE_MISMATCH,
        GRID_STUCK, TIMEOUT, INTERRUPTED, CAP, CANCELLED);

    /** A plain item id: namespaced, no component patch. */
    private static final Pattern PLAIN = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");

    /** The most one craft line, and all of them together, may ask for; and the most loads they are cut into. */
    public static final int MAX_LINE = 100_000, MAX_TOTAL = 1_000_000, MAX_LOTS = 4096;

    public static boolean plain(String id) {
        return id != null && PLAIN.matcher(id).matches();
    }

    /** What a reason means, for the order's note and the log. */
    public static String describe(String reason) {
        return switch (reason) {
            case NO_TABLE -> "no crafting table is set for this stash";
            case TABLE_MISSING -> "the block at the crafting table is not a crafting table";
            case TABLE_BLOCKED -> "the crafting table could not be reached or opened";
            case INGREDIENTS_SHORT -> "not enough ingredients";
            case INVENTORY_FULL -> "no room to carry what is needed";
            case RECIPE_MISMATCH -> "the order's recipes are not the bot's";
            case GRID_STUCK -> "the crafting grid would not clear";
            case TIMEOUT -> "crafting took too long";
            case INTERRUPTED -> "interrupted";
            case CAP -> "a crafting cap was reached";
            case CANCELLED -> "cancelled";
            default -> reason;
        };
    }

    /**
     * @param table    the bot's own recipe table (whose hash the order must name)
     * @param maxFills the most grid fills the bot makes for one order
     * @return null when the order can be carried out, else a short description of what is wrong (the reason is {@link #RECIPE_MISMATCH})
     */
    public static String check(Order.Craft craft, Recipes table, int maxFills) {
        if (craft == null) return "the order has craft lines but no craft block";
        if (craft.malformed()) return "the craft block can't be read";
        if (craft.v() != 1) return "craft version " + craft.v() + " is not 1";
        if (!craft.recipes().equals(table.hash())) return "the order was planned with another recipe table";
        if (craft.steps().size() > Order.MAX_CRAFT_STEPS || craft.take().size() > Order.MAX_CRAFT_TAKE) return "too many steps or ingredients";
        long fills = 0;
        for (Order.CraftStep s : craft.steps()) {
            Recipes.Recipe r = table.byId(s.recipe());
            if (r == null) return "recipe " + s.recipe() + " is not in the table";
            if (!r.result().equals(s.result()) || r.count() != s.count()) return "recipe " + s.recipe() + " makes something else here";
            if (!CraftSub.eligible(r)) return "recipe " + s.recipe() + " is not one a craft order may use";
            if (s.fills() < 1) return "recipe " + s.recipe() + " has no fills";
            fills += s.fills();
            for (Order.Qty in : s.inputs()) {
                if (in.count() < 1 || !plain(in.id())) return "recipe " + s.recipe() + " has a bad input";
                if (!r.accepts(in.id())) return "recipe " + s.recipe() + " takes no " + in.id();
            }
        }
        if (fills > maxFills) return fills + " grid fills are more than the " + maxFills + " this bot makes";
        for (Order.Qty t : craft.take()) if (t.count() < 1 || !plain(t.id())) return "a take id is not a plain item id";
        long total = 0;
        Map<String, Long> made = new HashMap<>(), taken = new HashMap<>();
        for (Order.CraftStep s : craft.steps()) made.merge(s.result(), (long) s.count() * s.fills(), Long::sum);
        for (Order.Qty t : craft.take()) taken.merge(t.id(), (long) t.count(), Long::sum);
        for (Order.Qty l : craft.lines()) {
            if (l.count() < 1 || !plain(l.id())) return "a craft line key is not a plain item id";
            if (l.count() > MAX_LINE) return "a craft line asks for " + l.count() + ", more than " + MAX_LINE;
            total += l.count();
            // Every line is covered by the steps that make it (or by what the order takes of it from the stash)
            if (made.getOrDefault(l.id(), 0L) + taken.getOrDefault(l.id(), 0L) < l.count()) return "the steps don't make " + l.count() + " of " + l.id();
        }
        if (total > MAX_TOTAL) return "the craft lines ask for " + total + " in all, more than " + MAX_TOTAL;
        return null;
    }

    /**
     * The reason code for a craft session that ended with this failure text (the words of {@link CraftHealth} and of the sorter and the
     * crafter). Mirrors the plugin's {@code CraftOrder.reasonOf}, with the addon's own failure words.
     * @param stuck     items are left in the grid with nowhere to go
     * @param cancelled the order was cancelled
     */
    public static String reasonOf(String failure, boolean stuck, boolean cancelled) {
        if (cancelled) return CANCELLED;
        if (stuck) return GRID_STUCK;
        if (failure == null) return INTERRUPTED;
        if (failure.equals(CraftHealth.NOT_REACHED) || failure.equals(CraftHealth.NOT_OPENED) || failure.contains("another dimension")) return TABLE_BLOCKED;
        if (failure.contains("website's crafting table isn't one") || failure.equals("the crafting table is gone")) return TABLE_MISSING;
        if (failure.contains("no crafting table")) return NO_TABLE;
        if (failure.equals(CraftHealth.NO_ROOM) || failure.contains("carry room") || failure.contains("no free inventory slots")) return INVENTORY_FULL;
        if (failure.startsWith(CraftHealth.MISSING) || failure.contains("not enough") || failure.contains("left (")) return INGREDIENTS_SHORT;
        if (failure.equals(CraftHealth.GRID)) return GRID_STUCK;
        if (failure.equals(CraftHealth.NO_RESULT) || failure.equals(CraftHealth.NOT_TAKEN) || failure.equals("it took too long") || failure.contains("too many trips")) return TIMEOUT;
        return INTERRUPTED;
    }
}
