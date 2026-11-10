package com.porter.qwerte.tweaks;

/**
 * When the shulker preview freezes and unfreezes. Pure, so it is tested without a game.
 *
 * <p>Holding the key over a shulker box freezes; letting go, leaving the screen or the preview being turned off
 * unfreezes. A frozen preview stays while the key is held even if the mouse moves off the box, so the grid can be read.
 */
public final class FreezeDecision {
    private FreezeDecision() {}

    public enum Action { NONE, FREEZE, UNFREEZE }

    /**
     * @param held          the freeze key is down right now
     * @param hoveredShulker the hovered slot holds a shulker box with a preview
     * @param frozen        a preview is currently frozen
     * @param sameScreen    the screen being drawn is the one the freeze started in (ignored when not frozen)
     */
    public static Action decide(boolean held, boolean hoveredShulker, boolean frozen, boolean sameScreen) {
        if (frozen) return held && sameScreen ? Action.NONE : Action.UNFREEZE;
        return held && hoveredShulker ? Action.FREEZE : Action.NONE;
    }
}
