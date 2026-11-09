package com.porter.qwerte.events;

import meteordevelopment.meteorclient.events.Cancellable;
import net.minecraft.world.item.ItemStack;

/**
 * Posted when vanilla is about to add a container item's text lines ("Dirt x64", "and 5 more...") to a
 * tooltip. 1.21.11: ItemContainerContents.addToTooltip; 1.21.4: ShulkerBoxBlock.appendHoverText; both HEAD.
 * Call setCancelled(true) to leave those lines out. The stack is the shulker box on 1.21.4 and is null on
 * 1.21.11 (vanilla hands the contents their components, not the stack). One reusable instance.
 */
public class ShulkerTooltipLinesEvent extends Cancellable {
    private static final ShulkerTooltipLinesEvent INSTANCE = new ShulkerTooltipLinesEvent();

    public ItemStack stack;

    public static ShulkerTooltipLinesEvent get(ItemStack stack) {
        INSTANCE.setCancelled(false);
        INSTANCE.stack = stack;
        return INSTANCE;
    }
}
