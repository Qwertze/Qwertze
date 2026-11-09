package com.porter.qwerte.events;

import meteordevelopment.meteorclient.events.Cancellable;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/**
 * Posted at the start of AbstractContainerScreen.slotClicked (HEAD), before the click is sent.
 * Call setCancelled(true) to swallow the click. slot may be null (a click outside the window, slotId -999).
 * <p>
 * mouseX / mouseY are the GUI-scaled mouse position as of the screen's last render (the same values the
 * ContainerScreenRenderEvent carried); slotClicked itself does not receive the mouse. They are 0 until a
 * container screen has rendered once. One reusable instance: read the fields during the event and do not keep it.
 */
public class ContainerSlotClickEvent extends Cancellable {
    private static final ContainerSlotClickEvent INSTANCE = new ContainerSlotClickEvent();

    /** Written by the render mixin on every container screen frame (two int stores). */
    public static int lastMouseX;
    public static int lastMouseY;

    public AbstractContainerScreen<?> screen;
    public AbstractContainerMenu menu;
    public Slot slot;
    public int slotId;
    public int button;
    public ClickType clickType;
    public int mouseX;
    public int mouseY;

    public static ContainerSlotClickEvent get(AbstractContainerScreen<?> screen, AbstractContainerMenu menu, Slot slot, int slotId, int button, ClickType clickType) {
        INSTANCE.setCancelled(false);
        INSTANCE.screen = screen;
        INSTANCE.menu = menu;
        INSTANCE.slot = slot;
        INSTANCE.slotId = slotId;
        INSTANCE.button = button;
        INSTANCE.clickType = clickType;
        INSTANCE.mouseX = lastMouseX;
        INSTANCE.mouseY = lastMouseY;
        return INSTANCE;
    }
}
