package com.porter.qwerte.events;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Posted at the end of AbstractContainerScreen.render (TAIL), after the tooltip, for every container screen.
 * hoveredSlot may be null. One reusable instance: read the fields during the event and do not keep the event.
 */
public class ContainerScreenRenderEvent {
    private static final ContainerScreenRenderEvent INSTANCE = new ContainerScreenRenderEvent();

    public AbstractContainerScreen<?> screen;
    public GuiGraphics graphics;
    public int mouseX;
    public int mouseY;
    public Slot hoveredSlot;

    public static ContainerScreenRenderEvent get(AbstractContainerScreen<?> screen, GuiGraphics graphics, int mouseX, int mouseY, Slot hoveredSlot) {
        INSTANCE.screen = screen;
        INSTANCE.graphics = graphics;
        INSTANCE.mouseX = mouseX;
        INSTANCE.mouseY = mouseY;
        INSTANCE.hoveredSlot = hoveredSlot;
        return INSTANCE;
    }
}
