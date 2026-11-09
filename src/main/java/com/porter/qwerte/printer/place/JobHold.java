package com.porter.qwerte.printer.place;

/**
 * Whether a follow-up job (an axe click on a log, a bucket use on a block) may hold the end of the run. Pure. A job holds
 * the end only while it can actually progress now: its tool is carried, its block is loaded, and its give-up time has not
 * passed. Anything else (no axe, an unloaded chunk, too long) never keeps the Printer on: the run ends and the report
 * lists what was left.
 */
public final class JobHold {
    private JobHold() {}

    public static boolean canProgress(boolean toolAvailable, boolean blockLoaded, boolean expired) {
        return toolAvailable && blockLoaded && !expired;
    }

    /** True when any of the jobs can progress. {@code count} jobs, each described by the three flags at its index. */
    public static boolean any(boolean[] tool, boolean[] loaded, boolean[] expired) {
        for (int i = 0; i < tool.length; i++) if (canProgress(tool[i], loaded[i], expired[i])) return true;
        return false;
    }
}
