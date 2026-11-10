package com.porter.qwerte.stash;

/**
 * The stash area draft being edited in the Stash window (AREA-CONTRACT.md "In-game Stash window"): one at a time, nothing is sent or applied until
 * Save. It survives closing the window, and is dropped on Cancel, on leaving the world, or {@value #LIFE_MS} ms after the last change. Pure; every
 * method is synchronized and takes the time, so it can be tested.
 */
public final class AreaDraft {
    public static final long LIFE_MS = 15 * 60_000L;

    /**
     * @param key    the stash the draft is for (a server stash's key, or the local stash's)
     * @param server the draft is for a server stash (Save sends it to the server), else for the local stash (Save applies it through the sorter)
     * @param label  the stash's name, for the page
     * @param draft  the area to save; null when {@code clear} (Save removes the area)
     * @param clear  the draft is "no area"
     */
    public record State(String key, boolean server, String label, AreaEdit.Draft draft, boolean clear, long touched) {
    }

    private State state;
    private boolean expiredNote;

    /** The draft, or null (none, or it lapsed: then {@link #takeExpired} says so once). */
    public synchronized State get(long now) {
        if (state != null && now - state.touched() > LIFE_MS) {
            state = null;
            expiredNote = true;
        }
        return state;
    }

    /** Starts or changes the draft; the 15 minutes start again. */
    public synchronized void set(String key, boolean server, String label, AreaEdit.Draft draft, long now) {
        state = new State(key, server, label, draft, false, now);
        expiredNote = false;
    }

    /** The draft is "no area": Save removes the stored one. */
    public synchronized void setClear(String key, boolean server, String label, long now) {
        state = new State(key, server, label, null, true, now);
        expiredNote = false;
    }

    /** Cancel, a save that went through, or leaving the world. */
    public synchronized void clear() {
        state = null;
        expiredNote = false;
    }

    /** True once after the draft lapsed (for the chat line). */
    public synchronized boolean takeExpired() {
        boolean was = expiredNote;
        expiredNote = false;
        return was;
    }

    /** The draft of this stash, or null. */
    public synchronized State forKey(String key, long now) {
        State s = get(now);
        return s != null && s.key().equals(key) ? s : null;
    }

    private static final AreaDraft SHARED = new AreaDraft();

    /** The one draft of the running game. */
    public static AreaDraft shared() {
        return SHARED;
    }

    /** "Area draft W×H×D: open the Stash window to Save or Cancel". */
    public static String hint(AreaEdit.Draft d) {
        return "Area draft " + d.size() + ": open the Stash window to Save or Cancel.";
    }
}
