package com.porter.qwerte.notify;

/** What happened to an order that is worth telling the player about. */
public enum Kind {
    /** Delivered with nothing short. */
    COMPLETE,
    /** Delivered, but with shortfalls (lines, craft or kit). */
    PARTIAL,
    FAILED,
    CANCELLED,
    /** The bot started the delivery trip. */
    ARRIVING;

    /** How loud this is when several happen in one poll: the highest is the one sound played. */
    public int severity() {
        return switch (this) {
            case FAILED -> 4;
            case CANCELLED -> 3;
            case PARTIAL -> 2;
            case COMPLETE -> 1;
            case ARRIVING -> 0;
        };
    }
}
