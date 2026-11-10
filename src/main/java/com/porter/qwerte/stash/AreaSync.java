package com.porter.qwerte.stash;

/**
 * What a heartbeat reply means for the stash area (AREA-CONTRACT.md "Heartbeat"), decided apart from the world: whether an area the server sent is
 * to be queued. A port of the plugin's {@code AreaSync}. Pure.
 * <ul>
 *     <li>While a change made in game is waiting to be applied, or was applied and the server has not acknowledged it yet, the server's area is
 *     ignored: it may be the one the edit replaces (a reply to an older heartbeat), and the last save wins.</li>
 *     <li>An area equal to the one in use, or to one already waiting, changes nothing.</li>
 *     <li>One that fails the checks is ignored and logged once.</li>
 * </ul>
 */
public final class AreaSync {
    private AreaSync() {
    }

    /**
     * @param queue the area to queue (box null: clear), or null
     * @param warn  a refusal to log (once), or null
     */
    public record Action(AreaPending.Request queue, String warn) {
        static final Action NOTHING = new Action(null, null);
    }

    /**
     * @param inUseHash      the hash of the area the bot uses now
     * @param waiting        the request waiting to be applied, or null
     * @param editedUnacked  a local edit was applied and the server has not acknowledged it
     * @param stashDimension the stash's dimension, or null when not known
     */
    public static Action react(StashIndex.AreaReply r, String inUseHash, AreaPending.Request waiting, boolean editedUnacked, String stashDimension) {
        if (!r.areaPresent()) return Action.NOTHING;
        if (waiting != null && !waiting.website()) return Action.NOTHING;
        if (editedUnacked) return Action.NOTHING;
        if (r.invalid() != null) return new Action(null, r.invalid());
        int[] box = r.area() == null ? null : r.area().box();
        String dimension = r.area() == null ? null : r.area().dimension();
        // Whatever form the server wrote it in, the dimension is kept as its full id
        if (dimension != null && !CraftBounds.fullId(dimension).isEmpty()) dimension = CraftBounds.fullId(dimension);
        String hash = AreaRules.hashOf(box, dimension);
        if (hash.equals(inUseHash)) return Action.NOTHING;
        if (waiting != null && waiting.hash().equals(hash)) return Action.NOTHING;
        if (box != null) {
            String why = AreaRules.validate(box, dimension, stashDimension);
            if (why != null) return new Action(null, why);
        }
        return new Action(new AreaPending.Request(box, dimension, true), null);
    }

    /** A local request's dimension: the one it was queued with if there was one, else the bot's now (null: it cannot be applied yet). */
    public static String resolveDimension(String queued, String current) {
        return queued != null ? queued : current;
    }
}
