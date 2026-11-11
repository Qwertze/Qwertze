package com.porter.qwerte.tweaks;

import java.util.function.IntPredicate;

/**
 * The keys held down, for the freeze debug log, written so it cannot spell out what the player types: only the modifier
 * keys and the freeze key are named, any other held key is only counted. Pure, so it is tested without a game.
 */
public final class DownKeys {
    private DownKeys() {}

    private static final int FIRST = 32;   // GLFW_KEY_SPACE
    private static final int LAST = 348;   // GLFW_KEY_LAST
    private static final int SHIFT = 340;  // GLFW_KEY_LEFT_SHIFT
    private static final int SUPER = 347;  // GLFW_KEY_RIGHT_SUPER

    /** For example {@code [342] others=1}. */
    public static String describe(IntPredicate down, int freezeKey) {
        StringBuilder named = new StringBuilder("[");
        int others = 0;
        for (int key = FIRST; key <= LAST; key++) {
            if (!down.test(key)) continue;
            if ((key >= SHIFT && key <= SUPER) || key == freezeKey) {
                if (named.length() > 1) named.append(' ');
                named.append(key);
            } else {
                others++;
            }
        }
        return named.append("] others=").append(others).toString();
    }
}
