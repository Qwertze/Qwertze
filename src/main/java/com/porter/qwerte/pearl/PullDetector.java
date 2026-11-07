package com.porter.qwerte.pearl;

import java.util.List;
import java.util.function.Supplier;

/**
 * Tells when the player has just been pulled through a stasis chamber by someone else's trigger, from the player's
 * position every tick: a jump of more than {@code minJump} blocks between two ticks (a lag-back or rubberband is a few
 * blocks at most) that ends in the column of a saved chamber on the same server and dimension (within {@code radius}
 * sideways, and inside the shaft vertically, see {@link Chamber#inColumn}), with no pearl thrown by the player in the
 * {@code ownThrowTicks} before it. A pull is let through once per {@code cooldownTicks} after {@link #markTriggered}.
 */
public final class PullDetector {
    public record Config(double radius, int ownThrowTicks, double minJump, int cooldownTicks) {}

    public enum Reason {
        /** No previous position to compare with (first tick, new world or server). */
        NO_BASELINE,
        /** The player moved less than the minimum jump. */
        SMALL_MOVE,
        /** A real jump, but not into any saved chamber's column. */
        NO_CHAMBER,
        /** Into a chamber, but within the cooldown of the last pull. */
        COOLDOWN,
        /** Into a chamber, but the player threw a pearl recently. */
        OWN_THROW,
        /** A pull. */
        PULL
    }

    /**
     * What the detector saw this tick.
     * @param chamber   the chamber matched (for PULL, COOLDOWN and OWN_THROW), else null
     * @param nearest   the chamber closest sideways to the landing, matched or not (null if none saved)
     * @param cooldownLeft ticks of cooldown left, 0 if none
     * @param ownThrowAgo  ticks since the last own throw, or Long.MAX_VALUE if never
     */
    public record Result(Reason reason, Chamber chamber, double jump, Chamber nearest, double nearestHorizontal,
                         double nearestVertical, boolean nearestServerOk, boolean nearestDimensionOk,
                         long cooldownLeft, long ownThrowAgo) {
        public boolean pull() {
            return reason == Reason.PULL;
        }
    }

    private final Supplier<Config> config;
    private boolean have;
    private double lx, ly, lz;
    private String lastServer = "", lastDimension = "";
    private long lastOwnThrow = Long.MIN_VALUE / 2;
    private long lastTrigger = Long.MIN_VALUE / 2;

    /** @param config read every tick, so settings changes apply at once */
    public PullDetector(Supplier<Config> config) {
        this.config = config;
    }

    /** Forgets the last position (new world, new player entity), so the arrival there isn't taken for a jump. */
    public void reset() {
        have = false;
    }

    /** The pull was acted on (the sequence began): starts the cooldown. A refused or failed start doesn't use it up. */
    public void markTriggered(long tick) {
        lastTrigger = tick;
    }

    /**
     * The player threw (or is throwing) an ender pearl at this tick, or one of theirs was just seen leaving them.
     * Calling this keeps pulls from counting until the window has passed.
     */
    public void ownThrow(long tick) {
        lastOwnThrow = tick;
    }

    /**
     * Call once a tick with the player's position.
     * @return the chamber the player was pulled through, or null
     */
    public Chamber update(long tick, double x, double y, double z, String server, String dimension, List<Chamber> chambers) {
        Result r = check(tick, x, y, z, server, dimension, chambers);
        return r.pull() ? r.chamber() : null;
    }

    /** Same as {@link #update} but says what was decided and why. */
    public Result inspect(long tick, double x, double y, double z, String server, String dimension, List<Chamber> chambers) {
        return check(tick, x, y, z, server, dimension, chambers);
    }

    private Result check(long tick, double x, double y, double z, String server, String dimension, List<Chamber> chambers) {
        boolean continuous = have && Chamber.sameServer(server, lastServer) && dimension.equals(lastDimension);
        double jump = continuous ? Math.sqrt((x - lx) * (x - lx) + (y - ly) * (y - ly) + (z - lz) * (z - lz)) : 0;
        have = true;
        lx = x;
        ly = y;
        lz = z;
        lastServer = server;
        lastDimension = dimension;

        Config c = config.get();

        // The closest chamber sideways, same server and dimension first
        Chamber nearest = null;
        double nearestH = Double.MAX_VALUE, nearestScore = Double.MAX_VALUE;
        boolean nSrv = false, nDim = false;
        for (Chamber chamber : chambers) {
            boolean srv = Chamber.sameServer(chamber.server(), server), dim = chamber.dimension().equals(dimension);
            double h = chamber.horizontalTo(x, z);
            double score = h + (srv && dim ? 0 : 1e9);
            if (score < nearestScore) {
                nearest = chamber;
                nearestScore = score;
                nearestH = h;
                nSrv = srv;
                nDim = dim;
            }
        }
        double nearestV = nearest == null ? 0 : nearest.verticalTo(y);
        long cooldownLeft = Math.max(0, c.cooldownTicks() - (tick - lastTrigger));
        long ownAgo = lastOwnThrow < -1_000_000_000L ? Long.MAX_VALUE : tick - lastOwnThrow;

        if (!continuous || jump <= c.minJump()) {
            Reason r = continuous ? Reason.SMALL_MOVE : Reason.NO_BASELINE;
            return new Result(r, null, jump, nearest, nearestH, nearestV, nSrv, nDim, cooldownLeft, ownAgo);
        }

        Chamber best = null;
        double bestH = Double.MAX_VALUE;
        for (Chamber chamber : chambers) {
            if (!Chamber.sameServer(chamber.server(), server) || !chamber.dimension().equals(dimension)) continue;
            if (!chamber.inColumn(x, y, z, c.radius())) continue;
            double h = chamber.horizontalTo(x, z);
            if (h < bestH) {
                best = chamber;
                bestH = h;
            }
        }
        Reason r;
        if (best == null) r = Reason.NO_CHAMBER;
        else if (tick - lastTrigger < c.cooldownTicks()) r = Reason.COOLDOWN;
        else if (tick - lastOwnThrow <= c.ownThrowTicks()) r = Reason.OWN_THROW;
        else r = Reason.PULL;
        return new Result(r, best, jump, nearest, nearestH, nearestV, nSrv, nDim, cooldownLeft, ownAgo);
    }
}
