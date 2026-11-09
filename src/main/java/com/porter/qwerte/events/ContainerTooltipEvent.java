package com.porter.qwerte.events;

import meteordevelopment.meteorclient.events.Cancellable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Posted at the start of AbstractContainerScreen.renderTooltip (HEAD), before vanilla draws the hovered
 * item's tooltip. Call setCancelled(true) to suppress the vanilla tooltip for this frame.
 * One reusable instance: read the fields during the event and do not keep the event.
 */
public class ContainerTooltipEvent extends Cancellable {
    private static final ContainerTooltipEvent INSTANCE = new ContainerTooltipEvent();

    public AbstractContainerScreen<?> screen;
    public GuiGraphics graphics;
    public int mouseX;
    public int mouseY;
    public Slot hoveredSlot;

    public static ContainerTooltipEvent get(AbstractContainerScreen<?> screen, GuiGraphics graphics, int mouseX, int mouseY, Slot hoveredSlot) {
        INSTANCE.setCancelled(false);
        INSTANCE.screen = screen;
        INSTANCE.graphics = graphics;
        INSTANCE.mouseX = mouseX;
        INSTANCE.mouseY = mouseY;
        INSTANCE.hoveredSlot = hoveredSlot;
        return INSTANCE;
    }
}
