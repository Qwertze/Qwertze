package com.porter.qwerte.pearl;

/**
 * Whether an ender pearl entity that this client has just seen appear was thrown by the player right now. Only a pearl
 * that is brand new, fast and next to the player's eyes counts: a stasis pearl that falls or is loaded in later (its
 * chunk came into range, it was thrown long ago) is never an own throw.
 */
public final class OwnThrow {
    /** Furthest a fresh throw can be from the eyes when first seen (it flies 1.5 blocks a tick). */
    public static final double MAX_EYE_DISTANCE = 4.0;
    /** A thrown pearl is fast; one resting or sinking in a chamber is not. */
    public static final double MIN_SPEED_SQR = 0.25;
    /** Ticks old at the first sighting. */
    public static final int MAX_AGE = 2;

    private OwnThrow() {}

    /**
     * @param ownedByPlayer the pearl's owner is the local player
     * @param firstSighting this client has not seen this entity before (and the world was not just joined)
     * @param eyeDistance   distance from the player's eyes to the pearl
     * @param speedSqr      squared speed of the pearl in blocks per tick
     * @param tickCount     the pearl's age in ticks
     */
    public static boolean isFreshThrow(boolean ownedByPlayer, boolean firstSighting, double eyeDistance, double speedSqr, int tickCount) {
        return ownedByPlayer && firstSighting && tickCount <= MAX_AGE && eyeDistance <= MAX_EYE_DISTANCE && speedSqr >= MIN_SPEED_SQR;
    }
}
