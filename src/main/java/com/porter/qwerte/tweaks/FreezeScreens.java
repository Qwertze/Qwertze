package com.porter.qwerte.tweaks;

/**
 * Which screens the shulker preview may freeze in. Pure, so it is tested without a game.
 *
 * <p>Chests, shulker boxes, crafting tables and every other container always can. The player inventory and the creative
 * inventory only can while the freeze-in-inventory setting is on.
 */
public final class FreezeScreens {
    private FreezeScreens() {}

    public enum Kind { INVENTORY, CONTAINER }

    public static boolean allowed(Kind kind, boolean freezeInInventory) {
        return kind != Kind.INVENTORY || freezeInInventory;
    }
}
