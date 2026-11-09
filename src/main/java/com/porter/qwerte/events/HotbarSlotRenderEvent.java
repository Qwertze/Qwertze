package com.porter.qwerte.events;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Posted after vanilla drew one hotbar item (Gui.renderSlot, TAIL); x and y are the item's top-left corner
 * in GUI coordinates. One reusable instance: read the fields during the event and do not keep the event.
 */
public class HotbarSlotRenderEvent {
    private static final HotbarSlotRenderEvent INSTANCE = new HotbarSlotRenderEvent();

    public GuiGraphics graphics;
    public int x;
    public int y;
    public ItemStack stack;

    public static HotbarSlotRenderEvent get(GuiGraphics graphics, int x, int y, ItemStack stack) {
        INSTANCE.graphics = graphics;
        INSTANCE.x = x;
        INSTANCE.y = y;
        INSTANCE.stack = stack;
        return INSTANCE;
    }
}
