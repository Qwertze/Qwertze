package com.porter.qwerte.messenger;

/** What the wrapped history depends on besides its messages: the width, the scale and whether heads take room. Pure. */
final class BlockCacheKey {
    private BlockCacheKey() {
    }

    /** Whether blocks made with the second key are still right for the first. */
    static boolean same(int width, float scale, boolean heads, int oldWidth, float oldScale, boolean oldHeads) {
        return width == oldWidth && scale == oldScale && heads == oldHeads;
    }
}
