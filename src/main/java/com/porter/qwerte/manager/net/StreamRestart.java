package com.porter.qwerte.manager.net;

/**
 * What the Map tab does when its stream thread has ended for good. Pure.
 * <ul>
 *   <li>{@link ViewerStream.End#AUTH}: never; the session-drop path shows the login.</li>
 *   <li>{@link ViewerStream.End#REVOKED}: again after about a second (the server redacts or refuses on the new stream).</li>
 *   <li>{@link ViewerStream.End#FORBIDDEN}, {@link ViewerStream.End#NOT_FOUND}, {@link ViewerStream.End#ERROR}: after 5 s, doubling to 60 s,
 *   or at once when something changed (the bot list or the account).</li>
 * </ul>
 */
public final class StreamRestart {
    private StreamRestart() {
    }

    /** Milliseconds to wait before starting again after the {@code attempt}-th end (0 = the first), or -1 for never. */
    public static long delayMs(ViewerStream.End end, int attempt) {
        switch (end) {
            case AUTH:
                return -1;
            case REVOKED:
                return attempt == 0 ? 1_000 : Math.min(60_000, 5_000L << Math.min(attempt - 1, 4));
            case NONE:
            case STOPPED:
                return 0;
            default:
                return Math.min(60_000, 5_000L << Math.min(attempt, 4));
        }
    }
}
