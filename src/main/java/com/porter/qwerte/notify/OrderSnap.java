package com.porter.qwerte.notify;

import java.util.List;

/**
 * One of the user's own orders as the notifier needs it.
 *
 * @param status  the server status ("pending", "accepted", "in_progress", "delivered", "failed", "cancelled")
 * @param phase   the bot's phase text, "" when none
 * @param message the bot's note, "" when none (already free of control characters)
 * @param shorts  what was not delivered, for a delivered order; empty otherwise
 */
public record OrderSnap(String id, String status, String phase, String message, List<Short> shorts) {
    /** An item that came up short. {@code count} is 0 when the amount is not known. */
    public record Short(String key, int count) {}

    public OrderSnap {
        shorts = List.copyOf(shorts);
    }

    public boolean finished() {
        return status.equals("delivered") || status.equals("failed") || status.equals("cancelled");
    }

    /** The final outcome, or null while the order is open. */
    public Kind outcome() {
        return switch (status) {
            case "delivered" -> shorts.isEmpty() ? Kind.COMPLETE : Kind.PARTIAL;
            case "failed" -> Kind.FAILED;
            case "cancelled" -> Kind.CANCELLED;
            default -> null;
        };
    }

    /** The bot is on the delivery trip: in progress with a phase that starts with "delivering" ("delivering 2/5"). */
    public boolean delivering() {
        return status.equals("in_progress") && phase.regionMatches(true, 0, "delivering", 0, "delivering".length());
    }
}
