package com.porter.qwerte.printer.place;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Which y level of the schematic is the active one. Only the active layer is printed; when it has no open cell left the
 * cursor moves to the next one. Pure.
 *
 * <p>An open cell is one that is still in the build: queued, awaiting its check, deferred, or a wrong block in the way
 * (the caller keeps one count per y). Tool and water jobs are not open cells, they never hold a layer.
 *
 * <p>Order: bottom to top, top to bottom, or most efficient first (the layer with the most cells ready now, then the
 * smallest distance in y to the player, then the lower y).
 *
 * <p>Carry-over: a layer whose cells can't be built right now (a lantern hanging under a block of a later layer, sand with
 * nothing below it when building top-down) is stalled; after {@link #STALL_TICKS} stalled ticks it is carried over and the
 * cursor goes on. When nothing else is left the carried layers are tried again, which works once the layers they were
 * waiting for are built. A full round with no progress at all releases the gate ("free"): the caller builds whatever it
 * can anywhere, so the cursor never deadlocks. The first block built ends free mode.
 */
public final class LayerCursor {
    public enum Mode { OFF, BOTTOM_TO_TOP, TOP_TO_BOTTOM, MOST_EFFICIENT }

    /** Open cells per y. {@link #low()} is greater than {@link #high()} when there are none. */
    public interface Counts {
        int open(int y);

        int low();

        int high();

        int total();
    }

    /** Ticks the active layer must be stalled (nothing to place, walk to, fetch or wait for) before it is carried over. */
    public static final int STALL_TICKS = 20;

    private final Mode mode;
    private Integer active;
    private final Set<Integer> carried = new HashSet<>();
    private final Set<Integer> visited = new HashSet<>();
    private int stall;
    private int lastTotal = -1;
    private boolean free;
    private boolean roundProgress = true;

    public LayerCursor(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    /** The layer being printed, or null when the gate is open (off, free mode, or nothing left). */
    public Integer active() {
        return active;
    }

    /** Nothing could be built in any layer for a whole round: the gate is open until something is built. */
    public boolean free() {
        return free;
    }

    public boolean isCarried(int y) {
        return carried.contains(y);
    }

    public int carriedCount() {
        return carried.size();
    }

    /** The cursor is still going to move (carry layers over, try them again); the run is not stuck yet. */
    public boolean working() {
        return mode != Mode.OFF && !free && lastTotal > 0;
    }

    /**
     * May a wrong block at this y be dealt with now: the cursor is off or open, it is the active layer, or a layer the
     * cursor has already gone through.
     */
    public boolean eligible(int y) {
        if (mode == Mode.OFF || free || active == null) return true;
        if (y == active) return true;
        return switch (mode) {
            case BOTTOM_TO_TOP -> y < active;
            case TOP_TO_BOTTOM -> y > active;
            case MOST_EFFICIENT -> visited.contains(y);
            case OFF -> true;
        };
    }

    /**
     * Called once per tick.
     *
     * @param counts   open cells per y
     * @param playerY  the player's block y
     * @param ready    cells ready now at a y (only asked when a layer is chosen in the most-efficient mode); may be null
     * @param stalled  this tick nothing at all could be done for the active layer
     * @return the active layer, or null when the gate is open
     */
    public Integer update(Counts counts, int playerY, IntUnaryOperator ready, boolean stalled) {
        if (mode == Mode.OFF) return null;
        int total = counts.total();
        if (total <= 0) {
            reset();
            return null;
        }
        boolean progress = lastTotal >= 0 && total < lastTotal;
        lastTotal = total;
        if (progress) {
            stall = 0;
            roundProgress = true;
            if (free) {
                free = false;
                carried.clear();
                active = null;
            }
        }
        if (free) return null;
        if (active != null && counts.open(active) == 0) {
            active = null;
            stall = 0;
        }
        if (active != null) {
            if (stalled && !progress) {
                if (++stall >= STALL_TICKS) {
                    carried.add(active);
                    roundProgress = false;
                    active = null;
                    stall = 0;
                }
            } else {
                stall = 0;
            }
        }
        if (active == null) pick(counts, playerY, ready);
        return active;
    }

    private void pick(Counts counts, int playerY, IntUnaryOperator ready) {
        Integer next = choose(counts, playerY, ready);
        if (next == null && !carried.isEmpty()) {
            if (roundProgress) {
                // Layers were built since these were carried over: what they waited for may be there now
                carried.clear();
                roundProgress = false;
                next = choose(counts, playerY, ready);
            } else {
                free = true;
            }
        }
        if (next != null) {
            active = next;
            visited.add(next);
        }
    }

    private Integer choose(Counts counts, int playerY, IntUnaryOperator ready) {
        int lo = counts.low(), hi = counts.high();
        if (lo > hi) return null;
        Integer best = null;
        int bestReady = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 0, n = hi - lo + 1; i < n; i++) {
            int y = mode == Mode.TOP_TO_BOTTOM ? hi - i : lo + i;
            if (counts.open(y) <= 0 || carried.contains(y)) continue;
            if (mode != Mode.MOST_EFFICIENT) return y;
            int r = ready == null ? 0 : ready.applyAsInt(y);
            int d = Math.abs(y - playerY);
            // More ready cells; then nearer to the player in y; then the lower y (the scan goes upward, so a tie keeps the first)
            if (best == null || r > bestReady || r == bestReady && d < bestDist) {
                best = y;
                bestReady = r;
                bestDist = d;
            }
        }
        return best;
    }

    private void reset() {
        active = null;
        carried.clear();
        visited.clear();
        stall = 0;
        lastTotal = -1;
        free = false;
        roundProgress = true;
    }
}
