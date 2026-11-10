package com.porter.qwerte.craft;

/**
 * Why the last try at the auto table failed, remembered for a while (the heartbeat says it, and the next chunk fails fast on a fresh one). Only a
 * failure on the craft-order path counts: a shulker craft or the chest expansion that fails to get a table must not make the bot look unable to
 * take craft orders. Pure.
 */
public final class AutoWhy {
    public static final long STICKY_MS = 10 * 60_000L;

    private String code;
    private long at;

    /** A try failed. @param orderPath it was a craft-order chunk's try; anything else is ignored */
    public void failed(boolean orderPath, String why, long now) {
        if (!orderPath) return;
        code = why == null ? CraftAuto.PATH_FAILED : why;
        at = now;
    }

    /** A table was had on the order path. */
    public void cleared() {
        code = null;
    }

    /** The code of a fresh failure, or null (it lapses after {@link #STICKY_MS}). */
    public String current(long now) {
        if (code != null && now - at > STICKY_MS) code = null;
        return code;
    }
}
