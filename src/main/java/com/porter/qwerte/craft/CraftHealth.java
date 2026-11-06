package com.porter.qwerte.craft;

import java.util.Objects;

/**
 * Whether the crafting table is worth another trip for this order: a table that could not be reached or opened, gave no
 * result in time, or never gave the result up is remembered as trouble for the order and that table (later crafts of the
 * order are refused at once with the same reason). A table that closed on the bot twice in a row counts too, a single close
 * does not. A new order or another table starts afresh. Pure, modelled on the proxy plugin's CraftHealth.
 */
public final class CraftHealth {
    public static final int CLOSED_IN_A_ROW = 2;

    public static final String NOT_REACHED = "couldn't reach the crafting table", NOT_OPENED = "couldn't open the crafting table",
        CLOSED = "the crafting table closed before it was done", NO_RESULT = "no result from the crafting table in time",
        NOT_TAKEN = "the crafting table didn't give the result", NO_ROOM = "no room in the inventory for what was crafted",
        MISSING = "the inputs aren't all in the inventory", GRID = "the grid didn't fill as it should";

    private String failure, order;
    private Object table;
    private int closed;
    private String closedOrder;
    private Object closedTable;

    /** Takes note of how a trip to this table for this order ended: {@code why} is null when it went through. */
    public void note(String orderId, Object tableSpot, String why) {
        if (!Objects.equals(orderId, closedOrder) || !Objects.equals(tableSpot, closedTable)) {
            closed = 0;
            closedOrder = orderId;
            closedTable = tableSpot;
        }
        if (why == null) {
            closed = 0;
            return;
        }
        boolean trouble = NOT_REACHED.equals(why) || NOT_OPENED.equals(why) || NO_RESULT.equals(why) || NOT_TAKEN.equals(why);
        if (CLOSED.equals(why)) trouble = ++closed >= CLOSED_IN_A_ROW;
        if (!trouble) return;
        failure = why;
        order = orderId;
        table = tableSpot;
    }

    /** The table trouble remembered for this order and table, or null. */
    public String problem(String orderId, Object tableSpot) {
        return failure != null && orderId != null && Objects.equals(orderId, order) && Objects.equals(tableSpot, table) ? failure : null;
    }
}
