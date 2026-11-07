package com.porter.qwerte.stash;

/**
 * An order waiting for room in the delivery storage. When every delivery chest is full and none can be put up (or auto
 * expand is off) the order is paused, not ended; it goes on once there is room again, and ends after {@link #TIMEOUT_MS}.
 * Pure (times are the caller's), so the wording and the bound are tested. The same as the proxy plugin's OrderFlow does.
 *
 * The bound is 2 hours, longer than the 10 minutes a held-up bot is given: what is waited for is a player claiming an earlier
 * order, which takes longer than a queue or a player taking the controls; and not unbounded, so a forgotten full chest does not
 * hold the bot (and every order after this one) for ever. The finished shulker stays in the inventory throughout and goes back
 * to the stash when the order ends.
 */
public final class SpaceWait {
    public static final long TIMEOUT_MS = 2 * 60 * 60 * 1000L;
    public static final String PREFIX = "waiting for delivery space: ";

    /** @param phase the order's phase while it waits ("paused: waiting for delivery space: ..."); @param endMessage the end of the order when the wait ran out, else null */
    public record Decision(String phase, String endMessage) {
        public boolean timedOut() {
            return endMessage != null;
        }
    }

    private long since;

    /**
     * @return null when there is room (carry on: the wait, if there was one, is over), else what to do
     * @param delivered shulkers delivered so far; @param total shulkers in the order
     */
    public Decision step(long now, DeliveryRoom room, int delivered, int total) {
        if (room.kind() != DeliveryRoom.Kind.WAIT) {
            since = 0;
            return null;
        }
        String why = room.why() == null ? "delivery storage full" : room.why();
        if (since == 0) since = now;
        else if (now - since > TIMEOUT_MS) {
            return new Decision(null, "delivery container full (" + delivered + "/" + total + "): waited " + (TIMEOUT_MS / 60_000) + " min for space, " + why);
        }
        return new Decision("paused: " + PREFIX + why, null);
    }

    public void reset() {
        since = 0;
    }
}
