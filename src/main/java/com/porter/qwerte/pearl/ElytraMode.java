package com.porter.qwerte.pearl;

/** What Reset Pearl does about an elytra in the chest slot, which makes some servers glide the player in water. */
public enum ElytraMode {
    /** Takes the elytra off for the climb (and guards the jump key and the fall-flying packet). */
    Auto,
    /** Only takes the elytra off. */
    SwapOff,
    /** Only guards: no jump while airborne, no start-gliding packets. */
    GuardOnly,
    /** Does nothing about it. */
    Off;

    public boolean swaps() {
        return this == Auto || this == SwapOff;
    }

    public boolean guards() {
        return this == Auto || this == GuardOnly;
    }
}
