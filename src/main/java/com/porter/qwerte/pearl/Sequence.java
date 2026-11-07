package com.porter.qwerte.pearl;

/**
 * The order of the re-arm: climb out (opening the trapdoors), hang over the rim, throw, wait for the pearl to pass the
 * trapdoors, close them. Only decides when to go on or give up; the module does the moving and clicking.
 */
public final class Sequence {
    public enum Phase { EXIT, HANG, THROW, WAIT_PEARL, COVER, DONE, FAILED }

    /** What the module sees this tick. */
    public record Obs(boolean onRim, boolean stationary, boolean fits, boolean thrown, boolean pearlSettled,
                      boolean coverNeeded, boolean coverDone) {}

    public static final int EXIT_LIMIT = 160, HANG_LIMIT = 100, THROW_LIMIT = 30, WAIT_LIMIT = 60, COVER_LIMIT = 60;
    /** The hang phase is not over before this many ticks, so a standstill at its very start doesn't count. */
    public static final int MIN_HANG = 6;
    /** How many standing-still ticks without a clear line the hang waits for, before giving up. */
    public static final int FIT_RETRIES = 3;

    private final int minHang;
    private Phase phase = Phase.EXIT;
    private int ticks;
    private int unfit;
    private String failure;

    public Sequence() {
        this(MIN_HANG);
    }

    /** @param minHang ticks on the rim before the edge is taken as reached */
    public Sequence(int minHang) {
        this.minHang = minHang;
    }

    public Phase phase() {
        return phase;
    }

    public int phaseTicks() {
        return ticks;
    }

    public String failure() {
        return failure;
    }

    public boolean finished() {
        return phase == Phase.DONE || phase == Phase.FAILED;
    }

    private void go(Phase next) {
        phase = next;
        ticks = 0;
        unfit = 0;
    }

    private void fail(String why) {
        failure = why;
        go(Phase.FAILED);
    }

    public Phase advance(Obs o) {
        if (finished()) return phase;
        ticks++;
        switch (phase) {
            case EXIT -> {
                if (o.onRim()) {
                    // A pearl thrown on the way up (from inside the shaft) needs no hang and no second throw
                    go(o.thrown() ? Phase.WAIT_PEARL : Phase.HANG);
                }
                else if (ticks > EXIT_LIMIT) fail("could not climb out of the chamber");
            }
            case HANG -> {
                if (o.stationary() && ticks >= minHang) {
                    if (o.fits()) go(Phase.THROW);
                    else if (++unfit > FIT_RETRIES) fail("could not line up over the shaft");
                }
                else if (ticks > HANG_LIMIT) fail("could not reach the edge of the shaft");
            }
            case THROW -> {
                if (o.thrown()) go(Phase.WAIT_PEARL);
                else if (ticks > THROW_LIMIT) fail("could not throw the pearl");
            }
            case WAIT_PEARL -> {
                if (o.pearlSettled()) go(o.coverNeeded() ? Phase.COVER : Phase.DONE);
                else if (ticks > WAIT_LIMIT) fail("the thrown pearl was not seen in the shaft");
            }
            case COVER -> {
                if (o.coverDone()) go(Phase.DONE);
                else if (ticks > COVER_LIMIT) fail("could not close the cover");
            }
            default -> {}
        }
        return phase;
    }
}
