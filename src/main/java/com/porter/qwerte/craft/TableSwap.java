package com.porter.qwerte.craft;

/**
 * Using the website's crafting table in place of the stash's own for the length of a craft chunk, without ever forgetting or overwriting the
 * user's own (CRAFT-CONTRACT.md: a website table overrides the local table). The stash's table fields are swapped for the website's while it is
 * {@linkplain #engage engaged} and swapped back on {@linkplain #release}; what is saved to disk meanwhile is always the user's own
 * ({@link #persistedPos}). Pure; generic over the position type. The client thread.
 */
public final class TableSwap<P> {
    private boolean on;
    private P savedPos;
    private String savedSource = "";

    public boolean engaged() {
        return on;
    }

    /** Remembers the user's table (null for none) before the website table is put in its place. Engaging twice keeps the first. */
    public void engage(P currentPos, String currentSource) {
        if (on) return;
        on = true;
        savedPos = currentPos;
        savedSource = currentSource == null ? "" : currentSource;
    }

    public P savedPos() {
        return savedPos;
    }

    public String savedSource() {
        return savedSource;
    }

    /** Lets go: the caller puts {@link #savedPos} and {@link #savedSource} back first. */
    public void release() {
        on = false;
        savedPos = null;
        savedSource = "";
    }

    /** The position to save to disk: the user's own while swapped, else the live one. */
    public P persistedPos(P live) {
        return on ? savedPos : live;
    }

    public String persistedSource(String live) {
        return on ? savedSource : live;
    }
}
