package com.porter.qwerte.events;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Posted when a container screen has finished its own drawing, for anything that must sit on top of the whole screen (the
 * recipe book, the carried item and the dragged-item snapback included). It is a draw-only point: decisions belong in
 * {@link ContainerScreenRenderEvent}, which this follows in the same frame. It can be posted more than once for a screen
 * and frame on some versions, so subscribers draw once and clear what they queued. One reusable instance: read the fields
 * during the event and do not keep the event.
 */
public class ContainerScreenDrawEvent {
    private static final ContainerScreenDrawEvent INSTANCE = new ContainerScreenDrawEvent();

    public AbstractContainerScreen<?> screen;
    public GuiGraphics graphics;
    public int mouseX;
    public int mouseY;

    public static ContainerScreenDrawEvent get(AbstractContainerScreen<?> screen, GuiGraphics graphics, int mouseX, int mouseY) {
        INSTANCE.screen = screen;
        INSTANCE.graphics = graphics;
        INSTANCE.mouseX = mouseX;
        INSTANCE.mouseY = mouseY;
        return INSTANCE;
    }
}
