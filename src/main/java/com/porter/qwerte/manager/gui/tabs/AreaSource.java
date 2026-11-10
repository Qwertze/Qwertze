package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.model.AreaModel;

import java.util.function.Consumer;

/**
 * Where the Area card gets the stash area from and sends a change to: the Stash Manager server ({@code ServerAreaSource}) or this client's own
 * stash ({@code LocalAreaSource}, applied through the sorter as a local edit). The tab is the same for both.
 */
public interface AreaSource {
    /**
     * @param data  the answer, or null
     * @param error why there is none (empty: nothing to say, an answer to an older request that was dropped)
     */
    record Result(AreaModel.Data data, String error) {
    }

    /** The area is edited on a server (Save is sent there). */
    boolean server();

    /** A stash is picked (or, locally, loaded). */
    boolean ready();

    /** Said in place of the card when {@link #ready} is false. */
    String notReady();

    /** The stash's key for the draft, and its name for the page. */
    String key();

    String label();

    /** The world the player is in is this stash's server (always true for the local stash). */
    boolean sameServer();

    /** The account may change the area. */
    boolean canWrite();

    /** Coordinates must not be drawn (voluntary streamer display). */
    boolean hide();

    /** The name of the stash's bot player (for the map), or "". */
    String botPlayer();

    /** Reads the area. The callback runs on the client thread. */
    void load(Consumer<Result> done);

    /** Sends the area ({@code box} null clears). The callback runs on the client thread. */
    void save(int[] box, String dimension, long rev, Consumer<Result> done);
}
