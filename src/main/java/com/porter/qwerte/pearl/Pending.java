package com.porter.qwerte.pearl;

/**
 * A pull that is waiting to start. The teleport lands before the chunks around it have loaded, and a screen or a mob
 * can be in the way for a moment, so the start is tried again every tick for a while; a missing pearl is final.
 */
public final class Pending {
    /** What stands between the pull and the start this tick. */
    public enum Block {
        NONE(null, false), NO_PEARL("no ender pearls", true), SCREEN("a screen is open", false), DANGER("something dangerous is nearby", false),
        UNLOADED("the chamber has not loaded", false), BAD_SHAFT("the shaft is not recognised", false);

        final String why;
        final boolean finalBlock;

        Block(String why, boolean finalBlock) {
            this.why = why;
            this.finalBlock = finalBlock;
        }
    }

    public enum Outcome { WAIT, GO, GIVE_UP }

    /** How long it keeps trying, in ticks (5 seconds). */
    public static final int LIMIT = 100;
    /** An unrecognised shaft is looked at again for this many ticks, in case the blocks were still arriving. */
    public static final int SHAFT_RETRIES = 10;

    private int ticks;
    private int shaftTicks;
    private int pearlTicks;
    private boolean pearlTimedOut;
    private String reason;

    /** @param detail what to tell the player instead of the block's own text, or null */
    public Outcome step(Block block, String detail) {
        return step(block, detail, 0);
    }

    /**
     * @param detail          what to tell the player instead of the block's own text, or null
     * @param pearlWaitTicks  how long a missing pearl is waited for (0: it is final at once). The ticks spent waiting for
     *                        the pearl are counted apart: they don't use up {@link #LIMIT}, which is for the chunks and
     *                        the rest, and only start counting once the pearl is there.
     */
    public Outcome step(Block block, String detail, int pearlWaitTicks) {
        if (block == Block.NO_PEARL && pearlWaitTicks > 0) {
            reason = "no ender pearls";
            if (++pearlTicks > pearlWaitTicks) {
                pearlTimedOut = true;
                reason = "no pearl received";
                return Outcome.GIVE_UP;
            }
            return Outcome.WAIT;
        }
        ticks++;
        if (block == Block.NONE) return Outcome.GO;
        reason = detail != null ? detail : block.why;
        if (block.finalBlock) return Outcome.GIVE_UP;
        // Only the ticks the shaft was really looked at count, not the ones spent waiting for the chunks
        if (block == Block.BAD_SHAFT && ++shaftTicks > SHAFT_RETRIES) return Outcome.GIVE_UP;
        if (ticks >= LIMIT) return Outcome.GIVE_UP;
        return Outcome.WAIT;
    }

    /** Why it was held up, last time. */
    public String reason() {
        return reason;
    }

    /** Ticks spent on everything but waiting for a pearl. */
    public int ticks() {
        return ticks;
    }

    /** Ticks spent waiting for a pearl. */
    public int pearlTicks() {
        return pearlTicks;
    }

    /** The give-up was the pearl wait running out. */
    public boolean pearlTimedOut() {
        return pearlTimedOut;
    }
}
