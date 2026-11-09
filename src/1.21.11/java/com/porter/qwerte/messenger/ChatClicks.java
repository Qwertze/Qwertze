package com.porter.qwerte.messenger;

import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;

/**
 * Left mouse button presses, for Minecraft 1.21.11. Subscribe the object to the event bus while the module is on.
 */
public final class ChatClicks {
    private final BooleanSupplier handler;

    public ChatClicks(BooleanSupplier handler) {
        this.handler = handler;
    }

    @EventHandler
    private void onMouse(MouseClickEvent event) {
        if (event.action != KeyAction.Press || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (handler.getAsBoolean()) event.cancel();
    }
}
