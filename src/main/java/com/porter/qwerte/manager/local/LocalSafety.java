package com.porter.qwerte.manager.local;

/**
 * The AFK safety rules of an order run by this client: stop when another player or a hostile mob is too close, or when it
 * takes too long. Pure; the caller measures the distances.
 */
public final class LocalSafety {
    /** Radii in blocks (0 turns that check off) and the overall time allowed in minutes (at least 1). */
    public record Limits(int playerRadius, int hostileRadius, int timeoutMinutes) {
        public Limits {
            playerRadius = Math.max(0, playerRadius);
            hostileRadius = Math.max(0, hostileRadius);
            timeoutMinutes = Math.max(1, timeoutMinutes);
        }

        public boolean scansEntities() {
            return playerRadius > 0 || hostileRadius > 0;
        }

        /** The largest distance anything has to be looked for at. */
        public int scanRadius() {
            return Math.max(playerRadius, hostileRadius);
        }
    }

    private LocalSafety() {
    }

    /**
     * @param nearestPlayer  distance to the nearest other player, or a negative number when none
     * @param nearestHostile distance to the nearest hostile mob, or a negative number when none
     * @return why the order must be stopped, or null
     */
    public static String check(Limits limits, double nearestPlayer, double nearestHostile, long elapsedMs) {
        if (limits.playerRadius() > 0 && nearestPlayer >= 0 && nearestPlayer <= limits.playerRadius()) return "another player came within " + limits.playerRadius() + " blocks";
        if (limits.hostileRadius() > 0 && nearestHostile >= 0 && nearestHostile <= limits.hostileRadius()) return "a hostile mob came within " + limits.hostileRadius() + " blocks";
        if (elapsedMs > limits.timeoutMinutes() * 60_000L) return "it took longer than " + limits.timeoutMinutes() + " minutes";
        return null;
    }
}
