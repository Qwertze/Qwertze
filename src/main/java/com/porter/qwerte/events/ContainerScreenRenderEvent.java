package com.porter.qwerte.events;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Posted once a container screen has drawn its slots (see AbstractContainerScreenRenderMixin), for every container screen,
 * the player and creative inventories included. It comes before the rest of the screen is drawn (on 1.21.11 the recipe
 * book, the carried item and the tooltip), so it is for deciding things; drawing that must sit on top waits for
 * {@link ContainerScreenDrawEvent}. hoveredSlot may be null. One reusable instance: read the fields during the event and
 * do not keep the event.
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
