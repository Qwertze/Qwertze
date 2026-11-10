package com.porter.qwerte.craft;

/** Which stacks a craft order may use as material: with plain-only requests (ingredients, and the planks, logs or table for the bot's own table) only stacks with no component patch. Pure. */
public final class CraftPlain {
    private CraftPlain() {
    }

    /**
     * @param plainOnly      the request is for plain stacks only
     * @param patchIsEmpty   the stack has no component patch (no name, no enchantments)
     */
    public static boolean allowed(boolean plainOnly, boolean patchIsEmpty) {
        return !plainOnly || patchIsEmpty;
    }
}
