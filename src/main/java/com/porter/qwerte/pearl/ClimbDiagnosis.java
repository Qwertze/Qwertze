package com.porter.qwerte.pearl;

/** Why a climb out of the chamber did not work, for the message. */
public final class ClimbDiagnosis {
    public static final int STUCK_TICKS = 40;

    private ClimbDiagnosis() {}

    public static String reason(boolean glidedInWater, boolean downColumn, boolean trapdoorClosed, int noProgressTicks) {
        if (glidedInWater) return "you glided in the water instead of swimming (the server's elytra quirk)";
        if (downColumn) return "a downward bubble column (magma) holds you";
        if (trapdoorClosed) return "a closed trapdoor above could not be opened";
        if (noProgressTicks >= STUCK_TICKS) return "stuck, no upward progress for " + noProgressTicks + " ticks";
        return "out of time";
    }
}
