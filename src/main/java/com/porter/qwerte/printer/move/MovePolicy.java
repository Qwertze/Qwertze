package com.porter.qwerte.printer.move;

/**
 * Walk or fly: which way the Printer moves toward the next blocks. Pure.
 *
 * <p>{@link Mode#STAND}: always walk. {@link Mode#FLY}: fly whenever flight is available. {@link Mode#STAND_THEN_FLY}: walk,
 * and fly when walking gets nowhere: no progress toward the target for {@link #STALL_TICKS}, or {@link #FAIL_LIMIT} path
 * searches in a row that found no path. While flying it goes back to walking only after a hysteresis: flight has run for at
 * least {@link #MIN_FLY_TICKS}, the player has stood on the ground for {@link #GROUND_TICKS} (flight is never switched off
 * in the air), and the back-off timer has run out. A walk try that fails again doubles the back-off, so a build that can't
 * be walked to does not flip between the two ways.
 */
public final class MovePolicy {
    public enum Mode { STAND, FLY, STAND_THEN_FLY }

    public enum Way { WALK, FLY }

    /**
     * @param distance blocks from the player to the nearest walk target
     * @param failures path searches that found nothing, counted over the run
     */
    public record Obs(int tick, double distance, int failures, boolean flightAvailable, boolean grounded) {}

    public static final int STALL_TICKS = 100, MIN_FLY_TICKS = 60, GROUND_TICKS = 20, FAIL_LIMIT = 2;
    public static final int FIRST_BACKOFF = 200, MAX_BACKOFF = 1200;
    /** A distance this much shorter than the best so far counts as progress. */
    public static final double PROGRESS = 0.5;
    /** A distance this much longer than the best so far means the target changed: the track starts again. */
    public static final double JUMP = 4.0;
    /** No observation for more than this many ticks (the Printer was placing, not walking): the walk track starts again. */
    public static final int GAP_TICKS = 5;

    private Mode mode;
    private Way way = Way.WALK;
    private double best = Double.NaN;
    private int progressTick, failBase, flyFrom, groundSince = -1, nextProbe, backoff = FIRST_BACKOFF, lastObsTick = Integer.MIN_VALUE;

    public MovePolicy(Mode mode) {
        this.mode = mode;
    }

    public Way way() {
        return way;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        if (mode == this.mode) return;
        this.mode = mode;
        reset();
    }

    public void reset() {
        way = Way.WALK;
        best = Double.NaN;
        progressTick = failBase = flyFrom = nextProbe = 0;
        lastObsTick = Integer.MIN_VALUE;
        groundSince = -1;
        backoff = FIRST_BACKOFF;
    }

    /** Decides the way for this tick. */
    public Way update(Obs o) {
        // Observations come only while a walk is wanted; a long gap means a placing period in between, and the distance
        // and the stall timer of the old walk say nothing about the new one
        boolean gap = lastObsTick != Integer.MIN_VALUE && o.tick() - lastObsTick > GAP_TICKS;
        lastObsTick = o.tick();
        if (gap && way == Way.WALK) startTrack(o);
        switch (mode) {
            case STAND -> way = Way.WALK;
            case FLY -> way = o.flightAvailable() ? Way.FLY : Way.WALK;
            case STAND_THEN_FLY -> thenFly(o);
        }
        return way;
    }

    private void thenFly(Obs o) {
        if (!o.flightAvailable()) {
            way = Way.WALK;
            startTrack(o);
            return;
        }
        if (way == Way.WALK) {
            if (Double.isNaN(best)) startTrack(o);
            if (o.distance() < best - PROGRESS) {
                best = o.distance();
                progressTick = o.tick();
                failBase = o.failures();
                backoff = FIRST_BACKOFF; // walking works: the next failure starts the back-off again
            } else if (o.distance() > best + JUMP) {
                startTrack(o);
            }
            if (o.tick() - progressTick >= STALL_TICKS || o.failures() - failBase >= FAIL_LIMIT) {
                way = Way.FLY;
                flyFrom = o.tick();
                groundSince = -1;
                nextProbe = o.tick() + backoff;
                backoff = Math.min(backoff * 2, MAX_BACKOFF);
            }
            return;
        }
        if (o.grounded()) {
            if (groundSince < 0) groundSince = o.tick();
        } else {
            groundSince = -1;
        }
        if (o.tick() - flyFrom >= MIN_FLY_TICKS && o.tick() >= nextProbe && groundSince >= 0 && o.tick() - groundSince >= GROUND_TICKS) {
            way = Way.WALK;
            startTrack(o);
        }
    }

    private void startTrack(Obs o) {
        best = o.distance();
        progressTick = o.tick();
        failBase = o.failures();
    }
}
