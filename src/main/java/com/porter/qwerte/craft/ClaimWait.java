package com.porter.qwerte.craft;

/**
 * What a {@code 204} answer to an order claim means for an order the bot still holds saved (CRAFT-CONTRACT.md / PROTOCOL.md "Claim"). A held
 * craft order claimed again without the {@code craftOrders} flag also gets {@code 204}: the server waits for a capable heartbeat before it
 * fails the order (and only after the third flagless claim and 120 s). So a saved craft order, and the ledger of what it already made, is kept
 * through two {@code 204}s and dropped at the third; anything else is dropped at once, as before. Any answer other than {@code 204} starts the
 * count again. Pure; the client thread. Mirrors the plugin's {@code OrderDriver} ({@code waits204}).
 */
public final class ClaimWait {
    /** The 204 on which a saved craft order is given up. */
    public static final int GIVE_UP_AT = 3;

    private int waits;

    /**
     * A claim was answered {@code 204}.
     * @param heldCraft the order saved here has craft lines
     * @return true when the saved order is kept for now
     */
    public boolean keepSaved(boolean heldCraft) {
        if (heldCraft && ++waits < GIVE_UP_AT) return true;
        waits = 0;
        return false;
    }

    /** A claim got any other answer (an order, an error): the count starts again. */
    public void other() {
        waits = 0;
    }

    public int waits() {
        return waits;
    }
}
