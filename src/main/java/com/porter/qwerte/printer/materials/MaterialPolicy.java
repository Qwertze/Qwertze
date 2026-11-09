package com.porter.qwerte.printer.materials;

/**
 * What the Printer does when blocks are ready but their item is not carried. Pure, so every case is tested.
 *
 * <p>Blocks without their item never hold up the others: when anything else can be placed or walked to, the printer does
 * that first ({@link Outcome#CONTINUE}) and comes back to the starved blocks when the item turns up (restock, stash
 * delivery, ender chest). Only when nothing else is left does the setting matter: with
 * {@code disable-when-out-of-materials} off the printer waits (a pause that resumes by itself), with it on it turns the
 * module off once nothing can supply the item any more.
 */
public final class MaterialPolicy {
    private MaterialPolicy() {}

    public enum Outcome {
        /** Other blocks can be placed or walked to: skip the starved ones for now. */
        CONTINUE,
        /** A materials source can be tried (shulker box, container, ender chest). */
        FETCH,
        /** A stash order covers it: wait for the delivery (the AWAITING_ORDER pause). */
        WAIT_ORDER,
        /** Nothing can supply it right now; pause and resume by itself. */
        WAIT,
        /** Nothing can supply it and the setting says to turn the module off. */
        DISABLE
    }

    /**
     * @param disableWhenOut  the setting
     * @param otherWorkLeft   a block with its item is ready, or there is somewhere to walk to
     * @param supplyPossible  a materials source is set up and not known to be empty
     * @param fetchFailed     the fetch flow has just ended without getting anything
     * @param orderWaiting    a stash order is open or about to be placed for the item
     */
    public static Outcome decide(boolean disableWhenOut, boolean otherWorkLeft, boolean supplyPossible, boolean fetchFailed, boolean orderWaiting) {
        if (otherWorkLeft) return Outcome.CONTINUE;
        if (fetchFailed) return orderWaiting || !disableWhenOut ? Outcome.WAIT : Outcome.DISABLE;
        if (supplyPossible) return Outcome.FETCH;
        if (orderWaiting) return Outcome.WAIT_ORDER;
        return disableWhenOut ? Outcome.DISABLE : Outcome.WAIT;
    }
}
