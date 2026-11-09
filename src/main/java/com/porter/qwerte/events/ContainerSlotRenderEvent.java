package com.porter.qwerte.events;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;

/**
 * Posted after vanilla drew one slot of a container screen (AbstractContainerScreen.renderSlot, TAIL).
 * One reusable instance: read the fields during the event and do not keep the event.
 * The graphics' pose is already translated to the screen's top-left, so slot.x / slot.y are the draw position.
 */
public class ContainerSlotRenderEvent {
    private static final ContainerSlotRenderEvent INSTANCE = new ContainerSlotRenderEvent();

    public GuiGraphics graphics;
    public Slot slot;

    public static ContainerSlotRenderEvent get(GuiGraphics graphics, Slot slot) {
        INSTANCE.graphics = graphics;
        INSTANCE.slot = slot;
        return INSTANCE;
    }
}
