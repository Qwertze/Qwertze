package com.porter.qwerte.pearl;

/** When the jump key may be held without the client asking to start gliding. */
public final class ElytraGuard {
    private ElytraGuard() {}

    /**
     * Jump held in the air, out of the water, is what starts an elytra glide. So it is held on the ground and in the
     * water only, and never while already gliding.
     */
    public static boolean mayHoldJump(boolean onGround, boolean inWater, boolean fallFlying) {
        if (fallFlying) return false;
        return onGround || inWater;
    }
}
