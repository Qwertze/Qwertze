package com.porter.qwerte.manager.local;

/**
 * When an opened menu is a container worth remembering, and when its contents are worth writing down. Pure: the game
 * side feeds it numbers.
 */
public final class CaptureRules {
    private CaptureRules() {}

    /** The containers the window remembers. Ender chests are left out (they belong to the player, not the place). */
    public enum Kind { CHEST, BARREL, SHULKER_BOX }

    /** A menu counts as the result of a click only if it appeared within this many ticks of it. */
    public static final int INTERACT_WINDOW = 10;
    /** Slots a container can hold at most; anything more is not stored. */
    public static final int MAX_SLOTS = 54;
    /** A container that really is empty is written down once it has been open this many ticks (items arrive by packet). */
    public static final int EMPTY_AFTER_TICKS = 3;

    /** Slots the player's own inventory adds to a container menu. */
    public static final int PLAYER_SLOTS = 36;

    public static int expectedSize(Kind kind, boolean doubleChest) {
        return kind == Kind.CHEST && doubleChest ? 54 : 27;
    }

    /**
     * A menu is captured only if it is a new one that opened after our last click (not the one already open then),
     * within the window, and has exactly the number of container slots that block should have. This keeps plugin GUIs,
     * NPC menus and a window opened by something else from being taken for the chest that was clicked.
     */
    public static boolean shouldCapture(boolean newMenu, boolean sameAsAtClick, long ticksSinceClick, int menuSlots, int expectedSize) {
        if (!newMenu || sameAsAtClick) return false;
        if (ticksSinceClick < 0 || ticksSinceClick > INTERACT_WINDOW) return false;
        return menuSlots - PLAYER_SLOTS == expectedSize && expectedSize <= MAX_SLOTS;
    }

    /**
     * Whether to write down the container's contents now. {@code lastSignature} is null before the first write. The
     * first write waits while the container looks empty (the items may not have arrived), until it has been open for
     * {@link #EMPTY_AFTER_TICKS}; after that every change is written.
     */
    public static boolean shouldRecord(Long lastSignature, long signature, boolean allEmpty, int ticksOpen) {
        if (lastSignature == null) return !allEmpty || ticksOpen >= EMPTY_AFTER_TICKS;
        return lastSignature != signature;
    }

    /** A cheap number for a stack that changes when the item, the count or the components change. */
    public static long mix(long h, long v) {
        return (h ^ v) * 0x100000001b3L + 0x9e3779b97f4a7c15L;
    }
}
