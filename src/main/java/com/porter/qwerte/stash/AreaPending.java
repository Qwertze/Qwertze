package com.porter.qwerte.stash;

/**
 * The area waiting to be applied (AREA-CONTRACT.md "Bots: applying an area"): from the website or from a local edit (the corners, the window's
 * area card). Anything may queue one; only the sorter's tick applies it, between tasks. Also the edit made in game that the server has not taken
 * yet: the hash of the edited area is kept, and only an acknowledgement of that very hash ends it, so an older reply cannot. A port of the
 * plugin's {@code AreaPending}, as an object the sorter owns. Every method is synchronized.
 *
 * <p>A request and an edit belong to the stash they were made for (its server and name, the {@code key}): asked about another stash they are
 * simply not there, and {@link #dropOthers} forgets them.
 */
public final class AreaPending {
    /** minX, minY, minZ, maxX, maxY, maxZ, or null to clear the area; {@code dimension} may be null for a local edit (resolved when applied). */
    public static final class Request {
        private final int[] box;
        private final String dimension;
        private final boolean website;
        private String hash;

        public Request(int[] box, String dimension, boolean website) {
            this.box = box;
            this.dimension = dimension;
            this.website = website;
        }

        public int[] box() {
            return box;
        }

        public String dimension() {
            return dimension;
        }

        public boolean website() {
            return website;
        }

        /** The area's hash (SHA-1), made once. */
        public synchronized String hash() {
            if (hash == null) hash = AreaRules.hashOf(box, dimension);
            return hash;
        }
    }

    private Request pending;
    private String pendingKey;
    private String why = AreaRules.BUSY;
    /** The edits made in game that the server has not acknowledged, by stash: they outlive a stop, a disconnect and a change of stash. */
    private final java.util.Map<String, String> edited = new java.util.HashMap<>();
    private final AreaHeld held = new AreaHeld();

    /** The leftovers timer (reset whenever a request is queued or dropped). */
    public AreaHeld held() {
        return held;
    }

    public synchronized void queue(Request r, String key) {
        why = AreaRules.BUSY;
        pending = r;
        pendingKey = key;
        held.reset();
    }

    /** The request waiting for this stash, or null (none, or it was made for another stash). */
    public synchronized Request get(String key) {
        return pending != null && key != null && key.equals(pendingKey) ? pending : null;
    }

    /** The request that was applied is removed, but only if it is still the one waiting. @return true when it was removed */
    public synchronized boolean clear(Request applied) {
        if (pending != applied) return false;
        pending = null;
        pendingKey = null;
        why = AreaRules.BUSY;
        held.reset();
        return true;
    }

    /** Drops whatever is waiting (nothing is applying it any more). */
    public synchronized void discard() {
        pending = null;
        pendingKey = null;
        why = AreaRules.BUSY;
        held.reset();
    }

    /**
     * The waiting request is forgotten (the module stopped, or the world was left). An edit the server has not acknowledged is kept: it is still
     * the area, and the server's older one must not replace it when the bot is back.
     */
    public synchronized void reset() {
        discard();
    }

    /** Forgets a request made for a stash other than this one; the unacknowledged edits stay with their stash. */
    public synchronized void dropOthers(String key) {
        if (pending != null && (key == null || !key.equals(pendingKey))) discard();
    }

    /** Why the queued area waits: busy, far or dimension. */
    public synchronized String why() {
        return why;
    }

    public synchronized void setWhy(String w) {
        why = w;
    }

    /** An area was changed in game and applied: it is reported as {@code edited} until the server acknowledges this very hash. */
    public synchronized void markEdited(String hash, String key) {
        if (key != null && hash != null) edited.put(key, hash);
    }

    public synchronized boolean edited(String key) {
        return key != null && edited.containsKey(key);
    }

    public synchronized String editedHash(String key) {
        return key == null ? null : edited.get(key);
    }

    /** A reply carried this areaHash. @return true when it was the awaited one (for this stash) and the edit has ended */
    public synchronized boolean acknowledge(String key, String replyHash) {
        if (!edited(key) || replyHash == null || !edited.get(key).equals(replyHash)) return false;
        edited.remove(key);
        return true;
    }
}
